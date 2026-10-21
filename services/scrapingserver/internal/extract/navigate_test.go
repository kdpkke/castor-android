package extract

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/chromedp/cdproto/runtime"
	"github.com/chromedp/chromedp"
)

func TestOnlyAFrameOnTheWebIsOpened(t *testing.T) {
	for src, want := range map[string]bool{
		"https://player.example/embed/1": true,
		"http://player.example/embed/1":  true,
		"javascript:alert(1)":            false,
		"data:text/html,<p>x</p>":        false,
		"file:///etc/passwd":             false,
		"chrome://settings":              false,
		"blob:https://site.example/id":   false,
	} {
		if got := webPage(src); got != want {
			t.Errorf("webPage(%q) = %v, want %v", src, got, want)
		}
	}
}

func TestVisibleIframeNavigationWaitsForItsDocumentAndPreservesReferrer(t *testing.T) {
	chrome := os.Getenv("TEST_CASTOR_CHROME")
	if chrome == "" {
		t.Skip("set TEST_CASTOR_CHROME to exercise real browser navigation")
	}
	requests := make(chan string, 8)
	ts := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		switch r.URL.Path {
		case "/parent":
			fmt.Fprint(w, `<iframe src="about:blank" style="width:1000px;height:600px"></iframe><iframe src="/hidden" style="visibility:hidden;width:1000px;height:600px"></iframe><iframe src="/child" style="position:fixed;top:0;left:0;width:300px;height:200px"></iframe>`)
		case "/child":
			requests <- r.Referer()
			fmt.Fprint(w, `<body id="child">child</body>`)
		default:
			fmt.Fprint(w, `<body>hidden</body>`)
		}
	}))
	ts.Start()
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	s, err := newSession(ctx, BrowserConfig{ChromePath: chrome, Headless: true, Timeout: 10 * time.Second}, ts.URL+"/parent")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	t.Run("canvas export preserves source pixels", func(t *testing.T) {
		var preserved bool
		if err := chromedp.Run(s.ctx, chromedp.Evaluate(`(() => {
			const canvas = document.createElement('canvas');
			canvas.width = 16; canvas.height = 1;
			const ctx = canvas.getContext('2d');
			ctx.fillStyle = '#808080'; ctx.fillRect(0, 0, 16, 1);
			const before = Array.from(ctx.getImageData(0, 0, 16, 1).data).join(',');
			canvas.toDataURL(); canvas.toDataURL();
			return before === Array.from(ctx.getImageData(0, 0, 16, 1).data).join(',');
		})()`, &preserved)); err != nil {
			t.Fatal(err)
		}
		if !preserved {
			t.Fatal("export changed the canvas used by the page")
		}
	})
	t.Run("notification permission supports event listeners", func(t *testing.T) {
		var supported bool
		if err := chromedp.Run(s.ctx, chromedp.Evaluate(`navigator.permissions.query({name:'notifications'}).then(status => {
			status.addEventListener('change', () => {}); return true;
		})`, &supported, func(p *runtime.EvaluateParams) *runtime.EvaluateParams { return p.WithAwaitPromise(true) })); err != nil {
			t.Fatal(err)
		}
		if !supported {
			t.Fatal("permission query no longer supplies a usable PermissionStatus")
		}
	})
	var frames []frameCandidate
	if err := chromedp.Run(s.ctx, chromedp.Evaluate(iframeSrcJS, &frames)); err != nil {
		t.Fatal(err)
	}
	if len(frames) != 1 || frames[0].Src != ts.URL+"/child" {
		t.Fatalf("visible iframe selection = %v", frames)
	}
	if err := openFrame(s.ctx, frames[0].Src, ts.URL+"/parent"); err != nil {
		t.Fatal(err)
	}
	var body string
	if err := chromedp.Run(s.ctx, chromedp.Text("body", &body)); err != nil || body != "child" {
		t.Fatalf("frame navigation returned before new body: %q, %v", body, err)
	}
	for range 2 { // The embedded load, then opening that frame as the page.
		select {
		case referer := <-requests:
			if referer != ts.URL+"/parent" {
				t.Errorf("frame Referer = %q", referer)
			}
		case <-ctx.Done():
			t.Fatal("frame request missing")
		}
	}
}

