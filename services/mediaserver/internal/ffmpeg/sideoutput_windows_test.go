package ffmpeg

import (
	"context"
	"errors"
	"io"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/Microsoft/go-winio"
)

func TestWindowsSideOutputCloseUnblocksReaders(t *testing.T) {
	for _, connect := range []bool{false, true} {
		name := "before connection"
		if connect {
			name = "after connection"
		}
		t.Run(name, func(t *testing.T) {
			cmd := exec.Command("ffmpeg", "-progress", ProgressPipe)
			output, err := newSideOutput(cmd, progressFD)
			if err != nil {
				t.Fatal(err)
			}
			defer output.Close()
			path := strings.TrimPrefix(cmd.Args[2], "file:")
			if connect {
				peer, err := winio.DialPipeContext(t.Context(), path)
				if err != nil {
					t.Fatal(err)
				}
				defer peer.Close()
				select {
				case <-output.accepted:
				case <-time.After(time.Second):
					t.Fatal("listener did not accept the connected client")
				}
			}
			read := make(chan error, 1)
			go func() { _, err := output.Read(make([]byte, 1)); read <- err }()
			if err := output.Close(); err != nil {
				t.Fatal(err)
			}
			select {
			case err := <-read:
				if !errors.Is(err, io.EOF) {
					t.Fatalf("closed read: %v", err)
				}
			case <-time.After(time.Second):
				t.Fatal("reader remained blocked after close")
			}
			ctx, cancel := context.WithTimeout(t.Context(), time.Second)
			defer cancel()
			peer, err := winio.DialPipeContext(ctx, path)
			if err == nil {
				peer.Close()
				t.Fatal("closed output still accepts clients")
			}
		})
	}
}
