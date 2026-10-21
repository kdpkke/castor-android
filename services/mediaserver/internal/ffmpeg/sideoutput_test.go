package ffmpeg

import (
	"bytes"
	"context"
	"io"
	"os/exec"
	"sync"
	"testing"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

func TestSideOutputProcessOwnership(t *testing.T) {
	binary, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not on PATH")
	}
	for _, mode := range []string{"transfer", "start failure", "input failure", "cancel before start", "cancel while running"} {
		t.Run(mode, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
			defer cancel()
			args := []string{"-hide_banner", "-f", "lavfi", "-i", "sine=sample_rate=8000", "-t", "0.1", "-progress", ProgressPipe, "-f", "s16le", PCMPipe}
			path := binary
			switch mode {
			case "start failure":
				path = "/nonexistent/castor-ffmpeg"
			case "input failure":
				args = []string{"-i", "/nonexistent/castor-input", "-progress", ProgressPipe, "-f", "s16le", PCMPipe}
			case "cancel before start":
				cancel()
			case "cancel while running":
				args = []string{"-re", "-f", "lavfi", "-i", "sine=sample_rate=8000", "-progress", ProgressPipe, "-f", "s16le", PCMPipe}
			}
			var pcm bytes.Buffer
			ready := make(chan struct{})
			signal := sync.OnceFunc(func() { close(ready) })
			proc, err := Start(ctx, path, NewCommand(args), Options{PCM: &pcm, Progress: func(media.Progress) { signal() }})
			if mode == "start failure" || mode == "cancel before start" {
				if err == nil {
					proc.Kill()
					_ = proc.Wait()
					t.Fatal("expected start failure")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			wait := sync.OnceValue(proc.Wait)
			t.Cleanup(func() { proc.Kill(); _ = wait() })
			if mode == "cancel while running" {
				select {
				case <-ready:
					cancel()
				case <-ctx.Done():
					t.Fatal("ffmpeg never reported progress")
				}
			}
			_, _ = io.Copy(io.Discard, proc.Stdout)
			err = wait()
			if mode != "transfer" {
				if err == nil {
					t.Fatal("expected process failure")
				}
				return
			}
			if err != nil {
				t.Fatalf("ffmpeg failed: %v; %v", err, proc.Evidence().Lines)
			}
			if pcm.Len() != 1600 {
				t.Fatalf("PCM bytes=%d, want 1600", pcm.Len())
			}
			if proc.Progress() == (media.Progress{}) {
				t.Fatal("progress was not received")
			}
		})
	}
}
