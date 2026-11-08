package cast

import (
	"connectrpc.com/connect"
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"github.com/stupside/castor/services/apiserver/internal/device"
	"google.golang.org/protobuf/types/known/durationpb"
	"google.golang.org/protobuf/types/known/emptypb"
)

type restartDevice struct {
	device.Device
	mu          sync.Mutex
	head        device.Playhead
	positionErr error
	stops       atomic.Int32
	onStop      func()
	ended       chan struct{}
}

func (d *restartDevice) Position(context.Context) (device.Playhead, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.head, d.positionErr
}
func (d *restartDevice) Stop(context.Context) error {
	d.stops.Add(1)
	if d.onStop != nil {
		d.onStop()
	}
	return nil
}
func (d *restartDevice) Play(context.Context, device.Playback) error { return nil }
func (d *restartDevice) Pause(context.Context) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.head.Paused = true
	return nil
}
func (d *restartDevice) Controls() device.Controls { return device.Controls{Pause: true} }

func (d *restartDevice) AwaitEnd(ctx context.Context) error {
	select {
	case <-d.ended:
		return nil
	case <-ctx.Done():
		return context.Cause(ctx)
	}
}

// stoppedAfterEnd reports its end once; asked again, it waits, as a DLNA renderer that stays stopped.
type stoppedAfterEnd struct {
	*restartDevice
	reported atomic.Bool
}

func (d *stoppedAfterEnd) AwaitEnd(ctx context.Context) error {
	if d.reported.CompareAndSwap(false, true) {
		return nil
	}
	<-ctx.Done()
	return context.Cause(ctx)
}

func TestManagedHandoffEndsWhenTheReceiverAlreadyReportedTheEnd(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := newCast("managed-ended", &castorv1.Device{}, &castorv1.CastRequest{Preferences: &castorv1.Preferences{ManagedPlayback: true}}, func(error) {})
		d := &stoppedAfterEnd{restartDevice: &restartDevice{}}
		ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
		defer cancel()
		began := time.Now()
		ended, err := runRestartable(ctx, c, d, func(ctx context.Context, p *playback, _ string, _ time.Duration) (*castorv1.Ended, error) {
			if err := p.Play(ctx, device.Playback{Play: &mediav1.DeviceCommand_Play{Stream: &mediav1.DeviceCommand_Play_Source_{Source: &mediav1.DeviceCommand_Play_Source{Duration: durationpb.New(12 * time.Second)}}}}); err != nil {
				return nil, err
			}
			// The media server follows the handoff to the receiver's own end.
			if err := p.AwaitEnd(ctx); err != nil {
				return nil, err
			}
			return &castorv1.Ended{Result: &castorv1.Ended_Completed{Completed: &emptypb.Empty{}}}, nil
		})
		if err != nil || ended.GetCompleted() == nil {
			t.Fatalf("ended = %v, %v", ended, err)
		}
		if waited := time.Since(began); waited > 0 {
			t.Fatalf("waited %s for an end the receiver had already reported", waited)
		}
	})
}

func TestRestartPositionPreservesTheCompleteFiniteClockAndExplicitLiveEdge(t *testing.T) {
	d := &restartDevice{head: device.Playhead{At: time.Minute, Paused: true}}
	p := &playback{Device: d}
	loaded := &loadedPlayback{base: 30 * time.Minute, duration: 2 * time.Hour,
		media: device.Playback{Play: &mediav1.DeviceCommand_Play{}}}
	p.loaded.Store(loaded)
	at, paused, err := restartPosition(t.Context(), p, 0)
	if err != nil || at != 31*time.Minute || !paused {
		t.Fatalf("finite = %s, %v, %v", at, paused, err)
	}
	d.positionErr = errors.New("receiver unavailable")
	at, paused, err = restartPosition(t.Context(), p, 0)
	if err != nil || at != 31*time.Minute || !paused {
		t.Fatalf("last observation = %s, %v, %v", at, paused, err)
	}
	p.loaded.Store(&loadedPlayback{media: device.Playback{Play: &mediav1.DeviceCommand_Play{Live: true}}})
	at, paused, err = restartPosition(t.Context(), p, 0)
	if err != nil || at != 0 || paused {
		t.Fatalf("live = %s, %v, %v", at, paused, err)
	}
}

func TestUnknownFinitePositionCannotSilentlyRestartFromZero(t *testing.T) {
	d := &restartDevice{positionErr: device.ErrNoPosition}
	p := &playback{Device: d}
	p.loaded.Store(&loadedPlayback{media: device.Playback{Play: &mediav1.DeviceCommand_Play{}}})
	if _, _, err := restartPosition(t.Context(), p, 0); err == nil {
		t.Fatal("unknown position restarted the video")
	}
}

