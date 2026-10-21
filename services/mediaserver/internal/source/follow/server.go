package follow

import (
	"context"
	"crypto/rand"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// server republishes each feed on loopback: its playlist at /name.m3u8, and every resource it lists under /name/.
type server struct {
	http     *http.Server
	base     *url.URL
	cancel   context.CancelFunc
	handlers sync.RWMutex
	serving  sync.WaitGroup
}

// serve starts republishing feeds, each under its name.
func serve(feeds ...*feed) (*server, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listening for the republished timeline: %w", err)
	}
	// FFmpeg fetches these inputs by URL; only its publication names the route.
	prefix := "/" + rand.Text()
	mux := http.NewServeMux()
	for _, feed := range feeds {
		base := "GET " + prefix + "/" + feed.name
		mux.HandleFunc(base+".m3u8", feed.playlist)
		mux.HandleFunc(base+"/{sequence}", feed.segment)
		mux.HandleFunc(base+"/init/{id}", feed.init)
		mux.HandleFunc(base+"/key/{id}", feed.key)
	}
	ctx, cancel := context.WithCancel(feeds[0].life)
	s := &server{
		cancel: cancel,
		base:   &url.URL{Scheme: "http", Host: ln.Addr().String(), Path: prefix},
	}
	s.http = &http.Server{
		ReadHeaderTimeout: 5 * time.Second,
		BaseContext:       func(net.Listener) context.Context { return ctx },
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			s.handlers.RLock()
			defer s.handlers.RUnlock()
			if ctx.Err() != nil {
				return
			}
			mux.ServeHTTP(w, r)
		}),
	}
	s.serving.Go(func() { _ = s.http.Serve(ln) })
	return s, nil
}

// URL is where the playlist of the feed served under name is read.
func (s *server) URL(name string) *url.URL { return s.base.JoinPath(name + ".m3u8") }

func (s *server) Close() error {
	s.cancel()
	err := s.http.Close()
	s.serving.Wait()
	// The canceled context prevents later requests from entering the feed;
	// the exclusive lock joins every handler already using it.
	s.handlers.Lock()
	defer s.handlers.Unlock()
	return err
}
