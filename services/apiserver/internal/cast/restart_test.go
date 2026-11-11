package cast

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"connectrpc.com/connect"
	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"github.com/stupside/castor/services/apiserver/internal/device"
	"google.golang.org/protobuf/types/known/durationpb"
	"google.golang.org/protobuf/types/known/emptypb"
)

type lostPositionDevice struct {
	*restartDevice
	queries atomic.Int32
}

func TestLiveRecoveryWaitsForReceiverObservationsToConfirmAStall(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("position-pending", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
		d := &restartDevice{head: &device.Playhead{At: time.Second}}
		done := make(chan struct{})
		go func() {
			defer close(done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, w *watched, _ string, _ time.Duration, _ bool) (*castorv1.Ended, error) {
				_ = w.Play(ctx, live)
				ticker := time.NewTicker(5 * time.Second)
				defer ticker.Stop()
				for observations := 0; ; observations++ {
					if observations < 2 {
						_, _ = w.Position(ctx)
					}
					select {
					case <-ctx.Done():
						return nil, ctx.Err()
					case <-ticker.C:
					}
				}
			}, noPause)
		}()
		time.Sleep(13 * time.Second)
		synctest.Wait()
		if got := c.automaticRestarts.Load(); got != 0 {
			t.Errorf("two receiver observations five seconds apart caused %d restarts", got)
		}
		cancel()
		<-done
	})
}

func (d *lostPositionDevice) Position(context.Context) (device.Playhead, error) {
	if d.queries.Add(1) == 1 {
		return device.Playhead{At: time.Second}, nil
	}
	return device.Playhead{}, device.ErrNoPosition
}

func TestLiveRecoveryDoesNotTreatAnOldPositionAsFreshStallEvidence(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("position-lost", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
		d := &lostPositionDevice{restartDevice: &restartDevice{}}
		var starts atomic.Int32
		done := make(chan struct{})
		go func() {
			defer close(done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, w *watched, _ string, _ time.Duration, _ bool) (*castorv1.Ended, error) {
				starts.Add(1)
				_ = w.Play(ctx, live)
				tick := time.NewTicker(5 * time.Second)
				defer tick.Stop()
				for {
					if head, err := w.Position(ctx); err == nil {
						heard(c, head.At, head.Paused)
					}
					select {
					case <-ctx.Done():
						return nil, ctx.Err()
					case <-tick.C:
					}
				}
			}, noPause)
		}()
		time.Sleep(25 * time.Second)
		synctest.Wait()
		if starts.Load() != 1 || d.stops.Load() != 0 {
			t.Errorf("position reads failed, but recovery started %d pipelines and sent %d STOPs", starts.Load(), d.stops.Load())
		}
		cancel()
		<-done
	})
}

func TestReceiverPositionExpiresAndBelongsToItsPlayback(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		w := &watched{Device: &restartDevice{head: &device.Playhead{At: time.Second}}}
		_ = w.Play(t.Context(), live)
		_, _ = w.Position(t.Context())
		_, fresh := w.reportedHead(time.Now())
		if fresh == nil {
			t.Fatal("a fresh position was lost")
		}
		time.Sleep(liveStallTimeout + time.Second)
		_, expired := w.reportedHead(time.Now())
		if expired != nil {
			t.Fatal("an expired position remained usable for live recovery")
		}
		_, _ = w.Position(t.Context())
		_ = w.Play(t.Context(), live)
		_, replaced := w.reportedHead(time.Now())
		if replaced != nil {
			t.Fatal("a replacement playback inherited the previous position")
		}
	})
}

type restartDevice struct {
	device.Device
	stops  atomic.Int32
	onStop func()
	ended  chan struct{}
	head   *device.Playhead
}

func (d *restartDevice) Position(context.Context) (device.Playhead, error) {
	if d.head == nil {
		return device.Playhead{}, device.ErrNoPosition
	}
	return *d.head, nil
}

func (d *restartDevice) Stop(context.Context) error {
	d.stops.Add(1)
	if d.onStop != nil {
		d.onStop()
	}
	return nil
}
func (d *restartDevice) Play(context.Context, device.Playback) error { return nil }

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

