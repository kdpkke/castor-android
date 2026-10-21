package dash

import (
	"context"
	"net/url"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/media"
	"github.com/stupside/castor/services/mediaserver/internal/source/sourcetest"
)

func FuzzPresentationWindow(f *testing.F) {
	f.Add(`<MPD><Period duration="PT12S"><AdaptationSet contentType="video"><Representation id="v"><SegmentTemplate media="$Number$.m4s" duration="2"/></Representation></AdaptationSet></Period></MPD>`)
	f.Add(`<MPD><Period><AdaptationSet contentType="video"><Representation id="v"><SegmentTemplate media="$Time$.m4s"><SegmentTimeline><S d="1" r="999999999"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet></Period></MPD>`)
	f.Fuzz(func(t *testing.T, body string) {
		if len(body) > 4096 {
			return
		}
		ctx, cancel := context.WithTimeout(t.Context(), 100*time.Millisecond)
		defer cancel()
		in := media.Input{URL: &url.URL{Scheme: "https", Host: "origin.test", Path: "/m.mpd"}, Representation: "v"}
		client := &sourcetest.Documents{ByPath: map[string]string{"/m.mpd": body}}
		window, err := (Format{}).Timeline(client, in, media.TrackVideo).Source.Window(ctx)
		if err == nil && len(window.Segments) > maxWindowSegments {
			t.Fatalf("expanded %d segments beyond budget", len(window.Segments))
		}
	})
}
