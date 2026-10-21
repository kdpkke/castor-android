package fetch

import (
	"maps"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// Plan is the fetch policy of each input of a program.
type Plan map[media.InputID]Policy

// ForProgram derives each input independently (demuxed program can read live/VOD at different paces).
func ForProgram(program media.Program, deadline time.Duration) Plan {
	plan := make(Plan, len(program.Inputs))
	for _, input := range program.Inputs {
		plan[input.ID] = For(input.Fetching(), deadline)
	}
	return plan
}

// Cautious lowers inputs that can give up startup burst (single finite recovery).
func (p Plan) Cautious() (Plan, bool) {
	changed := false
	plan := maps.Clone(p)
	for id, policy := range plan {
		if relaxed, ok := cautious(policy); ok {
			plan[id] = relaxed
			changed = true
		}
	}
	return plan, changed
}

// Pace returns tightest positive pace imposed on any input (zero if none).
func (p Plan) Pace() float64 {
	var pace float64
	for _, policy := range p {
		if policy.Pace.Realtime > 0 && (pace == 0 || policy.Pace.Realtime < pace) {
			pace = policy.Pace.Realtime
		}
	}
	return pace
}

// Encoding is the plan an encode reads a source with: every input under the ceiling, or with none, only segmented inputs paced.
func (p Plan) Encoding(program media.Program, ceiling Pace) Plan {
	plan := maps.Clone(p)
	for _, input := range program.Inputs {
		policy, ok := plan[input.ID]
		if !ok {
			continue
		}
		// Unbounded, only a playlist is paced, so a CDN does not answer a burst with 429s.
		if ceiling.Realtime <= 0 && !input.Fetching().Segmented {
			policy.Pace = Pace{}
		}
		policy.Pace = policy.Pace.capped(ceiling)
		plan[input.ID] = policy
	}
	return plan
}
