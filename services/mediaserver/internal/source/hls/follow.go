package hls

import (
	"context"
	"fmt"
	"net/url"

	"github.com/stupside/castor/services/mediaserver/internal/media"
	"github.com/stupside/castor/services/mediaserver/internal/source"
	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
)

// Timeline follows every media playlist: castor reads its segments ahead of the reader, several at once.
func (Format) Timeline(c source.Client, in media.Input, _ media.TrackKind) timeline.Reading {
	return timeline.Reading{Source: follower{Client: c, Headers: in.Headers, url: in.URL}, Decoder: Decoder{}}
}

// follower reads the origin's current window of one media playlist, and the media it lists.
type follower struct {
	source.Upstream
	url *url.URL
}

func (f follower) Window(ctx context.Context) (timeline.Window, error) {
	body, from, status, err := f.Client.Fetch(ctx, f.url, f.Headers)
	if err != nil {
		return timeline.Window{}, &timeline.Failure{Status: status, Err: err}
	}
	if multivariant(body) {
		return timeline.Window{}, fmt.Errorf("%s now names renditions instead of listing segments", f.url.Redacted())
	}
	listed, err := scanMedia(body, from)
	if err != nil {
		return timeline.Window{}, err
	}
	return timeline.Window{Segments: listed.segments, Closed: listed.closed, Start: listed.start}, nil
}
