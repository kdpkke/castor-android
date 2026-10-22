package telemetry

import (
	"context"
	"errors"
	"io"
	"strings"

	"connectrpc.com/connect"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	semconv "go.opentelemetry.io/otel/semconv/v1.43.0"
	"go.opentelemetry.io/otel/trace"
)

// RPC propagates W3C trace context across service boundaries and observes the
// entire RPC lifetime. Baggage, credentials and message bodies are not recorded.
func RPC() connect.Interceptor { return rpc{} }

type rpc struct{}

func (rpc) WrapUnary(next connect.UnaryFunc) connect.UnaryFunc {
	return func(ctx context.Context, req connect.AnyRequest) (connect.AnyResponse, error) {
		headers := propagation.HeaderCarrier(req.Header())
		operation, kind := RPCServer, trace.SpanKindServer
		if req.Spec().IsClient {
			operation, kind = RPCClient, trace.SpanKindClient
		} else {
			ctx = propagation.TraceContext{}.Extract(ctx, headers)
		}
		ctx, finish := start(ctx, operation, kind, procedure(req.Spec().Procedure)...)
		if req.Spec().IsClient {
			propagation.TraceContext{}.Inject(ctx, headers)
		}
		response, err := next(ctx, req)
		finish(err)
		return response, err
	}
}

func (rpc) WrapStreamingHandler(next connect.StreamingHandlerFunc) connect.StreamingHandlerFunc {
	return func(ctx context.Context, conn connect.StreamingHandlerConn) error {
		ctx = propagation.TraceContext{}.Extract(ctx, propagation.HeaderCarrier(conn.RequestHeader()))
		ctx, finish := start(ctx, RPCServer, trace.SpanKindServer, procedure(conn.Spec().Procedure)...)
		err := next(ctx, conn)
		finish(err)
		return err
	}
}

func (rpc) WrapStreamingClient(next connect.StreamingClientFunc) connect.StreamingClientFunc {
	return func(ctx context.Context, spec connect.Spec) connect.StreamingClientConn {
		ctx, finish := start(ctx, RPCClient, trace.SpanKindClient, procedure(spec.Procedure)...)
		conn := next(ctx, spec)
		propagation.TraceContext{}.Inject(ctx, propagation.HeaderCarrier(conn.RequestHeader()))
		return &observedStream{StreamingClientConn: conn, ctx: ctx, finish: finish}
	}
}

type observedStream struct {
	connect.StreamingClientConn
	ctx    context.Context
	finish func(error)
}

func (s *observedStream) Receive(msg any) error {
	err := s.StreamingClientConn.Receive(msg)
	if err != nil {
		if errors.Is(err, io.EOF) {
			s.finish(context.Cause(s.ctx))
		} else {
			s.finish(err)
		}
	}
	return err
}
func (s *observedStream) Send(msg any) error {
	err := s.StreamingClientConn.Send(msg)
	if err != nil && !errors.Is(err, io.EOF) {
		s.finish(err)
	}
	return err
}
func (s *observedStream) CloseRequest() error {
	err := s.StreamingClientConn.CloseRequest()
	if err != nil {
		s.finish(err)
	}
	return err
}
func (s *observedStream) CloseResponse() error {
	err := s.StreamingClientConn.CloseResponse()
	s.finish(errors.Join(err, context.Cause(s.ctx)))
	return err
}

// procedure names a Connect procedure the way OpenTelemetry names an RPC method: service/method, without the leading slash.
func procedure(p string) []attribute.KeyValue {
	return []attribute.KeyValue{semconv.RPCSystemNameConnectrpc, semconv.RPCMethod(strings.TrimPrefix(p, "/"))}
}
