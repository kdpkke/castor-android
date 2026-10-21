package storage

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"testing/synctest"

	"golang.org/x/sync/semaphore"
)

func liveSpool(t *testing.T, window int64) (*Spool, *semaphore.Weighted) {
	t.Helper()
	budget := semaphore.NewWeighted(window * 2)
	s, err := NewLiveSpoolWithBudget(filepath.Join(t.TempDir(), "live.ts"), window, budget)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := s.Close(); err != nil {
			t.Error(err)
		}
	})
	return s, budget
}

func TestLiveRingWrapPreservesAbsoluteBytesAndProbePrefix(t *testing.T) {
	s, budget := liveSpool(t, 8)
	if s.Path() != "" {
		t.Fatal("ring advertised as ordinary file")
	}
	for _, part := range []string{"0123", "4567", "89ab", "cdef"} {
		if _, err := s.Write([]byte(part)); err != nil {
			t.Fatal(err)
		}
	}
	s.CloseWrite(nil)
	if first, end := s.Retained(); first != 8 || end != 16 {
		t.Fatalf("retained = %d,%d", first, end)
	}
	if _, err := s.TailAt(t.Context(), 0); !errors.Is(err, ErrSpoolExpired) {
		t.Fatalf("expired = %v", err)
	}
	tail, err := s.TailAt(t.Context(), 9)
	if err != nil {
		t.Fatal(err)
	}
	defer tail.Close()
	got, err := io.ReadAll(tail)
	if err != nil || string(got) != "9abcdef" {
		t.Fatalf("wrapped bytes = %q, %v", got, err)
	}
	prefix, err := os.ReadFile(s.ProbePath())
	if err != nil {
		t.Fatal(err)
	}
	if string(prefix) != "01234567" {
		t.Fatalf("probe prefix = %q", prefix)
	}
	assertDiskAvailable(t, budget, 0)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	assertDiskAvailable(t, budget, 16)
}

func TestLiveRingFastReaderSurvivesManyWrapsSlowReaderExpires(t *testing.T) {
	s, _ := liveSpool(t, 16)
	fast, err := s.TailAt(t.Context(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer fast.Close()
	slow, err := s.TailAt(t.Context(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer slow.Close()
	for i := range 10000 {
		want := bytes.Repeat([]byte{byte(i)}, 7)
		if _, err := s.Write(want); err != nil {
			t.Fatal(err)
		}
		got := make([]byte, len(want))
		if _, err := io.ReadFull(fast, got); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(got, want) {
			t.Fatalf("corrupted data at wrap%d", i)
		}
	}
	if _, err := slow.Read(make([]byte, 1)); !errors.Is(err, ErrSpoolExpired) {
		t.Fatalf("slow reader = %v", err)
	}
	info, err := os.Stat(s.ProbePath() + ".ring")
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() != 16 {
		t.Fatalf("ring grew to %d", info.Size())
	}
}

func TestLiveRingParkedReaderCancels(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, _ := liveSpool(t, 8)
		ctx, cancel := context.WithCancel(t.Context())
		tail, err := s.TailAt(ctx, 0)
		if err != nil {
			t.Fatal(err)
		}
		defer tail.Close()
		result := make(chan error, 1)
		go func() { _, err := tail.Read(make([]byte, 1)); result <- err }()
		synctest.Wait()
		cancel()
		if err := <-result; !errors.Is(err, context.Canceled) {
			t.Fatalf("cancel = %v", err)
		}
	})
}

func TestLiveRingReservesCapacityBeforeCreatingFiles(t *testing.T) {
	path := filepath.Join(t.TempDir(), "live")
	budget := semaphore.NewWeighted(15)
	if _, err := NewLiveSpoolWithBudget(path, 8, budget); !errors.Is(err, ErrSpoolLimit) {
		t.Fatalf("admission = %v", err)
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("refused spool exists: %v", err)
	}
	assertDiskAvailable(t, budget, 15)
}

func TestLiveReaderAdmissionIsBoundedAndReleased(t *testing.T) {
	s, _ := liveSpool(t, 8)
	var readers []io.ReadCloser
	for range MaxSpoolReaders {
		r, err := s.TailAt(t.Context(), 0)
		if err != nil {
			t.Fatal(err)
		}
		readers = append(readers, r)
	}
	if _, err := s.TailAt(t.Context(), 0); !errors.Is(err, ErrSpoolReaders) {
		t.Fatalf("reader admission = %v", err)
	}
	for _, r := range readers {
		if err := r.Close(); err != nil {
			t.Fatal(err)
		}
	}
	r, err := s.TailAt(t.Context(), 0)
	if err != nil {
		t.Fatal(err)
	}
	_ = r.Close()
}
