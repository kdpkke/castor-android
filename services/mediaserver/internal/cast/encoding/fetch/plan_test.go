package fetch

import (
	"net/url"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

func TestForProgramDerivesEveryInputIndependently(t *testing.T) {
	program, err := media.NewProgram(media.Program{Inputs: []media.Input{
		{ID: "video", URL: planURL(t, "https://video.test/v.m3u8"), Fetch: media.Fetch{Segmented: true, Framing: media.FramingOutOfBand}},
		{ID: "audio", URL: planURL(t, "https://audio.test/a.m3u8"), Fetch: media.Fetch{Segmented: true, Framing: media.FramingInBand, Live: true}},
	}, Tracks: []media.TrackRef{{Input: "video", Kind: media.TrackVideo}, {Input: "audio", Kind: media.TrackAudio}}, ClockInput: "video", EndPolicy: media.EndAtLongest})
	if err != nil {
		t.Fatal(err)
	}
	plan := ForProgram(program, 17*time.Second)
	if plan["video"].Deadline != 0 || plan["audio"].Deadline != 17*time.Second {
		t.Errorf("deadlines = %s and %s, want a fragile video and a live audio", plan["video"].Deadline, plan["audio"].Deadline)
	}
	if got := plan.Pace(); got != 1 {
		t.Errorf("combined pace = %gx, want the slowest input at 1x", got)
	}
}

func planURL(t *testing.T, raw string) *url.URL {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	return u
}
