package telemetry

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"go.opentelemetry.io/otel"
	metrics "go.opentelemetry.io/proto/otlp/collector/metrics/v1"
	traces "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	"google.golang.org/protobuf/proto"
)

func TestShutdownExportsOperationSignals(t *testing.T) {
	for _, prefix := range []string{"", "/otel"} {
		t.Run(prefix, func(t *testing.T) {
			traceRequests := make(chan *traces.ExportTraceServiceRequest, 4)
			metricRequests := make(chan *metrics.ExportMetricsServiceRequest, 4)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, err := io.ReadAll(r.Body)
				if err != nil {
					t.Error(err)
					return
				}
				var message proto.Message
				switch r.URL.Path {
				case prefix + "/v1/traces":
					message = new(traces.ExportTraceServiceRequest)
				case prefix + "/v1/metrics":
					message = new(metrics.ExportMetricsServiceRequest)
				default:
					t.Errorf("unexpected signal path %s", r.URL.Path)
					w.WriteHeader(404)
					return
				}
				if err := proto.Unmarshal(body, message); err != nil {
					t.Error(err)
					return
				}
				switch v := message.(type) {
				case *traces.ExportTraceServiceRequest:
					traceRequests <- v
				case *metrics.ExportMetricsServiceRequest:
					metricRequests <- v
				}
				w.Header().Set("Content-Type", "application/x-protobuf")
			}))
			defer server.Close()
			beforeTrace, beforeMeter := otel.GetTracerProvider(), otel.GetMeterProvider()
			stop, err := Setup(t.Context(), Config{Endpoint: server.URL + prefix}, "castor-signals", "test")
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			defer func() { _ = stop(ctx) }()
			_, finish := Start(t.Context(), Playback)
			finish(nil)
			if err := stop(ctx); err != nil {
				t.Fatal(err)
			}
			if otel.GetTracerProvider() != beforeTrace || otel.GetMeterProvider() != beforeMeter {
				t.Fatal("providers were not restored")
			}
			select {
			case request := <-traceRequests:
				if !bytes.Contains([]byte(request.String()), []byte("castor-signals")) {
					t.Fatal("missing service resource")
				}
				spans := request.ResourceSpans[0].ScopeSpans[0].Spans
				if len(spans) != 1 || spans[0].Name != "playback" {
					t.Fatalf("unexpected spans: %v", spans)
				}
			default:
				t.Fatal("shutdown did not deliver traces")
			}
			select {
			case request := <-metricRequests:
				if !bytes.Contains([]byte(request.String()), []byte("castor-signals")) {
					t.Fatal("missing service resource")
				}
				metric := request.ResourceMetrics[0].ScopeMetrics[0].Metrics[0]
				if metric.Name != "castor.operation.duration" || metric.GetHistogram().DataPoints[0].Count != 1 {
					t.Fatalf("unexpected metric: %v", metric)
				}
			default:
				t.Fatal("shutdown did not deliver metrics")
			}
		})
	}
}
