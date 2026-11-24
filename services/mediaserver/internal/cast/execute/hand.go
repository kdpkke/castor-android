package execute

import (
	"context"
	"log/slog"

	"github.com/stupside/castor/internal/telemetry"
)

// hand points the device at p.
func hand(ctx context.Context, dev Device, p Playback) (err error) {
	ctx, finish := telemetry.Start(ctx, telemetry.Play)
	defer func() { finish(err) }()
	original, source := p.Stream.(Source)
	slog.InfoContext(ctx, "starting playback", "host", p.URL.Host, "stream", p.Stream)
	if err := dev.Play(ctx, p); err != nil {
		return &playRefused{err: err, source: source, direct: original.Direct}
	}
	return nil
}

// playRefused is the device's own refusal of the URL it was handed.
type playRefused struct {
	err    error
	source bool
	direct bool
}

func (e *playRefused) Error() string { return "starting playback: " + e.err.Error() }

func (e *playRefused) Unwrap() error { return e.err }
