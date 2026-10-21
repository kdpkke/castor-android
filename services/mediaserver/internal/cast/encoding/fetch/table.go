package fetch

import (
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// rule is one row of the read table: which shapes of source it answers and the policy it answers with.
type rule struct {
	when func(media.Fetch) bool
	// policy is the row's answer; every read but one abandoned mid-fragment is also given the caller's deadline.
	policy     Policy
	noDeadline bool
}

var rules = []rule{{
	when:   func(f media.Fetch) bool { return f.Live },
	policy: Policy{SegmentRetries: segmentOpenRetries, Pace: paceLive},
}, {
	when:       func(f media.Fetch) bool { return f.Segmented && f.Framing != media.FramingInBand },
	policy:     Policy{SegmentRetries: segmentOpenRetries, Pace: paceVOD},
	noDeadline: true,
}, {
	when:   func(f media.Fetch) bool { return f.Segmented },
	policy: Policy{SegmentRetries: segmentOpenRetries, Pace: paceVOD},
}}

// wholeFile answers what no row did: a source no playlist described, read as one long GET.
var wholeFile = rule{
	policy: Policy{Pace: paceVOD},
}

// For selects the policy a source of this shape is read with.
func For(f media.Fetch, deadline time.Duration) Policy {
	row := wholeFile
	for _, r := range rules {
		if r.when(f) {
			row = r
			break
		}
	}
	p := row.policy
	if !row.noDeadline {
		p.Deadline = deadline
	}
	return p
}

func cautious(p Policy) (Policy, bool) {
	// A live read held to 1x would fall behind for good, and one already at playback pace has nothing left to give.
	if p.Pace == paceLive || p.Pace == pacePlayback {
		return p, false
	}
	p.Pace = pacePlayback
	return p, true
}
