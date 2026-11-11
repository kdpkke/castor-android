package browserstream

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

const Prefix = "/player-playlists/"
const documentLimit = 4 << 20
const routeLimit = 1600

type baseKey struct{}

// WithBase identifies the reachable scraping endpoint that owns a capability.
func WithBase(ctx context.Context, base string) context.Context {
	return context.WithValue(ctx, baseKey{}, base)
}

func Base(ctx context.Context) string {
	base, _ := ctx.Value(baseKey{}).(string)
	return base
}

type Document struct {
	URL  string `json:"url"`
	Body string `json:"body"`
}

type Playlist struct {
	Source string `json:"source"`
	URL    string `json:"url"`
}

// Playlists owns bounded capabilities and the pages needed to read them.
type Playlists struct {
	mu     sync.Mutex
	routes map[string]resource
	groups map[*Group]struct{}
	closed bool
}

type resource struct {
	group       *Group
	source, url string
}

// Group keeps one page alive while its playlists are being read. Unselected
// candidates and stopped readers expire after one minute without a request.
type Group struct {
	owner   *Playlists
	base    string
	read    func(context.Context, string, string) (Document, error)
	release func()
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.Mutex
	last    time.Time
	timer   *time.Timer
	once    sync.Once
	paths   map[[2]string]string
}

func New() *Playlists {
	return &Playlists{routes: make(map[string]resource), groups: make(map[*Group]struct{})}
}

