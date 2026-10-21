package codec

import (
	"cmp"
	"context"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"github.com/stupside/castor/services/mediaserver/internal/cast/encoding/container"
	"github.com/stupside/castor/services/mediaserver/internal/ffmpeg"
	"github.com/stupside/castor/services/mediaserver/internal/media"
)

var videoCarriageRefusals = []refusalRule{{
	// Container carriage asks whether ffmpeg's muxer has a stream type for the codec.
	reason: reasonContainerVideo,
	when:   func(in Inputs) bool { return container.Uncarried(in.Probe, in.Into).Video },
}, {
	// One encoder gives the device one stream: a copy keeps each piece's own size and timestamps.
	reason: reasonSpliced,
	when:   func(in Inputs) bool { return in.Spliced },
}, {
	// ffmpeg turns the frames when it decodes them, so only a re-encode lands upright.
	reason: reasonRotated,
	when:   func(in Inputs) bool { return in.Probe.VideoRotation != 0 },
}, {
	// Kept apart from container's refusal because they are different failures with different lifetimes.
	reason: reasonVideoCopyFailed,
	when:   func(in Inputs) bool { return in.Decode.Video },
}}

var videoRefusals = slices.Concat([]refusalRule{{
	// The device's advertised envelope is the only thing that makes a copy safe at all.
	reason: reasonDeviceVideo,
	when:   func(in Inputs) bool { return !in.Caps.CanCopyVideo(in.Probe) },
}}, videoCarriageRefusals, []refusalRule{{
	// The ceiling is the user's request and may not be bypassed.
	reason: reasonHeightLimit,
	when:   func(in Inputs) bool { return !in.MaxHeight.Admits(in.Probe.VideoHeight) },
}, {
	reason: reasonInterlaced,
	when:   func(in Inputs) bool { return in.Probe.VideoInterlaced && !in.Caps.Deinterlaces },
}, {
	reason: reasonHDRPolicy,
	when:   func(in Inputs) bool { return in.Probe.VideoHDR },
}, {
	// A cue drawn into the picture needs decoded frames; a copy has no frames to draw on.
	reason: reasonSubtitleBurnIn,
	when:   func(in Inputs) bool { return in.BurnIn != "" },
}})

func decideVideo(ctx context.Context, in Inputs) (Track[VideoEncode], []Reason, error) {
	if in.Measured && !in.Probe.HasVideo() {
		return OmitVideo(), nil, nil
	}
	refused := refuse(videoRefusals, in)
	if len(refused) == 0 {
		return CopyVideo(), nil, nil
	}
	host := func(c media.Codec) (ffmpeg.Encoder, bool) { return in.Encoders(ctx, c) }
	enc, t, ok := selectVideoEncoder(in.Caps, host)
	if !ok {
		return Track[VideoEncode]{}, refused, noVideoEncoder(in.Caps)
	}
	return Encode(VideoEncode{
		Encoder:             enc,
		Profile:             t.profile,
		Level:               t.level,
		MaxWidth:            t.width,
		Deinterlace:         in.Probe.VideoInterlaced && !in.Caps.Deinterlaces,
		ToneMap:             in.Probe.VideoHDR,
		MaxFrameRate:        min(playbackFrameRate, t.frameRate),
		Bitrate:             t.bitrate,
		Maxrate:             t.maxrate,
		Bufsize:             t.bufsize,
		MaxHeight:           boundedHeight(in.MaxHeight, t.height),
		KeyframeIntervalSec: keyframeSeconds,
		SubtitleTextFile:    in.BurnIn,
	}), refused, nil
}

// playbackFrameRate is the most any device castor serves shows in a second; above it a re-encode drops frames.
const playbackFrameRate = 60

func selectVideoEncoder(caps media.Capabilities, selectEncoder func(media.Codec) (ffmpeg.Encoder, bool)) (ffmpeg.Encoder, videoTarget, bool) {
	for _, t := range videoTargets {
		for _, format := range caps.Formats {
			support := format.Video
			if support.Codec != t.codec {
				continue
			}
			bounded, ok := targetEnvelope(t, support)
			if !ok {
				continue
			}
			if enc, ok := selectEncoder(t.codec); ok && holdsRealtime(enc, t.codec) {
				return enc, bounded, true
			}
		}
	}
	// No common strategy is safer than inventing support the device never advertised.
	return ffmpeg.Encoder{}, videoTarget{}, false
}

func holdsRealtime(enc ffmpeg.Encoder, codec media.Codec) bool {
	return enc.Hardware || codec == media.CodecH264
}

func noVideoEncoder(caps media.Capabilities) error {
	advertised := make([]string, 0, len(caps.Formats))
	for _, format := range caps.Formats {
		s := format.Video
		if !slices.Contains(advertised, string(s.Codec)) {
			advertised = append(advertised, string(s.Codec))
		}
	}
	return fmt.Errorf("no video encoder for this device: it decodes %s", cmp.Or(strings.Join(advertised, ", "), "no video codec"))
}

type videoTarget struct {
	codec                     media.Codec
	profile, level            string
	width, height             int
	frameRate                 float64
	bitrate, maxrate, bufsize string
}

// floorVideo is the codec every device decodes, and the one a read-once buffer is produced in.
var floorVideo = videoTarget{codec: media.CodecH264, bitrate: "4M", maxrate: "4M", bufsize: "8M"}

// videoTargets is every codec castor encodes video to, in preference order.
var videoTargets = []videoTarget{
	{codec: media.CodecHEVC, bitrate: "2M", maxrate: "2M", bufsize: "4M"},
	floorVideo,
}

const keyframeSeconds = 2

const floorVideoQuality = 23

// targetEnvelope only selects encodes the host's 8-bit pipeline can honor.
// Unknown or smaller levels are refused rather than setting a misleading flag.
func targetEnvelope(t videoTarget, s media.VideoSupport) (videoTarget, bool) {
	if len(s.BitDepths) > 0 && !slices.Contains(s.BitDepths, 8) {
		return t, false
	}
	t.width, t.height, t.frameRate = 1920, 1080, 60
	var candidates []media.Profile
	if t.codec == media.CodecH264 {
		candidates = []media.Profile{media.ProfileHigh, media.ProfileMain, media.ProfileConstrainedBaseline, media.ProfileBaseline}
	} else {
		candidates = []media.Profile{media.ProfileMain}
	}
	selected := false
	for _, p := range candidates {
		if len(s.Profiles) == 0 || slices.Contains(s.Profiles, p) {
			t.profile = strings.ToLower(string(p))
			if p == media.ProfileConstrainedBaseline {
				t.profile = "baseline"
			}
			selected = true
			break
		}
	}
	if !selected {
		return t, false
	}
	if s.MaxLevel > 0 {
		divisor := 10.0
		if t.codec == media.CodecH264 {
			if s.MaxLevel < 31 {
				return t, false
			}
			if s.MaxLevel < 40 {
				t.width, t.height = 1280, 720
			}
			if s.MaxLevel < 42 {
				t.frameRate = 30
			}
		} else {
			divisor = 30
			if s.MaxLevel < 120 {
				return t, false
			}
			if s.MaxLevel < 123 {
				t.frameRate = 30
			}
		}
		t.level = strconv.FormatFloat(float64(s.MaxLevel)/divisor, 'f', 1, 64)
	}
	return t, true
}

func boundedHeight(requested media.HeightCap, envelope int) media.HeightCap {
	if requested <= 0 {
		return media.HeightCap(envelope)
	}
	return media.HeightCap(min(int(requested), envelope))
}
