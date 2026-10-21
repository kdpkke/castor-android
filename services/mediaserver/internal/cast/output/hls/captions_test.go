package hls

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/subtitle"
)

func captionFixture(t *testing.T, videoFilters ...string) (*captionDelivery, string) {
	t.Helper()
	binary, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg unavailable")
	}
	dir := t.TempDir()
	args := []string{"-hide_banner", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-c:a", "aac", "-t", "12", "-c:v", "libx264", "-g", "100", "-bf", "2", "-f", "hls", "-hls_time", "4", "-hls_list_size", "8", "-hls_segment_type", "fmp4", "-hls_segment_filename", "seg_%05d.m4s"}
	if len(videoFilters) > 0 {
		sourceArgs := []string{"-hide_banner", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=12", "-c:v", "libx264", "-g", "100", "-bf", "2", "-c:a", "aac"}
		sourceArgs = append(sourceArgs, videoFilters...)
		sourceArgs = append(sourceArgs, "source.mp4")
		source := exec.CommandContext(t.Context(), binary, sourceArgs...)
		source.Dir = dir
		if out, err := source.CombinedOutput(); err != nil {
			t.Fatalf("delayed source: %v %s", err, out)
		}
		args = []string{"-hide_banner", "-v", "error", "-i", "source.mp4", "-c", "copy", "-f", "hls", "-hls_time", "4", "-hls_list_size", "8", "-hls_segment_type", "fmp4", "-hls_segment_filename", "seg_%05d.m4s"}
	}
	args = append(args, "stream.m3u8")
	cmd := exec.CommandContext(t.Context(), binary, args...)
	cmd.Dir = dir
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v: %s", err, out)
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	return &captionDelivery{root: root, playlist: "stream.m3u8", cues: &subtitle.Cues{}, cache: make(map[string]captionSegment)}, dir
}

func captionRequest(t *testing.T, c *captionDelivery, name string) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	if !c.serve(recorder, httptest.NewRequest(http.MethodGet, "/"+name, nil)) {
		t.Fatal("request not routed")
	}
	return recorder
}

func TestCaptionRenditionsWaitForCommittedCues(t *testing.T) {
	c, _ := captionFixture(t)
	if got := captionRequest(t, c, "stream.m3u8").Code; got != http.StatusServiceUnavailable {
		t.Fatalf("unsettled video: %d", got)
	}
	c.cues.Commit([]subtitle.Word{{Start: 1, End: 2, Text: "Hello."}}, 4.2)
	video := captionRequest(t, c, "stream.m3u8").Body.String()
	if !strings.Contains(video, "seg_00000.m4s") || strings.Contains(video, "seg_00001.m4s") || strings.Contains(video, "ENDLIST") {
		t.Fatalf("premature video publication: %s", video)
	}
	playlist := captionRequest(t, c, "captions.m3u8").Body.String()
	if !strings.Contains(playlist, "caption-0.vtt") || strings.Contains(playlist, "caption-1.vtt") {
		t.Fatalf("mismatched captions: %s", playlist)
	}
	first := captionRequest(t, c, "caption-0.vtt")
	if first.Code != http.StatusOK || !strings.Contains(first.Body.String(), "Hello.") {
		t.Fatalf("caption response: %s", first.Body)
	}
	if !strings.Contains(first.Body.String(), "MPEGTS:7200") {
		t.Fatalf("B-frame presentation offset lost: %s", first.Body)
	}
	c.cues.Commit([]subtitle.Word{{Start: 4.5, End: 5.5, Text: "Next."}}, 12)
	c.cues.Close()
	if got := captionRequest(t, c, "caption-0.vtt").Body.String(); got != first.Body.String() {
		t.Fatal("published caption changed")
	}
	if got := captionRequest(t, c, "stream.m3u8").Body.String(); !strings.Contains(got, "ENDLIST") || !strings.Contains(got, "seg_00002.m4s") {
		t.Fatalf("finished video: %s", got)
	}
	if got := captionRequest(t, c, "captions.m3u8").Body.String(); !strings.Contains(got, "ENDLIST") || !strings.Contains(got, "caption-2.vtt") {
		t.Fatalf("finished captions: %s", got)
	}
	master := captionRequest(t, c, "master.m3u8").Body.String()
	if !strings.Contains(master, "CODECS=\"avc1.") || !strings.Contains(master, ",mp4a.40.2,wvtt\"") || !strings.Contains(master, "TYPE=SUBTITLES") || !strings.Contains(master, "SUBTITLES=\"captions\"") {
		t.Fatalf("missing rendition association: %s", master)
	}
}

