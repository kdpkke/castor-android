package container

import (
	"testing"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

func format(t *testing.T, contentType string) Format {
	t.Helper()
	f, ok := For(contentType)
	if !ok {
		t.Fatalf("castor cannot produce %q", contentType)
	}
	return f
}

// Carriage is codec plus container, not either alone.
func TestUncarriedMatrix(t *testing.T) {
	mpegts, mp4 := format(t, media.MPEGTS), format(t, media.MP4)

	for _, tt := range []struct {
		name         string
		probe        media.ProbeInfo
		into         Format
		video, audio bool
	}{
		{"what travels everywhere needs nothing", media.ProbeInfo{VideoCodec: media.CodecH264, AudioCodec: media.CodecAAC}, mpegts, false, false},
		{"flac is silently dropped by mpegts", media.ProbeInfo{VideoCodec: media.CodecH264, AudioCodec: media.CodecFLAC}, mpegts, false, true},
		{"flac travels in mp4", media.ProbeInfo{VideoCodec: media.CodecH264, AudioCodec: media.CodecFLAC}, mp4, false, false},
		// Alone in fragmented mp4 it decodes silently wrong.
		{"flv1 is private data in mpegts", media.ProbeInfo{VideoCodec: "flv1", AudioCodec: media.CodecAAC}, mpegts, true, false},
		{"mpeg2 travels in mpegts", media.ProbeInfo{VideoCodec: "mpeg2video", AudioCodec: media.CodecAAC}, mpegts, false, false},
		{"truehd alone in a fragmented mp4 is sliced wrong", media.ProbeInfo{AudioCodec: media.CodecTrueHD}, mp4, false, true},
		{"truehd beside video is correct", media.ProbeInfo{VideoCodec: media.CodecH264, AudioCodec: media.CodecTrueHD}, mp4, false, false},
	} {
		t.Run(tt.name, func(t *testing.T) {
			got := Uncarried(tt.probe, tt.into)
			if got.Video != tt.video || got.Audio != tt.audio {
				t.Errorf("Uncarried(%+v -> %s) = %s, want video=%v audio=%v", tt.probe, tt.into.ContentType, got, tt.video, tt.audio)
			}
		})
	}
}