func TestRestartJoinsOldPipelineBeforeStopAndKeepsTheCastIdentity(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("same-cast", &castorv1.Device{Id: "same-tv"}, &castorv1.CastRequest{}, func(error) {})
		s := New(nil, nil, nil, nil)
		d := &restartDevice{head: device.Playhead{At: 42 * time.Minute}}
		var running atomic.Int32
		starts := make(chan time.Duration, 3)
		finished := make(chan struct{})
		if err := s.casts.Add(c.id, c, finished); err != nil {
			t.Fatal(err)
		}
		go func() {
			defer close(finished)
			defer close(c.done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, p *playback, _ string, start time.Duration) (*castorv1.Ended, error) {
				if running.Add(1) != 1 {
					t.Error("replacement raced old cleanup")
				}
				p.loaded.Store(&loadedPlayback{duration: time.Hour, media: device.Playback{Play: &mediav1.DeviceCommand_Play{}}})
				starts <- start
				<-ctx.Done()
				running.Add(-1)
				return nil, ctx.Err()
			})
		}()
		<-starts
		if _, err := s.Restart(ctx, &castorv1.RestartRequest{CastId: c.id}); err != nil {
			t.Fatal(err)
		}
		if at := <-starts; at != 42*time.Minute {
			t.Fatalf("resumed at %s", at)
		}
		if d.stops.Load() != 1 || c.device.Id != "same-tv" {
			t.Fatal("restart did not retain the receiver")
		}
		cancel()
		<-finished
		s.Drain(t.Context())
	})
}

func TestLiveStallUsesTenSecondsOfKnownUnpausedReceiverTime(t *testing.T) {
	live := &loadedPlayback{media: device.Playback{Play: &mediav1.DeviceCommand_Play{Live: true}}}
	finite := &loadedPlayback{media: device.Playback{Play: &mediav1.DeviceCommand_Play{}}}
	base := time.Unix(0, 0)
	var tracker liveStall
	if tracker.sample(base, live, device.Playhead{At: time.Second}, nil) {
		t.Fatal("first observation stalled")
	}
	if tracker.sample(base.Add(10*time.Second), live, device.Playhead{At: time.Second}, nil) {
		t.Fatal("exactly ten seconds restarted")
	}
	if !tracker.sample(base.Add(10*time.Second+time.Millisecond), live, device.Playhead{At: time.Second}, nil) {
		t.Fatal("live stall was ignored")
	}
	for _, tc := range []struct {
		name   string
		loaded *loadedPlayback
		head   device.Playhead
		err    error
	}{
		{"finite with unknown duration", finite, device.Playhead{}, nil},
		{"paused live", live, device.Playhead{Paused: true}, nil},
		{"unknown live position", live, device.Playhead{}, device.ErrNoPosition},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var tracker liveStall
			tracker.sample(base, tc.loaded, tc.head, tc.err)
			if tracker.sample(base.Add(time.Minute), tc.loaded, tc.head, tc.err) {
				t.Fatal("not a live stall")
			}
		})
	}
	tracker.sample(base.Add(time.Minute), live, device.Playhead{At: 2 * time.Second}, nil)
	if tracker.sample(base.Add(time.Minute+5*time.Second), live, device.Playhead{At: 2 * time.Second}, nil) {
		t.Fatal("progress did not reset timer")
	}
}

func TestOnlyLiveStallsRestartInTheBackgroundAndRetriesAreBounded(t *testing.T) {
	for _, tc := range []struct {
		name         string
		live, paused bool
		err          error
		want         int32
	}{
		{"live", true, false, nil, 2}, {"finite", false, false, nil, 1},
		{"paused", true, true, nil, 1}, {"unknown", true, false, device.ErrNoPosition, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				ctx, cancel := context.WithCancel(t.Context())
				defer cancel()
				c := newCast("live", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
				d := &restartDevice{head: device.Playhead{At: time.Second, Paused: tc.paused}, positionErr: tc.err}
				var starts atomic.Int32
				done := make(chan struct{})
				go func() {
					defer close(done)
					_, _ = runRestartable(ctx, c, d, func(ctx context.Context, p *playback, _ string, _ time.Duration) (*castorv1.Ended, error) {
						p.loaded.Store(&loadedPlayback{media: device.Playback{Play: &mediav1.DeviceCommand_Play{Live: tc.live}}})
						starts.Add(1)
						<-ctx.Done()
						return nil, ctx.Err()
					})
				}()
				time.Sleep(13 * time.Second)
				synctest.Wait()
				if starts.Load() != tc.want {
					t.Fatalf("started %d, want %d", starts.Load(), tc.want)
				}
				if tc.name == "live" {
					time.Sleep(time.Minute)
					synctest.Wait()
					if starts.Load() != 4 || d.stops.Load() != 3 {
						t.Fatalf("unbounded retries: %d starts, %d stops", starts.Load(), d.stops.Load())
					}
				}
				cancel()
				<-done
			})
		})
	}
}

