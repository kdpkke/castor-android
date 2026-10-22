package telemetry

import (
	"context"
	"log/slog"
	"slices"
	"sync"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"

	"github.com/stupside/castor/internal/castid"
)

// Transition is the callback a state machine fires on each accepted event.
type Transition func(ctx context.Context, event, from, to string)

// Track records a state machine as one span per state visited, so a trace shows
// where a cast spent its time while it runs: a state's span is exported when the
// machine leaves it. Entering a terminal state records an instant span, since
// nothing leaves it. Each span parents under the span context of the event that
// entered it (a self-transition is an event on the state's span), and each visit's duration feeds castor.fsm.state.duration.
func Track(machine, initial string, terminal ...string) Transition {
	var (
		mu    sync.Mutex
		span  trace.Span
		since = time.Now()
	)
	enter := func(ctx context.Context, state string, at time.Time, event string) trace.Span {
		attrs := []attribute.KeyValue{attribute.String(keyMachine, machine), attribute.String(keyState, state)}
		if event != "" {
			attrs = append(attrs, attribute.String(keyEvent, event))
		}
		if id, ok := castid.From(ctx); ok {
			attrs = append(attrs, attribute.String(keyCastID, id))
		}
		_, sp := otel.Tracer(scope).Start(ctx, machine+"."+state, trace.WithTimestamp(at), trace.WithAttributes(attrs...))
		return sp
	}
	leave := func(ctx context.Context, sp trace.Span, state string, at time.Time) {
		sp.End(trace.WithTimestamp(at))
		hist, err := otel.Meter(scope).Float64Histogram(metricStateDuration, metric.WithUnit("s"), metric.WithDescription("Time a state machine spent in a state"), durationBuckets)
		if err != nil {
			otel.Handle(err)
			return
		}
		hist.Record(context.WithoutCancel(ctx), at.Sub(since).Seconds(),
			metric.WithAttributes(attribute.String(keyMachine, machine), attribute.String(keyState, state)))
	}
	return func(ctx context.Context, event, from, to string) {
		now := time.Now()
		mu.Lock()
		defer mu.Unlock()
		if span == nil {
			span = enter(ctx, from, since, "")
		}
		if from == to {
			// A self-transition is something that happened in the state, not a new visit.
			span.AddEvent(event)
			return
		}
		span.SetAttributes(attribute.String(keyExit, event))
		leave(ctx, span, from, now)
		span = nil
		if slices.Contains(terminal, to) {
			enter(ctx, to, now, event).End(trace.WithTimestamp(now))
		} else {
			span = enter(ctx, to, now, event)
		}
		since = now
		slog.DebugContext(ctx, "state transition", "machine", machine, "event", event, "from", from, "to", to)
	}
}
