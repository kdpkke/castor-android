package browse

import (
	"bytes"
	"context"
	"log/slog"
	"slices"
	"strings"
	"testing"

	"github.com/stupside/castor/internal/castid"
	"github.com/stupside/castor/internal/logging"
)

type written struct {
	lines *[]string
	attrs bool
}

func (w written) Enabled(context.Context, slog.Level) bool { return true }
func (w written) Handle(_ context.Context, r slog.Record) error {
	line := r.Message
	if w.attrs {
		r.Attrs(func(a slog.Attr) bool { line += " " + a.String(); return true })
	}
	*w.lines = append(*w.lines, line)
	return nil
}
func (w written) WithAttrs([]slog.Attr) slog.Handler { return w }
func (w written) WithGroup(string) slog.Handler      { return w }

func TestLinesLoggedUnderAScreenAreWrittenAfterItInOrder(t *testing.T) {
	var out []string
	prev := slog.Default()
	slog.SetDefault(slog.New(written{lines: &out}))
	t.Cleanup(func() { slog.SetDefault(prev) })

	_ = held(func() error {
		slog.Warn("discovery error")
		slog.Info("device found")
		if len(out) != 0 {
			t.Errorf("%v reached the terminal while the screen was up", out)
		}
		return nil
	})

	if want := []string{"discovery error", "device found"}; !slices.Equal(out, want) {
		t.Errorf("wrote %v after the screen, want %v", out, want)
	}
}

func TestALoggerKeptByBackgroundWorkResumesWritingAfterTheScreen(t *testing.T) {
	var out []string
	prev := slog.Default()
	slog.SetDefault(slog.New(written{lines: &out}))
	t.Cleanup(func() { slog.SetDefault(prev) })

	var background *slog.Logger
	_ = held(func() error {
		background = slog.Default().With("work", "discovery")
		background.Warn("screen open")
		return nil
	})
	background.Warn("screen closed")
	if want := []string{"screen open", "screen closed"}; !slices.Equal(out, want) {
		t.Errorf("wrote %v, want the retained logger to resume writing: %v", out, want)
	}
}

func TestAHeldLineIsWrittenUnderTheCastItWasLoggedFor(t *testing.T) {
	var got []string
	prev := slog.Default()
	slog.SetDefault(slog.New(castid.Tagged(written{lines: &got, attrs: true}, castid.LogKey)))
	t.Cleanup(func() { slog.SetDefault(prev) })

	_ = held(func() error {
		slog.InfoContext(castid.With(t.Context(), "CASTID"), "playing")
		return nil
	})

	if want := []string{"playing cast_id=CASTID"}; !slices.Equal(got, want) {
		t.Errorf("wrote %v after the screen, want the line still tagged: %v", got, want)
	}
}

func TestHeldGroupedCredentialsKeepTheirRedaction(t *testing.T) {
	var out bytes.Buffer
	prev := slog.Default()
	slog.SetDefault(slog.New(logging.Redact(slog.NewJSONHandler(&out, nil))))
	t.Cleanup(func() { slog.SetDefault(prev) })

	_ = held(func() error {
		slog.Default().WithGroup("credentials").With("account", "secret-value").Info("connecting")
		if out.Len() != 0 {
			t.Fatal("log reached the destination while the screen was open")
		}
		return nil
	})
	if strings.Contains(out.String(), "secret-value") || !strings.Contains(out.String(), "[redacted]") {
		t.Fatalf("grouped credential was not redacted after the screen: %s", out.String())
	}
}
