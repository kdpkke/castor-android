package ffmpeg

import (
	"fmt"
	"slices"
	"testing"
)

func TestSilentFailuresAreCaughtOnAnyLine(t *testing.T) {
	for _, marker := range []string{
		"[mpegts @ 0x1] AAC bitstream not in ADTS format and extradata missing",
		"[mp4 @ 0x2] Malformed AAC bitstream detected: use the audio bitstream filter 'aac_adtstoasc' to fix it",
	} {
		t.Run(marker, func(t *testing.T) {
			// The marker never enters the bounded tail of complaints and must still be remembered.
			lines := []string{marker}
			for i := range stderrTailCapacity * 2 {
				lines = append(lines, fmt.Sprintf("[warning] late packet %d", i))
			}
			tail, markers := drainLines(t, lines...)
			if markers.failure() == nil {
				t.Error("the marker was forgotten once it left the tail")
			}
			if got := tail.snapshot(); len(got) != stderrTailCapacity || slices.Contains(got, marker) {
				t.Errorf("the tail holds %d lines, want the last %d", len(got), stderrTailCapacity)
			}
		})
	}

	t.Run("a clean transcript reports nothing", func(t *testing.T) {
		if _, markers := drainLines(t, "Input #0, mpegts, from 'pipe:0':", "frame=  250 fps=0.0"); markers.failure() != nil {
			t.Errorf("clean run reported %v", markers.failure())
		}
	})
}

func TestDemuxIOErrorMarksReadIncomplete(t *testing.T) {
	line := "[in#0/hls @ 0x710c28140] [error] Error during demuxing: Input/output error"
	_, markers := drainLines(t, line)
	if markers.failure() == nil {
		t.Fatal("the HLS demux error was treated as a clean read")
	}
	if !markers.lostMedia() {
		t.Error("the HLS demux error did not mark the read incomplete")
	}
}
