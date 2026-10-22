package telemetry

import (
	"context"
	"errors"
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
	Connect    Operation = "device.connect"
	Play       Operation = "device.play"
	RPCServer  Operation = "rpc.server"
	RPCClient  Operation = "rpc.client"
)

// Start observes a business operation using the installed OTel providers. With
// no provider, OTel is a no-op. The returned completion function is idempotent.
func Start(ctx context.Context, operation Operation) (context.Context, func(error)) {
	return start(ctx, operation, trace.SpanKindInternal)
}

func start(ctx context.Context, operation Operation, kind trace.SpanKind, attrs ...attribute.KeyValue) (context.Context, func(error)) {
	started := time.Now()
	dimensions := append([]attribute.KeyValue{attribute.String(keyOperation, string(operation))}, attrs...)
	ctx, span := otel.Tracer(scope).Start(ctx, string(operation), trace.WithSpanKind(kind), trace.WithAttributes(dimensions...))
	if id, ok := castid.From(ctx); ok {
		span.SetAttributes(attribute.String(keyCastID, id))
	}
	// The SDK reuses instruments by name. Resolve through the current provider so
	// embedded hosts can install their own SDK before starting work.
	duration, err := otel.Meter(scope).Float64Histogram(metricOperationDuration, metric.WithUnit("s"), metric.WithDescription("Completed operation duration, including failures"), durationBuckets)
	if err != nil {
		otel.Handle(err)
	}
	var once sync.Once
	return ctx, func(err error) {
		once.Do(func() {
			result := OutcomeSuccess
			switch {
			case errors.Is(err, context.Canceled):
				result = OutcomeCanceled
			case errors.Is(err, context.DeadlineExceeded):
				result = OutcomeDeadline
			case err != nil:
				result = OutcomeError
			}
			labels := append(dimensions, attribute.String(keyOutcome, result))
			span.SetAttributes(attribute.String(keyOutcome, result))
			if err != nil && result != OutcomeCanceled {
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
