package media

import (
	"net/http"
	"net/url"
	"testing"
	"time"
)

func observedProgram(t *testing.T) Program {
	t.Helper()
	p, err := NewProgram(Program{
		Inputs:     []Input{{ID: PrimaryInputID, URL: &url.URL{Scheme: "https", Host: "origin.example", Path: "/video.m3u8"}, Headers: http.Header{"Referer": {"https://player.example/"}}}},
		Tracks:     []TrackRef{{Input: PrimaryInputID, Kind: TrackVideo, Optional: true}, {Input: PrimaryInputID, Kind: TrackAudio, Optional: true}},
		ClockInput: PrimaryInputID, EndPolicy: EndAtLongest,
	})
	if err != nil {
		t.Fatal(err)
	}
	p.SetDeclaration(Declaration{VideoCodec: CodecH264, AudioCodec: CodecAAC})
	p.SetMeasurement(ProbeInfo{VideoCodec: CodecH264, VideoHeight: 720, AudioCodec: CodecAAC})
	return p
}

func TestProgramEvidenceCannotFollowAChangedSelection(t *testing.T) {
	base := observedProgram(t)
	for _, tc := range []struct {
		name   string
		change func(*Program)
	}{
		{"URL", func(p *Program) { p.Inputs[0].URL.Path = "/other.m3u8" }},
		{"headers", func(p *Program) { p.Inputs[0].Headers.Set("Referer", "https://other.example/") }},
		{"representation", func(p *Program) { p.Inputs[0].Representation = "other" }},
		{"format", func(p *Program) { p.Inputs[0].ContentType = MP4 }},
		{"track", func(p *Program) { p.Tracks[1].Index = 1 }},
		{"clock", func(p *Program) { p.Offsets = map[InputID]time.Duration{PrimaryInputID: time.Second} }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			changed := base.Clone()
			tc.change(&changed)
			if _, ok := changed.Measurement(); ok {
				t.Fatal("new selection reused an old observation")
			}
			if _, ok := changed.Declaration(); ok {
				t.Fatal("new selection reused another rendition's declaration")
			}
			if _, ok := base.Measurement(); !ok {
				t.Fatal("changing a clone invalidated the original observation")
			}
		})
	}
}

func TestObservationOwnsItsProbeFacts(t *testing.T) {
	p := observedProgram(t)
	offset := time.Second
	info := ProbeInfo{VideoHeight: 720, VideoStartOffset: &offset, ProgramHeights: map[int]int{0: 720}}
	p.SetMeasurement(info)
	offset = 9 * time.Second
	info.ProgramHeights[0] = 2160
	first, _ := p.Measurement()
	if *first.VideoStartOffset != time.Second || first.ProgramHeights[0] != 720 {
		t.Fatalf("observation changed with its input: %+v", first)
	}
	*first.VideoStartOffset = 8 * time.Second
	first.ProgramHeights[0] = 1080
	second, _ := p.Measurement()
	if *second.VideoStartOffset != time.Second || second.ProgramHeights[0] != 720 {
		t.Fatalf("caller changed retained facts: %+v", second)
	}
}

func TestDeclarationRequiresTracksWithoutBecomingAnObservation(t *testing.T) {
	p := observedProgram(t)
	fresh, err := NewProgram(p)
	if err != nil {
		t.Fatal(err)
	}
	fresh.SetDeclaration(Declaration{VideoCodec: CodecH264})
	if _, ok := fresh.Measurement(); ok {
		t.Fatal("manifest declaration became a completed probe")
	}
	if got := fresh.RequiredTracks(); !got.Video || got.Audio {
		t.Fatalf("declared track requirements = %+v", got)
	}
}
