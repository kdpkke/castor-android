package follow

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
)

// throttled is an origin serving each request at a fixed pace, whatever else it serves at once, and hanging the first request of hung.
type throttled struct {
	window timeline.Window
	each   time.Duration
	hung   map[string]bool

	mu      sync.Mutex
	asked   map[string]int
	busy    int
	busiest int
}

func (o *throttled) Window(context.Context) (timeline.Window, error) { return o.window, nil }

func (o *throttled) Read(ctx context.Context, uri string, _ timeline.Range) (io.ReadCloser, error) {
	o.mu.Lock()
	o.asked[uri]++
	first := o.asked[uri] == 1
	o.busy++
	o.busiest = max(o.busiest, o.busy)
	o.mu.Unlock()
	defer func() {
		o.mu.Lock()
		o.busy--
		o.mu.Unlock()
	}()
	wait := o.each
	if first && o.hung[uri] {
		wait = time.Hour
	}
	select {
	case <-time.After(wait):
		return io.NopCloser(bytes.NewReader([]byte(uri))), nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func origin(segments int, each time.Duration, hung ...string) *throttled {
	o := &throttled{each: each, asked: map[string]int{}, hung: map[string]bool{}}
	for i := range segments {
		uri := fmt.Sprintf("https://origin.example/%d.ts", i)
		o.window.Segments = append(o.window.Segments, timeline.Segment{URI: uri, Duration: 4 * time.Second, Place: timeline.Place{Start: int64(i), End: int64(i + 1)}})
	}
	for _, h := range hung {
		o.hung[h] = true
	}
	o.window.Closed = true
	return o
}

// played asks for every segment in order, as a reader does, and reports how long it took.
func played(t *testing.T, f *feed, segments int) time.Duration {
	t.Helper()
	began := time.Now()
	for i := range segments {
		f.mu.Lock()
		s, ok := f.ledger.Entry(int64(i))
		f.mu.Unlock()
		if !ok {
			t.Fatalf("segment %d is not listed", i)
		}
		b, err := f.fetched(t.Context(), int64(i), s)
		if err != nil || string(b) != s.URI {
			t.Fatalf("segment %d = %q, %v; want its bytes", i, b, err)
		}
	}
	return time.Since(began)
}

func TestSegmentsAreReadAheadSeveralAtOnce(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		o := origin(12, 2*time.Second)
		f := testFeed(t.Context(), "primary", o, nil)
		if err := f.start(); err != nil {
			t.Fatal(err)
		}
		// One at a time, 12 segments at 2s each would take 24s.
		if took := played(t, f, 12); took > 8*time.Second {
			t.Errorf("reading 12 segments took %v, want them read several at once", took)
		}
		if o.busiest > parallel {
			t.Errorf("the origin served %d reads at once, want at most %d", o.busiest, parallel)
		}
	})
}

func TestASegmentReadThatHangsIsAskedForAgain(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		o := origin(3, time.Second, "https://origin.example/1.ts")
		f := testFeed(t.Context(), "primary", o, nil)
		if err := f.start(); err != nil {
			t.Fatal(err)
		}
		if took := played(t, f, 3); took > hedgeDefault+2*time.Second {
			t.Errorf("a hung read held playback %v, want it asked again within the hedge deadline", took)
		}
		if o.asked["https://origin.example/1.ts"] != 2 {
			t.Errorf("the hung segment was asked for %d times, want twice", o.asked["https://origin.example/1.ts"])
		}
	})
}

func TestReadingAheadStopsAtTheWindow(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		o := origin(40, 100*time.Millisecond)
		f := testFeed(t.Context(), "primary", o, nil)
		if err := f.start(); err != nil {
			t.Fatal(err)
		}
		played(t, f, 1)
		synctest.Wait()
		o.mu.Lock()
		defer o.mu.Unlock()
		// The reader asked for segment 0; 30s ahead of it is 8 segments of 4s.
		if read := len(o.asked); read > 1+int(ahead/(4*time.Second))+1 {
			t.Errorf("read %d of 40 segments for a reader at the first, want only the window ahead of it", read)
		}
	})
}

