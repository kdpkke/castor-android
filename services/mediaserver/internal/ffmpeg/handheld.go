package ffmpeg

import (
	"slices"
	"strings"
)

// Android extension: a phone's cores hold libx264 and libx265 at veryfast only just ahead of a 1080p50 read and fall
// behind it once they warm, which starves a live cast; superfast keeps about twice realtime. Only an Android host
// declares MediaCodec encoders, so a host that does is a handheld.
const handheldPreset = "superfast"

// forHost is list as the host runs it: on a handheld every software encoder takes handheldPreset; list is not edited.
func forHost(declared, list []Encoder) []Encoder {
	if !slices.ContainsFunc(declared, func(e Encoder) bool { return strings.HasSuffix(e.Name, "_mediacodec") }) {
		return list
	}
	out := slices.Clone(list)
	for i, e := range out {
		if e.Hardware {
			continue
		}
		flags := slices.Clone(e.Flags)
		if at := slices.Index(flags, "-preset"); at >= 0 && at+1 < len(flags) {
			flags[at+1] = handheldPreset
		}
		out[i].Flags = flags
	}
	return out
}
