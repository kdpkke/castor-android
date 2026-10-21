// Package mediaserver is castor's media server, run as `castor media-server` or inside castor itself.
package mediaserver

import (
	"context"
	"net/http"
	"net/url"

	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
	"github.com/stupside/castor/gen/castor/media/v1/mediav1connect"
	"github.com/stupside/castor/internal/transport"
	"github.com/stupside/castor/services/mediaserver/internal/cast"
	"github.com/stupside/castor/services/mediaserver/internal/mediaroute"
)

// Server is the media server: its API, for the API server alone, and its media route, for devices.
type Server struct {
	API   http.Handler
	Media http.Handler
	casts *cast.Service
}

// New serves the media contract, binding each cast to what it asked with caster, telling devices to reach its media route at reach.
func New(caster func(asked *mediav1.PlaybackSettings) cast.Caster, reach *url.URL) *Server {
	publications := mediaroute.NewPublisher(reach)
	casts := cast.New(caster, publications)

	valid := transport.Options()
	api := http.NewServeMux()
	api.Handle(mediav1connect.NewCastServiceHandler(casts, valid))
	api.Handle(mediav1connect.NewDeviceServiceHandler(casts, valid))
	api.Handle(mediav1connect.NewStreamServiceHandler(casts, valid))
	transport.Introspect(api, mediav1connect.CastServiceName, mediav1connect.DeviceServiceName, mediav1connect.StreamServiceName)
	return &Server{API: api, Media: publications, casts: casts}
}

// Shutdown takes no new cast, ends those running, failed as the server shuts down, and waits until they let go of what they hold, or ctx ends.
func (s *Server) Shutdown(ctx context.Context) { s.casts.Drain(ctx) }
