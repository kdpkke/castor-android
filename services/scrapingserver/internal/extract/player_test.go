package extract

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stupside/castor/internal/browserstream"
)

func TestPlayerPreparedPlaylistsStayReadableAfterExtraction(t *testing.T) {
	chrome := os.Getenv("TEST_CASTOR_CHROME")
	if chrome == "" {
		t.Skip("set TEST_CASTOR_CHROME to exercise the real browser player")
	}
	fixture, err := os.ReadFile("../../../../internal/browserstream/testdata/player.html")
	if err != nil {
		t.Fatal(err)
	}
	var accesses, visits atomic.Int32
	frame := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/player":
			visits.Add(1)
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write(fixture)
		case "/access":
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w, `{"token":%d}`, accesses.Add(1))
		case "/manifest":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			fmt.Fprint(w, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nopaque-reference\n")
		default:
			http.NotFound(w, r)
		}
	}))
	defer frame.Close()
	page := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprintf(w, `<iframe src="%s/player" style="width:800px;height:600px"></iframe>`, frame.URL)
	}))
	defer page.Close()
	extractor := New(Config{Browser: BrowserConfig{ChromePath: chrome, Headless: true, Timeout: 10 * time.Second}, Capture: CaptureConfig{MaxConcurrency: 2}})
	defer extractor.Playlists.Close()
	relay := httptest.NewServer(extractor.Playlists)
	defer relay.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	// Retained players must not occupy extraction slots needed by the rest of a batch.
	streams, err := extractor.Resolve(browserstream.WithBase(ctx, relay.URL), []string{page.URL + "/first", page.URL + "/second", page.URL + "/third"})
	if err != nil {
		t.Fatal(err)
	}
	if len(streams) != 3 || !strings.HasPrefix(streams[0].Stream.Url, relay.URL+browserstream.Prefix) {
		t.Fatalf("player source was not retained: %v", streams)
	}
	cancel() // The extraction RPC can finish while the source still needs its page.
	read := func() string {
		t.Helper()
		response, err := http.Get(streams[0].Stream.Url)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, _ := io.ReadAll(response.Body)
		if response.StatusCode != 200 {
			t.Fatalf("status=%d body=%s", response.StatusCode, body)
		}
		return string(body)
	}
	before := visits.Load()
	first, second := read(), read()
	if first == second || !strings.Contains(second, frame.URL+"/segments/") || strings.Contains(second, "opaque-reference") {
		t.Fatalf("player transformation or reload missing: %s", second)
	}
	if visits.Load() != before {
		t.Fatal("playlist reload navigated the page again")
	}
	extractor.Playlists.Close()
	response, err := http.Get(streams[0].Stream.Url)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 404 {
		t.Fatalf("released capability status=%d", response.StatusCode)
	}
}

func TestPlayerBindingPreservesMastersAndExactRenditions(t *testing.T) {
	first := playerPlaylist{Source: "https://media.example/master", URL: "https://media.example/level?quality=1"}
	second := first
	second.URL = "https://media.example/level?quality=2"
	documents := []playerPlaylist{first, second}
	master, found := matchPlayer(first.Source, documents)
	if !found || master.URL != first.Source {
		t.Fatal("multiple levels hid their owning master")
	}
	selected, found := matchPlayer(second.URL, documents)
	if !found || selected.URL != second.URL {
		t.Fatal("an exact rendition did not keep its query")
	}
	if _, found := matchPlayer("https://media.example/level?expired=1", documents); found {
		t.Fatal("an ambiguous renewed rendition was selected")
	}
}
