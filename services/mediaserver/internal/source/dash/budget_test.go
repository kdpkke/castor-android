package dash

import (
	"context"
	"errors"
	"fmt"
	"testing"

	"github.com/stupside/castor/services/mediaserver/internal/media"
	"github.com/stupside/castor/services/mediaserver/internal/source/sourcetest"
)

func TestExpansionBudget(t *testing.T) {
	for name, addressing := range map[string]string{
		"repeat":   `<SegmentTemplate media="$Number$.m4s"><SegmentTimeline><S d="1" r="99999"/></SegmentTimeline></SegmentTemplate>`,
		"duration": `<SegmentTemplate media="$Number$.m4s" duration="1"/>`,
		"list":     `<SegmentList><SegmentTimeline><S d="1" r="999999999"/></SegmentTimeline><SegmentURL media="one.m4s"/></SegmentList>`,
		"format":   `<SegmentTemplate media="$Number%099999999d$.m4s" duration="1"/>`,
		"overflow": `<SegmentTemplate media="$Time$.m4s"><SegmentTimeline><S t="18446744073709551615" d="1" r="1"/></SegmentTimeline></SegmentTemplate>`,
	} {
		t.Run(name, func(t *testing.T) {
			body := fmt.Sprintf(`<MPD><Period duration="PT100000S"><AdaptationSet contentType="video">%s<Representation id="v"/></AdaptationSet></Period></MPD>`, addressing)
			in := media.Input{URL: sourcetest.URL(t, "https://example.test/m.mpd"), Representation: "v"}
			f := Format{}.Timeline(&sourcetest.Documents{ByPath: map[string]string{"/m.mpd": body}}, in, media.TrackVideo)
			if _, err := f.Source.Window(t.Context()); err == nil {
				t.Fatal("oversized expansion accepted")
			}
		})
	}
}

func TestBudgetAggregatesAndCancels(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	b := newExpansionBudget(ctx)
	if err := b.take(maxWindowSegments - 1); err != nil {
		t.Fatal(err)
	}
	if err := b.take(2); !errors.Is(err, errExpansionLimit) {
		t.Fatalf("overflow = %v", err)
	}
	cancel()
	if err := b.take(0); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel = %v", err)
	}
}

func TestWindowBudgetSpansPeriods(t *testing.T) {
	body := `<MPD>`
	for i := range 2 {
		body += fmt.Sprintf(`<Period id="p%d" duration="PT30000S"><AdaptationSet contentType="video"><Representation id="v"><SegmentTemplate media="$Number$.m4s" duration="1"/></Representation></AdaptationSet></Period>`, i)
	}
	body += `</MPD>`
	in := media.Input{URL: sourcetest.URL(t, "https://example.test/m.mpd"), Representation: "v"}
	f := Format{}.Timeline(&sourcetest.Documents{ByPath: map[string]string{"/m.mpd": body}}, in, media.TrackVideo)
	if _, err := f.Source.Window(t.Context()); !errors.Is(err, errExpansionLimit) {
		t.Fatalf("aggregate limit = %v", err)
	}
}
