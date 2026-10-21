package hls

import (
	"errors"
	"fmt"
	"html"
	"io"
	"math"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/Eyevinn/hls-m3u8/m3u8"
	"github.com/stupside/castor/services/mediaserver/internal/subtitle"
)

var errCaptionsPending = errors.New("waiting for settled transcript")

var errCaptionWindow = errors.New("initial media window expired before caption delivery")

// captionDelivery derives both renditions from the same producer playlist. Only
// segments whose transcript is settled are advertised. Frozen VTT responses are
// retained as long as their video fragment remains fetchable, never for the whole film.
type captionDelivery struct {
	root        *os.Root
	playlist    string
	cues        *subtitle.Cues
	mu          sync.Mutex
	origin      *float64
	videoOffset time.Duration
	bandwidth   uint64
	codecs      string
	cache       map[string]captionSegment
}

type captionSegment struct {
	media string
	text  string
}

func (c *captionDelivery) serve(w http.ResponseWriter, r *http.Request) bool {
	name := strings.TrimPrefix(r.URL.Path, "/")
	if name != "master.m3u8" && name != "captions.m3u8" && name != c.playlist && !(strings.HasPrefix(name, "caption-") && strings.HasSuffix(name, ".vtt")) {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")

	if segment, ok := c.cache[name]; ok {
		w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		io.WriteString(w, segment.text)
		return true
	}
	video, captions, err := c.playlists()
	if err != nil {
		w.Header().Set("Retry-After", "1")
		http.Error(w, "captions are not ready", http.StatusServiceUnavailable)
		return true
	}
	switch name {
	case "master.m3u8":
		fmt.Fprintf(w, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"captions\",NAME=\"Transcription\",DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,URI=\"captions.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=%d,CODECS=\"%s\",SUBTITLES=\"captions\"\n%s\n", c.bandwidth, c.codecs, c.playlist)
	case c.playlist:
		io.WriteString(w, video)
	case "captions.m3u8":
		io.WriteString(w, captions)
	default:
		segment, ok := c.cache[name]
		if !ok {
			http.NotFound(w, r)
			return true
		}
		w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		io.WriteString(w, segment.text)
	}
	return true
}

func (c *captionDelivery) playlists() (string, string, error) {
	f, err := c.root.Open(c.playlist)
	if err != nil {
		return "", "", err
	}
	decoded, _, err := m3u8.DecodeFrom(io.LimitReader(f, 64<<10), true)
	f.Close()
	if err != nil {
		return "", "", err
	}
	p, ok := decoded.(*m3u8.MediaPlaylist)
	if !ok {
		return "", "", fmt.Errorf("expected media playlist")
	}
	var segments []*m3u8.MediaSegment
	for _, s := range p.Segments {
		if s != nil {
			segments = append(segments, s)
		}
	}
	if len(segments) == 0 {
		return "", "", errCaptionsPending
	}
	if len(segments) > segmentFileLimit {
		return "", "", fmt.Errorf("invalid media window")
	}
	init := p.Map
	if segments[0].Map != nil {
		init = segments[0].Map
	}
	if init == nil {
		return "", "", fmt.Errorf("missing media initialization")
	}
	timing, err := captionMedia(c.root, init.URI, segments[0].URI)
	if err != nil {
		return "", "", err
	}
	c.codecs = timing.Codecs
	if c.origin == nil {
		if p.SeqNo != 0 {
			return "", "", errCaptionWindow
		}
		if timing.VideoStart == nil {
			return "", "", fmt.Errorf("first caption fragment has no video clock")
		}
		origin := *timing.VideoStart - c.videoOffset.Seconds()
		c.origin = &origin
	}
	c.cues.DiscardBefore(timing.Start - *c.origin)
	var video, captions strings.Builder
	fmt.Fprintf(&video, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:%d\n#EXT-X-MAP:URI=\"%s\"\n", p.TargetDuration, p.SeqNo, init.URI)
	fmt.Fprintf(&captions, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:%d\n", p.TargetDuration, p.SeqNo)
	published := 0
	for i, s := range segments {
		if s.Duration <= 0 || math.IsNaN(s.Duration) || math.IsInf(s.Duration, 0) {
			return "", "", fmt.Errorf("invalid segment duration")
		}
		name := fmt.Sprintf("caption-%d.vtt", p.SeqNo+uint64(i))
		if _, ok := c.cache[name]; !ok {
			interval := timing
			if i > 0 {
				interval, err = captionMedia(c.root, init.URI, s.URI)
				if err != nil {
					return "", "", err
				}
			}
			end := interval.End - *c.origin
			snapshot := c.cues.Snapshot(interval.Start-*c.origin, end)
			if !snapshot.Complete && snapshot.SettledTo+0.000001 < end {
				break
			}
			c.cache[name] = captionSegment{media: s.URI, text: captionVTT(snapshot.Cues, *c.origin)}
		}
		fmt.Fprintf(&video, "#EXTINF:%.6f,\n%s\n", s.Duration, s.URI)
		fmt.Fprintf(&captions, "#EXTINF:%.6f,\n%s\n", s.Duration, name)
		info, err := c.root.Stat(s.URI)
		if err != nil {
			return "", "", err
		}
		rate := uint64(math.Ceil(float64(info.Size()+int64(len(c.cache[name].text))) * 8 / s.Duration))
		c.bandwidth = max(c.bandwidth, rate)
		published++
	}
	if published == 0 {
		return "", "", errCaptionsPending
	}
	if p.Closed && published == len(segments) {
		video.WriteString("#EXT-X-ENDLIST\n")
		captions.WriteString("#EXT-X-ENDLIST\n")
	}
	for name, s := range c.cache {
		if _, err := c.root.Stat(s.media); errors.Is(err, os.ErrNotExist) {
			delete(c.cache, name)
		}
	}
	return video.String(), captions.String(), nil
}

func captionVTT(cues []subtitle.Cue, origin float64) string {
	var b strings.Builder
	ticks := int64(math.Round(origin * 90000))
	ticks = ((ticks % (1 << 33)) + (1 << 33)) % (1 << 33)
	fmt.Fprintf(&b, "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:%d\n\n", ticks)
	for _, cue := range cues {
		fmt.Fprintf(&b, "%s --> %s\n%s\n\n", captionTime(cue.Start), captionTime(cue.End), html.EscapeString(cue.Text))
	}
	return b.String()
}

func captionTime(seconds float64) string {
	ms := time.Duration(math.Round(max(0, seconds)*1000)) * time.Millisecond
	return fmt.Sprintf("%02d:%02d:%02d.%03d", int64(ms/time.Hour), int64(ms/time.Minute)%60, int64(ms/time.Second)%60, int64(ms/time.Millisecond)%1000)
}
