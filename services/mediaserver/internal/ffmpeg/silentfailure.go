package ffmpeg

import (
	"fmt"
	"strings"
	"sync"
)

// silentFailure is one stderr line that means the output is not playable, or that media is missing from it.
type silentFailure struct {
	marker string
	// lost is media the source never delivered, which a second read of the same link may still deliver.
	lost bool
}

// silentFailures are markers ffmpeg emits on exit 0 with unplayable or incomplete output.
var silentFailures = []silentFailure{
	{
		// aac_adtstoasc with ADTS input: muxer rejects every packet, output unplayable.
		marker: "AAC bitstream not in ADTS format and extradata missing",
	},
	{
		// A response shorter than its Content-Length; ffmpeg keeps what arrived and moves on.
		marker: "Stream ends prematurely",
		lost:   true,
	},
	{
		// The HLS demuxer drops a segment it could not open and carries on to exit 0.
		marker: "failed too many times, skipping",
		lost:   true,
	},
	{
		// The HLS demuxer can stop on a segment I/O error and still exit 0.
		marker: "Error during demuxing: Input/output error",
		lost:   true,
	},
	{
		// Exits non-zero; free to catch early.
		marker: "Malformed AAC bitstream detected",
	},
}

// SilentFailure returns a non-nil error once ffmpeg printed unplayable-output line, or nil.
func (p *Process) SilentFailure() error { return p.markers.failure() }

// LostMedia reports that the source delivered less than it declared, whatever ffmpeg's exit status says.
func (p *Process) LostMedia() bool { return p.markers.lostMedia() }

// markerWatch scans every line for unplayable-output markers (survives tail scrolling).
type markerWatch struct {
	mu sync.Mutex
	// silent is the FIRST silent-failure line (nil until one appears).
	silent error
	lost   bool
}

func (w *markerWatch) Observe(line string) {
	for _, f := range silentFailures {
		if !strings.Contains(line, f.marker) {
			continue
		}
		w.mu.Lock()
		if w.silent == nil {
			w.silent = fmt.Errorf("ffmpeg produced unplayable output: %s", line)
		}
		w.lost = w.lost || f.lost
		w.mu.Unlock()
		return
	}
}

func (w *markerWatch) failure() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.silent
}

func (w *markerWatch) lostMedia() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.lost
}
