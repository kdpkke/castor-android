package follow

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
	"testing/synctest"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
)

// listed is a window of one-second segments named after their origin sequence, as an origin numbering from first publishes them.
func listed(prefix string, first, last int64) timeline.Window {
	var w timeline.Window
	for n := first; n <= last; n++ {
		w.Segments = append(w.Segments, timeline.Segment{
			URI:      fmt.Sprintf("https://origin.example/%s%03d.ts", prefix, n),
			Duration: time.Second,
			Place:    timeline.Place{Start: n, End: n + 1},
		})
	}
	return w
}

// scripted answers each window request with the next reply in turn, repeating the last.
type scripted struct {
	replies []reply
	asked   int
}

type reply struct {
	window timeline.Window
	err    error
}

// testFeed uses an undecorated timeline, as the synthetic origins in relay tests do.
func testFeed(ctx context.Context, name string, origin timeline.Source, repackage Repackage) *feed {
	return newFeed(ctx, name, timeline.Reading{Source: origin, Decoder: timeline.IdentityDecoder{}}, repackage)
}

func (*scripted) Read(context.Context, string, timeline.Range) (io.ReadCloser, error) {
	return nil, errors.New("scripted origins serve no media")
}

func (s *scripted) Window(context.Context) (timeline.Window, error) {
	r := s.replies[min(s.asked, len(s.replies)-1)]
	s.asked++
	return r.window, r.err
}

func serving(t *testing.T, s timeline.Source, repackage Repackage) *server {
	t.Helper()
	server, err := serve(testFeed(t.Context(), "primary", s, repackage))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = server.Close() })
	return server
}

func fetch(t *testing.T, u string) (int, string) {
	t.Helper()
	resp, err := http.Get(u)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(body)
}

func TestAReloadTheOriginCouldNotAnswerIsAnsweredFromTheLastRender(t *testing.T) {
	server := serving(t, &scripted{replies: []reply{
		{window: listed("a", 0, 3)},
		{err: &timeline.Failure{Status: http.StatusServiceUnavailable, Err: errors.New("busy")}},
	}}, nil)
	_, first := fetch(t, server.URL("primary").String())
	status, second := fetch(t, server.URL("primary").String())
	if status != http.StatusOK || second != first {
		t.Errorf("a 503 on reload answered %d %q, want the last render", status, second)
	}
}

func TestAnOriginsFinalNoIsRelayed(t *testing.T) {
	server := serving(t, &scripted{replies: []reply{
		{window: listed("a", 0, 3)},
		{err: &timeline.Failure{Status: http.StatusForbidden, Err: errors.New("expired")}},
	}}, nil)
	fetch(t, server.URL("primary").String())
	if status, _ := fetch(t, server.URL("primary").String()); status != http.StatusForbidden {
		t.Errorf("a 403 on reload answered %d, want the origin's 403", status)
	}
}

func TestAFirstReadTheOriginCouldNotAnswerIsABadGateway(t *testing.T) {
	server := serving(t, &scripted{replies: []reply{{err: errors.New("connection reset")}}}, nil)
	if status, _ := fetch(t, server.URL("primary").String()); status != http.StatusBadGateway {
		t.Errorf("answered %d with nothing to fall back on, want 502", status)
	}
}

func TestAClosedTimelineIsNeverFetchedAgain(t *testing.T) {
	w := listed("a", 0, 3)
	w.Closed = true
	origin := &scripted{replies: []reply{{window: w}}}
	server := serving(t, origin, nil)
	fetch(t, server.URL("primary").String())
	_, body := fetch(t, server.URL("primary").String())
	if origin.asked != 1 || !strings.HasSuffix(body, "#EXT-X-ENDLIST\n") {
		t.Errorf("fetched the origin %d times after it closed (body %q)", origin.asked, body)
	}
}

// A timeline castor must read before any cast starts is read again after a passing failure, never after a final no.
func TestAFeedStartsThroughAPassingFailureButNotARefusal(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		busy := &timeline.Failure{Status: http.StatusServiceUnavailable, Err: errors.New("busy")}
		passing := &scripted{replies: []reply{{err: busy}, {err: errors.New("connection reset")}, {window: listed("a", 0, 3)}}}
		if err := testFeed(t.Context(), "primary", passing, nil).start(); err != nil {
			t.Errorf("start = %v after a 503 and a reset, want the window the origin answered next", err)
		}

		gone := &scripted{replies: []reply{{err: &timeline.Failure{Status: http.StatusGone, Err: errors.New("expired")}}, {window: listed("a", 0, 3)}}}
		if err := testFeed(t.Context(), "primary", gone, nil).start(); err == nil || gone.asked != 1 {
			t.Errorf("start = %v after %d reads, want the origin's 410 at once", err, gone.asked)
		}
	})
}
