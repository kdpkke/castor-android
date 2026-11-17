// Package scrapingserver exposes page extraction as a standalone RPC boundary.
// It owns the browser and returns candidates, never ranking or playing them.
package scrapingserver

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"connectrpc.com/connect"
	scrapingv1 "github.com/stupside/castor/gen/castor/scraping/v1"
	"github.com/stupside/castor/gen/castor/scraping/v1/scrapingv1connect"
	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"github.com/stupside/castor/internal/admission"
	"github.com/stupside/castor/internal/browserstream"
	"github.com/stupside/castor/internal/transport"
	"github.com/stupside/castor/services/scrapingserver/internal/extract"
)

// Resolver is implemented by the composition root that owns the browser.
type Resolver interface {
	Resolve(context.Context, []string) ([]*castorv1.StreamCandidate, error)
}
type Server struct {
	http.Handler
	resolver  Resolver
	requests  *admission.Gate
	shut      context.CancelCauseFunc
	playlists *browserstream.Playlists
}

const maxRequests = 16

var errShutdown = errors.New("the scraping server is shutting down")

func New(resolver Resolver) *Server {
	life, shut := context.WithCancelCause(context.Background())
	s := &Server{requests: admission.New(life, maxRequests), resolver: resolver, shut: shut}
	if extractor, ok := resolver.(*extract.Extractor); ok {
		s.playlists = extractor.Playlists
	}
	mux := http.NewServeMux()
	mux.Handle(scrapingv1connect.NewScrapingServiceHandler(s, transport.Options()))
	transport.Introspect(mux, scrapingv1connect.ScrapingServiceName)
	if s.playlists != nil {
		mux.Handle(browserstream.Prefix, s.playlists)
	}
	s.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		scheme := "http"
		if r.TLS != nil {
			scheme = "https"
		}
		mux.ServeHTTP(w, r.WithContext(browserstream.WithBase(r.Context(), scheme+"://"+r.Host)))
	})
	return s
}

// Authorized keeps RPCs behind the service token. Playlist paths are separate,
// unguessable read capabilities, usable by the media reader across services.
func (s *Server) Authorized(token string) http.Handler {
	api := transport.Authorized(s, token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.playlists != nil && strings.HasPrefix(r.URL.Path, browserstream.Prefix) {
			s.ServeHTTP(w, r)
			return
		}
		api.ServeHTTP(w, r)
	})
}

func (s *Server) Resolve(ctx context.Context, req *scrapingv1.ResolveRequest) (*scrapingv1.ResolveResponse, error) {
	working, leave, err := s.requests.Enter(ctx)
	switch {
	case errors.Is(err, admission.ErrFull):
		return nil, connect.NewError(connect.CodeResourceExhausted, errors.New("concurrent request capacity exhausted"))
	case err != nil:
		return nil, connect.NewError(connect.CodeUnavailable, errShutdown)
	}
	defer leave()
	streams, err := s.resolver.Resolve(working, req.GetUrls())
	if err != nil {
		code := connect.CodeNotFound
		switch {
		case errors.Is(err, context.Canceled):
			code = connect.CodeCanceled
		case errors.Is(err, context.DeadlineExceeded):
			code = connect.CodeDeadlineExceeded
		}
		return nil, s.requests.Answer(working, connect.NewError(code, err))
	}
	if len(streams) == 0 {
		return nil, connect.NewError(connect.CodeNotFound, fmt.Errorf("no streams extracted"))
	}
	return &scrapingv1.ResolveResponse{Streams: streams}, nil
}

func (s *Server) Shutdown(ctx context.Context) {
	s.shut(errShutdown)
	if s.playlists != nil {
		s.playlists.Close()
	}
	_ = s.requests.Wait(ctx)
}

var _ scrapingv1connect.ScrapingServiceHandler = (*Server)(nil)