func TestOffscreenLazyIframeIsDiscovered(t *testing.T) {
	chrome := os.Getenv("TEST_CASTOR_CHROME")
	if chrome == "" {
		t.Skip("set TEST_CASTOR_CHROME to exercise real browser navigation")
	}
	ts := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, `<iframe loading="lazy" src="/child" style="position:absolute;top:10000px;width:640px;height:360px"></iframe>`)
	}))
	ts.Start()
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	s, err := newSession(ctx, BrowserConfig{ChromePath: chrome, Headless: true, Timeout: 10 * time.Second}, ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	var frames []frameCandidate
	if err := chromedp.Run(s.ctx, chromedp.Evaluate(iframeSrcJS, &frames)); err != nil {
		t.Fatal(err)
	}
	if len(frames) != 1 || frames[0].Src != ts.URL+"/child" {
		t.Fatalf("offscreen frame=%v", frames)
	}
}

func TestNavigatesPastLargerFrameAndClicksVideo(t *testing.T) {
	chrome := os.Getenv("TEST_CASTOR_CHROME")
	if chrome == "" {
		t.Skip("set TEST_CASTOR_CHROME to exercise real browser navigation")
	}
	ts := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		switch r.URL.Path {
		case "/":
			fmt.Fprint(w, `<iframe src="/ad" style="width:1000px;height:600px"></iframe><iframe src="/player" style="width:640px;height:360px"></iframe>`)
		case "/ad":
			fmt.Fprint(w, `<body>advertisement</body>`)
		case "/player":
			fmt.Fprint(w, `<video style="width:640px;height:360px" onclick="fetch('/master.m3u8')"></video>`)
		case "/master.m3u8":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			fmt.Fprint(w, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nmovie.m3u8\n")
		default:
			http.NotFound(w, r)
		}
	}))
	ts.Start()
	defer ts.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 25*time.Second)
	defer cancel()
	s, err := newSession(ctx, BrowserConfig{ChromePath: chrome, Headless: true, Timeout: 10 * time.Second}, ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := navigateIframe(s.ctx, s.collector.hasMaster); err != nil {
		t.Fatal(err)
	}
	streams, err := s.collector.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(streams) != 1 || streams[0].GetStream().GetUrl() != ts.URL+"/master.m3u8" {
		t.Fatalf("streams = %v", streams)
	}
}

func TestClicksSameOriginFrameWithoutLeavingParent(t *testing.T) {
	chrome := os.Getenv("TEST_CASTOR_CHROME")
	if chrome == "" {
		t.Skip("set TEST_CASTOR_CHROME to exercise real browser navigation")
	}
	ts := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		switch r.URL.Path {
		case "/":
			fmt.Fprint(w, `<iframe src="/player" style="width:640px;height:360px"></iframe>`)
		case "/player":
			fmt.Fprint(w, `<video style="width:500px;height:280px" onclick="fetch('/master.m3u8')"></video>`)
		case "/master.m3u8":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			fmt.Fprint(w, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nmovie.m3u8\n")
		default:
			http.NotFound(w, r)
		}
	}))
	ts.Start()
	defer ts.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
	defer cancel()
	s, err := newSession(ctx, BrowserConfig{ChromePath: chrome, Headless: true, Timeout: 10 * time.Second}, ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := clickPlayer(s.ctx); err != nil {
		t.Fatal(err)
	}
	streams, err := s.collector.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var at string
	if err := chromedp.Run(s.ctx, chromedp.Location(&at)); err != nil {
		t.Fatal(err)
	}
	if at != ts.URL+"/" || len(streams) != 1 || streams[0].GetStream().GetUrl() != ts.URL+"/master.m3u8" {
		t.Fatalf("page=%q streams=%v", at, streams)
	}
}
