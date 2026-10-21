package cast

import (
	"testing"

	"connectrpc.com/connect"
	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"golang.org/x/sync/semaphore"
)

func TestWatchRejectsAtCapacityBeforeFindingCast(t *testing.T) {
	s := &Service{ctx: t.Context(), watchers: semaphore.NewWeighted(256), resolutions: semaphore.NewWeighted(resolutionCapacity)}
	for range 256 {
		if !s.watchers.TryAcquire(1) {
			t.Fatal("cannot occupy capacity")
		}
		defer s.watchers.Release(1)
	}
	err := s.Watch(t.Context(), &castorv1.WatchRequest{CastId: "missing"}, nil)
	if connect.CodeOf(err) != connect.CodeResourceExhausted {
		t.Fatalf("capacity = %v", err)
	}
}

func TestRankRejectsAtCapacity(t *testing.T) {
	s := &Service{ctx: t.Context(), watchers: semaphore.NewWeighted(256), resolutions: semaphore.NewWeighted(resolutionCapacity)}
	for range 16 {
		if !s.resolutions.TryAcquire(1) {
			t.Fatal("cannot occupy capacity")
		}
		defer s.resolutions.Release(1)
	}
	_, err := s.Rank(t.Context(), nil)
	if connect.CodeOf(err) != connect.CodeResourceExhausted {
		t.Fatalf("capacity = %v", err)
	}
}
