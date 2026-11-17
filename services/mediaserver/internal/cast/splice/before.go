package splice

import "time"

// Android extension: where a chain carries a delivery on after its hardware encoder failed (execute/carryon.go).

// Before is the last cut whose picture is at or before pts, if the stream has one.
func (x *Index) Before(pts time.Duration) (Cut, bool) {
	x.mu.Lock()
	defer x.mu.Unlock()
	for i := len(x.cuts) - 1; i >= 0; i-- {
		if x.cuts[i].PTS <= pts {
			return x.cuts[i], true
		}
	}
	return Cut{}, false
}
