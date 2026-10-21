package execute

import (
	"context"
	"log/slog"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/cast/encoding/fetch"
	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// facts is what a cast knows about its tracks and whether it knows the whole subject.
type facts struct {
	probe    media.ProbeInfo
	measured bool
}

// measure probes subject once; failure leaves presence unproven and prevents
// treating missing codec parameters as evidence that a track can be omitted.
func measure(ctx context.Context, subject string, p media.Prober) facts {
	ctx, cancel := context.WithTimeout(ctx, probeBudget)
	defer cancel()

	// Reach separation (refused vs. unreached) costs the same re-encode by cast time.
	info, _, err := p.Probe(ctx)
	if err != nil {
		slog.WarnContext(ctx, "probe failed, so the cast re-encodes",
			"subject", subject, "error", err)
	}
	return facts{probe: info, measured: err == nil}
}

// sounds is whether program's read is sure to carry sound: heard by the probe, or else declared or required by the source.
func (f facts) sounds(p media.Program) bool {
	if f.probe.HasAudio() || f.measured {
		return f.probe.HasAudio()
	}
	return p.RequiredTracks().Audio
}

// probeBudget bounds probe time; ffmpeg HLS demuxer walks whole 403-playlist (199s seen).
const probeBudget = fetch.BackoffMax / 2

// startSlack is under a frame at any real rate: two inputs this close already open together.
const startSlack = 10 * time.Millisecond

// aligned offsets each input by its measured start, since ffmpeg rebases every input to zero.
func aligned(p media.Program, starts map[media.InputID]time.Duration) media.Program {
	clock, measured := starts[p.ClockInput]
	if !measured || len(p.Inputs) < 2 {
		return p
	}
	out := p.Clone()
	for _, input := range p.Inputs {
		start, ok := starts[input.ID]
		_, declared := p.Offsets[input.ID]
		if !ok || declared || input.ID == p.ClockInput || (start-clock).Abs() < startSlack {
			continue
		}
		if out.Offsets == nil {
			out.Offsets = map[media.InputID]time.Duration{}
		}
		out.Offsets[input.ID] = start - clock
	}
	return out
}
