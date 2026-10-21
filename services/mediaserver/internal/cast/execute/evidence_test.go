package execute

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/cast/health"
	"github.com/stupside/castor/services/mediaserver/internal/cast/recovery"
	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// TestTheOutcomeCarriesWhatTheRecoveryLoopClassifies folds each typed ending into the evidence.
func TestTheOutcomeCarriesWhatTheRecoveryLoopClassifies(t *testing.T) {
	playing := ran{reached: health.Playing}

	t.Run("an undelivered stream", func(t *testing.T) {
		short := &health.Undelivered{Handed: 2 * time.Minute, Produced: 2 * time.Hour}
		if e := evidence(playing, fmt.Errorf("delivering the stream: %w", short), false); e.Undelivered != short {
			t.Errorf("evidence Undelivered = %v, want the delivery's own account", e.Undelivered)
		}
	})

	t.Run("a device that went away", func(t *testing.T) {
		gone := &media.Gone{Device: "Living Room", Err: errors.New("no route to host")}
		if e := evidence(playing, errors.Join(gone, fmt.Errorf("delivering the stream: %w", context.Canceled)), false); e.DeviceGone != gone {
			t.Error("the evidence does not carry the device's own account")
		}
	})

	t.Run("a watch verdict", func(t *testing.T) {
		verdict := &health.Fault{Kind: health.Dead, Vitals: health.Vitals{Samples: 4}, Evidence: []string{"404"}}
		if e := evidence(playing, verdict, false); e.Verdict != health.Dead || e.Vitals.Samples != 4 || len(e.Lines) != 1 {
			t.Errorf("evidence = %+v, want the verdict, its measurements and its lines", e)
		}
	})
}

// The evidence is read off how the attempt ended, never off flags a step set on the way.
func TestTheEvidenceIsWhatThePipelineReturned(t *testing.T) {
	refusal := errors.New("SOAP 714")
	reader := &pull{done: make(chan struct{}), proc: finished(t)}
	for _, tt := range []struct {
		name string
		r    ran
		err  error
		want func(recovery.Evidence) bool
	}{
		{"a cast that ended cleanly was delivered", ran{reached: health.Playing}, nil,
			func(e recovery.Evidence) bool { return e.Reached == health.Delivered }},
		{"a device refusing the source itself was handed it", ran{}, &playRefused{err: refusal, source: true},
			func(e recovery.Evidence) bool { return e.Handoff && errors.Is(e.PlayErr, refusal) }},
		{"a device refusing what castor serves was not handed the source", ran{reached: health.Opening}, &playRefused{err: refusal},
			func(e recovery.Evidence) bool { return !e.Handoff && e.PlayErr != nil }},
		{"a timeline castor could not read is named", ran{}, &timelineUnreadable{err: refusal},
			func(e recovery.Evidence) bool { return errors.Is(e.TimelineErr, refusal) }},
		{"a read that reached its delivery served a proven buffer", ran{reached: health.Opening, reader: reader}, errors.New("stalled"),
			func(e recovery.Evidence) bool { return e.Buffered && e.Reached == health.Opening }},
		{"a read stopped at its gate served nothing", ran{reached: health.Reading, reader: reader}, errors.New("dead"),
			func(e recovery.Evidence) bool { return !e.Buffered }},
		{"a remux that reached its delivery buffered nothing", ran{reached: health.Opening}, errors.New("stalled"),
			func(e recovery.Evidence) bool { return !e.Buffered }},
	} {
		if e := evidence(tt.r, tt.err, false); !tt.want(e) {
			t.Errorf("%s: evidence = %+v", tt.name, e)
		}
	}
}

// Cancellation is read from the context the attempt ran under, not from how its error reads.
func TestACancelledAttemptSaysSo(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	for _, tc := range []struct {
		ctx  context.Context
		want bool
	}{{ctx, true}, {t.Context(), false}} {
		dev := &fakeDevice{caps: chromecastLike(media.MP4), ended: true}
		out := Cast{Timelines: direct{}, Outputs: testOutputs(), MaxHeight: 1080, Device: dev}.Run(tc.ctx,
			recovery.Attempt{Program: programFromStream(t, handoffStream())})
		if out.Evidence.Cancelled != tc.want {
			t.Errorf("Cancelled = %v under a context cancelled %v", out.Evidence.Cancelled, tc.want)
		}
	}
}
