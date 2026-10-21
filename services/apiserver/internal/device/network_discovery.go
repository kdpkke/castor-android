package device

import (
	"context"
	"slices"
	"sync"
)

// NetworkDiscovery finds devices through Castor's protocol families.
func NetworkDiscovery(families []Family) Discovery {
	return func(ctx context.Context) ([]Info, error) {
		found := make([][]Info, len(families))
		var wg sync.WaitGroup
		for i, family := range families {
			wg.Go(func() { found[i] = family.Discover(ctx) })
		}
		wg.Wait()
		return slices.Concat(found...), nil
	}
}