var (
	finite = device.Playback{Play: &mediav1.DeviceCommand_Play{}}
	live   = device.Playback{Play: &mediav1.DeviceCommand_Play{Live: true}}
	source = device.Playback{Play: &mediav1.DeviceCommand_Play{Stream: &mediav1.DeviceCommand_Play_Source_{Source: &mediav1.DeviceCommand_Play_Source{Duration: durationpb.New(time.Hour)}}}}
)

// heard is the media server saying where c's device plays.
func heard(c *cast, at time.Duration, paused bool) {
	c.show(&castorv1.CastStatus{State: &castorv1.CastStatus_Casting{Casting: &castorv1.CastingStatus{Attempt: 1, Playhead: &castorv1.Playhead{Position: durationpb.New(at), Paused: paused}}}})
}

func noPause(context.Context, string) error { return nil }

func TestManagedHandoffEndsWhenTheReceiverAlreadyReportedTheEnd(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := newCast("managed-ended", &castorv1.Device{}, &castorv1.CastRequest{Preferences: &castorv1.Preferences{ManagedPlayback: true}}, func(error) {})
		d := &stoppedAfterEnd{restartDevice: &restartDevice{}}
		ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
		defer cancel()
		began := time.Now()
		ended, err := runRestartable(ctx, c, d, func(ctx context.Context, w *watched, _ string, _ time.Duration, _ bool) (*castorv1.Ended, error) {
			if err := w.Play(ctx, source); err != nil {
				return nil, err
			}
			// The media server follows the handoff to the receiver's own end.
			if err := w.AwaitEnd(ctx); err != nil {
				return nil, err
			}
			return &castorv1.Ended{Result: &castorv1.Ended_Completed{Completed: &emptypb.Empty{}}}, nil
		}, noPause)
		if err != nil || ended.GetCompleted() == nil {
			t.Fatalf("ended = %v, %v", ended, err)
		}
		if waited := time.Since(began); waited > 0 {
			t.Fatalf("waited %s for an end the receiver had already reported", waited)
		}
	})
}

func TestRestartPositionIsWhereTheMediaServerLastHeardTheDeviceAndTheLiveEdge(t *testing.T) {
	c := newCast("position", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
	w := &watched{}
	if at, paused, err := restartPosition(c, w, 5*time.Minute); err != nil || at != 5*time.Minute || paused {
		t.Fatalf("before any stream = %s, %v, %v; want the cast's own start", at, paused, err)
	}
	w.loaded.Store(&finite)
	heard(c, 31*time.Minute, true)
	if at, paused, err := restartPosition(c, w, 0); err != nil || at != 31*time.Minute || !paused {
		t.Fatalf("finite = %s, %v, %v", at, paused, err)
	}
	w.loaded.Store(&live)
	if at, paused, err := restartPosition(c, w, 0); err != nil || at != 0 || paused {
		t.Fatalf("live = %s, %v, %v", at, paused, err)
	}
}

func TestUnknownFinitePositionCannotSilentlyRestartFromZero(t *testing.T) {
	c := newCast("unknown", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
	w := &watched{}
	w.loaded.Store(&finite)
	if _, _, err := restartPosition(c, w, 0); err == nil {
		t.Fatal("unknown position restarted the video")
	}
}

func TestRestartJoinsOldPipelineBeforeStopAndKeepsTheCastIdentity(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("same-cast", &castorv1.Device{Id: "same-tv"}, &castorv1.CastRequest{}, func(error) {})
		s := New(nil, nil, nil, nil)
		d := &restartDevice{}
		var running atomic.Int32
		starts := make(chan time.Duration, 3)
		ids := make(chan string, 3)
		finished := make(chan struct{})
		if err := s.casts.Add(c.id, c, finished); err != nil {
			t.Fatal(err)
		}
		go func() {
			defer close(finished)
			defer close(c.done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, w *watched, id string, start time.Duration, _ bool) (*castorv1.Ended, error) {
				if running.Add(1) != 1 {
					t.Error("replacement raced old cleanup")
				}
				_ = w.Play(ctx, finite)
				heard(c, 42*time.Minute, false)
				ids <- id
				starts <- start
				<-ctx.Done()
				running.Add(-1)
				return nil, ctx.Err()
			}, noPause)
		}()
		<-starts
		if first := <-ids; first != c.id || c.mediaID() != c.id {
			t.Fatalf("the first run played as %q and controls reach %q, want the cast's own id", first, c.mediaID())
		}
		if _, err := s.Restart(ctx, &castorv1.RestartRequest{CastId: c.id}); err != nil {
			t.Fatal(err)
		}
		if at := <-starts; at != 42*time.Minute {
			t.Fatalf("resumed at %s", at)
		}
		if next := <-ids; next == c.id || c.mediaID() != next {
			t.Fatalf("the restart played as %q and controls reach %q, want a new media cast they follow", next, c.mediaID())
		}
		if d.stops.Load() != 1 || c.device.Id != "same-tv" {
			t.Fatal("restart did not retain the receiver")
		}
		cancel()
		<-finished
		s.Drain(t.Context())
	})
}

