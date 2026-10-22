package telemetry

// Every name castor exports lives here, in three layers: the instrumentation
// scope, the signal names (instruments, events) and the attribute keys. Names
// castor invents sit under "castor."; the rpc.* keys are OpenTelemetry's own.

// scope names every tracer and meter castor owns.
const scope = "castor"

// Instrument and event names.
const (
	metricOperationDuration = "castor.operation.duration"
	metricStreamClients     = "castor.stream.clients"
	metricStreamSent        = "castor.stream.sent"
	metricMediaPlans        = "castor.media.plans"
	metricMediaRefusals     = "castor.media.refusals"
	metricRecoveryRevisions = "castor.recovery.revisions"
	metricStateDuration     = "castor.fsm.state.duration"
)

// Attribute keys.
const (
	keyCastID    = "castor.cast.id"
	keyOperation = "castor.operation"
	keyOutcome   = "castor.outcome"
	keyAxis      = "castor.media.axis"
	keyMode      = "castor.media.mode"
	keyRefusal   = "castor.media.refusal"
	keyAction    = "castor.recovery.action"
	keyMachine   = "castor.fsm.machine"
	keyState     = "castor.fsm.state"
	keyEvent     = "castor.fsm.event"
	keyExit      = "castor.fsm.exit"
)

// Outcomes a finished operation or a stream client reports; bounded, never an error string.
const (
	OutcomeSuccess      = "success"
	OutcomeError        = "error"
	OutcomeCanceled     = "canceled"
	OutcomeDeadline     = "deadline"
	OutcomeDisconnected = "disconnected"
	OutcomeStalled      = "stalled"
)
