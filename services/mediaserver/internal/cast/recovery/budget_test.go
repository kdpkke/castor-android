package recovery

import (
	"errors"
	"testing"

	"github.com/stupside/castor/services/mediaserver/internal/cast/health"
)

func TestResourceExhaustionHasNoRecoveryStrategy(t *testing.T) {
	e := Evidence{ResourceErr: errors.New("disk budget"), Reached: health.Reading, Verdict: health.Dead, ReadExit: 1}
	got := classFor(e)
	if got.kind != resourceExhausted {
		t.Fatalf("class = %v", got.kind)
	}
	if len(playbook[got.kind]) != 0 {
		t.Fatal("resource exhaustion offers retry")
	}
}
