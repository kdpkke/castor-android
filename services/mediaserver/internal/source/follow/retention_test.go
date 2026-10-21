package follow

import (
	"fmt"
	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
	"testing"
)

func TestLiveResourceNamesExpireWithoutReuse(t *testing.T) {
	f := testFeed(t.Context(), "v", nil, nil)
	f.repackaging = new(false)
	for i := range 10000 {
		m := timeline.Map{URI: fmt.Sprintf("init-%d", i)}
		s := timeline.Segment{URI: fmt.Sprintf("segment-%d", i), Map: &m, Key: timeline.Key{URI: fmt.Sprintf("key-%d", i)}, Place: timeline.Place{Period: fmt.Sprint(i), Start: 0, End: 1}}
		f.ledger.Merge(timeline.Window{Segments: []timeline.Segment{s}})
		f.inits.id(m)
		f.keys.id(s.Key.URI)
		f.carriage[m] = true
		f.pruneResources()
	}
	if len(f.inits.held) != 1 || len(f.keys.held) != 1 || len(f.carriage) != 1 || len(f.ledger.periods) > 2 {
		t.Fatalf("unbounded retention: inits=%d keys=%d carriage=%d periods=%d", len(f.inits.held), len(f.keys.held), len(f.carriage), len(f.ledger.periods))
	}
	if _, ok := f.inits.lookup("0"); ok {
		t.Fatal("expired init ID resolves")
	}
	if _, ok := f.keys.lookup("0"); ok {
		t.Fatal("expired key ID resolves")
	}
	if m, ok := f.inits.lookup("9999"); !ok || m.URI != "init-9999" {
		t.Fatalf("current init = %v, %v", m, ok)
	}
}
