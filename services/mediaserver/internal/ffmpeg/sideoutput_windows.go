package ffmpeg

import (
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"net"
	"os/exec"
	"sync"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
)

// Windows side outputs use local named pipes restricted to the process user.
type sideOutput struct {
	// accepted closes once ffmpeg's connection is taken, or accepting was abandoned and conn stays nil.
	accepted chan struct{}
	conn     net.Conn

	stopAccepting func()
}

// newSideOutput assigns a private named pipe to the command's logical output.
func newSideOutput(cmd *exec.Cmd, fd int) (*sideOutput, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, fmt.Errorf("side output owner: %w", err)
	}
	path := `\\.\pipe\castor-` + rand.Text()
	ln, err := winio.ListenPipe(path, &winio.PipeConfig{
		SecurityDescriptor: "D:P(A;;GA;;;" + user.User.Sid.String() + ")",
	})
	if err != nil {
		return nil, fmt.Errorf("side output pipe: %w", err)
	}
	address := "file:" + path
	logical := pipeURL(fd)
	for i, arg := range cmd.Args {
		if arg == logical {
			cmd.Args[i] = address
		}
	}
	l := &sideOutput{
		accepted:      make(chan struct{}),
		stopAccepting: sync.OnceFunc(func() { _ = ln.Close() }),
	}
	go func() {
		defer close(l.accepted)
		conn, err := ln.Accept()
		l.stopAccepting()
		if err == nil {
			l.conn = conn
		}
	}()
	return l, nil
}

// Read waits for ffmpeg to connect; an ffmpeg that exits without ever opening the output reads as EOF.
func (l *sideOutput) Read(p []byte) (int, error) {
	<-l.accepted
	if l.conn == nil {
		return 0, io.EOF
	}
	n, err := l.conn.Read(p)
	if errors.Is(err, net.ErrClosed) {
		err = io.EOF
	}
	return n, err
}

func (l *sideOutput) Close() error {
	l.stopAccepting()
	<-l.accepted
	if l.conn == nil {
		return nil
	}
	if err := l.conn.Close(); !errors.Is(err, net.ErrClosed) {
		return err
	}
	return nil
}

// finishSideOutputs releases readers whose connection was never opened.
func finishSideOutputs(outputs []*sideOutput) {
	for _, output := range outputs {
		output.stopAccepting()
	}
}
