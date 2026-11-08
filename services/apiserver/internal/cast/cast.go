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
	id                string
	device            *castorv1.Device
	source            *castorv1.Source
	mediaInfo         *castorv1.MediaInfo
	start             time.Duration
	started           time.Time
	restarts          chan *restartRequest
	done              chan struct{}
	restarting        atomic.Bool
	automaticRestarts atomic.Uint32
	managed           bool
	// stop ends the cast; its cause is why, errStopped when asked.
	stop     context.CancelCauseFunc
	now      *latest.Value[view]
	playback atomic.Pointer[playback]
}

// view is a cast at one moment.
type view struct {
	status *castorv1.CastStatus
	ended  *castorv1.Ended
}

func newCast(id string, device *castorv1.Device, req *castorv1.CastRequest, stop context.CancelCauseFunc) *cast {
	return &cast{
		id: id, device: device,
		source: proto.CloneOf(req.GetSource()), mediaInfo: proto.CloneOf(req.GetMediaInfo()), start: req.GetStart().AsDuration(),
		started: time.Now(), stop: stop,
		managed:  req.GetPreferences().GetManagedPlayback(),
		restarts: make(chan *restartRequest), done: make(chan struct{}),
		now: latest.New(view{status: &castorv1.CastStatus{State: &castorv1.CastStatus_Connecting{Connecting: &emptypb.Empty{}}}}),
	}
}

// show publishes a new status snapshot until the owner publishes completion.
func (c *cast) show(status *castorv1.CastStatus) {
	status = proto.CloneOf(status)
	if playing := status.GetCasting(); playing != nil {
		playing.AutomaticRestarts = c.automaticRestarts.Load()
	}
	c.now.Update(func(v view) (view, bool) {
		if v.ended != nil {
			return v, false
		}
		return view{status: status}, true
	})
}
