package recovery

import (
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/stupside/castor/services/mediaserver/internal/cast/health"
)

func TestTheCastIsReportedUnderThePartyThatFailed(t *testing.T) {
	readErr := errors.New("upstream pull: exit status 183")
	noticed := fmt.Errorf("encoder: spool producer failed: %w", readErr)
	ownFault := errors.New("encoder: an audio repack toward an in-band container")
	verdict := &health.Fault{Kind: health.Dead, Err: readErr}

	for _, tt := range []struct {
		name         string
		err, readErr error
		want         error
	}{
		{"a reported read failure keeps its error chain", noticed, readErr, noticed},
		{"a verdict keeps its own account", verdict, readErr, verdict},
		{"a leg with no reader has nobody else to blame", ownFault, nil, ownFault},
		{"a cast that ran its course reports nothing", nil, readErr, nil},
	} {
		t.Run(tt.name, func(t *testing.T) {
			if got := attribute(tt.err, tt.readErr); got != tt.want {
				t.Errorf("attribute = %v, want %v", got, tt.want)
			}
		})
	}

	joined := attribute(ownFault, readErr)
	if !errors.Is(joined, readErr) || !errors.Is(joined, ownFault) || !strings.HasPrefix(joined.Error(), readErr.Error()) {
		t.Errorf("attribute = %q, want both accounts led by the read", joined)
	}
}

func TestAttributionPreservesCleanupFailureAlongsideReadFailure(t *testing.T) {
	readErr, cleanupErr := errors.New("read failed"), errors.New("storage close failed")
	original := errors.Join(fmt.Errorf("encoder input: %w", readErr), cleanupErr)
	got := attribute(original, readErr)
	if !errors.Is(got, readErr) || !errors.Is(got, cleanupErr) || got != original {
		t.Fatalf("attribution lost or duplicated failures: %v", got)
	}
}
