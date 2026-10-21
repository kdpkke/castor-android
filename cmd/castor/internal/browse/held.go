package browse

import (
	"context"
	"log/slog"
	"sync"
)

// held runs a full-screen program with this process's log lines held back, then writes them below it.
func held(run func() error) error {
	prev := slog.Default()
	h := &holding{next: prev.Handler(), lines: &lines{}}
	slog.SetDefault(slog.New(h))
	defer func() {
		slog.SetDefault(prev)
		h.lines.mu.Lock()
		defer h.lines.mu.Unlock()
		for _, k := range h.lines.kept {
			_ = k.handler.Handle(context.WithoutCancel(k.ctx), k.record)
		}
		h.lines.kept = nil
		h.lines.released = true
	}()
	return run()
}

// holding keeps what next would have written, in order, instead of writing it.
type holding struct {
	next  slog.Handler
	lines *lines
}

// heldLine is a record with the context it was logged under, so the cast it belongs to is not lost when it is written.
type heldLine struct {
	ctx     context.Context
	record  slog.Record
	handler slog.Handler
}

type lines struct {
	mu       sync.Mutex
	kept     []heldLine
	released bool
}

func (h *holding) Enabled(ctx context.Context, level slog.Level) bool {
	return h.next.Enabled(ctx, level)
}

func (h *holding) Handle(ctx context.Context, r slog.Record) error {
	r = r.Clone()
	h.lines.mu.Lock()
	if h.lines.released {
		h.lines.mu.Unlock()
		return h.next.Handle(ctx, r)
	}
	h.lines.kept = append(h.lines.kept, heldLine{ctx: ctx, record: r, handler: h.next})
	h.lines.mu.Unlock()
	return nil
}

func (h *holding) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &holding{next: h.next.WithAttrs(attrs), lines: h.lines}
}

func (h *holding) WithGroup(name string) slog.Handler {
	return &holding{next: h.next.WithGroup(name), lines: h.lines}
}
