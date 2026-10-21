// Package storage buffers media in finite or bounded live spools with blocking readers.
package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"sync"

	"github.com/stupside/castor/services/mediaserver/internal/cast/output/storage/diskbudget"
	"golang.org/x/sync/semaphore"
)

// Spool holds immutable absolute byte positions with blocking tails. Finite
// spools retain every byte; live spools expire positions outside a bounded window.
type Spool struct {
	path string
	w    *os.File
	live *liveWindow

	mu                sync.Mutex
	cond              *sync.Cond
	size              int64
	limit             int64
	budget            *semaphore.Weighted
	readers           int
	removed, released bool
	completion        *spoolCompletion // nil while writing; immutable once published
}

type spoolCompletion struct{ err error }

// DefaultSpoolLimit bounds disk consumption of each append-only stream to 8 GiB.
// Applications needing longer streams may explicitly select a larger limit.
const DefaultSpoolLimit int64 = 8 << 30

var ErrSpoolLimit = diskbudget.ErrLimit
var ErrSpoolReaders = errors.New("stream reader capacity exhausted")

const MaxSpoolReaders = 128

func NewSpool(path string) (*Spool, error) { return NewSpoolWithLimit(path, DefaultSpoolLimit) }

func NewSpoolWithLimit(path string, limit int64) (*Spool, error) {
	return NewSpoolWithBudget(path, limit, diskbudget.Shared)
}

// NewSpoolWithBudget uses an explicit shared disk budget. The owner must Close
// after its producer ends; CloseWrite alone retains the completed file.
func NewSpoolWithBudget(path string, limit int64, budget *semaphore.Weighted) (*Spool, error) {
	if budget == nil {
		return nil, fmt.Errorf("spool requires a disk budget")
	}
	if limit <= 0 {
		return nil, fmt.Errorf("spool byte limit must be positive")
	}
	f, err := os.Create(path)
	if err != nil {
		return nil, fmt.Errorf("creating spool file: %w", err)
	}
	s := &Spool{path: path, w: f, limit: limit, budget: budget}
	s.cond = sync.NewCond(&s.mu)
	return s, nil
}

// Write appends to the spool and wakes any blocked tails.
func (s *Spool) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.completion != nil {
		return 0, fs.ErrClosed
	}
	if s.live != nil {
		return s.writeLive(p)
	}
	if int64(len(p)) > s.limit-s.size {
		return 0, ErrSpoolLimit
	}
	if !s.budget.TryAcquire(int64(len(p))) {
		return 0, ErrSpoolLimit
	}
	// Serialize admission with the write so concurrent producers cannot exceed the budget.
	n, err := s.w.Write(p)
	s.budget.Release(int64(len(p) - n))
	s.size += int64(n)
	s.cond.Broadcast()
	return n, err
}

// CloseWrite marks write finished; err records early stop reason, drained by tails.
func (s *Spool) CloseWrite(err error) {
	s.mu.Lock()
	if s.completion != nil {
		s.mu.Unlock()
		return
	}
	if s.live != nil {
		err = errors.Join(err, s.live.snapshot.Close())
	} else {
		err = errors.Join(err, s.w.Close())
	}
	s.completion = &spoolCompletion{err: err}
	s.cond.Broadcast()
	s.mu.Unlock()
}

func (s *Spool) Size() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.size
}

// Path is the full finite file, or empty for live storage. Use ProbePath for
// startup inspection and TailAt for reading a live stream.
func (s *Spool) Path() string {
	if s.live != nil {
		return ""
	}
	return s.path
}

// ProbePath is a finite file for startup probing. Live spools expose only their
// retained initial prefix, never the wrapped physical ring as a media file.
func (s *Spool) ProbePath() string { return s.path }

// Retained is the half-open interval of absolute byte offsets currently readable.
func (s *Spool) Retained() (first, end int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.live != nil {
		first = max(0, s.size-s.limit)
	}
	return first, s.size
}

func (s *Spool) Live() bool { return s.live != nil }

// TailAt returns tail from byte offset with resume support (append-only guarantees immutability).
func (s *Spool) TailAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.removed {
		return nil, fs.ErrClosed
	}
	if s.readers >= MaxSpoolReaders {
		return nil, ErrSpoolReaders
	}
	var f *os.File
	var err error
	if offset < 0 {
		return nil, fmt.Errorf("negative spool offset")
	}
	if s.live != nil {
		if offset < max(0, s.size-s.limit) {
			return nil, ErrSpoolExpired
		}
	} else {
		f, err = os.Open(s.path)
	}
	if err != nil {
		return nil, fmt.Errorf("opening spool for tail: %w", err)
	}
	s.readers++
	ctx, cancel := context.WithCancelCause(ctx)
	t := &tailReader{spool: s, f: f, ctx: ctx, offset: offset}
	t.close = sync.OnceValue(func() error {
		s.mu.Lock()
		cancel(fs.ErrClosed)
		s.cond.Broadcast()
		s.mu.Unlock()
		t.stop()
		var err error
		if f != nil {
			err = f.Close()
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		s.readers--
		s.releaseDisk()
		return err
	})
	// Register cond wake for ctx cancellation; cleanup via Close.
	t.stop = context.AfterFunc(ctx, func() {
		s.mu.Lock()
		s.cond.Broadcast()
		s.mu.Unlock()
	})
	return t, nil
}

// Err reports the producer's terminal error independently of whether a receiver
// drained the available bytes. A truncated spool must never report success.
func (s *Spool) Err() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.completion != nil {
		return s.completion.err
	}
	return nil
}

// Close removes the owned file; existing tails may finish reading their open
// descriptor. Its disk reservation remains held until those tails close.
func (s *Spool) Close() error {
	s.CloseWrite(nil)
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.removed {
		return nil
	}
	if s.live != nil {
		_ = s.w.Close()
		if err := os.Remove(s.path + ".ring"); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
	}
	if err := os.Remove(s.path); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	s.removed = true
	s.releaseDisk()
	return nil
}

// releaseDisk is called with s.mu held, only when actual storage is reclaimable.
func (s *Spool) releaseDisk() {
	if s.removed && (s.live != nil || s.readers == 0) && !s.released {
		amount := s.size
		if s.live != nil {
			amount = s.live.reserved
		}
		s.budget.Release(amount)
		s.released = true
	}
}

// Snapshot reports bytes written and whether production has ended atomically.
func (s *Spool) Snapshot() (size int64, complete bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.size, s.completion != nil
}
