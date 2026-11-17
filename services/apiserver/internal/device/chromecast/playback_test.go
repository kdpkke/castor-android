package chromecast

import (
	"errors"
	"net"
	"testing"

	castmedia "github.com/vishen/go-chromecast/cast"

	"github.com/stupside/castor/services/apiserver/internal/device"
	"github.com/stupside/castor/services/apiserver/internal/device/devicetest"
)

func TestChromecastPlaybackState(t *testing.T) {
	const content = "https://castor.test/stream.mp4"
	status := func(session int, mediaID, playerState, idleReason string) castmedia.MediaStatusResponse {
		return castmedia.MediaStatusResponse{
			Type: "MEDIA_STATUS",
			Status: []castmedia.Media{{
				MediaSessionId: session,
				PlayerState:    playerState,
				IdleReason:     idleReason,
				Media:          castmedia.MediaItem{ContentId: mediaID},
			}},
		}
	}
	closed := castmedia.MediaStatusResponse{Type: "CLOSE"}
	for _, tt := range []struct {
		name     string
		messages []castmedia.MediaStatusResponse
		want     bool
		wantErr  bool
	}{
		{"stale finished status is ignored", []castmedia.MediaStatusResponse{status(4, "https://old.test/movie.mp4", "IDLE", "FINISHED")}, false, false},
		{"finish after playing ends", []castmedia.MediaStatusResponse{status(7, content, "PLAYING", ""), status(7, content, "IDLE", "FINISHED")}, true, false},
		{"another session cannot end this one", []castmedia.MediaStatusResponse{status(7, content, "PLAYING", ""), status(8, "https://other.test/movie.mp4", "IDLE", "FINISHED")}, false, false},
		{"pause remains active", []castmedia.MediaStatusResponse{status(7, content, "PLAYING", ""), status(7, content, "PAUSED", "")}, false, false},
		{"receiver close after playing ends", []castmedia.MediaStatusResponse{status(7, content, "PLAYING", ""), closed}, true, false},
		{"receiver close while still loading does not end", []castmedia.MediaStatusResponse{closed}, false, false},
		{"receiver playback error fails", []castmedia.MediaStatusResponse{status(7, content, "PLAYING", ""), status(7, content, "IDLE", "ERROR")}, true, true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			var watch playback
			watch.begin(content)
			got := false
			var gotErr error
			for i := range tt.messages {
				got, gotErr = playbackOutcome(&watch, &tt.messages[i])
			}
			if got != tt.want {
				t.Errorf("playbackOutcome = %v, want %v", got, tt.want)
			}
			if (gotErr != nil) != tt.wantErr {
				t.Errorf("playbackOutcome error = %v, want error %v", gotErr, tt.wantErr)
			}
		})
	}
}

func TestChromecastAnswersWhenTheCastEnds(t *testing.T) {
	devicetest.AwaitsTheCastsEnd(t, func() device.Device {
		return &session{ch: &channel{gone: make(chan struct{})}, ending: newEnding()}
	})
}

func TestAChromecastThatDropsTheConnectionIsGone(t *testing.T) {
	near, far := net.Pipe()
	dev := &session{name: "Living Room", ending: newEnding()}
	dev.ch = newChannel(near, dev.watchMessage)
	t.Cleanup(func() { _ = dev.Close() })
	_ = far.Close()
	if _, gone := errors.AsType[*device.Gone](dev.AwaitEnd(t.Context())); !gone {
		t.Error("a receiver that went away mid-cast is not reported gone, so the cast outlives it")
	}
}
