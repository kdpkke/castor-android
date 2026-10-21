package codec

import (
	"context"
	"slices"
	"testing"

	"github.com/stupside/castor/services/mediaserver/internal/ffmpeg"
	"github.com/stupside/castor/services/mediaserver/internal/media"
)

func hostEncoders(_ context.Context, codec media.Codec) (ffmpeg.Encoder, bool) {
	switch codec {
	case media.CodecHEVC:
		return ffmpeg.Encoder{Name: "hevc_videotoolbox", Codec: media.CodecHEVC, Hardware: true}, true
	case media.CodecH264:
		return ffmpeg.Encoder{Name: "libx264", Codec: media.CodecH264}, true
	}
	return ffmpeg.Encoder{}, false
}

func TestSelectVideoEncoder(t *testing.T) {
	decodes := func(codecs ...media.Codec) media.Capabilities {
		var r media.Capabilities
		for _, c := range codecs {
			r.Formats = append(r.Formats, media.FormatSupport{Transport: media.TransportMP4, Video: media.VideoSupport{Codec: c}})
		}
		return r
	}
	hevcHW := ffmpeg.Encoder{Name: "hevc_videotoolbox", Codec: media.CodecHEVC, Hardware: true}
	hevcSW := ffmpeg.Encoder{Name: "libx265", Codec: media.CodecHEVC}
	h264HW := ffmpeg.Encoder{Name: "h264_videotoolbox", Codec: media.CodecH264, Hardware: true}
	h264SW := ffmpeg.Encoder{Name: "libx264", Codec: media.CodecH264}

	for _, tt := range []struct {
		name  string
		caps  media.Capabilities
		avail map[media.Codec]ffmpeg.Encoder
		want  string
	}{
		{"hardware HEVC first", decodes(media.CodecHEVC, media.CodecH264), map[media.Codec]ffmpeg.Encoder{media.CodecHEVC: hevcHW, media.CodecH264: h264HW}, "hevc_videotoolbox"},
		{"software HEVC is never run live", decodes(media.CodecHEVC, media.CodecH264), map[media.Codec]ffmpeg.Encoder{media.CodecHEVC: hevcSW, media.CodecH264: h264HW}, "h264_videotoolbox"},
		{"only what the device decodes", decodes(media.CodecH264), map[media.Codec]ffmpeg.Encoder{media.CodecHEVC: hevcHW, media.CodecH264: h264SW}, "libx264"},
		{"nothing in common", decodes(media.CodecVP8), map[media.Codec]ffmpeg.Encoder{media.CodecH264: h264SW}, ""},
	} {
		t.Run(tt.name, func(t *testing.T) {
			got, _, _ := selectVideoEncoder(tt.caps, func(c media.Codec) (ffmpeg.Encoder, bool) { e, ok := tt.avail[c]; return e, ok })
			if got.Name != tt.want {
				t.Errorf("selectVideoEncoder = %q, want %q", got.Name, tt.want)
			}
		})
	}
}

func TestWhatForcesAVideoReEncode(t *testing.T) {
	h264 := media.Capabilities{Formats: []media.FormatSupport{{Transport: media.TransportMP4, Video: media.VideoSupport{Codec: media.CodecH264}}}}
	hdrCapable := media.Capabilities{Formats: []media.FormatSupport{{Transport: media.TransportMP4, Video: media.VideoSupport{Codec: media.CodecHEVC, Profiles: []media.Profile{"Main 10"}, BitDepths: []int{8, 10}}}, {Transport: media.TransportMP4, Video: media.VideoSupport{Codec: media.CodecH264}}}}
	sdr := media.ProbeInfo{VideoCodec: media.CodecH264, VideoHeight: 720, VideoBitDepth: 8}
	tall := media.ProbeInfo{VideoCodec: media.CodecH264, VideoHeight: 2160, VideoBitDepth: 8}
	hdr := media.ProbeInfo{VideoCodec: media.CodecHEVC, VideoProfile: "Main 10", VideoHeight: 720, VideoBitDepth: 10, VideoHDR: true}

	for _, tt := range []struct {
		name   string
		caps   media.Capabilities
		probe  media.ProbeInfo
		burnIn string
		want   []Reason
	}{
		{name: "nothing against it copies", caps: h264, probe: sdr},
		{name: "a source above the ceiling", caps: h264, probe: tall, want: []Reason{reasonHeightLimit}},
		{name: "HDR even on a device that decodes it", caps: hdrCapable, probe: hdr, want: []Reason{reasonHDRPolicy}},
		{name: "a burn-in needs decoded frames", caps: h264, probe: sdr, burnIn: "/tmp/cue.txt", want: []Reason{reasonSubtitleBurnIn}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			in := Inputs{Caps: tt.caps, Probe: tt.probe, Into: testFormat(t, media.MPEGTS), MaxHeight: 1080, BurnIn: tt.burnIn, Encoders: hostEncoders}
			track, refused, err := decideVideo(t.Context(), in)
			if err != nil {
				t.Fatalf("decideVideo: %v", err)
			}
			if !slices.Equal(refused, tt.want) {
				t.Errorf("reasons = %v, want %v", refused, tt.want)
			}
			if enc, ok := track.Encode(); ok && (enc.MaxHeight != in.MaxHeight || enc.SubtitleTextFile != tt.burnIn) {
				t.Errorf("re-encode carries ceiling %d and burn-in %q, want %d and %q", enc.MaxHeight, enc.SubtitleTextFile, in.MaxHeight, tt.burnIn)
			}
		})
	}
}
