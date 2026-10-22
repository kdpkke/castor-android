package telemetry_test

import (
	"testing"

	"go.opentelemetry.io/otel"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"

	"github.com/stupside/castor/internal/telemetry"
)

func TestCountersAreNamedUnderCastorWithBoundedLabels(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	old := otel.GetMeterProvider()
	otel.SetMeterProvider(sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader)))
	t.Cleanup(func() { otel.SetMeterProvider(old) })

	telemetry.StreamClientEnded(t.Context(), telemetry.OutcomeDisconnected)
	telemetry.StreamSent(t.Context(), 10)
	telemetry.MediaPlanned(t.Context(), telemetry.Video, telemetry.Encode)
	telemetry.MediaRefused(t.Context(), "device-video-incompatible")
	telemetry.RecoveryRevised(t.Context(), "relax-read")

	var data metricdata.ResourceMetrics
	if err := reader.Collect(t.Context(), &data); err != nil {
		t.Fatal(err)
	}
	got := map[string]int{}
	for _, scope := range data.ScopeMetrics {
		for _, m := range scope.Metrics {
			sum, ok := m.Data.(metricdata.Sum[int64])
			if !ok || len(sum.DataPoints) != 1 {
				t.Fatalf("%s: unexpected data %T", m.Name, m.Data)
			}
			got[m.Name] = sum.DataPoints[0].Attributes.Len()
		}
	}
	want := map[string]int{
		"castor.stream.clients": 1, "castor.stream.sent": 0, "castor.media.plans": 2,
		"castor.media.refusals": 1, "castor.recovery.revisions": 1,
	}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for name, labels := range want {
		if got[name] != labels {
			t.Errorf("%s labels = %d (present: %v), want %d", name, got[name], got, labels)
		}
	}
}
