// Package diskbudget owns the process-wide budget shared by output adapters.
package diskbudget

import (
	"errors"

	"golang.org/x/sync/semaphore"
)

const Limit int64 = 32 << 30

var Shared = semaphore.NewWeighted(Limit)
var ErrLimit = errors.New("media output disk budget exceeded")
