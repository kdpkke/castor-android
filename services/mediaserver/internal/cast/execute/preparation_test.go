package execute

import "github.com/stupside/castor/services/mediaserver/internal/cast/compose"

// Fixture tests exercise preparation without a device event loop. Production
// advances these operations only through the attempt coordinator.
func (s *preparation) preparePresentation() (presentation, error) {
	inspected, err := s.inspectPresentation()
	if err != nil {
		return inspected.presentation, err
	}
	planned, err := s.planPresentation(inspected)
	if err != nil || planned.kind == compose.Handoff {
		return planned.presentation, err
	}
	return s.producePresentation(planned)
}
