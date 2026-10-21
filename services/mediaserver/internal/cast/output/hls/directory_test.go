package hls

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"testing/synctest"

	"github.com/stupside/castor/services/mediaserver/internal/cast/output/storage/diskbudget"
	"golang.org/x/sync/semaphore"
)

func TestDirectoryBudgetCountsPlaylistInitSegmentsAndTemporaryFiles(t *testing.T) {
	dir := t.TempDir()
	for _, name := range []string{"stream.m3u8", "init.mp4", "seg.m4s", "seg.m4s.tmp"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("1234"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := checkDirectory(dir, 16, 4); err != nil {
		t.Fatal(err)
	}
	if err := checkDirectory(dir, 15, 4); !errors.Is(err, diskbudget.ErrLimit) {
		t.Fatalf("bytes = %v", err)
	}
	if err := checkDirectory(dir, 16, 3); !errors.Is(err, diskbudget.ErrLimit) {
		t.Fatalf("files = %v", err)
	}
}

func TestDirectoryReservationReleasedAfterRemoval(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "output")
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "segment"), []byte("1234"), 0600); err != nil {
		t.Fatal(err)
	}
	budget := semaphore.NewWeighted(8)
	reservation, err := reserveDirectory(dir, 8, budget)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := reserveDirectory(dir, 1, budget); !errors.Is(err, diskbudget.ErrLimit) {
		t.Fatalf("admission = %v", err)
	}
	assertDiskAvailable(t, budget, 0)
	if err := reservation(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(dir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("directory remains: %v", err)
	}
	assertDiskAvailable(t, budget, 8)
	if err := reservation(); err != nil {
		t.Fatal(err)
	}
}

func TestSegmentStorageGuardReportsFailure(t *testing.T) {
	dir := t.TempDir()
	for i := range segmentFileLimit + 1 {
		if err := os.WriteFile(filepath.Join(dir, fmt.Sprint(i)), nil, 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := monitorDirectory(t.Context(), dir); !errors.Is(err, diskbudget.ErrLimit) {
		t.Fatalf("guard = %v", err)
	}
}
func TestSegmentStorageGuardCancelsWithoutFailure(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := monitorDirectory(ctx, t.TempDir()); err != nil {
		t.Fatal(err)
	}
}
func TestSegmentStorageGuardDetectsGrowthAfterStartup(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		dir := t.TempDir()
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		result := make(chan error, 1)
		go func() { result <- monitorDirectory(ctx, dir) }()
		synctest.Wait()
		file, err := os.Create(filepath.Join(dir, "growing.m4s"))
		if err != nil {
			t.Fatal(err)
		}
		if err := file.Truncate(SegmentDirectoryLimit + 1); err != nil {
			t.Fatal(err)
		}
		file.Close()
		synctest.Sleep(2 * directoryCheckInterval)
		if err := <-result; !errors.Is(err, diskbudget.ErrLimit) {
			t.Fatalf("guard = %v", err)
		}
	})
}

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
