package ffmpeg

import (
	"strconv"
	"strings"
)

// Extra output fd constants: -progress fd 3 is the one output every invocation has.
const (
	firstExtraFD = 3
	progressFD   = 3
	pcmFD        = 4
)

// pipeURL spells an fd the way ffmpeg's pipe protocol takes it.
func pipeURL(fd int) string { return "pipe:" + strconv.Itoa(fd) }

// The pipes a command reads its input from and routes its outputs to; Start connects the progress feed and PCM tee using the platform transport.
var (
	StdinPipe    = pipeURL(0)
	StdoutPipe   = pipeURL(1)
	ProgressPipe = pipeURL(progressFD)
	PCMPipe      = pipeURL(pcmFD)
)

// Command is an argv plus the extra output pipes it routes to, counted from the argv itself.
type Command struct {
	Args       []string
	ExtraPipes int
}

// NewCommand counts the extra pipes an argv routes to.
func NewCommand(args []string) Command {
	pipes := 0
	for _, arg := range args {
		rest, ok := strings.CutPrefix(arg, "pipe:")
		if !ok {
			continue
		}
		if fd, err := strconv.Atoi(rest); err == nil && fd >= firstExtraFD {
			pipes = max(pipes, fd-firstExtraFD+1)
		}
	}
	return Command{Args: args, ExtraPipes: pipes}
}
