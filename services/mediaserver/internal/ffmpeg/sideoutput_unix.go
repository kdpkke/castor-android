//go:build !windows

package ffmpeg

import (
	"os"
	"os/exec"
)

// The native read end needs no wrapper: the child owns the inherited write end.
type sideOutput = os.File

func newSideOutput(cmd *exec.Cmd, _ int) (*sideOutput, error) {
	read, write, err := os.Pipe()
	if err != nil {
		return nil, err
	}
	cmd.ExtraFiles = append(cmd.ExtraFiles, write)
	return read, nil
}

// The kernel delivers EOF when the child closes its inherited descriptors.
func finishSideOutputs([]*sideOutput) {}
