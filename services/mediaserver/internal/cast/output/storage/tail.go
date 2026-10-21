package storage

import (
	"context"
	"fmt"
	"io"
	"os"
)

type tailReader struct {
	spool  *Spool
	f      *os.File
	ctx    context.Context
	stop   func() bool
	offset int64
	close  func() error
}

func (t *tailReader) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	s := t.spool
	if s.live != nil {
		return t.readLive(p)
	}
	s.mu.Lock()
	for t.offset >= s.size && s.completion == nil && t.ctx.Err() == nil {
		s.cond.Wait()
	}
	size, completion := s.size, s.completion
	s.mu.Unlock()

	if err := context.Cause(t.ctx); err != nil {
		return 0, err
	}
	if t.offset >= size && completion != nil {
		if completion.err != nil {
			return 0, fmt.Errorf("spool producer failed: %w", completion.err)
		}
		return 0, io.EOF
	}

	n, err := t.f.ReadAt(p, t.offset)
	t.offset += int64(n)
	// The file holds at least size bytes, so EOF here only means more data may arrive.
	if err == io.EOF {
		err = nil
	}
	return n, err
}

// Close terminates parked Read with broadcast to prevent encoder deadlock.
func (t *tailReader) Close() error { return t.close() }