func TestARestartOfAPausedVideoPausesTheNewRunThroughTheMediaServer(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("paused", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
		s := New(nil, nil, nil, nil)
		finished := make(chan struct{})
		if err := s.casts.Add(c.id, c, finished); err != nil {
			t.Fatal(err)
		}
		var mu sync.Mutex
		var paused []string
		ids := make(chan string, 2)
		go func() {
			defer close(finished)
			defer close(c.done)
			_, _ = runRestartable(ctx, c, &restartDevice{}, func(ctx context.Context, w *watched, id string, _ time.Duration, _ bool) (*castorv1.Ended, error) {
				_ = w.Play(ctx, finite)
				heard(c, time.Minute, true)
				ids <- id
				<-ctx.Done()
				return nil, ctx.Err()
			}, func(_ context.Context, id string) error {
				mu.Lock()
				defer mu.Unlock()
				paused = append(paused, id)
				return nil
			})
		}()
		<-ids
		if _, err := s.Restart(ctx, &castorv1.RestartRequest{CastId: c.id}); err != nil {
			t.Fatal(err)
		}
		next := <-ids
		synctest.Wait()
		mu.Lock()
		got := append([]string(nil), paused...)
		mu.Unlock()
		if len(got) != 1 || got[0] != next {
			t.Fatalf("paused %v, want only the restarted run %q, as its viewer had it", got, next)
		}
		cancel()
		<-finished
		s.Drain(t.Context())
	})
}

func TestLiveStallUsesTenSecondsOfKnownUnpausedReceiverTime(t *testing.T) {
	base := time.Unix(0, 0)
	at := func(d time.Duration, paused bool) *castorv1.Playhead {
		return &castorv1.Playhead{Position: durationpb.New(d), Paused: paused}
	}
	var tracker liveStall
	if tracker.sample(base, &live, at(time.Second, false)) {
		t.Fatal("first observation stalled")
	}
	if tracker.sample(base.Add(10*time.Second), &live, at(time.Second, false)) {
		t.Fatal("exactly ten seconds restarted")
	}
	if !tracker.sample(base.Add(10*time.Second+time.Millisecond), &live, at(time.Second, false)) {
		t.Fatal("live stall was ignored")
	}
	for _, tc := range []struct {
		name   string
		loaded *device.Playback
		head   *castorv1.Playhead
	}{
		{"finite", &finite, at(time.Second, false)},
		{"paused live", &live, at(time.Second, true)},
		{"unknown live position", &live, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var tracker liveStall
			tracker.sample(base, tc.loaded, tc.head)
			if tracker.sample(base.Add(time.Minute), tc.loaded, tc.head) {
				t.Fatal("not a live stall")
			}
		})
	}
	tracker.sample(base.Add(time.Minute), &live, at(2*time.Second, false))
	if tracker.sample(base.Add(time.Minute+5*time.Second), &live, at(2*time.Second, false)) {
		t.Fatal("progress did not reset timer")
	}
}

