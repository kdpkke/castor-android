package extract

import (
	"context"
	_ "embed"
	"errors"
	"fmt"
	"log/slog"

	"github.com/chromedp/cdproto/browser"
	"github.com/chromedp/cdproto/cdp"
	"github.com/chromedp/cdproto/network"
	"github.com/chromedp/cdproto/runtime"
	"github.com/chromedp/chromedp"

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
	}
	s.collector = newCollector(taskCtx, s.readBody, graceAfterActions, collectionWindow, preRollWindow)
	chromedp.ListenTarget(s.collector.events, s.collector.listen)

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
