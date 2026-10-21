// Package fetch decides how a cast fetches the bytes of a source, and how fast.
package fetch

import (
	"cmp"
	"time"
)

// BackoffMax is the longest ffmpeg waits before it reconnects to a source.
const BackoffMax = 60 * time.Second

// EncodeBurst is how much media a subtitle-burning encode reads ahead at wire speed.
const EncodeBurst = 10 * time.Second

// Window is how far ahead of playback a read of a finite program keeps.
const Window = 90 * time.Second

// Pace is how fast a read may consume its source.
type Pace struct {
	// Realtime is the media seconds per wall-clock second the read is allowed.
	Realtime float64

	// Burst is how much of the stream may be read at wire speed before Realtime binds.
	Burst time.Duration

	// Catchup is the rate a read that fell behind its schedule takes until it is back on it; 0 is Realtime.
	Catchup float64
}

func (p Pace) capped(ceiling Pace) Pace {
	if ceiling.Realtime <= 0 {
		return p
	}
	if p.Realtime <= 0 {
		return ceiling
	}
	capped := Pace{Realtime: min(p.Realtime, ceiling.Realtime), Burst: min(p.Burst, ceiling.Burst)}
	if catchup := min(cmp.Or(p.Catchup, p.Realtime), cmp.Or(ceiling.Catchup, ceiling.Realtime)); catchup > capped.Realtime {
		capped.Catchup = catchup
	}
	return capped
}

var (
	// A VOD read fills a window ahead of playback at wire speed, then keeps pace, so an origin serving on demand is never asked for the whole film.
	paceVOD = Pace{Realtime: 1.0, Burst: Window, Catchup: 2.0}
	// A live read held to 1x never wins back what a stalled segment cost it, and falls off the window.
	paceLive = Pace{Realtime: 1.0, Catchup: paceVOD.Catchup}
	// pacePlayback asks for a link at the rate it is played, with no burst.
	pacePlayback = Pace{Realtime: 1.0}
)

// segmentOpenRetries is how many times a segment that would not open is asked for again.
const segmentOpenRetries = 3

// Policy is how one input is fetched: how long a read may stall, how often a segment is retried, and how fast.
type Policy struct {
	// Deadline is how long ONE read may stall before ffmpeg abandons it and reconnects (-rw_timeout).
	Deadline time.Duration

	SegmentRetries int

	// Pace is how fast the source may be consumed.
	Pace Pace
}
