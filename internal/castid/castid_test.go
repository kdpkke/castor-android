package castid

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"

	"buf.build/gen/go/bufbuild/protovalidate/protocolbuffers/go/buf/validate"
	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/emptypb"

	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
)

func TestARecordLoggedUnderACastCarriesItsID(t *testing.T) {
	var out bytes.Buffer
	log := slog.New(Tagged(slog.NewTextHandler(&out, nil), LogKey))

	log.InfoContext(With(t.Context(), "c1"), "in cast")
	log.InfoContext(t.Context(), "outside")

	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	if !strings.Contains(lines[0], "cast_id=c1") || strings.Contains(lines[1], "cast_id") {
		t.Errorf("got %q, want only the cast's line tagged", lines)
	}
}

// seen serves a unary and a streaming procedure, each reporting the id its context arrived with.
func seen(t *testing.T) (client *http.Client, url string, got chan string) {
	t.Helper()
	got = make(chan string, 2)
	mux := http.NewServeMux()
	mux.Handle("/unary", connect.NewUnaryHandler("/unary", func(ctx context.Context, _ *connect.Request[emptypb.Empty]) (*connect.Response[emptypb.Empty], error) {
		id, _ := From(ctx)
		got <- id
		return connect.NewResponse(&emptypb.Empty{}), nil
	}, connect.WithInterceptors(Interceptor())))
	mux.Handle("/stream", connect.NewServerStreamHandler("/stream", func(ctx context.Context, _ *connect.Request[emptypb.Empty], out *connect.ServerStream[emptypb.Empty]) error {
		id, _ := From(ctx)
		got <- id
		return out.Send(&emptypb.Empty{})
	}, connect.WithInterceptors(Interceptor())))
	srv := httptest.NewTestServer(t, mux)
	return srv.Client(), srv.URL, got
}

func TestACastsIDTravelsOnUnaryAndStreamingCalls(t *testing.T) {
	client, url, got := seen(t)
	id := New()
	ctx := With(t.Context(), id)
	req := connect.NewRequest(&emptypb.Empty{})

	unary := connect.NewClient[emptypb.Empty, emptypb.Empty](client, url+"/unary", connect.WithInterceptors(Interceptor()))
	if _, err := unary.CallUnary(ctx, req); err != nil {
		t.Fatal(err)
	}
	stream := connect.NewClient[emptypb.Empty, emptypb.Empty](client, url+"/stream", connect.WithInterceptors(Interceptor()))
	s, err := stream.CallServerStream(ctx, connect.NewRequest(&emptypb.Empty{}))
	if err != nil {
		t.Fatal(err)
	}
	for s.Receive() {
	}
	if err := s.Err(); err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if g := <-got; g != id {
			t.Errorf("the handler saw %q, want %q", g, id)
		}
	}
}

func TestACallMadeUnderNoCastCarriesNone(t *testing.T) {
	client, url, got := seen(t)
	unary := connect.NewClient[emptypb.Empty, emptypb.Empty](client, url+"/unary", connect.WithInterceptors(Interceptor()))
	if _, err := unary.CallUnary(t.Context(), connect.NewRequest(&emptypb.Empty{})); err != nil {
		t.Fatal(err)
	}
	if g := <-got; g != "" {
		t.Errorf("the handler saw %q, want none", g)
	}
}

// The media contract states the rule an id meets in its own language; this holds New to it.
func TestNewMakesWhatTheMediaContractAccepts(t *testing.T) {
	field := (&mediav1.StartRequest{}).ProtoReflect().Descriptor().Fields().ByName("cast_id")
	rules, ok := proto.GetExtension(field.Options(), validate.E_Field).(*validate.FieldRules)
	if !ok {
		t.Fatal("StartRequest.cast_id states no rule")
	}
	if id := New(); !regexp.MustCompile(rules.GetString().GetPattern()).MatchString(id) {
		t.Errorf("New() = %q, want it to match %s", id, rules.GetString().GetPattern())
	}
}
