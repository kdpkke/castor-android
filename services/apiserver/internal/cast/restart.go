package cast

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"connectrpc.com/connect"
	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"github.com/stupside/castor/internal/castid"
	"github.com/stupside/castor/services/apiserver/internal/device"
)

const liveStallTimeout = 10 * time.Second
const maxStalledLiveRestarts = 3

type restartRequest struct {
	ctx  context.Context
	done chan error
}

// Restart keeps ownership and the private original source, including its headers.
// A closed pipeline is joined before STOP or any replacement can reach the TV.
func (s *Service) Restart(ctx context.Context, req *castorv1.RestartRequest) (*castorv1.RestartResponse, error) {
	c, err := s.find(req.GetCastId())
	if err != nil {
		return nil, err
	}
	if !c.restarting.CompareAndSwap(false, true) {
		return nil, connect.NewError(connect.CodeAborted, errors.New("this cast is already restarting"))
	}
	defer c.restarting.Store(false)
	request := &restartRequest{ctx: ctx, done: make(chan error, 1)}
	select {
	case c.restarts <- request:
	case <-c.done:
		return nil, connect.NewError(connect.CodeFailedPrecondition, errors.New("this cast has ended"))
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	}
	select {
	case err := <-request.done:
		if err != nil {
			return nil, err
		}
		return &castorv1.RestartResponse{}, nil
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	}
}

func (s *Service) runConnected(ctx context.Context, c *cast, lent device.Device, asked *mediav1.PlaybackSettings) (*castorv1.Ended, error) {
	return runRestartable(ctx, c, lent, func(ctx context.Context, p *playback, id string, start time.Duration) (*castorv1.Ended, error) {
		return s.runPlayback(ctx, c, p, asked, id, start)
	})
}

func runRestartable(ctx context.Context, c *cast, lent device.Device, run func(context.Context, *playback, string, time.Duration) (*castorv1.Ended, error)) (*castorv1.Ended, error) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	start, paused := c.start, false
	key := c.id
	var stall liveStall
	consecutive := 0
	for {
		life, cancel := context.WithCancel(ctx)
		p := &playback{Device: lent, lifetime: life, requests: make(chan *playbackSeek)}
		p.pauseOnLoad.Store(paused)
		c.playback.Store(p)
		done := make(chan playbackResult, 1)
		go func() { ended, err := run(life, p, key, start); done <- playbackResult{ended, err} }()
		var request *restartRequest
		automatic := false
		handedOff := false
	wait:
		for {
			select {
			case result := <-done:
				loaded := p.loaded.Load()
				if c.managed && !handedOff && !p.ended.Load() && result.err == nil && result.ended.GetCompleted() != nil && loaded != nil && loaded.media.Play.GetSource() != nil && ctx.Err() == nil {
					// Direct delivery needs no media pipeline after LOAD, but Android still
					// owns the receiver until playback ends, Stop, or an explicit restart.
					// A receiver that already reported the end is not waited for again: a
					// stopped DLNA renderer would only be judged refused 15 polls later.
					handedOff = true
					done = make(chan playbackResult, 1)
					go func() {
						err := p.AwaitEnd(life)
						done <- playbackResult{ended: result.ended, err: err}
					}()
					continue
				}
				cancel()
				c.playback.Store(nil)
				return result.ended, result.err
			case <-ctx.Done():
				cancel()
				result := <-done
				c.playback.Store(nil)
				return result.ended, result.err
			case request = <-c.restarts:
				if request.ctx.Err() != nil {
					request.done <- context.Cause(request.ctx)
					continue
				}
				var err error
				start, paused, err = restartPosition(request.ctx, p, c.start)
				if err != nil {
					request.done <- err
					continue
				}
				break wait
			case now := <-ticker.C:
				loaded := p.loaded.Load()
				if loaded == nil || !loaded.media.Play.GetLive() {
					stall = liveStall{}
					continue
				}
				check, stop := context.WithTimeout(life, 2*time.Second)
				head, err := lent.Position(check)
				stop()
				if stall.sample(now, loaded, head, err) {
					if consecutive >= maxStalledLiveRestarts {
						cancel()
						<-done
						c.playback.Store(nil)
						return nil, fmt.Errorf("live playback remained stalled after %d automatic restarts", consecutive)
					}
					automatic, start, paused = true, 0, false
					consecutive++
					c.automaticRestarts.Add(1)
					break wait
				}
				if stall.healthy {
					consecutive = 0
				}
			}
		}
		cancel()
		<-done
		c.playback.Store(nil)
		if ctx.Err() != nil {
			if request != nil && !automatic {
				request.done <- context.Cause(ctx)
			}
			return nil, context.Cause(ctx)
		}
		stop, finish := context.WithTimeout(ctx, 5*time.Second)
		err := lent.Stop(stop)
		finish()
		if err == nil && ctx.Err() != nil {
			err = context.Cause(ctx)
		}
		if err != nil {
			if request != nil && !automatic {
				request.done <- err
			}
			return nil, fmt.Errorf("stopping playback for restart: %w", err)
		}
		c.show(&castorv1.CastStatus{State: &castorv1.CastStatus_Restarting{Restarting: &castorv1.RestartingStatus{Automatic: automatic}}})
		slog.InfoContext(ctx, "restarting cast on its leased receiver", "automatic", automatic, "at", start)
		if request != nil && !automatic {
			request.done <- nil
		}
		key, stall = castid.New(), liveStall{}
	}
}

func restartPosition(ctx context.Context, p *playback, initial time.Duration) (time.Duration, bool, error) {
	loaded := p.loaded.Load()
	if loaded == nil {
		return initial, false, nil
	}
	if loaded.media.Play.GetLive() {
		return 0, false, nil
	}
	check, cancel := context.WithTimeout(ctx, 2*time.Second)
	state, err := p.state(check)
	cancel()
	if err != nil || state.GetPosition() == nil {
		state = p.last.Load()
	}
	if state.GetPosition() == nil {
		return 0, false, errors.New("the receiver has not reported a position to resume this video")
	}
	return state.GetPosition().AsDuration(), state.GetPaused(), nil
}

// Only an inspected live presentation and consecutive, successful receiver
// observations establish a stall. Unknown position, pause and a new LOAD reset it.
type liveStall struct {
	loaded    *loadedPlayback
	at, began time.Duration
	since     time.Time
	healthy   bool
}

func (s *liveStall) sample(now time.Time, loaded *loadedPlayback, head device.Playhead, err error) bool {
	if loaded == nil || !loaded.media.Play.GetLive() || err != nil || head.Paused {
		*s = liveStall{}
		return false
	}
	if s.loaded != loaded || s.since.IsZero() {
		*s = liveStall{loaded: loaded, at: head.At, began: head.At, since: now}
		return false
	}
	if head.At != s.at {
		s.at, s.since = head.At, now
	}
	s.healthy = head.At-s.began >= 30*time.Second
	return now.Sub(s.since) > liveStallTimeout
}
