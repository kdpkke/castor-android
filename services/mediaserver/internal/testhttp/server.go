// Package testhttp hosts delivery handlers on the listeners supplied by tests.
package testhttp

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// Publication owns a test handler's URL and serving lifetime.
type Publication struct {
	base  *url.URL
	close func() error
}

func (p *Publication) URL(path string) *url.URL { return p.base.JoinPath(path) }
func (p *Publication) Close() error             { return p.close() }

// Publish serves h on ln until Close; ctx supplies values for its requests.
func Publish(ctx context.Context, ln net.Listener, h http.Handler) *Publication {
	base, cancel := context.WithCancel(context.WithoutCancel(ctx))
	server := &http.Server{Handler: h, ReadHeaderTimeout: 5 * time.Second, BaseContext: func(net.Listener) context.Context { return base }}
	server.RegisterOnShutdown(cancel)
	go func() {
		defer cancel()
		if err := server.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.ErrorContext(base, "test HTTP server failed", "error", err)
		}
	}()
	return &Publication{
		base:  &url.URL{Scheme: "http", Host: ln.Addr().String(), Path: "/"},
		close: sync.OnceValue(func() error { return stop(server) }),
	}
}

// stop lets existing responses drain, then cuts any remaining connections.
func stop(server *http.Server) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		return errors.Join(err, server.Close())
	}
	return nil
}
