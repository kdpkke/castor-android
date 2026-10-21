package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"

	"github.com/stupside/castor/services/mediaserver/internal/cast/output/storage/diskbudget"
	"golang.org/x/sync/semaphore"
)

// liveWindow holds resources that exist only for rolling storage. A finite
// spool has no window; it cannot accidentally carry partially initialized live state.
type liveWindow struct {
	snapshot         *os.File
	prefix, reserved int64
}

const DefaultLiveWindow int64 = 256 << 20
const liveProbeLimit int64 = 16 << 20

var ErrSpoolExpired = errors.New("the requested stream bytes have expired from the live retention window")

// NewLiveSpool retains a bounded byte window plus a bounded initial probe prefix.
// Its ring is never exposed as an ordinary media file.
func NewLiveSpool(path string) (*Spool, error) {
	return NewLiveSpoolWithBudget(path, DefaultLiveWindow, diskbudget.Shared)
}

func NewLiveSpoolWithBudget(path string, window int64, budget *semaphore.Weighted) (*Spool, error) {
	if window <= 0 || window > DefaultSpoolLimit || budget == nil {
		return nil, fmt.Errorf("invalid live spool budget")
	}
	prefix := min(window, liveProbeLimit)
	if !budget.TryAcquire(window + prefix) {
		return nil, ErrSpoolLimit
	}
	s, err := NewSpoolWithBudget(path, window, budget)
	if err != nil {
		budget.Release(window + prefix)
		return nil, err
	}
	ring, err := os.Create(path + ".ring")
	if err == nil {
		err = ring.Truncate(window)
	}
	if err != nil {
		if ring != nil {
			_ = ring.Close()
			_ = os.Remove(path + ".ring")
		}
		_ = s.w.Close()
		_ = os.Remove(path)
		budget.Release(window + prefix)
		return nil, fmt.Errorf("creating live ring: %w", err)
	}
	s.live = &liveWindow{snapshot: s.w, prefix: prefix, reserved: window + prefix}
	s.w = ring
	return s, nil
}

// writeLive runs under s.mu. Readers cannot race an overwritten physical offset.
func (s *Spool) writeLive(p []byte) (int, error) {
	written := 0
	for len(p) > 0 {
		at := s.size % s.limit
		chunk := p[:min(int64(len(p)), s.limit-at)]
		n, err := s.w.WriteAt(chunk, at)
		if n > 0 && s.size < s.live.prefix {
			keep := min(int64(n), s.live.prefix-s.size)
			_, snapshotErr := s.live.snapshot.WriteAt(chunk[:keep], s.size)
			err = errors.Join(err, snapshotErr)
		}
		s.size += int64(n)
		written += n
		p = p[n:]
		s.cond.Broadcast()
		if err != nil {
			return written, err
		}
		if n == 0 {
			return written, io.ErrShortWrite
		}
	}
	return written, nil
}

func (t *tailReader) readLive(p []byte) (int, error) {
	s := t.spool
	s.mu.Lock()
	defer s.mu.Unlock()
	for t.offset >= s.size && s.completion == nil && !s.removed && t.ctx.Err() == nil {
		s.cond.Wait()
	}
	if err := context.Cause(t.ctx); err != nil {
		return 0, err
	}
	if s.removed {
		return 0, fs.ErrClosed
	}
	if t.offset < max(0, s.size-s.limit) {
		return 0, ErrSpoolExpired
	}
	if t.offset >= s.size {
		if s.completion != nil && s.completion.err != nil {
			return 0, fmt.Errorf("spool producer failed: %w", s.completion.err)
		}
		return 0, io.EOF
	}
	at := t.offset % s.limit
	length := min(int64(len(p)), s.size-t.offset, s.limit-at)
	n, err := s.w.ReadAt(p[:length], at)
	t.offset += int64(n)
	return n, err
}
