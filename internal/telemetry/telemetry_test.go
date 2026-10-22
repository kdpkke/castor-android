package telemetry

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSetupLeavesLoggerOwnershipWithTheCaller(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Header().Set("Content-Type", "application/x-protobuf") }))
	defer server.Close()
	before := slog.Default()
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	shutdown, err := Setup(ctx, Config{Endpoint: server.URL}, "test", "test")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = shutdown(context.Background()) })
	if slog.Default() != before {
		t.Fatal("setup replaced the default logger")
	}
	// With the stock default this used to recurse through log.Logger's mutex.
	slog.InfoContext(ctx, "logger remains usable")
	if _, err := Setup(ctx, Config{Endpoint: server.URL}, "duplicate", "test"); err == nil {
		t.Fatal("a second process exporter was silently installed")
	}
	if err := shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if err := shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if Handler().Enabled(ctx, slog.LevelInfo) {
		t.Fatal("shutdown retained the exporter")
	}
}

func TestHandlerPreservesAttributesAndGroupsAcrossSetup(t *testing.T) {
	var out bytes.Buffer
	h := Handler().WithAttrs([]slog.Attr{slog.String("root", "one")}).WithGroup("cast").WithAttrs([]slog.Attr{slog.String("id", "two")})
	if h.Enabled(t.Context(), slog.LevelInfo) {
		t.Fatal("unconfigured handler enabled")
	}
	d := &destination{handler: slog.NewTextHandler(&out, nil)}
	if !active.CompareAndSwap(nil, d) {
		t.Fatal("previous exporter leaked")
	}
	t.Cleanup(func() { active.CompareAndSwap(d, nil) })
	slog.New(h).Info("hello")
	for _, field := range []string{"root=one", "cast.id=two", "msg=hello"} {
		if !strings.Contains(out.String(), field) {
			t.Fatalf("missing %q in %s", field, out.String())
		}
	}
}
