package cast

import (
	"context"
	"sync/atomic"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/emptypb"

	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"github.com/stupside/castor/internal/latest"
)

// cast is one cast this server runs, and where it stands for its watchers.
type cast struct {
	id        string
	device    *castorv1.Device
	source    *castorv1.Source
	mediaInfo *castorv1.MediaInfo
	start     time.Duration
	started   time.Time
	// stop ends the cast; its cause is why, errStopped when asked.
	stop context.CancelCauseFunc
	now  *latest.Value[view]
	// done closes once the cast ended and let its device go.
	done chan struct{}

	// Android: Restart reloads the source on the same lease, and a stalled live stream restarts by itself.
	restarts   chan *restartRequest
	restarting atomic.Bool
	// media is the media server's id for the run playing now; nil is c's own id.
	media             atomic.Pointer[string]
	automaticRestarts atomic.Uint32
	// Each run reads an immutable preference snapshot; only an accepted restart replaces it.
	preferences atomic.Pointer[castorv1.Preferences]
	// managed keeps a direct handoff's receiver until it ends, as the Android client asks.
	managed bool
}

// view is a cast at one moment.
type view struct {
	status *castorv1.CastStatus
	ended  *castorv1.Ended
}

func newCast(id string, device *castorv1.Device, req *castorv1.CastRequest, stop context.CancelCauseFunc) *cast {
	c := &cast{
		id: id, device: device,
		source: proto.CloneOf(req.GetSource()), mediaInfo: proto.CloneOf(req.GetMediaInfo()), start: req.GetStart().AsDuration(),
		started: time.Now(), stop: stop,
		now:  latest.New(view{status: &castorv1.CastStatus{State: &castorv1.CastStatus_Connecting{Connecting: &emptypb.Empty{}}}}),
		done: make(chan struct{}),

		restarts: make(chan *restartRequest),
		managed:  req.GetPreferences().GetManagedPlayback(),
	}
	c.preferences.Store(proto.CloneOf(req.GetPreferences()))
	return c
}

// show publishes a new status snapshot until the cast has ended.
func (c *cast) show(status *castorv1.CastStatus) {
	c.now.Update(func(v view) (view, bool) {
		if v.ended != nil {
			return v, false
		}
		status = proto.CloneOf(status)
		if playing := status.GetCasting(); playing != nil {
			playing.AutomaticRestarts = c.automaticRestarts.Load()
		}
		return view{status: status}, true
	})
}

// end publishes how the cast ended, once; the last status stays what it was.
func (c *cast) end(ended *castorv1.Ended) {
	c.now.Update(func(v view) (view, bool) {
		if v.ended != nil {
			return v, false
		}
		return view{status: v.status, ended: ended}, true
	})
}

// over reports whether the cast has ended.
func (c *cast) over() bool {
	v, _ := c.now.Load()
	return v.ended != nil
}
