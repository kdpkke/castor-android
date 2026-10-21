package device

import (
	"context"
	"log/slog"

	castorv1 "github.com/stupside/castor/gen/castor/v1"
)

// ExternalDiscovery converts devices supplied through the public API contract.
func ExternalDiscovery(find func(context.Context) ([]*castorv1.Device, error)) Discovery {
	return func(ctx context.Context) ([]Info, error) {
		found, err := find(ctx)
		if err != nil {
			return nil, err
		}
		out := make([]Info, 0, len(found))
		for _, d := range found {
			kind, err := requestedType(d.GetType())
			if err != nil {
				slog.WarnContext(ctx, "external discovery returned an invalid device type", "id", d.GetId())
				continue
			}
			out = append(out, Info{ID: d.GetId(), Name: d.GetName(), Type: kind, Address: d.GetAddress()})
		}
		return out, nil
	}
}
