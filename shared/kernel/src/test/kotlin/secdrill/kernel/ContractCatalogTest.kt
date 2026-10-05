package secdrill.kernel

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.enums.EnumEntries
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractCatalogTest {
    private val catalog: JsonNode = JsonMapper.builder().build()
        .readTree(Path.of(System.getProperty("secdrill.contracts.dir"), "enums.json").toFile())

    private val kotlinEnums: Map<String, EnumEntries<*>> = mapOf(
        "Mode" to Mode.entries,
        "Phase" to Phase.entries,
        "SessionStatus" to SessionStatus.entries,
        "LabState" to LabState.entries,
        "JobKind" to JobKind.entries,
        "JobState" to JobState.entries,
        "SubmissionKind" to SubmissionKind.entries,
        "SubmissionStatus" to SubmissionStatus.entries,
        "Verdict" to Verdict.entries,
        "PatchGate" to PatchGate.entries,
        "GateResult" to GateResult.entries,
        "ObjectiveState" to ObjectiveState.entries,
        "ScenarioVersionStatus" to ScenarioVersionStatus.entries,
        "ChallengeKind" to ChallengeKind.entries,
        "ArtifactSensitivity" to ArtifactSensitivity.entries,
        "EvidenceSource" to EvidenceSource.entries,
        "TrustLevel" to TrustLevel.entries,
        "EventType" to EventType.entries,
        "AuthTokenKind" to AuthTokenKind.entries,
        "AuthRevokeReason" to AuthRevokeReason.entries,
        "OperatorRole" to OperatorRole.entries,
        "AuditActorType" to AuditActorType.entries,
        "JobFailure" to JobFailure.entries,
        "DeletionScope" to DeletionScope.entries,
        "DeletionStatus" to DeletionStatus.entries,
        "TombstoneSubject" to TombstoneSubject.entries,
        "ValidationStatus" to ValidationStatus.entries,
        "LabDesiredState" to LabDesiredState.entries,
        "LabTerminateReason" to LabTerminateReason.entries,
        "WorkloadKind" to WorkloadKind.entries,
        "IrActionType" to IrActionType.entries,
    )

    private fun strings(node: JsonNode): List<String> = node.values().map { it.asString() }

    @Test
    fun `every catalog enum has a Kotlin enum with the same values in order`() {
        val enums = catalog["enums"]
        assertEquals(enums.propertyNames().toSet(), kotlinEnums.keys)
        kotlinEnums.forEach { (name, entries) ->
            assertEquals(strings(enums[name]["values"]), entries.map { it.name }, name)
        }
    }

    @Test
    fun `terminal and MVP entry flags follow the catalog`() {
        val enums = catalog["enums"]
        assertEquals(strings(enums["Mode"]["mvpEntry"]), Mode.entries.filter { it.mvpEntry }.map { it.name })
        assertEquals(strings(enums["SessionStatus"]["terminal"]), SessionStatus.entries.filter { it.terminal }.map { it.name })
        assertEquals(strings(enums["LabState"]["terminal"]), LabState.entries.filter { it.terminal }.map { it.name })
        assertEquals(strings(enums["JobState"]["terminal"]), JobState.entries.filter { it.terminal }.map { it.name })
    }

    @Test
    fun `error codes match catalog status and retryability`() {
        val codes = catalog["errorCodes"].properties().filterNot { it.key.startsWith("$") }
        assertEquals(codes.map { it.key }, ErrorCode.entries.map { it.name })
        codes.forEach { (name, spec) ->
            val code = ErrorCode.valueOf(name)
            assertEquals(spec["httpStatus"].asInt(), code.httpStatus, name)
            assertEquals(spec["retryable"].asBoolean(), code.retryable, name)
        }
    }
}
