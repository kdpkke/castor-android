package device

import (
	"buf.build/go/protovalidate"
	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
	"google.golang.org/protobuf/proto"
	"testing"
)

func TestCapabilityBoundaryRejectsIncompleteVariants(t *testing.T) {
	valid := &mediav1.Capabilities{Formats: []*mediav1.FormatSupport{{
		Transport: &mediav1.FormatSupport_Progressive{Progressive: &mediav1.ProgressiveSupport{Container: mediav1.ProgressiveSupport_CONTAINER_MP4}},
		Tracks:    &mediav1.FormatSupport_Av{Av: &mediav1.AVSupport{Video: H264Support(), Audio: &mediav1.AudioSupport{Codec: mediav1.AudioCodec_AUDIO_CODEC_AAC}}},
	}}}
	if err := protovalidate.Validate(valid); err != nil {
		t.Fatal(err)
	}
	for name, change := range map[string]func(*mediav1.FormatSupport){
		"no transport":        func(f *mediav1.FormatSupport) { f.Transport = nil },
		"unknown container":   func(f *mediav1.FormatSupport) { f.GetProgressive().Container = 999 },
		"no tracks":           func(f *mediav1.FormatSupport) { f.Tracks = nil },
		"incomplete AV":       func(f *mediav1.FormatSupport) { f.GetAv().Audio = nil },
		"missing video codec": func(f *mediav1.FormatSupport) { f.GetAv().Video.Codec = nil },
		"unknown audio":       func(f *mediav1.FormatSupport) { f.GetAv().Audio.Codec = 999 },
		"unknown profile": func(f *mediav1.FormatSupport) {
			f.GetAv().Video.GetH264().Profiles = []mediav1.H264Support_Profile{999}
		},
		"unknown HLS subtitle mode": func(f *mediav1.FormatSupport) {
			f.Transport = &mediav1.FormatSupport_Hls{Hls: &mediav1.HLSSupport{Subtitles: 999}}
		},
		"unspecified HLS segment format": func(f *mediav1.FormatSupport) {
			f.Transport = &mediav1.FormatSupport_Hls{Hls: &mediav1.HLSSupport{ExtraSegmentFormats: []mediav1.HLSSupport_ExtraSegmentFormat{mediav1.HLSSupport_EXTRA_SEGMENT_FORMAT_UNSPECIFIED}}}
		},
		"duplicate HLS segment format": func(f *mediav1.FormatSupport) {
			f.Transport = &mediav1.FormatSupport_Hls{Hls: &mediav1.HLSSupport{ExtraSegmentFormats: []mediav1.HLSSupport_ExtraSegmentFormat{mediav1.HLSSupport_EXTRA_SEGMENT_FORMAT_TIKTIKPX, mediav1.HLSSupport_EXTRA_SEGMENT_FORMAT_TIKTIKPX}}}
		},
	} {
		t.Run(name, func(t *testing.T) {
			c := proto.CloneOf(valid)
			change(c.Formats[0])
			if err := protovalidate.Validate(c); err == nil {
				t.Fatal("invalid variant accepted")
			}
		})
	}
	knownFuture := proto.CloneOf(valid)
	knownFuture.Formats[0].Transport = &mediav1.FormatSupport_Hls{Hls: &mediav1.HLSSupport{ExtraSegmentFormats: []mediav1.HLSSupport_ExtraSegmentFormat{999}}}
	if err := protovalidate.Validate(knownFuture); err != nil {
		t.Fatalf("a future HLS segment format was rejected: %v", err)
	}
	for name, tracks := range map[string]*mediav1.FormatSupport{
		"audio only": {Tracks: &mediav1.FormatSupport_Audio{Audio: valid.Formats[0].GetAv().Audio}},
		"video only": {Tracks: &mediav1.FormatSupport_Video{Video: valid.Formats[0].GetAv().Video}},
	} {
		t.Run(name, func(t *testing.T) {
			tracks.Transport = &mediav1.FormatSupport_Hls{Hls: &mediav1.HLSSupport{Subtitles: mediav1.HLSSupport_SUBTITLES_WEBVTT}}
			if err := protovalidate.Validate(&mediav1.Capabilities{Formats: []*mediav1.FormatSupport{tracks}}); err != nil {
				t.Fatal(err)
			}
		})
	}
	if err := protovalidate.Validate(&mediav1.Capabilities{}); err == nil {
		t.Fatal("empty envelope list accepted")
	}
}
