package secdrill.kernel

import java.util.UUID

/** Strict UUID handling. `UUID.fromString` accepts non-canonical text such as `1-1-1-1-1`; contracts do not. */
object Uuids {
    private val canonical = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun parse(text: String): UUID {
        require(canonical.matches(text)) { "not a canonical UUID" }
        return UUID.fromString(text)
    }
}

@JvmInline value class UserId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class ScenarioId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class ScenarioVersionId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class ChallengeId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class SessionId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class LabId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class ArtifactId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class SubmissionId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class JobId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class EvaluationId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class EvidenceId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class EventId(val value: UUID) { override fun toString() = value.toString() }
