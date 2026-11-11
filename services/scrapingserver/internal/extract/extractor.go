// Package extract captures video stream URLs from a page with headless Chrome.
package extract

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"net/url"
	"slices"
	"sync"

	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"github.com/stupside/castor/internal/browserstream"
	"github.com/stupside/castor/internal/telemetry"
	"google.golang.org/protobuf/proto"
)

// Extractor opens pages in a browser and captures the streams they fetch.
type Extractor struct {
	cfg       Config
	slots     chan struct{}
	Playlists *browserstream.Playlists
}

func New(cfg Config) *Extractor {
	cfg.Capture.MaxConcurrency = max(1, cfg.Capture.MaxConcurrency)
	return &Extractor{cfg: cfg, slots: make(chan struct{}, cfg.Capture.MaxConcurrency), Playlists: browserstream.New()}
}

func (e *Extractor) extract(ctx context.Context, targetURL string) ([]*castorv1.StreamCandidate, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if !webPage(targetURL) {
		return nil, fmt.Errorf("target %q is not a web page", targetURL)
	}
	select {
	case e.slots <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	defer func() { <-e.slots }()
	ctx, cancel := context.WithTimeout(ctx, pageBudget)
	defer cancel()
	lifetime, stopLifetime := context.WithCancel(context.WithoutCancel(ctx))
	stopRequest := context.AfterFunc(ctx, stopLifetime)
	retained := false
	var retainedGroup *browserstream.Group
	var session *session
	release := sync.OnceFunc(func() {
		stopLifetime()
		if session != nil {
			session.Close()
		}
	})
	defer func() {
		stopRequest()
		if ctx.Err() != nil && retainedGroup != nil {
			retainedGroup.Close()
		}
		if !retained {
			release()
		}
	}()
	session, err := newSession(lifetime, e.cfg.Browser, targetURL)
	if err != nil {
		return nil, fmt.Errorf("creating session for %s: %w", targetURL, err)
	}

	session.runActions()

	streams, err := session.collector.Wait(ctx)
	if err != nil {
		return nil, fmt.Errorf("waiting for streams: %w", err)
	}
	for _, stream := range streams {
		stream.SourcePage = targetURL
	}
	if browserstream.Base(ctx) != "" && ctx.Err() == nil {
		documents := session.playerPlaylists(ctx)
		bySource := make(map[string]playerPlaylist)
		ambiguous := make(map[string]bool)
		for _, document := range documents {
			if previous, found := bySource[document.Source]; found && previous.context != document.context {
				ambiguous[document.Source] = true
			}
			bySource[document.Source] = document
		}
		documents = slices.DeleteFunc(documents, func(document playerPlaylist) bool { return ambiguous[document.Source] })
		selected := make(map[int]playerPlaylist)
		for index, stream := range streams {
			if document, found := matchPlayer(stream.Stream.Url, documents); found {
				selected[index] = document
			}
		}
		if len(selected) > 0 {
			group, groupErr := e.Playlists.Retain(browserstream.Base(ctx), func(readCtx context.Context, source, address string) (browserstream.Document, error) {
				var document *browserstream.Document
				err := session.player(readCtx, bySource[source].context, map[string]string{"operation": "read", "source": source, "url": address}, &document)
				if err == nil && document == nil {
					err = errors.New("player playlist unavailable")
				}
				if err != nil {
					return browserstream.Document{}, err
				}
				return *document, nil
			}, release)
			if groupErr != nil {
				return nil, groupErr
			}
			retainedGroup = group
			for index, document := range selected {
				streams[index].Stream.Url, err = group.Register(document.Source, document.URL)
				if err != nil {
					group.Close()
					return nil, err
				}
			}
			retained = true
			for _, document := range bySource {
				var result any
				_ = session.player(ctx, document.context, map[string]string{"operation": "suspend"}, &result)
			}
		}
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	return streams, nil
}

func samePath(left, right string) bool {
	a, errA := url.Parse(left)
	b, errB := url.Parse(right)
	return errA == nil && errB == nil && a.Scheme == b.Scheme && a.Host == b.Host && a.EscapedPath() == b.EscapedPath()
}

// Resolve captures unranked candidates from pages within the browser concurrency budget.
func (e *Extractor) Resolve(ctx context.Context, urls []string) (streams []*castorv1.StreamCandidate, err error) {
	ctx, finish := telemetry.Start(ctx, telemetry.Extract)
	defer func() { finish(err) }()
	slog.InfoContext(ctx, "extracting streams", "urls", len(urls))

	results := make([][]*castorv1.StreamCandidate, len(urls))
	failures := make([]error, len(urls))
	// extract holds each page to the browser budget itself.
	var wg sync.WaitGroup
	for i, targetURL := range urls {
		if ctx.Err() != nil {
			break
		}
		wg.Go(func() {
			slog.DebugContext(ctx, "extracting", "url", targetURL, "index", i+1, "total", len(urls))
			streams, err := e.extract(ctx, targetURL)
			if err != nil {
				slog.WarnContext(ctx, "extraction failed", "url", targetURL, "error", err)
				failures[i] = fmt.Errorf("%s: %w", targetURL, err)
				return
			}
			results[i] = streams
			slog.DebugContext(ctx, "extracted", "url", targetURL, "count", len(streams))
		})
	}
	wg.Wait()
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	deduped := deduplicate(slices.Concat(results...))
	if len(deduped) == 0 {
		if err := errors.Join(failures...); err != nil {
			return nil, fmt.Errorf("no stream extracted from %d URL(s): %w", len(urls), err)
		}
		return nil, fmt.Errorf("no stream extracted from %d URL(s)", len(urls))
	}
	slog.InfoContext(ctx, "extraction complete", "urls", len(urls), "streams", len(deduped))
	return deduped, nil
}

func deduplicate(streams []*castorv1.StreamCandidate) []*castorv1.StreamCandidate {
	// A URL is not sufficient to replay a browser request: two pages can
	// fetch the same resource with different cookies or authorization. Keep
	// each access path so media can try another one if the first fails.
	byURL := make(map[string][]*castorv1.StreamCandidate, len(streams))
	out := make([]*castorv1.StreamCandidate, 0, len(streams))
	for _, candidate := range streams {
		stream := candidate.GetStream()
		var duplicate *castorv1.StreamCandidate
		for _, prior := range byURL[stream.GetUrl()] {
			if maps.Equal(prior.GetStream().GetHeaders(), stream.GetHeaders()) {
				duplicate = prior
				break
			}
		}
		if duplicate != nil {
			// The same request may have been observed before its document was
			// inspected. Retain the strongest evidence available for ranking.
			if duplicate.Ladder == castorv1.Ladder_LADDER_UNSPECIFIED || candidate.GetLadder() == castorv1.Ladder_LADDER_MULTIVARIANT {
				duplicate.Ladder = candidate.GetLadder()
			}
			if duplicate.GetStream().ContentType == "" {
				duplicate.Stream.ContentType = stream.GetContentType()
			}
			continue
		}
		stored := proto.CloneOf(candidate)
		byURL[stream.GetUrl()] = append(byURL[stream.GetUrl()], stored)
		out = append(out, stored)
	}
	return out
}
