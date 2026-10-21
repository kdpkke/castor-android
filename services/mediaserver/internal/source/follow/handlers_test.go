package follow

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"testing/iotest"

	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
)

func TestRepublishedVODFramesLargeHTTPBodies(t *testing.T) {
	window := listed("segment-", 0, 299)
	window.Closed = true
	origin := &stored{window: window, resources: map[string][]byte{}}
	segment := bytes.Repeat([]byte("media"), 1024)
	for _, s := range window.Segments {
		origin.resources[s.URI] = segment
	}
	feed := testFeed(t.Context(), "primary", origin, nil)
	t.Cleanup(feed.wait)

	playlist := httptest.NewRecorder()
	feed.playlist(playlist, httptest.NewRequest(http.MethodGet, "/primary.m3u8", nil))
	if got, want := playlist.Header().Get("Content-Length"), strconv.Itoa(playlist.Body.Len()); got != want || playlist.Body.Len() <= 2048 {
		t.Errorf("playlist Content-Length = %q for %d bytes, want %s", got, playlist.Body.Len(), want)
	}

	request := httptest.NewRequest(http.MethodGet, "/primary/0", nil)
	request.SetPathValue("sequence", "0")
	response := httptest.NewRecorder()
	feed.segment(response, request)
	if got, want := response.Header().Get("Content-Length"), strconv.Itoa(len(segment)); got != want || !bytes.Equal(response.Body.Bytes(), segment) {
		t.Errorf("segment Content-Length = %q for %d bytes, want %s and the complete segment", got, response.Body.Len(), want)
	}

	fragments := fragmented("avc1")
	fragments.resources["https://origin.example/0.m4s"] = segment
	repackaged := testFeed(t.Context(), "primary", fragments, labelled)
	t.Cleanup(repackaged.wait)
	if err := repackaged.start(); err != nil {
		t.Fatal(err)
	}
	request = httptest.NewRequest(http.MethodGet, "/primary/0.ts", nil)
	request.SetPathValue("sequence", "0.ts")
	response = httptest.NewRecorder()
	repackaged.segment(response, request)
	if got, want := response.Header().Get("Content-Length"), strconv.Itoa(response.Body.Len()); got != want || !bytes.HasPrefix(response.Body.Bytes(), []byte("TS:")) || response.Body.Len() <= 2048 {
		t.Errorf("repackaged segment Content-Length = %q for %d bytes, want %s and a complete MPEG-TS body", got, response.Body.Len(), want)
	}
}

func TestRepublishedInputsRequireTheirPublicationURL(t *testing.T) {
	server := serving(t, fragmented("avc1"), labelled)
	for _, path := range []string{"/primary.m3u8", "/unknown/primary.m3u8", server.base.Path + "wrong/primary.m3u8"} {
		at := *server.base
		at.Path = path
		if status, _ := fetch(t, at.String()); status != http.StatusNotFound {
			t.Errorf("unpublished path %s answered %d, want 404", path, status)
		}
	}
	if status, body := fetch(t, server.URL("primary").String()); status != http.StatusOK || !strings.HasPrefix(body, "#EXTM3U") {
		t.Fatalf("published playlist: status %d, body %q", status, body)
	}
}

func TestASegmentCastorNoLongerHoldsIsGone(t *testing.T) {
	server := serving(t, fragmented("avc1"), labelled)
	fetch(t, server.URL("primary").String())
	if status, _ := fetch(t, server.base.JoinPath("primary", "9.ts").String()); status != http.StatusNotFound {
		t.Errorf("a segment the ledger never held answered %d, want 404", status)
	}
}

func TestTheOriginsRefusalIsRelayedAndAnythingElseIsABadGateway(t *testing.T) {
	for status, want := range map[int]int{http.StatusForbidden: http.StatusForbidden, http.StatusOK: http.StatusBadGateway} {
		m := fragmented("av01")
		m.fail = &timeline.Failure{Status: status, Err: errors.New("origin")}
		server := serving(t, m, labelled)
		fetch(t, server.URL("primary").String())
		if got, _ := fetch(t, server.base.JoinPath("primary", "1").String()); got != want {
			t.Errorf("an origin failing with %d answered %d, want %d", status, got, want)
		}
	}
}

// An empty 200 would read as an empty segment, which the reader never retries.
func TestABodyThatBreaksBeforeItsFirstByteIsABadGateway(t *testing.T) {
	m := fragmented("av01")
	m.breaks = true
	server := serving(t, m, labelled)
	fetch(t, server.URL("primary").String())
	if got, _ := fetch(t, server.base.JoinPath("primary", "1").String()); got != http.StatusBadGateway {
		t.Errorf("a segment whose body broke before any byte answered %d, want 502", got)
	}
}

type truncatedSegment struct{ *stored }

func (s truncatedSegment) Read(ctx context.Context, uri string, r timeline.Range) (io.ReadCloser, error) {
	if strings.HasSuffix(uri, ".m4s") {
		return io.NopCloser(io.MultiReader(strings.NewReader("partial fragment"), iotest.ErrReader(io.ErrUnexpectedEOF))), nil
	}
	return s.stored.Read(ctx, uri, r)
}

func TestABodyThatBreaksAfterItsFirstByteDoesNotSucceedAsAPartialSegment(t *testing.T) {
	server := serving(t, truncatedSegment{fragmented("av01")}, nil)
	fetch(t, server.URL("primary").String())
	resp, err := http.Get(server.base.JoinPath("primary", "1").String())
	if err == nil {
		defer resp.Body.Close()
		_, err = io.ReadAll(resp.Body)
	}
	if err == nil && resp.StatusCode == http.StatusOK {
		t.Fatal("a truncated fragment was answered as a complete HTTP response")
	}
}

func TestAResumeOrAHeadNeverReachesTheOrigin(t *testing.T) {
	m := fragmented("av01")
	server := serving(t, m, labelled)
	fetch(t, server.URL("primary").String())
	req, _ := http.NewRequest(http.MethodGet, server.base.JoinPath("primary", "1").String(), nil)
	req.Header.Set("Range", "bytes=4-")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusRequestedRangeNotSatisfiable {
		t.Errorf("a resume partway in answered %d, want 416", resp.StatusCode)
	}
	head, err := http.Head(server.base.JoinPath("primary", "1").String())
	if err != nil {
		t.Fatal(err)
	}
	head.Body.Close()
	if head.StatusCode != http.StatusOK || m.reads["https://origin.example/1.m4s"] != 0 {
		t.Errorf("a HEAD answered %d after %d origin reads, want 200 and none", head.StatusCode, m.reads["https://origin.example/1.m4s"])
	}
}

func TestAKeyCastorCannotApplyIsRelayedForTheReader(t *testing.T) {
	m := fragmented("av01")
	m.resources["https://origin.example/skey"] = []byte("key bytes")
	for i := range m.window.Segments {
		m.window.Segments[i].Key = timeline.Key{Method: "SAMPLE-AES", URI: "https://origin.example/skey", IV: "0x01"}
	}
	server := serving(t, m, labelled)
	_, playlist := fetch(t, server.URL("primary").String())
	if !strings.Contains(playlist, `#EXT-X-KEY:METHOD=SAMPLE-AES,URI="primary/key/0",IV=0x01`) {
		t.Fatalf("the key is not relayed through castor:\n%s", playlist)
	}
	if _, key := fetch(t, server.base.JoinPath("primary", "key", "0").String()); key != "key bytes" {
		t.Errorf("the relayed key = %q", key)
	}
}
