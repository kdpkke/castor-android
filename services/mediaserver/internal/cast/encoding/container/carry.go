package container

import (
	"slices"
	"strings"

	"github.com/stupside/castor/services/mediaserver/internal/ffmpeg"
	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// Uncarried is the axes into cannot carry as probed, which a cast encodes rather than copies.
func Uncarried(probe media.ProbeInfo, into Format) media.Axes {
	return media.Axes{
		Video: probe.HasVideo() && probe.VideoCodec == "" || uncarried(videoRules, probe.VideoCodec, probe, into),
		Audio: probe.HasAudio() && probe.AudioCodec == "" || uncarried(audioRules, probe.AudioCodec, probe, into),
	}
}

// rule reports whether a container will not carry a codec as probed.
type rule func(codec media.Codec, probe media.ProbeInfo, into Format) bool

// uncarried reports whether any rule of table refuses the codec; no track, nothing to carry.
func uncarried(rules []rule, codec media.Codec, probe media.ProbeInfo, into Format) bool {
	return codec != "" && slices.ContainsFunc(rules, func(r rule) bool { return r(codec, probe, into) })
}

// inBand and outOfBand match declared framing.
func inBand(into Format) bool    { return into.Framing == media.FramingInBand }
func outOfBand(into Format) bool { return into.Framing == media.FramingOutOfBand }

var audioRules = []rule{
	// MPEG-TS writes unsupported codecs as unreadable private data.
	func(c media.Codec, _ media.ProbeInfo, into Format) bool {
		return inBand(into) && (c == media.CodecFLAC || c == media.CodecVorbis || isPCM(c))
	},
	// The mp4 family refuses what it cannot carry, loudly and before a single byte.
	func(c media.Codec, _ media.ProbeInfo, into Format) bool {
		return outOfBand(into) && slices.Contains([]media.Codec{"aac_latm", "wmav2", "pcm_mulaw", "pcm_alaw"}, c)
	},
	// The one rule here that no artifact can reveal.
	func(c media.Codec, probe media.ProbeInfo, into Format) bool {
		return c == media.CodecTrueHD && into.Muxer == ffmpeg.FormatMP4 && !probe.HasVideo()
	},
}

var videoRules = []rule{
	// MPEG-TS has a stream type for four video codecs only; any other becomes unreadable private data.
	func(c media.Codec, _ media.ProbeInfo, into Format) bool {
		return inBand(into) && !slices.Contains([]media.Codec{media.CodecH264, media.CodecHEVC, "mpeg2video", media.CodecMPEG4}, c)
	},
	// The codecs the mp4 family has no tag for.
	func(c media.Codec, _ media.ProbeInfo, into Format) bool {
		return outOfBand(into) && slices.Contains([]media.Codec{
			media.CodecVP8, "msmpeg4v3",
			"theora", "flv1", "vp6f", "wmv1", "wmv2", "wmv3", "vc1",
			"h263", "h263p", "prores", "dvvideo", "rv40", "svq3",
		}, c)
	},
}

// isPCM matches every PCM codec by ffprobe's pcm_ prefix; MPEG-TS treats them alike.
func isPCM(c media.Codec) bool { return strings.HasPrefix(string(c), "pcm_") }
