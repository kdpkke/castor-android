package browserstream

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"
)

func TestPlayerPlaylistsReloadAndPreserveReferences(t *testing.T) {
	playlists := New()
	defer playlists.Close()
	server := httptest.NewServer(playlists)
	defer server.Close()
	var reads atomic.Int32
	group, err := playlists.Retain(server.URL, func(_ context.Context, source, address string) (Document, error) {
		if source != "https://media.example/master" {
			t.Errorf("source = %s", source)
		}
		reads.Add(1)
		if address == source {
			return Document{URL: source, Body: "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo\n"}, nil
		}
		return Document{URL: "https://cdn.example/live/index", Body: fmt.Sprintf("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:%d\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nsegment.ts\n", reads.Load())}, nil
	}, func() {})
	if err != nil {
		t.Fatal(err)
	}
	root, err := group.Register("https://media.example/master", "https://media.example/master")
	if err != nil {
		t.Fatal(err)
	}
	read := func(address string) string {
		t.Helper()
		response, err := http.Get(address)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil || response.StatusCode != 200 {
			t.Fatalf("status=%d error=%v body=%s", response.StatusCode, err, body)
		}
		return string(body)
	}
	master := read(root)
	if strings.Contains(master, `URI="audio"`) {
		t.Fatal("extensionless audio playlist bypassed the player")
	}
	lines := strings.Split(strings.TrimSpace(master), "\n")
	child := lines[len(lines)-1]
	first := read(child)
	second := read(child)
	if first == second || !strings.Contains(second, "https://cdn.example/live/segment.ts") || !strings.Contains(second, `URI="https://cdn.example/live/key"`) || !strings.Contains(second, `URI="https://cdn.example/live/init.mp4"`) {
		t.Fatalf("bad refreshed media playlist %s", second)
	}
}

func TestClosingPlayerPlaylistsCancelsReadsAndRevokesCapabilities(t *testing.T) {
	playlists := New()
	defer playlists.Close()
	server := httptest.NewServer(playlists)
	defer server.Close()
	started := make(chan struct{})
	var released atomic.Int32
	group, err := playlists.Retain(server.URL, func(ctx context.Context, _, _ string) (Document, error) {
		close(started)
		<-ctx.Done()
		return Document{}, ctx.Err()
	}, func() { released.Add(1) })
	if err != nil {
		t.Fatal(err)
	}
	address, err := group.Register("https://media.example/live", "https://media.example/live")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		response, err := http.Get(address)
		if err == nil {
			response.Body.Close()
		}
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("reader did not start")
	}
	group.Close()
	<-done
	response, err := http.Get(address)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 404 || released.Load() != 1 {
		t.Fatalf("status=%d releases=%d", response.StatusCode, released.Load())
	}
	if _, err := group.Register("https://media.example/live", "https://media.example/late"); err == nil {
		t.Fatal("released page registered another capability")
	}
}

func TestPlayerPlaylistValidationAndExpiry(t *testing.T) {
	for _, body := range []string{"<html>failure</html>", "#EXTM3U\n#EXTINF:6,\nfile:///private\n", "#EXTM3U" + strings.Repeat(" ", documentLimit)} {
		t.Run(fmt.Sprint(len(body)), func(t *testing.T) {
			playlists := New()
			defer playlists.Close()
			group, err := playlists.Retain("http://localhost:1234", func(context.Context, string, string) (Document, error) {
				return Document{URL: "https://media.example/live", Body: body}, nil
			}, func() {})
			if err != nil {
				t.Fatal(err)
			}
			if _, err := group.render("https://media.example/live", Document{URL: "https://media.example/live", Body: body}); err == nil {
				t.Fatal("invalid playlist accepted")
			}
			group.last = time.Now().Add(-2 * time.Minute)
			group.expire()
			if group.ctx.Err() == nil {
				t.Fatal("unused browser was retained")
			}
		})
	}
}

func TestPlayerPlaylistRenderingPublishesOnlyValidChildren(t *testing.T) {
	playlists := New()
	defer playlists.Close()
	group, err := playlists.Retain("http://localhost:1234", nil, func() {})
	if err != nil {
		t.Fatal(err)
	}
	source := "https://media.example/master"
	if _, err := group.render(source, Document{URL: source, Body: "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"file:///private\"\n"}); err == nil {
		t.Fatal("invalid child accepted")
	}
	if len(playlists.routes) != 0 || len(group.paths) != 0 {
		t.Fatal("failed render published a partial document")
	}
	body, err := group.render(source, Document{URL: source, Body: "\ufeff#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo\n"})
	if err != nil || !strings.Contains(body, Prefix) || len(playlists.routes) != 1 {
		t.Fatalf("BOM playlist: %s %v", body, err)
	}
}

func TestCancelledRequestDoesNotOverlapTheNextPlayerLoad(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		playlists := New()
		defer playlists.Close()
		finish := make(chan struct{})
		var reads atomic.Int32
		var finished atomic.Bool
		source := "https://media.example/live"
		group, err := playlists.Retain("http://localhost:1234", func(ctx context.Context, _, _ string) (Document, error) {
			reads.Add(1)
			defer finished.Store(true)
			select {
			case <-finish:
				return Document{URL: source, Body: "#EXTM3U\n#EXT-X-ENDLIST\n"}, nil
			case <-ctx.Done():
				return Document{}, ctx.Err()
			}
		}, func() {})
		if err != nil {
			t.Fatal(err)
		}
		address, err := group.Register(source, source)
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		first := httptest.NewRecorder()
		go playlists.ServeHTTP(first, httptest.NewRequestWithContext(ctx, http.MethodGet, address, nil))
		synctest.Wait()
		cancel()
		synctest.Wait()
		if finished.Load() {
			t.Error("a cancelled HTTP request released a loader that was still running")
		}
		close(finish)
		synctest.Wait()
		second := httptest.NewRecorder()
		playlists.ServeHTTP(second, httptest.NewRequest(http.MethodGet, address, nil))
		if second.Code != http.StatusOK || reads.Load() != 2 {
			t.Errorf("next read status=%d, loads=%d", second.Code, reads.Load())
		}
	})
}
