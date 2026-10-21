package hls

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/cast/output/storage/diskbudget"
	"github.com/stupside/castor/services/mediaserver/internal/subtitle"
)

func captionMP4(t *testing.T, duration string) []byte {
	t.Helper()
	binary, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.CommandContext(t.Context(), binary, "-hide_banner", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25", "-t", duration, "-c:v", "libx264", "-g", "100", "-bf", "2", "-movflags", "delay_moov+default_base_moof", "-frag_duration", "4000000", "-min_frag_duration", "4000000", "-f", "mp4", "pipe:1")
	data, err := cmd.Output()
	if err != nil {
		t.Fatalf("ffmpeg: %v", err)
	}
	return data
}

func captionRoot(t *testing.T) (*os.Root, string) {
	t.Helper()
	dir := t.TempDir()
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	return root, dir
}

func TestCaptionPublisherBackpressuresAtUnsettledFragment(t *testing.T) {
	data := captionMP4(t, "12")
	root, dir := captionRoot(t)
	cues := &subtitle.Cues{}
	reader, writer := io.Pipe()
	defer writer.Close()
	written := make(chan error, 1)
	go func() { _, err := writer.Write(data); writer.Close(); written <- err }()
	published := make(chan error, 1)
	go func() { published <- publishCaptionHLS(t.Context(), root, reader, cues, 0) }()
	awaitCaptionFile(t, filepath.Join(dir, "fragment.tmp"))
	select {
	case err := <-written:
		t.Fatalf("producer drained beyond unsettled first fragment: %v", err)
	case <-time.After(100 * time.Millisecond):
	}
	if _, err := os.Stat(filepath.Join(dir, "stream.m3u8")); !os.IsNotExist(err) {
		t.Fatalf("unsettled playlist published: %v", err)
	}
	cues.Commit(nil, 4)
	awaitCaptionFile(t, filepath.Join(dir, "stream.m3u8"))
	playlist, err := os.ReadFile(filepath.Join(dir, "stream.m3u8"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(playlist), "seg_00000.m4s") || strings.Contains(string(playlist), "seg_00001.m4s") {
		t.Fatalf("premature publication: %s", playlist)
	}
	select {
	case err := <-written:
		t.Fatalf("producer drained beyond unsettled second fragment: %v", err)
	case <-time.After(100 * time.Millisecond):
	}
	cues.Close()
	if err := <-published; err != nil {
		t.Fatal(err)
	}
	if err := <-written; err != nil {
		t.Fatal(err)
	}
	playlist, err = os.ReadFile(filepath.Join(dir, "stream.m3u8"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(playlist), "#EXT-X-ENDLIST") || !strings.Contains(string(playlist), "seg_00002.m4s") {
		t.Fatalf("incomplete playlist: %s", playlist)
	}
}

func awaitCaptionFile(t *testing.T, path string) {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		if info, err := os.Stat(path); err == nil && info.Size() > 0 {
			return
		}
		select {
		case <-deadline:
			t.Fatalf("file not published: %s", path)
		case <-time.After(10 * time.Millisecond):
		}
	}
}

func TestCaptionPublisherBoundsRollingWindow(t *testing.T) {
	root, dir := captionRoot(t)
	cues := &subtitle.Cues{}
	cues.Close()
	if err := publishCaptionHLS(t.Context(), root, io.NopCloser(bytes.NewReader(captionMP4(t, "44"))), cues, 0); err != nil {
		t.Fatal(err)
	}
	files, err := filepath.Glob(filepath.Join(dir, "seg_*.m4s"))
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 11 {
		t.Fatalf("retained %d fragments", len(files))
	}
	playlist, err := os.ReadFile(filepath.Join(dir, "stream.m3u8"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(playlist), "#EXT-X-MEDIA-SEQUENCE:3") {
		t.Fatalf("window did not roll: %s", playlist)
	}
}

func TestCaptionPublisherReturnsWhenOwnerInterruptsRead(t *testing.T) {
	root, _ := captionRoot(t)
	reader, writer := io.Pipe()
	defer writer.Close()
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() { done <- publishCaptionHLS(ctx, root, reader, &subtitle.Cues{}, 0) }()
	cancel()
	_ = reader.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("canceled reader succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancellation did not unblock pipe")
	}
}

func TestCaptionPublisherCancellationRemovesUnsettledFragment(t *testing.T) {
	root, dir := captionRoot(t)
	ctx, cancel := context.WithCancel(t.Context())
	data := captionMP4(t, "8")
	done := make(chan error, 1)
	go func() { done <- publishCaptionHLS(ctx, root, io.NopCloser(bytes.NewReader(data)), &subtitle.Cues{}, 0) }()
	awaitCaptionFile(t, filepath.Join(dir, "fragment.tmp"))
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("error: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancellation waited on transcript")
	}
	if _, err := os.Stat(filepath.Join(dir, "fragment.tmp")); !os.IsNotExist(err) {
		t.Fatalf("staged fragment retained: %v", err)
	}
}

func TestCaptionPublisherRejectsOversizedBoxBeforeReadingPayload(t *testing.T) {
	root, _ := captionRoot(t)
	header := make([]byte, 8)
	binary.BigEndian.PutUint32(header, uint32(SegmentDirectoryLimit+1))
	copy(header[4:], "ftyp")
	err := publishCaptionHLS(t.Context(), root, io.NopCloser(bytes.NewReader(header)), &subtitle.Cues{}, 0)
	if !errors.Is(err, diskbudget.ErrLimit) {
		t.Fatalf("oversized input: %v", err)
	}
}

func TestCaptionPublisherNeverPublishesTruncatedFragment(t *testing.T) {
	data := captionMP4(t, "8")
	offset := 0
	for offset < len(data) {
		size := int(binary.BigEndian.Uint32(data[offset : offset+4]))
		if string(data[offset+4:offset+8]) == "mdat" {
			data = data[:offset+size-1]
			break
		}
		offset += size
	}
	root, dir := captionRoot(t)
	cues := &subtitle.Cues{}
	cues.Close()
	if err := publishCaptionHLS(t.Context(), root, io.NopCloser(bytes.NewReader(data)), cues, 0); err == nil {
		t.Fatal("truncated media accepted")
	}
	for _, name := range []string{"stream.m3u8", "seg_00000.m4s", "fragment.tmp"} {
		if _, err := os.Stat(filepath.Join(dir, name)); !os.IsNotExist(err) {
			t.Fatalf("partial output retained %s: %v", name, err)
		}
	}
}

func TestCaptionFragmentRetentionHonorsPlaylistLifetime(t *testing.T) {
	root, _ := captionRoot(t)
	file, err := root.Create("old.m4s")
	if err != nil {
		t.Fatal(err)
	}
	file.Close()
	removed := time.Now()
	expiry := removed.Add(4*time.Second + 32*time.Second)
	fragments := []retiredCaptionFragment{{name: "old.m4s", expires: expiry}}
	kept, err := expireCaptionFragments(root, fragments, expiry.Add(-time.Nanosecond))
	if err != nil {
		t.Fatal(err)
	}
	if len(kept) != 1 {
		t.Fatal("fragment expired before last advertised playlist lifetime")
	}
	if _, err := root.Stat("old.m4s"); err != nil {
		t.Fatal(err)
	}
	kept, err = expireCaptionFragments(root, kept, expiry)
	if err != nil {
		t.Fatal(err)
	}
	if len(kept) != 0 {
		t.Fatal("expired fragment retained")
	}
	if _, err := root.Stat("old.m4s"); !os.IsNotExist(err) {
		t.Fatalf("expired media still present: %v", err)
	}
}

func TestCaptionPublisherRefusesInventedVideoClock(t *testing.T) {
	binary, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.CommandContext(t.Context(), binary, "-hide_banner", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=8", "-c:a", "aac", "-movflags", "delay_moov+default_base_moof", "-frag_duration", "4000000", "-min_frag_duration", "4000000", "-f", "mp4", "pipe:1")
	data, err := cmd.Output()
	if err != nil {
		t.Fatal(err)
	}
	root, dir := captionRoot(t)
	cues := &subtitle.Cues{}
	cues.Close()
	err = publishCaptionHLS(t.Context(), root, io.NopCloser(bytes.NewReader(data)), cues, 6*time.Second)
	if err == nil || !strings.Contains(err.Error(), "no video clock") {
		t.Fatalf("audio-only first fragment: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "stream.m3u8")); !os.IsNotExist(err) {
		t.Fatalf("invented video clock published: %v", err)
	}
}
