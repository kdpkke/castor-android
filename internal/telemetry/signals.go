package telemetry

import (
	"context"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// durationBuckets are explicit second-scale bounds: the SDK default (0 to 10000) puts every castor
// operation, from a 50ms RPC to a 30s extraction, in its first few buckets and makes percentiles meaningless.
var durationBuckets = metric.WithExplicitBucketBoundaries(0.025, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 180, 600, 1800, 3600)

// add counts n on the named counter. The SDK reuses instruments by name, and
// resolving through the current provider lets embedded hosts install theirs first.
func add(ctx context.Context, name, unit, description string, n int64, attrs ...attribute.KeyValue) {
	counter, err := otel.Meter(scope).Int64Counter(name, metric.WithUnit(unit), metric.WithDescription(description))
	if err != nil {
		otel.Handle(err)
		return
	}
	counter.Add(context.WithoutCancel(ctx), n, metric.WithAttributes(attrs...))
}

// StreamClientEnded counts a stream client that stopped being written to, by outcome.
func StreamClientEnded(ctx context.Context, outcome string) {
	add(ctx, metricStreamClients, "{client}", "Stream clients that stopped being written to, by outcome", 1, attribute.String(keyOutcome, outcome))
}

// StreamSent counts bytes written to stream clients.
func StreamSent(ctx context.Context, n int64) {
	add(ctx, metricStreamSent, "By", "Bytes written to stream clients", n)
}

// Axis is one half of a program a plan decides.
type Axis string

const (
	Video Axis = "video"
	Audio Axis = "audio"
)

// Mode is how a plan carries an axis to the device.
type Mode string

const (
	Copy   Mode = "copy"
	Encode Mode = "encode"
)

// MediaPlanned counts an axis planned as a copy or an encode.
func MediaPlanned(ctx context.Context, axis Axis, mode Mode) {
	add(ctx, metricMediaPlans, "{plan}", "Axes planned, by whether they are copied or encoded",
		1, attribute.String(keyAxis, string(axis)), attribute.String(keyMode, string(mode)))
}

// MediaRefused counts a reason that kept an axis from being copied; reasons are a closed set.
func MediaRefused(ctx context.Context, reason string) {
	add(ctx, metricMediaRefusals, "{refusal}", "Reasons stream-copying was refused", 1, attribute.String(keyRefusal, reason))
}

// RecoveryRevised counts a cast revised by a recovery action; actions are a closed set.
func RecoveryRevised(ctx context.Context, action string) {
	add(ctx, metricRecoveryRevisions, "{revision}", "Casts revised after a fault, by recovery action", 1, attribute.String(keyAction, action))
}
