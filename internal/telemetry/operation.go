package telemetry

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"

	"github.com/stupside/castor/internal/castid"
)

// Operation names are stable dimensions, never URLs, device names or cast IDs.
type Operation string

const (
	Extract    Operation = "extract"
	Rank       Operation = "rank"
	Measure    Operation = "measure"
	Recovery   Operation = "playback.recovery"
	Playback   Operation = "playback"
	Attempt    Operation = "playback.attempt"
	Transcribe Operation = "transcribe"
)

// Start observes a business operation using the installed OTel providers. With
// no provider, OTel is a no-op. The returned completion function is idempotent.
func Start(ctx context.Context, operation Operation) (context.Context, func(error)) {
	return start(ctx, string(operation), trace.SpanKindInternal)
}

func start(ctx context.Context, operation string, kind trace.SpanKind, attrs ...attribute.KeyValue) (context.Context, func(error)) {
	started := time.Now()
	dimensions := append([]attribute.KeyValue{attribute.String("operation", operation)}, attrs...)
	ctx, span := otel.Tracer("castor").Start(ctx, operation, trace.WithSpanKind(kind), trace.WithAttributes(dimensions...))
	if id, ok := castid.From(ctx); ok {
		span.SetAttributes(attribute.String("cast.id", id))
	}
	// The SDK reuses instruments by name. Resolve through the current provider so
	// embedded hosts can install their own SDK before starting work.
	duration, err := otel.Meter("castor").Float64Histogram("castor.operation.duration", metric.WithUnit("s"), metric.WithDescription("Completed operation duration, including failures"))
	if err != nil {
		otel.Handle(err)
	}
	var once sync.Once
	return ctx, func(err error) {
		once.Do(func() {
			result := "success"
			switch {
			case errors.Is(err, context.Canceled):
				result = "canceled"
			case errors.Is(err, context.DeadlineExceeded):
				result = "deadline"
			case err != nil:
				result = "error"
			}
			labels := append(dimensions, attribute.String("outcome", result))
			span.SetAttributes(attribute.String("outcome", result))
			if err != nil && result != "canceled" {
				span.SetStatus(codes.Error, result)
			}
			// Error strings may contain source credentials. Detailed diagnostics remain
			// in the redacted log stream; spans and metrics carry only bounded outcomes.
			if duration != nil {
				duration.Record(ctx, time.Since(started).Seconds(), metric.WithAttributes(labels...))
			}
			span.End()
		})
	}
}

// WithTrace carries request trace identity onto independently owned work. The
// new work keeps lifetime's cancellation; an accepted cast outlives its RPC.
func WithTrace(lifetime, request context.Context) context.Context {
	return trace.ContextWithSpanContext(lifetime, trace.SpanContextFromContext(request))
}

// Transition records an accepted state-machine event in the owner's trace and logs.
func Transition(ctx context.Context, machine, event, from, to string) {
	trace.SpanFromContext(ctx).AddEvent("state.transition", trace.WithAttributes(
		attribute.String("machine", machine), attribute.String("event", event),
		attribute.String("from", from), attribute.String("to", to),
	))
	slog.DebugContext(ctx, "state transition", "machine", machine, "event", event, "from", from, "to", to)
}
