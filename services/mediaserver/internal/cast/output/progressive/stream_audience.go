package progressive

import (
	"sync"
	"time"
)

// streamAudience owns the changing observations of concurrent HTTP readers.
// Producer ownership and server configuration never change with these observations.
type streamAudience struct {
	mu             sync.Mutex
	active         int
	completed      bool
	lastDisconnect time.Time
	sent           int64
	lastFetch      time.Time
}

func (a *streamAudience) joined() func(bool) {
	a.mu.Lock()
	a.active++
	a.mu.Unlock()
	return func(reachedEOF bool) {
		a.mu.Lock()
		defer a.mu.Unlock()
		a.active--
		a.completed = a.completed || reachedEOF
		a.lastDisconnect = time.Now()
	}
}

func (a *streamAudience) record(sent int64, at time.Time) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.sent = max(a.sent, sent)
	a.lastFetch = at
}

func (a *streamAudience) handed() (int64, time.Time) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.sent, a.lastFetch
}

func (a *streamAudience) settled(grace time.Duration) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.active == 0 && (a.completed || time.Since(a.lastDisconnect) > grace)
}
