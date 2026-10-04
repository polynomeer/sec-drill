package secdrill.kernel

// Mirrors SecDrill-docs/contracts/enums.json. ContractCatalogTest fails if names, order or flags drift.

enum class Mode(val mvpEntry: Boolean) {
    CTF(true), WARGAME(true), PURPLE(true), PATCH(false), DETECTION(false), INVESTIGATE(false)
}

enum class Phase { ANALYZE, ATTACK, OBSERVE, DETECT, CONTAIN, PATCH, VERIFY, POSTMORTEM }

enum class SessionStatus(val terminal: Boolean) {
    CREATED(false), ACTIVE(false), SUBMITTED(false), EVALUATING(false),
    COMPLETED(true), CANCELLED(true), EXPIRED(true), EVALUATION_FAILED(false)
}

enum class LabState(val terminal: Boolean) {
    REQUESTED(false), PROVISIONING(false), READY(false), TERMINATING(false),
    TERMINATED(true), FAILED(false), CLEANUP_FAILED(false)
}

enum class JobKind { PROVISION, GRADE, REPORT, CLEANUP, EXPORT }

enum class JobState(val terminal: Boolean) {
    PENDING(false), DISPATCHED(false), LEASED(false), RUNNING(false),
    SUCCEEDED(true), RETRY_WAIT(false), FAILED(true), CANCELLED(true)
}

enum class SubmissionKind { FLAG, OBJECTIVE, PATCH, DETECTION, POSTMORTEM }

enum class SubmissionStatus { ACCEPTED, EVALUATING, EVALUATED, EVALUATION_FAILED }

/** SYSTEM_ERROR is a platform failure and is never negative skill evidence (00). */
enum class Verdict { PASS, FAIL, SYSTEM_ERROR }

enum class PatchGate { VERIFIED, NOT_VERIFIED, INCONCLUSIVE }

enum class GateResult { PASS, FAIL, INCONCLUSIVE }

enum class ObjectiveState { UNATTEMPTED, ATTEMPTED, CONFIRMED, REVOKED }

enum class ScenarioVersionStatus { DRAFT, VALIDATED, PUBLISHED, QUARANTINED }

enum class ChallengeKind { FLAG, OBJECTIVE }

enum class ArtifactSensitivity { LEARNER, PRIVATE_ORACLE, RAW_LOG, PUBLIC_SUMMARY }

enum class EvidenceSource { CONTROL, VERIFIER, SUPERVISOR, COLLECTOR, USER, SIMULATOR }

enum class TrustLevel { SERVER_VERIFIED, OBSERVED, USER_REPORTED, SIMULATED }

@Suppress("EnumEntryName")
enum class EventType {
    SessionCreated, LabRequested, LabReady, SubmissionAccepted, ExecutionCompleted,
    EvaluationCommitted, ActionApplied, LabTerminationRequested, LabTerminated, SessionFinished
}
