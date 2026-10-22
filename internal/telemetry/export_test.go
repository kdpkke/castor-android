package telemetry

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	collector "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	"google.golang.org/protobuf/proto"

	"github.com/stupside/castor/internal/castid"
	"github.com/stupside/castor/internal/logging"
)

// Exercise the real SDK encoder, batch processor and HTTP exporter together.
func TestShutdownExportsRedactedLogsToCollector(t *testing.T) {
	for _, prefix := range []string{"", "/otel/"} {
		t.Run(prefix, func(t *testing.T) {
			received := make(chan *collector.ExportLogsServiceRequest, 1)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == strings.TrimSuffix(prefix, "/")+"/v1/metrics" {
					w.Header().Set("Content-Type", "application/x-protobuf")
					return
				}
				if r.URL.Path != strings.TrimSuffix(prefix, "/")+"/v1/logs" || r.Method != http.MethodPost {
					t.Errorf("unexpected OTLP request: %s %s", r.Method, r.URL.Path)
					w.WriteHeader(http.StatusNotFound)
					return
				}
				body, err := io.ReadAll(r.Body)
				if err != nil {
					t.Error(err)
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				request := new(collector.ExportLogsServiceRequest)
				if err := proto.Unmarshal(body, request); err != nil {
					t.Error(err)
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				received <- request
				w.Header().Set("Content-Type", "application/x-protobuf")
			}))
			defer server.Close()
			stop, err := Setup(t.Context(), Config{Endpoint: server.URL + prefix}, "castor-test", "test")
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			defer func() { _ = stop(ctx) }()
			slog.New(logging.Redact(Handler())).InfoContext(castid.With(t.Context(), "cast-test"), "playback",
				"source", "https://alice:password@example.com/video?token=private#secret", "authorization", "Bearer private")
			if err := stop(ctx); err != nil {
				t.Fatal(err)
			}
			select {
			case request := <-received:
				payload := request.String()
				for _, secret := range []string{"alice", "password", "private", "secret"} {
					if strings.Contains(payload, secret) {
						t.Errorf("OTLP payload leaked %q", secret)
					}
				}
				for _, want := range []string{"castor-test", "cast-test", "playback", "https://example.com/video"} {
					if !strings.Contains(payload, want) {
						t.Errorf("OTLP payload lost %q", want)
					}
				}
			default:
				t.Fatal("shutdown returned without delivering the queued log")
			}
		})
	}
}
