package extract

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync"

	"github.com/chromedp/cdproto/browser"
	"github.com/chromedp/cdproto/cdp"
	"github.com/chromedp/cdproto/network"
	"github.com/chromedp/cdproto/runtime"
	"github.com/chromedp/chromedp"
	"github.com/stupside/castor/internal/browserstream"

	"github.com/stupside/castor/services/scrapingserver/internal/extract/stealth"
)

// session owns the browser that extracts one page.
type session struct {
	ctx         context.Context
	cancel      context.CancelFunc
	allocCancel context.CancelFunc
	collector   *collector
	centerX     float64
	centerY     float64
	contextsMu  sync.Mutex
	contexts    map[runtime.ExecutionContextID]struct{}
}

func newSession(ctx context.Context, cfg BrowserConfig, targetURL string) (*session, error) {
	profile := stealth.New()
	allocCtx, allocCancel := chromedp.NewExecAllocator(ctx, append(allocatorOpts(cfg), profile.Flags()...)...)
	taskCtx, taskCancel := chromedp.NewContext(allocCtx)
	width, height := profile.Screen()
	s := &session{
		ctx:         taskCtx,
		cancel:      taskCancel,
		allocCancel: allocCancel,
		centerX:     float64(width) / 2,
		centerY:     float64(height) / 2,
		contexts:    make(map[runtime.ExecutionContextID]struct{}),
	}
	s.collector = newCollector(taskCtx, s.readBody, graceAfterActions, collectionWindow, preRollWindow)
	chromedp.ListenTarget(s.collector.events, s.collector.listen)
	chromedp.ListenTarget(taskCtx, func(event any) {
		s.contextsMu.Lock()
		defer s.contextsMu.Unlock()
		switch event := event.(type) {
		case *runtime.EventExecutionContextCreated:
			if event.Context.Name == "" && webPage(event.Context.Origin) {
				s.contexts[event.Context.ID] = struct{}{}
			}
		case *runtime.EventExecutionContextDestroyed:
			delete(s.contexts, event.ExecutionContextID)
		case *runtime.EventExecutionContextsCleared:
			clear(s.contexts)
		}
	})

	// No deadline here: the first Run starts the browser, and its context is the browser's lifetime.
	if err := chromedp.Run(taskCtx,
		runtime.Enable(),
		network.Enable(),
		browser.SetDownloadBehavior(browser.SetDownloadBehaviorBehaviorDeny),
		profile.Apply(),
	); err != nil {
		s.Close()
		return nil, fmt.Errorf("starting the browser: %w", err)
	}

	navCtx, navCancel := context.WithTimeout(taskCtx, cfg.Timeout)
	err := chromedp.Run(navCtx, chromedp.Navigate(targetURL))
	navCancel()
	if err != nil {
		if !s.collector.hasHits() {
			s.Close()
			return nil, fmt.Errorf("navigating to %s: %w", targetURL, err)
		}
		slog.WarnContext(ctx, "navigation did not finish; carrying on with what the page already fetched",
			"url", targetURL, "timeout", cfg.Timeout, "error", err)
	}

	return s, nil
}

// allocatorOpts are the flags a browser castor drives runs with.
func allocatorOpts(cfg BrowserConfig) []chromedp.ExecAllocatorOption {
	opts := []chromedp.ExecAllocatorOption{
		chromedp.ExecPath(cfg.ChromePath),
		chromedp.NoFirstRun,
		chromedp.NoDefaultBrowserCheck,
		chromedp.Flag("no-sandbox", cfg.NoSandbox),
		chromedp.Flag("disable-dev-shm-usage", true),
		chromedp.Flag("disable-background-timer-throttling", true),
		chromedp.Flag("disable-backgrounding-occluded-windows", true),
		chromedp.Flag("disable-renderer-backgrounding", true),
		chromedp.Flag("autoplay-policy", "no-user-gesture-required"),
		chromedp.Flag("incognito", true),
	}
	// Only appended when wanted: its presence matters, not its value.
	if cfg.Headless {
		opts = append(opts, chromedp.Flag("headless", "new"))
	}
	return opts
}

