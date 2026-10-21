package hls

import (
	"io"
	"log/slog"
	"net/http"
	"path"
	"strings"
	"time"
)

// Handler serves retained artifacts for the publication's lifetime, independently
// of whether the producer is still writing them.
func (s *Segments) Handler() http.Handler {
	files := http.FileServerFS(s.store.root.FS())
	mux := http.NewServeMux()
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		s.lastRequest = time.Now()
		s.mu.Unlock()
		if s.o.WriteDeadline > 0 {
			control := http.NewResponseController(w)
			if err := control.SetWriteDeadline(time.Now().Add(s.o.WriteDeadline)); err != nil {
				slog.WarnContext(r.Context(), "HLS write deadline unavailable", "error", err)
			}
			defer control.SetWriteDeadline(time.Time{})
		}
		if r.Context().Err() != nil {
			return
		}
		// Set device headers first, then artifact's own type.
		for k, v := range s.o.Headers {
			w.Header().Set(k, v)
		}
		if s.store.captions != nil && s.store.captions.serve(w, r) {
			return
		}
		// Go doesn't register .m3u8/.m4s; set type before ServeContent sniffs.
		if ct, ok := artifactContentType(r.URL.Path); ok {
			w.Header().Set("Content-Type", ct)
		}
		// Count from answer, not request (404 on window-rolled segments shouldn't count).
		answer := &answered{ResponseWriter: w}
		files.ServeHTTP(answer, r)
		if r.Method == http.MethodGet && answer.carriedBytes() && isMediaArtifact(strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")) {
			s.served.Add(1)
		}
	})
	return mux
}

// answered tracks response status for Served count (ResponseWriter doesn't report it).
type answered struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (a *answered) WriteHeader(code int) {
	if a.status == 0 {
		// First status is the one sent; second WriteHeader never reaches wire.
		a.status = code
	}
	a.ResponseWriter.WriteHeader(code)
}

func (a *answered) Write(b []byte) (int, error) {
	if a.status == 0 {
		// net/http's implicit 200
		a.status = http.StatusOK
	}
	n, err := a.ResponseWriter.Write(b)
	a.bytes += int64(n)
	return n, err
}

// ReadFrom keeps file server's ReaderFrom (sendfile, not userspace copy).
func (a *answered) ReadFrom(r io.Reader) (int64, error) {
	if a.status == 0 {
		a.status = http.StatusOK
	}
	n, err := io.Copy(a.ResponseWriter, r)
	a.bytes += n
	return n, err
}

// Unwrap lets http.ResponseController reach the connection's own flush and deadlines.
func (a *answered) Unwrap() http.ResponseWriter { return a.ResponseWriter }

// carriedBytes reports successful response (2xx); 404 and 416 hand over nothing.
func (a *answered) carriedBytes() bool { return a.status >= 200 && a.status < 300 && a.bytes > 0 }
