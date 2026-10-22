package telemetry_test

import (
	"context"
	"errors"
	"testing"

	"go.opentelemetry.io/otel"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"

	"github.com/stupside/castor/internal/castid"
	"github.com/stupside/castor/internal/telemetry"
)

func TestOperationsRecordBoundedOutcomesWithoutCredentialsOrCastLabels(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	metrics := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	exporter := tracetest.NewInMemoryExporter()
	traces := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exporter))
	oldMetrics, oldTraces := otel.GetMeterProvider(), otel.GetTracerProvider()
	otel.SetMeterProvider(metrics)
	otel.SetTracerProvider(traces)
	t.Cleanup(func() {
		otel.SetMeterProvider(oldMetrics)
		otel.SetTracerProvider(oldTraces)
		_ = metrics.Shutdown(context.Background())
		_ = traces.Shutdown(context.Background())
	})
	for _, err := range []error{nil, context.Canceled, context.DeadlineExceeded, errors.New("https://user:secret@host/video?token=secret")} {
		_, finish := telemetry.Start(castid.With(t.Context(), "unique-cast"), telemetry.Extract)
		finish(err)
		finish(err) // Closing two paths must not count one operation twice.
	}
	var data metricdata.ResourceMetrics
	if err := reader.Collect(t.Context(), &data); err != nil {
		t.Fatal(err)
	}
	outcomes := map[string]uint64{}
	for _, scope := range data.ScopeMetrics {
		for _, measurement := range scope.Metrics {
			histogram, ok := measurement.Data.(metricdata.Histogram[float64])
			if !ok {
				t.Fatalf("unexpected measurement %T", measurement.Data)
			}
			for _, point := range histogram.DataPoints {
				if point.Attributes.Len() != 2 {
					t.Fatal("unbounded or private labels", point.Attributes)
				}
				operation, _ := point.Attributes.Value("castor.operation")
				outcome, _ := point.Attributes.Value("castor.outcome")
				if operation.AsString() != "extract" {
					t.Fatal(operation)
				}
				outcomes[outcome.AsString()] += point.Count
			}
		}
	}
	for _, outcome := range []string{"success", "canceled", "deadline", "error"} {
		if outcomes[outcome] != 1 {
			t.Fatalf("outcome %s count = %d", outcome, outcomes[outcome])
		}
	}
	spans := exporter.GetSpans()
	if len(spans) != 4 {
		t.Fatalf("spans=%d", len(spans))
	}
	for _, span := range spans {
		if len(span.Events) != 0 {
			t.Fatal("raw errors were attached to trace")
		}
		if span.Status.Description != "" && span.Status.Description != "deadline" && span.Status.Description != "error" {
			t.Fatal("private status", span.Status)
		}
	}
}
