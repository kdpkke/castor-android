// Package mediaroute is where devices fetch the handlers a cast publishes.
package mediaroute

import "net/http"

// ServeHTTP routes device requests to their published media or captions handler.
func (s *Publisher) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mux.ServeHTTP(w, r)
}

func (s *Publisher) serveDelivery(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("delivery")
	s.mu.Lock()
	p := s.open[id]
	s.mu.Unlock()
	if p == nil {
		http.NotFound(w, r)
		return
	}
	http.StripPrefix("/media/"+id, p).ServeHTTP(w, r)
}