func TestCaptionWindowUsesActualFragmentTimestamps(t *testing.T) {
	c, dir := captionFixture(t)
	c.cues.Commit([]subtitle.Word{{Start: 3.5, End: 4.5, Text: "Across."}}, 12)
	c.cues.Close()
	if _, _, err := c.playlists(); err != nil {
		t.Fatal(err)
	}
	before := captionRequest(t, c, "caption-1.vtt").Body.String()
	if !strings.Contains(before, "Across.") {
		t.Fatalf("overlapping cue missing: %s", before)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "stream.m3u8"))
	if err != nil {
		t.Fatal(err)
	}
	text := strings.Replace(string(raw), "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:1", 1)
	text = strings.Replace(text, "#EXTINF:4.000000,\nseg_00000.m4s\n", "", 1)
	if err := os.WriteFile(filepath.Join(dir, "stream.m3u8"), []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
	delete(c.cache, "caption-1.vtt")
	if _, _, err := c.playlists(); err != nil {
		t.Fatal(err)
	}
	after := captionRequest(t, c, "caption-1.vtt").Body.String()
	if before != after {
		t.Fatalf("rolling playlist shifted timeline:\n%s\n%s", before, after)
	}
	if _, ok := c.cache["caption-0.vtt"]; !ok {
		t.Fatal("caption evicted while its video remains fetchable")
	}
	if err := os.Remove(filepath.Join(dir, "seg_00000.m4s")); err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.playlists(); err != nil {
		t.Fatal(err)
	}
	if _, ok := c.cache["caption-0.vtt"]; ok {
		t.Fatal("caption retained after its video expired")
	}
}

func TestCaptionVTTClockEscapingAndEmptySegments(t *testing.T) {
	text := captionVTT([]subtitle.Cue{{Start: 3661.125, End: 3662, Text: "<b> & -->"}}, 0)
	if !strings.Contains(text, "01:01:01.125 --> 01:01:02.000") || !strings.Contains(text, "&lt;b&gt; &amp; --&gt;") {
		t.Fatal(text)
	}
	if empty := captionVTT(nil, 0); empty != "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\n\n" {
		t.Fatal(empty)
	}
}

func TestCaptionDeliveryRejectsLostInitialTimeline(t *testing.T) {
	c, dir := captionFixture(t)
	c.cues.Close()
	raw, err := os.ReadFile(filepath.Join(dir, "stream.m3u8"))
	if err != nil {
		t.Fatal(err)
	}
	text := strings.Replace(string(raw), "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:1", 1)
	if err := os.WriteFile(filepath.Join(dir, "stream.m3u8"), []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.playlists(); !errors.Is(err, errCaptionWindow) {
		t.Fatalf("lost origin: %v", err)
	}
}

func TestCaptionDeliveryRoutesThroughSegments(t *testing.T) {
	c, dir := captionFixture(t)
	o := testOpening{Publisher: newLAN(t),
		Format: format()}
	store, err := openSegmentStore(dir, o.Format.Tuning.Output)
	if err != nil {
		t.Fatal(err)
	}
	store.captions = &captionDelivery{root: store.root, playlist: c.playlist, cues: c.cues, videoOffset: c.videoOffset, cache: make(map[string]captionSegment)}
	srv, err := publishSegmentsFixture(t.Context(), o, store, func(context.Context) error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { srv.Close() })
	if srv.URL().Path != "/master.m3u8" {
		t.Fatalf("URL = %s", srv.URL())
	}
	if got, _ := srv.Artifact().Landed(); got != 0 {
		t.Fatalf("unsettled artifact ready: %d", got)
	}
	c.cues.Commit(nil, 12)
	c.cues.Close()
	if got, _ := srv.Artifact().Landed(); got == 0 {
		t.Fatal("settled artifact not ready")
	}
	for _, name := range []string{"/master.m3u8", "/stream.m3u8", "/captions.m3u8", "/caption-0.vtt"} {
		response, err := request(t.Context(), srv, http.MethodGet, name)
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusOK {
			t.Fatalf("%s: %d", name, response.StatusCode)
		}
	}
	if srv.Served() != 0 {
		t.Fatal("subtitle traffic counted as video delivery")
	}
}

func TestCaptionMediaClockIncludesSampleDurations(t *testing.T) {
	c, _ := captionFixture(t)
	first, err := captionMedia(c.root, "init.mp4", "seg_00000.m4s")
	if err != nil {
		t.Fatal(err)
	}
	next, err := captionMedia(c.root, "init.mp4", "seg_00001.m4s")
	if err != nil {
		t.Fatal(err)
	}
	if first.VideoStart == nil || next.VideoStart == nil || *first.VideoStart != 0.08 || first.Start > *first.VideoStart || first.End < 4.08 || *next.VideoStart != 4.08 || next.End < 8.08 {
		t.Fatalf("media clocks: %+v, %+v", first, next)
	}
}

func TestCaptionTimelinePreservesAudioBeforeVideo(t *testing.T) {
	c, _ := captionFixture(t, "-vf", "setpts=PTS+2/TB", "-fps_mode", "passthrough")
	c.videoOffset = 2 * time.Second
	c.cues.Commit([]subtitle.Word{{Start: 0.3, End: 1.6, Text: "Before the picture."}, {Start: 2.5, End: 3.5, Text: "On the original clock."}}, 12)
	c.cues.Close()
	if _, _, err := c.playlists(); err != nil {
		t.Fatal(err)
	}
	text := captionRequest(t, c, "caption-0.vtt").Body.String()
	if !strings.Contains(text, "00:00:00.450 --> 00:00:01.400") || !strings.Contains(text, "Before the picture.") || !strings.Contains(text, "00:00:02.650 --> 00:00:03.300") {
		t.Fatalf("cue lost source clock: %s", text)
	}
	// A two-second source video delay must not become a two-second subtitle shift.
	if *c.origin > 0.2 || *c.origin < -0.2 {
		t.Fatalf("video delay leaked into mapping: %fs", *c.origin)
	}
}