// readBody asks this session's page for the bytes of a response it already holds.
func (s *session) readBody(reqID network.RequestID) ([]byte, error) {
	target := chromedp.FromContext(s.ctx).Target
	if target == nil {
		return nil, errors.New("no CDP target on the session")
	}
	return network.GetResponseBody(reqID).Do(cdp.WithExecutor(s.ctx, target))
}

func click(ctx context.Context, x, y float64) error {
	return chromedp.Run(ctx, chromedp.MouseClickXY(x, y, chromedp.ButtonLeft))
}

//go:embed js/player_pos.js
var playerPosJS string

func clickPlayer(ctx context.Context) error {
	var pos *struct {
		X float64 `json:"x"`
		Y float64 `json:"y"`
	}
	if err := chromedp.Run(ctx, chromedp.Evaluate(playerPosJS, &pos)); err != nil || pos == nil {
		return err
	}
	return click(ctx, pos.X, pos.Y)
}

// action is one best-effort nudge at the page: a name for the log and the work.
type action struct {
	name string
	do   func() error
}

// runActions drives the page until it has been driven as far as it can usefully be driven.
func (s *session) runActions() {
	actions := []action{
		{"click player", func() error { return clickPlayer(s.ctx) }},
		{"click", func() error { return click(s.ctx, s.centerX, s.centerY) }},
		{"navigate iframe", func() error {
			return navigateIframe(s.ctx, s.collector.hasMaster)
		}},
		{"bypass turnstile", func() error {
			return bypassTurnstile(s.ctx)
		}},
		{"click", func() error { return click(s.ctx, s.centerX, s.centerY) }},
		{"click player", func() error { return clickPlayer(s.ctx) }},
	}

	for _, a := range actions {
		if s.ctx.Err() != nil {
			return
		}
		// A master playlist is what the page was driven for, so nothing further is asked of it.
		if s.collector.hasMaster() {
			return
		}
		if err := a.do(); err != nil {
			slog.DebugContext(s.ctx, a.name+" failed", "error", err)
		}
	}
}

func (s *session) Close() {
	s.cancel()
	s.collector.close()
	s.allocCancel()
}

func (s *session) player(ctx context.Context, id runtime.ExecutionContextID, request any, result any) error {
	argument, err := json.Marshal(request)
	if err != nil {
		return err
	}
	target := chromedp.FromContext(s.ctx).Target
	if target == nil {
		return errors.New("browser target unavailable")
	}
	expression := "(" + browserstream.PlayerScript + ")(window, value => value," + string(argument) + ")"
	value, exception, err := runtime.Evaluate(expression).WithContextID(id).WithAwaitPromise(true).WithReturnByValue(true).Do(cdp.WithExecutor(ctx, target))
	if err != nil {
		return err
	}
	if exception != nil || value == nil || len(value.Value) > 4<<20 {
		return errors.New("player playlist evaluation failed")
	}
	return json.Unmarshal(value.Value, result)
}

type playerPlaylist struct {
	browserstream.Playlist
	context runtime.ExecutionContextID
}

func (s *session) playerPlaylists(ctx context.Context) []playerPlaylist {
	s.contextsMu.Lock()
	contexts := make([]runtime.ExecutionContextID, 0, len(s.contexts))
	for id := range s.contexts {
		contexts = append(contexts, id)
	}
	s.contextsMu.Unlock()
	var out []playerPlaylist
	for _, id := range contexts {
		var documents []browserstream.Playlist
		if s.player(ctx, id, map[string]string{"operation": "discover"}, &documents) == nil {
			for _, document := range documents {
				out = append(out, playerPlaylist{Playlist: document, context: id})
			}
		}
	}
	return out
}