func TestOnlyLiveStallsRestartInTheBackgroundAndRetriesAreBounded(t *testing.T) {
	for _, tc := range []struct {
		name         string
		live, paused bool
		heard        bool
		want         int32
	}{
		{"live", true, false, true, 2}, {"finite", false, false, true, 1},
		{"paused", true, true, true, 1}, {"unknown", true, false, false, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				ctx, cancel := context.WithCancel(t.Context())
				defer cancel()
				c := newCast("live", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
				d := &restartDevice{head: &device.Playhead{At: time.Second, Paused: tc.paused}}
				var starts atomic.Int32
				done := make(chan struct{})
				go func() {
					defer close(done)
					_, _ = runRestartable(ctx, c, d, func(ctx context.Context, w *watched, _ string, _ time.Duration, software bool) (*castorv1.Ended, error) {
						played := finite
						if tc.live {
							played = live
						}
						_ = w.Play(ctx, played)
						attempt := starts.Add(1)
						if software != (attempt > 1) {
							t.Errorf("attempt %d software=%v, want %v", attempt, software, attempt > 1)
						}
						tick := time.NewTicker(5 * time.Second)
						defer tick.Stop()
						for {
							if tc.heard {
								if head, err := w.Position(ctx); err == nil {
									heard(c, head.At, head.Paused)
								}
							}
							select {
							case <-ctx.Done():
								return nil, ctx.Err()
							case <-tick.C:
							}
						}
					}, noPause)
				}()
				time.Sleep(18 * time.Second)
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

func TestStopDuringRestartCannotLaunchAnotherPipeline(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		c := newCast("stop-during-restart", &castorv1.Device{}, &castorv1.CastRequest{}, func(error) {})
		s := New(nil, nil, nil, nil)
		d := &restartDevice{onStop: cancel}
		var starts atomic.Int32
		started, cleaning, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
		finished := make(chan struct{})
		if err := s.casts.Add(c.id, c, finished); err != nil {
			t.Fatal(err)
		}
		go func() {
			defer close(finished)
			defer close(c.done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, w *watched, _ string, _ time.Duration, _ bool) (*castorv1.Ended, error) {
				if starts.Add(1) > 1 {
					t.Error("Stop resurrected playback")
					return nil, ctx.Err()
				}
				_ = w.Play(ctx, finite)
				heard(c, time.Minute, false)
				close(started)
				<-ctx.Done()
				close(cleaning)
				<-release
				return nil, ctx.Err()
			}, noPause)
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
		d := &restartDevice{ended: make(chan struct{})}
		var starts atomic.Int32
		ready := make(chan struct{}, 2)
		finished := make(chan struct{})
		if err := s.casts.Add(c.id, c, finished); err != nil {
			t.Fatal(err)
		}
		go func() {
			defer close(finished)
			defer close(c.done)
			_, _ = runRestartable(ctx, c, d, func(ctx context.Context, w *watched, _ string, _ time.Duration, _ bool) (*castorv1.Ended, error) {
				starts.Add(1)
				if err := w.Play(ctx, source); err != nil {
					return nil, err
				}
				heard(c, time.Minute, false)
				ready <- struct{}{}
				return &castorv1.Ended{Result: &castorv1.Ended_Completed{Completed: &emptypb.Empty{}}}, nil
			}, noPause)
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

func TestControlsReachTheMediaCastTheCastPlaysNow(t *testing.T) {
	req := &castorv1.SeekRequest{CastId: "api-cast", Position: durationpb.New(time.Minute)}
	moved := withCastID(req, "media-run-2")
	if moved.GetCastId() != "media-run-2" || moved.GetPosition().AsDuration() != time.Minute || req.GetCastId() != "api-cast" {
		t.Fatalf("withCastID = %v from %v, want the same seek for the media run, the request untouched", moved, req)
	}
	if same := withCastID(req, "api-cast"); same != req {
		t.Error("a request already naming the media cast was copied")
	}
}