// flaky fails the first read of each uri it is told to, then serves it.
type flaky struct {
	*throttled
	failing map[string]bool
}

func (o *flaky) Read(ctx context.Context, uri string, r timeline.Range) (io.ReadCloser, error) {
	o.mu.Lock()
	fail := o.failing[uri]
	delete(o.failing, uri)
	o.mu.Unlock()
	if fail {
		return nil, errors.New("connection reset")
	}
	return o.throttled.Read(ctx, uri, r)
}

func TestASegmentThatFailedAheadOfTheReaderIsReadAgainWhenAskedFor(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		uri := "https://origin.example/1.ts"
		f := testFeed(t.Context(), "primary", origin(3, time.Second), nil)
		if err := f.start(); err != nil {
			t.Fatal(err)
		}
		s, _ := f.ledger.Entry(1)
		f.mu.Lock()
		f.slots[1] = &slot{done: make(chan struct{}), err: errors.New("stale failure")}
		close(f.slots[1].done)
		f.mu.Unlock()
		b, err := f.fetched(t.Context(), 1, s)
		if err != nil || string(b) != uri {
			t.Errorf("segment 1 = %q, %v; want it read again, not the earlier failure replayed", b, err)
		}
	})
}

func TestAFailedReadIsAskedAgainAfterAPause(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		uri := "https://origin.example/0.ts"
		o := &flaky{throttled: origin(1, 0), failing: map[string]bool{uri: true}}
		f := testFeed(t.Context(), "primary", o, nil)
		if err := f.start(); err != nil {
			t.Fatal(err)
		}
		began := time.Now()
		if took := played(t, f, 1); took < retryAfter {
			t.Errorf("asked again after %v, want a pause of at least %v", time.Since(began), retryAfter)
		}
	})
}

func TestAnOriginThatGoesQuietMidReadIsGivenUpOn(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		pr, pw := io.Pipe()
		defer pw.Close()
		go func() { _, _ = pw.Write([]byte("start")) }()
		f := testFeed(t.Context(), "primary", readerSource{pr}, nil)
		_, err := f.pull(t.Context(), "https://origin.example/0.ts", timeline.Range{}, segmentLimit)
		if !errors.Is(err, errIdle) {
			t.Errorf("pull = %v, want the quiet origin given up on", err)
		}
	})
}

func TestAResourceLargerThanItsLimitIsRefused(t *testing.T) {
	body := strings.Repeat("x", 100)
	f := testFeed(t.Context(), "primary", readerSource{strings.NewReader(body)}, nil)
	if _, err := f.pull(t.Context(), "https://origin.example/0.ts", timeline.Range{}, 99); err == nil {
		t.Error("read 100 bytes against a limit of 99")
	}
	f = testFeed(t.Context(), "primary", readerSource{strings.NewReader(body)}, nil)
	if b, err := f.pull(t.Context(), "https://origin.example/0.ts", timeline.Range{}, 100); err != nil || len(b) != 100 {
		t.Errorf("pull at the limit = %d bytes, %v; want them all", len(b), err)
	}
}

// readerSource serves one body for every read, honouring cancellation when it is a pipe.
type readerSource struct{ io.Reader }

func (readerSource) Window(context.Context) (timeline.Window, error) { return timeline.Window{}, nil }

func (s readerSource) Read(ctx context.Context, _ string, _ timeline.Range) (io.ReadCloser, error) {
	if pr, ok := s.Reader.(*io.PipeReader); ok {
		go func() { <-ctx.Done(); pr.CloseWithError(context.Cause(ctx)) }()
	}
	return io.NopCloser(s.Reader), nil
}

func TestWinningHedgeJoinsTheCancelledOriginRead(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		o := origin(1, time.Second, "https://origin.example/0.ts")
		f := testFeed(t.Context(), "primary", o, nil)
		if _, err := f.hedged(t.Context(), o.window.Segments[0]); err != nil {
			t.Fatal(err)
		}
		o.mu.Lock()
		defer o.mu.Unlock()
		if o.busy != 0 {
			t.Fatalf("hedged returned with %d origin reads still running", o.busy)
		}
	})
}
