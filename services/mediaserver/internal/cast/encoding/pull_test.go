package encoding

import (
	"bytes"
	"encoding/binary"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/cast/encoding/fetch"
	"github.com/stupside/castor/services/mediaserver/internal/ffmpeg"
	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// A real pull against a real origin, reading the spool, progress and PCM outputs.
func TestTheExtraPipesCarryWhatTheFlagsSay(t *testing.T) {
	ffmpegPath, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not on PATH")
	}
	fixture := filepath.Join(t.TempDir(), "video.mp4")
	gen := exec.CommandContext(t.Context(), ffmpegPath,
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc=size=64x64:rate=5:duration=1",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=1",
		"-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-ac", "2", "-shortest",
		"-movflags", "+faststart", fixture)
	if out, err := gen.CombinedOutput(); err != nil {
		t.Fatalf("generating fixture: %v\n%s", err, out)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.ServeFile(w, r, fixture)
	}))
	defer srv.Close()

	opts := copyingPull(muxedSource(t, mustURL(t, srv.URL+"/video.mp4"), media.MP4, fetch.For(media.Fetch{}, 30*time.Second)))
	opts.PCMSampleRate = 16000
	cmd, err := PullArgs(opts)
	if err != nil {
		t.Fatal(err)
	}

	var (
		samples []media.Progress
		pcm     countingWriter
	)
	proc, err := ffmpeg.Start(t.Context(), ffmpegPath, cmd, ffmpeg.Options{PCM: &pcm,
		Progress: func(s media.Progress) { samples = append(samples, s) }})
	if err != nil {
		t.Fatal(err)
	}
	spooled, _ := io.Copy(io.Discard, proc.Stdout)
	if err := proc.Wait(); err != nil {
		t.Fatalf("the pull failed: %v\n%q", err, proc.Evidence().Lines)
	}

	if spooled == 0 {
		t.Error("the spool output produced nothing")
	}
	// One second of mono s16le at 16 kHz is 32000 bytes.
	if pcm.n < 16000 {
		t.Errorf("PCM feed delivered %d bytes", pcm.n)
	}
	if len(samples) == 0 {
		t.Fatal("no progress samples arrived")
	}
	last := samples[len(samples)-1]
	if last.Position <= 0 || last.Speed <= 0 || last.Bytes <= 0 {
		t.Errorf("final sample = %+v, want a position, a size and a speed", last)
	}
	if got := proc.Progress(); got != last {
		t.Errorf("Progress() = %+v, want the last sample %+v", got, last)
	}
}

// countingWriter is safe to read after Wait, which joins the tee.
type countingWriter struct{ n int64 }

func (w *countingWriter) Write(p []byte) (int, error) {
	w.n += int64(len(p))
	return len(p), nil
}

// A timestamp-free PCM feed must retain the same elapsed time as the picture.
// Start the container at a nonzero timestamp, delay the audio, and omit a second
// of audio packets: none of those offsets may be flattened into adjacent speech.
func TestPCMRetainsDelayedAudioAndGapsOnNormalizedProgramClock(t *testing.T) {
	ffmpegPath, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not on PATH")
	}
	fixture := filepath.Join(t.TempDir(), "delayed.mp4")
	gen := exec.CommandContext(t.Context(), ffmpegPath,
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "color=size=64x64:rate=10:duration=6",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=4",
		"-af", "aselect='not(between(t,1,2))',asetpts=PTS+1/TB",
		"-c:v", "libx264", "-bf", "0", "-c:a", "aac", "-output_ts_offset", "7",
		"-movflags", "+faststart", fixture)
	if out, err := gen.CombinedOutput(); err != nil {
		t.Fatalf("fixture: %v\n%s", err, out)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.ServeFile(w, r, fixture) }))
	defer srv.Close()
	opts := copyingPull(muxedSource(t, mustURL(t, srv.URL+"/delayed.mp4"), media.MP4, fetch.Policy{}))
	opts.PCMSampleRate = 16000
	command, err := PullArgs(opts)
	if err != nil {
		t.Fatal(err)
	}
	var pcm bytes.Buffer
	proc, err := ffmpeg.Start(t.Context(), ffmpegPath, command, ffmpeg.Options{PCM: &pcm})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.Copy(io.Discard, proc.Stdout); err != nil {
		t.Fatal(err)
	}
	if err := proc.Wait(); err != nil {
		t.Fatalf("pull: %v; %v", err, proc.Evidence().Lines)
	}
	duration := float64(pcm.Len()) / (16000 * 2)
	if duration < 4.9 || duration > 5.2 {
		t.Fatalf("PCM duration %.3fs, want about 5s on normalized picture clock", duration)
	}
	for _, window := range []struct {
		name       string
		start, end float64
		silent     bool
	}{
		{"delayed start", 0.1, 0.8, true},
		{"first speech", 1.2, 1.8, false},
		{"missing packets", 2.2, 2.8, true},
		{"later speech", 3.2, 4.8, false},
	} {
		t.Run(window.name, func(t *testing.T) {
			start, end := int(window.start*16000)*2, int(window.end*16000)*2
			var energy float64
			for i := start; i < end; i += 2 {
				sample := float64(int16(binary.LittleEndian.Uint16(pcm.Bytes()[i : i+2])))
				energy += sample * sample
			}
			rms := math.Sqrt(energy / float64((end-start)/2))
			if window.silent && rms > 10 {
				t.Fatalf("silence RMS %.1f, expected <=10", rms)
			}
			if !window.silent && rms < 100 {
				t.Fatalf("speech RMS %.1f, expected >=100", rms)
			}
		})
	}
}
