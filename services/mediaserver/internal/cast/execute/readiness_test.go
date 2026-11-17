package execute

import (
	"context"
	"net/url"
	"reflect"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// handingDevice keeps the playback it was handed.
type handingDevice struct {
	fakeDevice
	handed Playback
}

func (d *handingDevice) Play(_ context.Context, p Playback) error {
	d.handed = p
	return nil
}

func TestAResumedStreamTellsTheDeviceWhereItBegins(t *testing.T) {
	u, _ := url.Parse("http://127.0.0.1:1/stream.ts")
	ready, err := certifyPresentation(presentation{target: u, contentType: media.MPEGTS, runtime: 104 * time.Second,
		readiness: outputValidated, delivery: &delivery{}, reached: ran{began: 15 * time.Second}})
	if err != nil {
		t.Fatal(err)
	}
	dev := &handingDevice{}
	if err := ready.play(t.Context(), dev); err != nil {
		t.Fatal(err)
	}
	if dev.handed.Begins != 15*time.Second {
		t.Fatalf("the device was told the stream begins at %v, want 15s, where the attempt resumed", dev.handed.Begins)
	}
}

func TestAProgramWithNoEndIsHandedAsAStreamWithNoEnd(t *testing.T) {
	for _, tt := range []struct {
		name string
		p    presentation
		want Stream
	}{
		{"a source", presentation{source: true, contentType: media.HLS, live: true}, Source{ContentType: media.HLS, Live: true}},
		{"a playlist", presentation{contentType: media.HLS, segments: media.MP4, live: true}, Playlist{Segments: media.MP4, Live: true}},
		{"a served stream", presentation{contentType: media.MPEGTS, live: true}, Served{ContentType: media.MPEGTS, Live: true}},
		{"a source with an end", presentation{source: true, contentType: media.MP4, runtime: time.Hour}, Source{ContentType: media.MP4, Runtime: time.Hour}},
		{"a playlist with an end", presentation{contentType: media.HLS, segments: media.MP4, runtime: time.Hour}, Playlist{Runtime: time.Hour, Segments: media.MP4}},
		{"a served stream with an end", presentation{contentType: media.MPEGTS, runtime: time.Hour}, Served{ContentType: media.MPEGTS, Runtime: &Runtime{Length: time.Hour}}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.p.stream(); !reflect.DeepEqual(got, tt.want) {
				t.Errorf("stream = %+v, want %+v", got, tt.want)
			}
		})
	}
}
