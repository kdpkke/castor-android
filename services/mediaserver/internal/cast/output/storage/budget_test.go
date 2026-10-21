package storage

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sync/semaphore"
)

func TestSpoolBudgetNeverWritesBeyondLimit(t *testing.T) {
	s, err := NewSpoolWithLimit(filepath.Join(t.TempDir(), "stream"), 4)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.Write([]byte("1234")); err != nil {
		t.Fatal(err)
	}
	if n, err := s.Write([]byte("5")); n != 0 || !errors.Is(err, ErrSpoolLimit) {
		t.Fatalf("overflow = %d, %v", n, err)
	}
	s.CloseWrite(ErrSpoolLimit)
	info, err := os.Stat(s.Path())
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() != 4 {
		t.Fatalf("size = %d", info.Size())
	}
	r, err := s.TailAt(t.Context(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	body, err := io.ReadAll(r)
	if string(body) != "1234" || !errors.Is(err, ErrSpoolLimit) {
		t.Fatalf("tail = %q, %v", body, err)
	}
}

func TestSharedDiskBudgetHeldUntilRemovedTailsClose(t *testing.T) {
	budget := semaphore.NewWeighted(8)
	first, err := NewSpoolWithBudget(filepath.Join(t.TempDir(), "a"), 8, budget)
	if err != nil {
		t.Fatal(err)
	}
	second, err := NewSpoolWithBudget(filepath.Join(t.TempDir(), "b"), 8, budget)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	if _, err := first.Write([]byte("12345678")); err != nil {
		t.Fatal(err)
	}
	first.CloseWrite(nil)
	tail, err := first.TailAt(t.Context(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := second.Write([]byte("1")); !errors.Is(err, ErrSpoolLimit) {
		t.Fatalf("aggregate cap = %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	assertDiskAvailable(t, budget, 0)
	if _, err := first.TailAt(t.Context(), 0); err == nil {
		t.Fatal("removed spool reopened")
	}
	if err := tail.Close(); err != nil {
		t.Fatal(err)
	}
	assertDiskAvailable(t, budget, 8)
	if _, err := second.Write([]byte("12345678")); err != nil {
		t.Fatal(err)
	}
	if err := second.Close(); err != nil {
		t.Fatal(err)
	}
	if err := second.Close(); err != nil {
		t.Fatal(err)
	}
	assertDiskAvailable(t, budget, 8)
}

// Check capacity through admission, without exposing accounting for tests.
func assertDiskAvailable(t *testing.T, budget *semaphore.Weighted, available int64) {
	t.Helper()
	if !budget.TryAcquire(available) {
		t.Fatalf("expected %d bytes available", available)
	}
	defer budget.Release(available)
	if budget.TryAcquire(1) {
		budget.Release(1)
		t.Fatalf("more than %d bytes available", available)
	}
}
