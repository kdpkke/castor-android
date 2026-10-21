package extract

import (
	"context"
	_ "embed"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"time"

	"github.com/chromedp/cdproto/page"
	"github.com/chromedp/chromedp"
)

// iframeSrcJS ranks displayed iframes, including offscreen lazy players (100x100+).
//
//go:embed js/iframe_src.js
var iframeSrcJS string

type frameCandidate struct {
	Src  string  `json:"src"`
	Area float64 `json:"area"`
}

type frameVisit struct {
	src, parent string
	depth       int
}

// navigateIframe explores a bounded set of player frames, largest first.
func navigateIframe(ctx context.Context, stop func() bool) error {
	iframeCtx, cancel := context.WithTimeout(ctx, navigateIframeTimeout)
	defer cancel()
	var parent string
	if err := chromedp.Run(iframeCtx, chromedp.Location(&parent)); err != nil {
		return err
	}
	frames, err := findFrames(iframeCtx)
	if err != nil {
		return err
	}
	queue := make([]frameVisit, 0, len(frames))
	for _, f := range frames {
		queue = append(queue, frameVisit{src: f.Src, parent: parent, depth: 1})
	}
	visited := map[string]bool{parent: true}
	for len(queue) > 0 && len(visited) <= 8 && iframeCtx.Err() == nil && !stop() {
		v := queue[0]
		queue = queue[1:]
		if visited[v.src] || v.depth > navigateIframeMaxDepth {
			continue
		}
		visited[v.src] = true
		slog.DebugContext(ctx, "navigating to iframe", "src", v.src, "depth", v.depth)
		if err := openFrame(iframeCtx, v.src, v.parent); err != nil {
			slog.DebugContext(ctx, "iframe navigation failed", "src", v.src, "error", err)
			continue
		}
		if err := clickPlayer(iframeCtx); err != nil {
			slog.DebugContext(ctx, "iframe player click failed", "src", v.src, "error", err)
		}
		if v.depth == navigateIframeMaxDepth || stop() {
			continue
		}
		children, err := findFrames(iframeCtx)
		if err != nil {
			continue
		}
		branch := make([]frameVisit, 0, len(children))
		for _, child := range children {
			branch = append(branch, frameVisit{src: child.Src, parent: v.src, depth: v.depth + 1})
		}
		queue = append(branch, queue...)
	}
	return nil
}

func findFrames(ctx context.Context) ([]frameCandidate, error) {
	var frames []frameCandidate
	// Let dynamically inserted and lazy frames appear without spending the whole
	// traversal budget on a page that has no children.
	waitCtx, cancel := context.WithTimeout(ctx, time.Second)
	defer cancel()
	err := chromedp.Run(waitCtx, chromedp.Poll(iframeSrcJS, &frames, chromedp.WithPollingTimeout(0)))
	if waitCtx.Err() == context.DeadlineExceeded && ctx.Err() == nil {
		return nil, nil
	}
	return frames, err
}

// openFrame loads a frame's document as its parent would, since embed hosts refuse a request no site framed; only a web page is opened.
func openFrame(ctx context.Context, src, parent string) error {
	if !webPage(src) {
		return fmt.Errorf("frame %q is not a web page", src)
	}
	_, err := chromedp.RunResponse(ctx, chromedp.ActionFunc(func(ctx context.Context) error {
		_, _, errorText, _, err := page.Navigate(src).
			WithReferrer(parent).
			WithReferrerPolicy(page.ReferrerPolicyStrictOriginWhenCrossOrigin).
			Do(ctx)
		if err != nil {
			return err
		}
		if errorText != "" {
			return fmt.Errorf("page load error %s", errorText)
		}
		return nil
	}))
	return err
}

// webPage is whether src is a page on the web, rather than script, data, a file or the browser's own.
func webPage(src string) bool {
	u, err := url.Parse(src)
	return err == nil && u.Hostname() != "" && u.Opaque == "" && (strings.EqualFold(u.Scheme, "http") || strings.EqualFold(u.Scheme, "https"))
}
