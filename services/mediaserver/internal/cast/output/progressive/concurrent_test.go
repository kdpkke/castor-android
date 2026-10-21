package progressive

import (
	"bytes"
	"fmt"
	"io"
	"sync"
	"testing"
	"testing/synctest"
)

func TestConcurrentRangeAndDisconnectStorm(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		want := payload(1 << 20)
		srv := spooled(t, testOpening{}, want)
		var wg sync.WaitGroup
		for i := range 96 {
			wg.Go(func() {
				start := i * 4096
				resp := get(t, srv, fmt.Sprintf("bytes=%d-%d", start, start+4095))
				defer resp.Body.Close()
				if i%3 == 0 {
					_, _ = io.CopyN(io.Discard, resp.Body, 1)
					return
				}
				got, err := io.ReadAll(resp.Body)
				if err != nil || !bytes.Equal(got, want[start:start+4096]) {
					t.Errorf("consumer %d: %d bytes %v", i, len(got), err)
				}
			})
		}
		wg.Wait()
		synctest.Wait()
		srv.audience.mu.Lock()
		active := srv.audience.active
		srv.audience.mu.Unlock()
		if active != 0 {
			t.Errorf("%d active handlers after all clients finished", active)
		}
	})
}
