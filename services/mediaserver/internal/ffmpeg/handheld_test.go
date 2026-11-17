package ffmpeg

import (
	"slices"
	"testing"
)

// Android extension: handheld.go.
func TestAHandheldEncodesInSoftwareAtAPresetItsCoresHold(t *testing.T) {
	preset := func(list []Encoder, name string) string {
		for _, e := range list {
			if e.Name != name {
				continue
			}
			if at := slices.Index(e.Flags, "-preset"); at >= 0 && at+1 < len(e.Flags) {
				return e.Flags[at+1]
			}
		}
		return ""
	}
	handheld := candidates([]Encoder{h264MediaCodec})
	desktop := candidates(nil)
	for _, name := range []string{"libx264", "libx265"} {
		if got := preset(handheld, name); got != handheldPreset {
			t.Errorf("a handheld encodes %s at %q, want %s", name, got, handheldPreset)
		}
		if got := preset(desktop, name); got != "veryfast" {
			t.Errorf("a host that declares nothing encodes %s at %q, want veryfast", name, got)
		}
		if got := preset(encoders, name); got != "veryfast" {
			t.Errorf("the shared table was edited: %s is at %q", name, got)
		}
	}
	if got := preset(handheld, "h264_qsv"); got != "veryfast" {
		t.Errorf("a hardware encoder lost its own preset on a handheld: %q", got)
	}
}
