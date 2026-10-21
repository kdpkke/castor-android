// Package codec decides, track by track, what a cast copies and what it encodes, and into which container.
package codec

import (
	"context"
	"fmt"

	"github.com/stupside/castor/services/mediaserver/internal/cast/encoding/container"
	"github.com/stupside/castor/services/mediaserver/internal/ffmpeg"
	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// Encoders finds the host's working encoder for a codec.
type Encoders func(context.Context, media.Codec) (ffmpeg.Encoder, bool)

// Inputs is everything a plan is decided against.
type Inputs struct {
	// Caps is the connected device's advertised support.
	Caps     media.Capabilities
	Probe    media.ProbeInfo
	Measured bool
	// Into is the container this encode writes.
	Into      container.Format
	Decode    media.Axes
	MaxHeight media.HeightCap
	// Spliced is a source stitched from pieces encoded apart, or live and free to become so, which only a re-encode hands over as one stream.
	Spliced bool
	BurnIn  string
	// Encoders is the host's encoder lookup; required.
	Encoders Encoders
}

// Plan is the complete codec plan for an FFmpeg encode; Refusals explain why it is not a copy.
type Plan struct {
	Video    Track[VideoEncode]
	Audio    Track[AudioEncode]
	Refusals []Reason
}

// Encoded returns which halves of the program this plan produces rather than passes through.
func (p Plan) Encoded() media.Axes {
	_, video := p.Video.Encode()
	_, audio := p.Audio.Encode()
	return media.Axes{Video: video, Audio: audio}
}

// Copied excludes omitted tracks: recovery can only revise a copy that ran.
func (p Plan) Copied() media.Axes {
	return media.Axes{Video: p.Video.Action() == CopyTrack, Audio: p.Audio.Action() == CopyTrack}
}

// Served plans the encode a device is served, from its capabilities, track by track.
func Served(ctx context.Context, in Inputs) (Plan, error) {
	if len(in.Caps.Formats) > 0 {
		var best Plan
		score := 3
		var last error
		for _, f := range in.Caps.Formats {
			if f.Transport.ContentType() != in.Into.ContentType {
				continue
			}
			candidate := in
			candidate.Caps.Formats = []media.FormatSupport{f}
			plan, err := servedTracks(ctx, candidate)
			if err != nil {
				last = err
				continue
			}
			cost := 0
			axes := plan.Encoded()
			if axes.Video {
				cost++
			}
			if axes.Audio {
				cost++
			}
			if cost < score {
				best, score = plan, cost
			}
		}
		if score < 3 {
			return best, nil
		}
		return Plan{}, fmt.Errorf("no usable receiver combination for %s: %v", in.Into.ContentType, last)
	}

	return Plan{}, fmt.Errorf("no playback envelopes advertised")
}

func servedTracks(ctx context.Context, in Inputs) (Plan, error) {
	video, videoRefusals, err := decideVideo(ctx, in)
	if err != nil {
		return Plan{}, fmt.Errorf("planning video: %w", err)
	}
	audio, audioRefusals, err := decideAudio(in)
	if err != nil {
		return Plan{}, fmt.Errorf("planning audio: %w", err)
	}
	return Plan{Video: video, Audio: audio, Refusals: append(videoRefusals, audioRefusals...)}, nil
}