func (p *Playlists) Retain(base string, read func(context.Context, string, string) (Document, error), release func()) (*Group, error) {
	if !webURL(base) {
		return nil, errors.New("missing browser playlist endpoint")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed || len(p.groups) >= 16 {
		return nil, errors.New("browser playlist capacity exhausted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	g := &Group{owner: p, base: strings.TrimRight(base, "/"), read: read, release: release, ctx: ctx, cancel: cancel, last: time.Now(), paths: make(map[[2]string]string)}
	p.groups[g] = struct{}{}
	g.timer = time.AfterFunc(time.Minute, g.expire)
	return g, nil
}

func (g *Group) expire() {
	g.mu.Lock()
	wait := time.Minute - time.Since(g.last)
	if wait > 0 && g.ctx.Err() == nil {
		g.timer.Reset(wait)
		g.mu.Unlock()
		return
	}
	g.mu.Unlock()
	g.Close()
}

func (g *Group) Close() {
	g.once.Do(func() {
		g.cancel()
		g.owner.mu.Lock()
		delete(g.owner.groups, g)
		for path, resource := range g.owner.routes {
			if resource.group == g {
				delete(g.owner.routes, path)
			}
		}
		g.owner.mu.Unlock()
		g.mu.Lock()
		g.timer.Stop()
		g.mu.Unlock()
		g.release()
	})
}

func (g *Group) Register(source, address string) (string, error) {
	if !webURL(source) || !webURL(address) {
		return "", errors.New("invalid player playlist URL")
	}
	g.owner.mu.Lock()
	defer g.owner.mu.Unlock()
	if g.ctx.Err() != nil || g.owner.closed {
		return "", errors.New("browser playlist unavailable")
	}
	key := [2]string{source, address}
	if path := g.paths[key]; path != "" {
		return g.base + path, nil
	}
	if len(g.owner.routes) >= routeLimit {
		return "", errors.New("browser playlist capacity exhausted")
	}
	path := Prefix + rand.Text() + ".m3u8"
	g.paths[key] = path
	g.owner.routes[path] = resource{group: g, source: source, url: address}
	return g.base + path, nil
}

func (p *Playlists) Close() {
	p.mu.Lock()
	p.closed = true
	groups := make([]*Group, 0, len(p.groups))
	for group := range p.groups {
		groups = append(groups, group)
	}
	p.mu.Unlock()
	for _, group := range groups {
		group.Close()
	}
}

func (p *Playlists) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	p.mu.Lock()
	resource, found := p.routes[r.URL.Path]
	p.mu.Unlock()
	if !found {
		http.NotFound(w, r)
		return
	}
	g := resource.group
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	stop := context.AfterFunc(g.ctx, cancel)
	defer stop()
	g.mu.Lock()
	defer g.mu.Unlock()
	defer func() { g.last = time.Now() }()
	if ctx.Err() != nil {
		http.Error(w, "The player could not read the playlist", http.StatusBadGateway)
		return
	}
	// Cancelling an HTTP request does not cancel the page-side loader promise.
	// Keep reads serialized until that bounded load finishes or the page closes.
	readCtx, finishRead := context.WithTimeout(context.WithoutCancel(ctx), 20*time.Second)
	defer finishRead()
	stopRead := context.AfterFunc(g.ctx, finishRead)
	defer stopRead()
	document, err := g.read(readCtx, resource.source, resource.url)
	if err == nil {
		document.Body, err = g.render(resource.source, document)
	}
	if err != nil || ctx.Err() != nil {
		http.Error(w, "The player could not read the playlist", http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", fmt.Sprint(len(document.Body)))
	if r.Method == http.MethodGet {
		_, _ = w.Write([]byte(document.Body))
	}
}

var uriAttribute = regexp.MustCompile(`URI="([^"]*)"`)

func (g *Group) render(source string, document Document) (string, error) {
	document.Body = strings.TrimPrefix(document.Body, "\ufeff")
	if len(document.Body) > documentLimit || !strings.HasPrefix(strings.TrimSpace(document.Body), "#EXTM3U") || !webURL(document.URL) {
		return "", errors.New("invalid player playlist")
	}
	base, _ := url.Parse(document.URL)
	g.owner.mu.Lock()
	defer g.owner.mu.Unlock()
	if g.ctx.Err() != nil || g.owner.closed {
		return "", errors.New("browser playlist unavailable")
	}
	// Publish child capabilities only after the entire document has been validated.
	pending := make(map[[2]string]string)
	reference := func(raw string, playlist bool) (string, error) {
		u, err := base.Parse(raw)
		if err != nil || !webURL(u.String()) {
			return "", errors.New("invalid playlist reference")
		}
		if playlist {
			key := [2]string{source, u.String()}
			path := g.paths[key]
			if path == "" {
				path = pending[key]
			}
			if path == "" {
				if len(g.owner.routes)+len(pending) >= routeLimit {
					return "", errors.New("browser playlist capacity exhausted")
				}
				path = Prefix + rand.Text() + ".m3u8"
				pending[key] = path
			}
			return g.base + path, nil
		}
		return u.String(), nil
	}
	var out strings.Builder
	nextPlaylist := false
	for raw := range strings.SplitSeq(strings.TrimPrefix(document.Body, "\ufeff"), "\n") {
		line := strings.TrimSpace(raw)
		var err error
		if line != "" && !strings.HasPrefix(line, "#") {
			line, err = reference(line, nextPlaylist)
			nextPlaylist = false
		} else {
			playlist := strings.HasPrefix(line, "#EXT-X-MEDIA:") || strings.HasPrefix(line, "#EXT-X-I-FRAME-STREAM-INF:") || strings.HasPrefix(line, "#EXT-X-RENDITION-REPORT:")
			line = uriAttribute.ReplaceAllStringFunc(line, func(attribute string) string {
				if err != nil {
					return attribute
				}
				var address string
				address, err = reference(attribute[5:len(attribute)-1], playlist)
				return `URI="` + address + `"`
			})
			if strings.HasPrefix(line, "#EXT-X-STREAM-INF:") {
				nextPlaylist = true
			}
		}
		if err != nil {
			return "", err
		}
		if out.Len()+len(line)+1 > documentLimit {
			return "", errors.New("expanded playlist exceeds limit")
		}
		out.WriteString(line)
		out.WriteByte('\n')
	}
	for key, path := range pending {
		g.paths[key] = path
		g.owner.routes[path] = resource{group: g, source: key[0], url: key[1]}
	}
	return out.String(), nil
}

func webURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && len(raw) <= 8192 && u.Host != "" && u.User == nil && (u.Scheme == "http" || u.Scheme == "https")
}
