package storage

import (
	"bytes"
	"io"
	"os/exec"
	"strings"
	"testing"
)

// Real transport and decode through many physical ring wraps; no network listener.
func TestLiveRingCarriesRealFFmpegAcrossWraps(t *testing.T) {
	for _, tc := range []struct {
		name, format, codec string
		args                []string
	}{
		{"MPEGTS", "mpegts", "h264", []string{"-c:v", "libx264", "-preset", "ultrafast"}},
		{"FragmentedMP4", "mp4", "h264", []string{"-c:v", "libx264", "-preset", "ultrafast", "-movflags", "frag_keyframe+empty_moov"}},
		{"WebM", "webm", "vp8", []string{"-c:v", "libvpx", "-deadline", "realtime"}},
	} {
		t.Run(tc.name, func(t *testing.T) { testLiveRingFFmpeg(t, tc.format, tc.codec, tc.args) })
	}
}

func testLiveRingFFmpeg(t *testing.T, format, codec string, encoding []string) {
	binary, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg unavailable")
	}
	spool, _ := liveSpool(t, 32<<10)
	tail, err := spool.TailAt(t.Context(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer tail.Close()
	args := []string{"-hide_banner", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25", "-t", "5"}
	args = append(args, encoding...)
	args = append(args, "-f", format, "pipe:1")
	producer := exec.CommandContext(t.Context(), binary, args...)
	decoder := exec.CommandContext(t.Context(), binary, "-hide_banner", "-v", "error", "-f", format, "-i", "pipe:0", "-f", "framemd5", "pipe:1")
	var producerErr, decoderErr, frames bytes.Buffer
	producer.Stderr = &producerErr
	decoder.Stderr = &decoderErr
	decoder.Stdout = &frames
	output, err := producer.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	input, err := decoder.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := decoder.Start(); err != nil {
		t.Fatal(err)
	}
	if err := producer.Start(); err != nil {
		_ = input.Close()
		_ = decoder.Wait()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if producer.Process != nil {
			_ = producer.Process.Kill()
		}
		if decoder.Process != nil {
			_ = decoder.Process.Kill()
		}
	})
	chunk := make([]byte, 4096)
	for {
		n, readErr := output.Read(chunk)
		if n > 0 {
			if _, err := spool.Write(chunk[:n]); err != nil {
				t.Fatal(err)
			}
			echoed := make([]byte, n)
			if _, err := io.ReadFull(tail, echoed); err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(echoed, chunk[:n]) {
				t.Fatal("ring corrupted media bytes")
			}
			if _, err := input.Write(echoed); err != nil {
				t.Fatalf("decoder pipe: %v: %s", err, &decoderErr)
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			t.Fatal(readErr)
		}
	}
	if err := producer.Wait(); err != nil {
		t.Fatalf("producer: %v: %s", err, &producerErr)
	}
	spool.CloseWrite(nil)
	_ = input.Close()
	if err := decoder.Wait(); err != nil {
		t.Fatalf("decoder: %v: %s", err, &decoderErr)
	}
	count := 0
	for line := range strings.SplitSeq(frames.String(), "\n") {
		if line != "" && !strings.HasPrefix(line, "#") {
			count++
		}
	}
	if count != 125 {
		t.Fatalf("decoded %d frames, want125", count)
	}
	if probe, err := exec.LookPath("ffprobe"); err == nil {
		result, err := exec.CommandContext(t.Context(), probe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", spool.ProbePath()).CombinedOutput()
		if err != nil || !strings.Contains(string(result), codec) {
			t.Fatalf("initial snapshot probe = %q, %v", result, err)
		}
	} else {
		t.Fatal("ffprobe unavailable for snapshot validation")
	}
	if first, _ := spool.Retained(); first == 0 {
		t.Fatal("fixture never wrapped the ring")
	}
}
