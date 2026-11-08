package cast

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync/atomic"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"

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
	return runRestartable(ctx, c, lent, func(ctx context.Context, w *watched, id string, start time.Duration) (*castorv1.Ended, error) {
		return s.runPlayback(ctx, c, w, asked, id, start)
	}, func(ctx context.Context, id string) error {
		_, err := s.media.Casts.Pause(ctx, &castorv1.PauseRequest{CastId: id})
		return err
	})
}

// mediaID is the media server's id for the run c plays now: each restart starts a media cast of its own.
func (c *cast) mediaID() string {
	if id := c.media.Load(); id != nil {
		return *id
	}
	return c.id
}

func runRestartable(ctx context.Context, c *cast, lent device.Device, run func(context.Context, *watched, string, time.Duration) (*castorv1.Ended, error), pause func(context.Context, string) error) (*castorv1.Ended, error) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	start, paused := c.start, false
	key := c.id
	var stall liveStall
	consecutive := 0
	for {
		life, cancel := context.WithCancel(ctx)
		w := &watched{Device: lent}
		if paused {
			id := key
			w.pauseOnLoad = func() { holdPaused(life, func(ctx context.Context) error { return pause(ctx, id) }) }
		}
		c.media.Store(&key)
		done := make(chan playbackResult, 1)
		go func() { ended, err := run(life, w, key, start); done <- playbackResult{ended, err} }()
		var request *restartRequest
		automatic := false
		handedOff := false
	wait:
		for {
			select {
			case result := <-done:
				loaded := w.loaded.Load()
				if c.managed && !handedOff && !w.ended.Load() && result.err == nil && result.ended.GetCompleted() != nil && loaded != nil && loaded.Play.GetSource() != nil && ctx.Err() == nil {
					// Direct delivery needs no media pipeline after LOAD, but Android still
					// owns the receiver until playback ends, Stop, or an explicit restart.
					// A receiver that already reported the end is not waited for again: a
					// stopped DLNA renderer would only be judged refused 15 polls later.
					handedOff = true
					done = make(chan playbackResult, 1)
					go func() {
						err := w.AwaitEnd(life)
						done <- playbackResult{ended: result.ended, err: err}
					}()
					continue
				}
				cancel()
				return result.ended, result.err
			case <-ctx.Done():
				cancel()
				result := <-done
				return result.ended, result.err
			case request = <-c.restarts:
				if request.ctx.Err() != nil {
					request.done <- context.Cause(request.ctx)
					continue
				}
				var err error
				start, paused, err = restartPosition(c, w, c.start)
				if err != nil {
					request.done <- err
					continue
				}
				break wait
			case now := <-ticker.C:
				loaded := w.loaded.Load()
				if stall.sample(now, loaded, playhead(c)) {
					if consecutive >= maxStalledLiveRestarts {
						cancel()
						<-done
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

type playbackResult struct {
	ended *castorv1.Ended
	err   error
}

// watched is the leased device as one run plays it: what it was handed last, and whether it said that ended.
type watched struct {
	device.Device
	loaded atomic.Pointer[device.Playback]
	// ended is the receiver's own report that what was handed last has finished.
	ended atomic.Bool
	// pauseOnLoad holds a restarted run paused, as its viewer had it, once it plays.
	pauseOnLoad func()
}

func (w *watched) Play(ctx context.Context, p device.Playback) error {
	if err := w.Device.Play(ctx, p); err != nil {
		return err
	}
	w.ended.Store(false)
	w.loaded.Store(&p)
	if hold := w.pauseOnLoad; hold != nil {
		w.pauseOnLoad = nil
		go hold()
	}
	return nil
}

func (w *watched) AwaitEnd(ctx context.Context) error {
	err := w.Device.AwaitEnd(ctx)
	if err == nil {
		w.ended.Store(true)
	}
	return err
}

// Controls and Seek keep the device's own seek reachable through the run that plays it.
func (w *watched) Controls() device.Controls { return device.ControlsOf(w.Device) }

func (w *watched) Seek(ctx context.Context, at time.Duration) error {
	return device.Seek(ctx, w.Device, at)
}

// holdPaused asks the media server to pause its new run as the viewer had it, once the run has the device.
func holdPaused(ctx context.Context, pause func(context.Context) error) {
	for range 20 {
		asking, cancel := context.WithTimeout(ctx, 2*time.Second)
		err := pause(asking)
		cancel()
		if err == nil || ctx.Err() != nil {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(250 * time.Millisecond):
		}
	}
}

// playhead is where the media server last said c's device plays, nil before it said.
func playhead(c *cast) *castorv1.Playhead {
	v, _ := c.now.Load()
	return v.status.GetCasting().GetPlayhead()
}

func restartPosition(c *cast, w *watched, initial time.Duration) (time.Duration, bool, error) {
	loaded := w.loaded.Load()
	if loaded == nil {
		return initial, false, nil
	}
	if loaded.Play.GetLive() {
		return 0, false, nil
	}
	head := playhead(c)
	if head.GetPosition() == nil {
		return 0, false, errors.New("the receiver has not reported a position to resume this video")
	}
	return head.GetPosition().AsDuration(), head.GetPaused(), nil
}

// Only a live stream and consecutive positions the receiver reported establish a stall.
// Unknown position, pause and a new stream reset it.
type liveStall struct {
	loaded    *device.Playback
	at, began time.Duration
	since     time.Time
	healthy   bool
}

func (s *liveStall) sample(now time.Time, loaded *device.Playback, head *castorv1.Playhead) bool {
	if loaded == nil || !loaded.Play.GetLive() || head.GetPosition() == nil || head.GetPaused() {
		*s = liveStall{}
		return false
	}
	at := head.GetPosition().AsDuration()
	if s.loaded != loaded || s.since.IsZero() {
		*s = liveStall{loaded: loaded, at: at, began: at, since: now}
		return false
	}
	if at != s.at {
		s.at, s.since = at, now
	}
	s.healthy = at-s.began >= 30*time.Second
	return now.Sub(s.since) > liveStallTimeout
}

// withCastID is req for the media cast id.
func withCastID[Req interface {
	GetCastId() string
	proto.Message
}](req Req, id string) Req {
	if req.GetCastId() == id {
		return req
	}
	m := proto.CloneOf(req)
	r := m.ProtoReflect()
	r.Set(r.Descriptor().Fields().ByName("cast_id"), protoreflect.ValueOfString(id))
	return m
}
