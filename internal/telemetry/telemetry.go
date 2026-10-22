// Package telemetry exports process logs, traces and metrics over OTLP.
package telemetry

import (
	"context"
	"errors"
	"log/slog"
	"net/url"
	"slices"
	"sync"
	"sync/atomic"

	"go.opentelemetry.io/contrib/bridges/otelslog"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.43.0"

	"github.com/stupside/castor/internal/castid"
)

// Config identifies the OTLP collector base URL; empty disables process exporters.
type Config struct {
	Endpoint string `yaml:"endpoint" validate:"omitempty,http_url"`
}

type destination struct{ handler slog.Handler }

var active atomic.Pointer[destination]
var setupMu sync.Mutex

// Handler is the optional telemetry branch of the process logger. Setup never
// replaces slog's global logger: doing so can route the standard logger back
// through itself. The composition root installs this handler beside its outputs.
func Handler() slog.Handler { return forwarding{} }

type decoration struct {
	attrs []slog.Attr
	group string
}
type forwarding struct{ decorations []decoration }

func (h forwarding) target() slog.Handler {
	d := active.Load()
	if d == nil {
		return nil
	}
	target := d.handler
	for _, op := range h.decorations {
		if op.group != "" {
			target = target.WithGroup(op.group)
		} else {
			target = target.WithAttrs(op.attrs)
		}
	}
	return target
}
func (h forwarding) Enabled(ctx context.Context, level slog.Level) bool {
	target := h.target()
	return target != nil && target.Enabled(ctx, level)
}
func (h forwarding) Handle(ctx context.Context, record slog.Record) error {
	if target := h.target(); target != nil && target.Enabled(ctx, record.Level) {
		return target.Handle(ctx, record)
	}
	return nil
}
func (h forwarding) WithAttrs(attrs []slog.Attr) slog.Handler {
	return forwarding{decorations: append(slices.Clone(h.decorations), decoration{attrs: slices.Clone(attrs)})}
}
func (h forwarding) WithGroup(name string) slog.Handler {
	if name == "" {
		return h
	}
	return forwarding{decorations: append(slices.Clone(h.decorations), decoration{group: name})}
}

// Setup owns one process exporter until its returned shutdown function runs.
// Embedded consumers install Handler in their logger explicitly.
func Setup(ctx context.Context, c Config, service, version string) (func(context.Context) error, error) {
	if c.Endpoint == "" {
		return func(context.Context) error { return nil }, nil
	}
	setupMu.Lock()
	defer setupMu.Unlock()
	if active.Load() != nil {
		return nil, errors.New("telemetry exporter already configured for this process")
	}
	// WithEndpointURL takes a signal-specific URL, unlike the collector base
	// URL in our configuration. Preserve any reverse-proxy path prefix.
	endpoint, err := url.JoinPath(c.Endpoint, "v1/logs")
	if err != nil {
		return nil, errors.New("invalid telemetry collector URL")
	}
	exp, err := otlploghttp.New(ctx, otlploghttp.WithEndpointURL(endpoint))
	if err != nil {
		return nil, err
	}
	res := resource.NewSchemaless(semconv.ServiceName(service), semconv.ServiceVersion(version))
	provider := sdklog.NewLoggerProvider(
		sdklog.WithProcessor(sdklog.NewBatchProcessor(exp)), sdklog.WithResource(res),
	)
	traceURL, _ := url.JoinPath(c.Endpoint, "v1/traces")
	traceExp, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpointURL(traceURL))
	if err != nil {
		_ = provider.Shutdown(ctx)
		return nil, err
	}
	traces := sdktrace.NewTracerProvider(sdktrace.WithBatcher(traceExp), sdktrace.WithResource(res))
	metricURL, _ := url.JoinPath(c.Endpoint, "v1/metrics")
	metricExp, err := otlpmetrichttp.New(ctx, otlpmetrichttp.WithEndpointURL(metricURL))
	if err != nil {
		_ = traces.Shutdown(ctx)
		_ = provider.Shutdown(ctx)
		return nil, err
	}
	metrics := sdkmetric.NewMeterProvider(sdkmetric.WithReader(sdkmetric.NewPeriodicReader(metricExp)), sdkmetric.WithResource(res))
	previousTraces, previousMetrics := otel.GetTracerProvider(), otel.GetMeterProvider()
	otel.SetTracerProvider(traces)
	otel.SetMeterProvider(metrics)
	d := &destination{handler: castid.Tagged(otelslog.NewHandler(service, otelslog.WithLoggerProvider(provider)), keyCastID)}
	active.Store(d)
	var once sync.Once
	var shutdownErr error
	return func(ctx context.Context) error {
		once.Do(func() {
			setupMu.Lock()
			defer setupMu.Unlock()
			active.CompareAndSwap(d, nil)
			// Leave a provider installed by an embedded host after Setup alone.
			if otel.GetTracerProvider() == traces {
				otel.SetTracerProvider(previousTraces)
			}
			if otel.GetMeterProvider() == metrics {
				otel.SetMeterProvider(previousMetrics)
			}
			shutdownErr = errors.Join(metrics.Shutdown(ctx), traces.Shutdown(ctx), provider.Shutdown(ctx))
		})
		return shutdownErr
	}, nil
}
