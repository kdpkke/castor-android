package execute

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

type fakeProber struct {
	info   media.ProbeInfo
	err    error
	budget time.Duration
}

func (p *fakeProber) Probe(ctx context.Context) (media.ProbeInfo, media.Reach, error) {
	if deadline, ok := ctx.Deadline(); ok {
		p.budget = time.Until(deadline)
	}
	return p.info, media.ReachUnproven, p.err
}

// TestMeasureKeepsWhatAnsweredWithinItsOwnDeadline: a partial probe is used but never called measured.
func TestMeasureKeepsWhatAnsweredWithinItsOwnDeadline(t *testing.T) {
	half := &fakeProber{
		info: media.ProbeInfo{VideoCodec: media.CodecH264},
		err:  errors.New("probing audio rendition: ffprobe: exit status 1"),
	}
	facts := measure(t.Context(), "the source", half)
	if facts.measured {
		t.Error("a partial measurement reports Measured true")
	}
	if facts.probe.VideoCodec != media.CodecH264 {
		t.Errorf("Probe = %+v, want the half that answered", facts.probe)
	}
	if half.budget == 0 || half.budget > probeBudget {
		t.Errorf("the probe was given %s, want a deadline within %s", half.budget, probeBudget)
	}

	if whole := measure(t.Context(), "the source", &fakeProber{info: media.ProbeInfo{AudioChannels: 6}}); !whole.measured || whole.probe.AudioChannels != 6 {
		t.Errorf("a probe that answered reports %+v", whole)
	}
}

func TestARenditionThatStartsLaterPlaysLater(t *testing.T) {
	program := media.Program{
		Inputs:     []media.Input{{ID: "video"}, {ID: "audio"}, {ID: "commentary"}},
		ClockInput: "video",
		Offsets:    map[media.InputID]time.Duration{"commentary": 500 * time.Millisecond},
	}
	got := aligned(program, map[media.InputID]time.Duration{
		"video": 1400 * time.Millisecond, "audio": 2400 * time.Millisecond, "commentary": 9 * time.Second,
	})
	if got.Offsets["audio"] != time.Second {
		t.Errorf("audio offset %v, want the 1s it starts after the picture", got.Offsets["audio"])
	}
	if got.Offsets["commentary"] != 500*time.Millisecond {
		t.Errorf("commentary offset %v, want the offset the source declared kept", got.Offsets["commentary"])
	}
	if _, set := program.Offsets["audio"]; set {
		t.Error("aligning changed the program it was given")
	}
	if same := aligned(program, map[media.InputID]time.Duration{"video": time.Second, "audio": time.Second + time.Millisecond}); same.Offsets["audio"] != 0 {
		t.Errorf("inputs a millisecond apart were offset by %v, want them left together", same.Offsets["audio"])
	}
}

// declared is p as a source that stated its tracks before any probe.
func declared(p media.Program, info media.Declaration) media.Program {
	p.SetDeclaration(info)
	return p
}

// A transcription adds a sound-only output, which ffmpeg refuses to open over a source with no sound.
func TestOnlyAReadSureToCarrySoundIsTranscribed(t *testing.T) {
	program := func(optional bool) media.Program {
		return media.Program{Tracks: []media.TrackRef{{Kind: media.TrackAudio, Input: media.PrimaryInputID, Optional: optional}}}
	}
	for _, tt := range []struct {
		name    string
		facts   facts
		program media.Program
		want    bool
	}{
		{"the probe heard sound", facts{probe: media.ProbeInfo{AudioCodec: media.CodecAAC}, measured: true}, program(true), true},
		{"the probe heard silence", facts{measured: true}, program(false), false},
		{"unprobed, and the program may be silent", facts{}, program(true), false},
		{"unprobed, and the program requires sound", facts{}, program(false), true},
		{"half a probe that heard sound", facts{probe: media.ProbeInfo{AudioCodec: media.CodecAAC}}, program(true), true},
		{"unprobed, and the source declared sound", facts{}, declared(program(true), media.Declaration{AudioCodec: media.CodecAAC}), true},
	} {
		if got := tt.facts.sounds(tt.program); got != tt.want {
			t.Errorf("%s: sounds = %v, want %v", tt.name, got, tt.want)
		}
	}
}
