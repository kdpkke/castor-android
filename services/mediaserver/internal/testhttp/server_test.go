package testhttp

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestStopReportsForcedShutdown(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		close(entered)
		<-release
	}))
	defer server.Close()
	defer close(release)
	done := make(chan struct{})
	go func() {
		defer close(done)
		response, err := server.Client().Get(server.URL)
		if err == nil {
			response.Body.Close()
		}
	}()
	<-entered
	if err := stop(server.Config); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Stop() = %v, want deadline from forced shutdown", err)
	}
	<-done
}
