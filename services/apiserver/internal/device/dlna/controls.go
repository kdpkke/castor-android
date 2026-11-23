package dlna

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/stupside/castor/services/apiserver/internal/device"
)

func (s *session) Controls() device.Controls {
	p := s.played.Load()
	return device.Controls{Pause: true, Seek: p != nil && p.seekable}
}

func (s *session) Seek(ctx context.Context, at time.Duration) error {
	p := s.played.Load()
	if at < 0 || p == nil || !p.seekable {
		return device.ErrUnsupportedControl
	}
	// AVTransport's REL_TIME refers to the current track, including a paused one.
	target := fmt.Sprintf("%d:%02d:%02d", int64(at/time.Hour), int64(at/time.Minute)%60, int64(at/time.Second)%60)
	seek := func() error {
		return s.action(ctx, "Seek", &struct{ InstanceID, Unit, Target string }{"0", "REL_TIME", target})
	}
	err := seek()
	if upnpError(err, actionFailed) && p.file && s.state(ctx) == statePaused {
		// LG's renderer seeks a file only while it plays, so it is played, sought and held again.
		// Not a stream castor makes: that renderer stalls seeking one while it plays.
		err = s.Resume(ctx)
		if err == nil {
			s.awaitPlaying(ctx)
			err = errors.Join(seek(), s.Pause(ctx))
		}
	}
	if err != nil {
		return fmt.Errorf("seeking playback: %w", err)
	}
	return nil
}

// state is the renderer's transport state, empty where it does not answer.
func (s *session) state(ctx context.Context) transportState {
	ctx, cancel := context.WithTimeout(ctx, actionTimeout)
	defer cancel()
	info, _ := s.transportInfo(ctx)
	return info.CurrentTransportState
}

// awaitPlaying gives a renderer told to play a moment to say it does.
func (s *session) awaitPlaying(ctx context.Context) {
	for range 25 {
		if state := s.state(ctx); state == "" || state == statePlaying {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(200 * time.Millisecond):
		}
	}
}