func TestFiniteRestartUsesLastKnownPositionWhenReceiverReadFails(t *testing.T) {
	p := &playback{Device: &restartDevice{positionErr: errors.New("offline")}}
	p.loaded.Store(&loadedPlayback{media: device.Playback{Play: &mediav1.DeviceCommand_Play{}}})
	p.last.Store(&castorv1.PlaybackState{Position: durationpb.New(54 * time.Minute)})
	if at, _, err := restartPosition(t.Context(), p, 0); err != nil || at != 54*time.Minute {
		t.Fatalf("resume = %s, %v", at, err)
	}
}

func TestStopDuringRestartCannotLaunchAnotherPipeline(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("stop-during-restart", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
		s := New(nil, nil, nil, nil)
		d := &restartDevice{head: device.Playhead{At: time.Minute}, onStop: cancel}
		var starts atomic.Int32
		started, cleaning, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
		finished := make(chan struct{})
		if err := s.casts.Add(c.id, c, finished); err != nil {
			t.Fatal(err)
		}
		go func() {
			defer close(finished)
			defer close(c.done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, p *playback, _ string, _ time.Duration) (*castorv1.Ended, error) {
				if starts.Add(1) > 1 {
					t.Error("Stop resurrected playback")
					return nil, ctx.Err()
				}
				p.loaded.Store(&loadedPlayback{media: device.Playback{Play: &mediav1.DeviceCommand_Play{}}})
				close(started)
				<-ctx.Done()
				close(cleaning)
				<-release
				return nil, ctx.Err()
			})
		}()
		<-started
		response := make(chan error, 1)
		go func() { _, err := s.Restart(ctx, &castorv1.RestartRequest{CastId: c.id}); response <- err }()
		<-cleaning
		_, err := s.Restart(t.Context(), &castorv1.RestartRequest{CastId: c.id})
		if connect.CodeOf(err) != connect.CodeAborted {
			t.Fatalf("overlapping restart=%v", err)
		}
		close(release)
		<-finished
		if err := <-response; err == nil {
			t.Fatal("cancelled restart succeeded")
		}
		if starts.Load() != 1 {
			t.Fatalf("started %d pipelines", starts.Load())
		}
		s.Drain(t.Context())
	})
}

func TestManagedHandoffKeepsControlsAndRestartUntilTheReceiverEnds(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("managed-source", &castorv1.Device{}, &castorv1.CastRequest{Preferences: &castorv1.Preferences{ManagedPlayback: true}}, func(error) {})
		s := New(nil, nil, nil, nil)
		d := &restartDevice{head: device.Playhead{At: time.Minute}, ended: make(chan struct{})}
		var starts atomic.Int32
		ready := make(chan struct{}, 2)
		finished := make(chan struct{})
		if err := s.casts.Add(c.id, c, finished); err != nil {
			t.Fatal(err)
		}
		go func() {
			defer close(finished)
			defer close(c.done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, p *playback, _ string, start time.Duration) (*castorv1.Ended, error) {
				starts.Add(1)
				if err := p.Play(ctx, device.Playback{Play: &mediav1.DeviceCommand_Play{Stream: &mediav1.DeviceCommand_Play_Source_{Source: &mediav1.DeviceCommand_Play_Source{Duration: durationpb.New(time.Hour)}}}}); err != nil {
					return nil, err
				}
				ready <- struct{}{}
				return &castorv1.Ended{Result: &castorv1.Ended_Completed{Completed: &emptypb.Empty{}}}, nil
			})
		}()
		<-ready
		synctest.Wait()
		select {
		case <-finished:
			t.Fatal("managed handoff lost receiver ownership")
		default:
		}
		if _, err := s.Restart(ctx, &castorv1.RestartRequest{CastId: c.id}); err != nil {
			t.Fatal(err)
		}
		<-ready
		if starts.Load() != 2 || d.stops.Load() != 1 {
			t.Fatal("managed source did not restart exactly once")
		}
		close(d.ended)
		<-finished
		s.Drain(t.Context())
	})
}
