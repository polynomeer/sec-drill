package secdrill.content

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Synthetic, test-only bundles. They pass every static gate so tests can exercise publishing; they are not real
 * content and never replace SecDrill-docs/examples (which stay unpublishable on purpose).
 */
object SyntheticBundles {
    private val json = JsonMapper.builder().build()

    fun valid(scenarioId: UUID = UUID.randomUUID(), versionId: UUID = UUID.randomUUID(), version: Int = 1): ContentBundle {
        val manifest = json.readTree(
            """{"schemaVersion":1,"scenarioId":"$scenarioId","scenarioVersionId":"$versionId","version":$version,
            "title":"Synthetic tenant orders","family":"synthetic-${scenarioId.toString().take(8)}","modes":["CTF","PURPLE"],"phases":["ANALYZE","ATTACK","PATCH","VERIFY"],
            "competencyTags":["AUTHORIZATION"],"brief":"Synthetic brief for tests.","difficulty":"BEGINNER","estimatedMinutes":30,
            "scope":{"allowedTargets":["app.lab.internal"],"syntheticDataOnly":true,"externalNetwork":false},
            "runtime":{"profile":"lab-strong","imageDigest":"sha256:${"e".repeat(64)}","vcpus":2,"memoryMiB":2048,"diskMiB":4096,"pids":256,"idleTtlSeconds":900,"hardTtlSeconds":3600},
            "versions":{"rubric":"synthetic-v1","engine":"ir-v1","randomization":"ids-v1"},
            "challenges":[{"id":"${UUID.randomUUID()}","key":"cross-tenant-order","kind":"FLAG","objective":"Prove the tenant boundary violation.","basePoints":100}],
            "completionRequirements":{"CTF":["objective_confirmed"]},"publishable":true}""",
        )
        val oracle = json.readTree(
            """{"schemaVersion":1,"visibility":"GRADER_ONLY","scenarioVersionId":"$versionId",
            "verifier":{"kind":"independent-api-observer","requires":["actorTenantDiffersFromResource"]},
            "flag":{"algorithm":"HMAC-SHA256","secretRef":"secret-ref:content/synthetic-orders","bind":["sessionId","challengeId","nonce"],"plaintextLogging":false},
            "hiddenTests":[{"id":"hidden-direct-other-tenant","expected":"deny"},{"id":"hidden-own-tenant-read","expected":"allow"}],
            "mutants":["mutant-deny-everything","mutant-fix-direct-route-only"],
            "weights":{"attack":50,"patch":50},
            "gate":{"mandatorySecurityTests":"ALL","mandatoryRegressionTests":"ALL","platformFailure":"INCONCLUSIVE"},
            "referencePatchRef":"private/reference.patch","publishable":true}""",
        )
        return ContentBundle(
            manifest, oracle,
            mapOf("public/README.md" to "Synthetic public notes.".toByteArray()),
            mapOf("private/reference.patch" to "--- synthetic reference patch ---".toByteArray()),
        )
    }

    fun withManifest(bundle: ContentBundle, change: (ObjectNode) -> Unit): ContentBundle =
        ContentBundle((bundle.manifest.deepCopy() as ObjectNode).also(change), bundle.oracle, bundle.publicFiles, bundle.privateFiles)

    fun withOracle(bundle: ContentBundle, change: (ObjectNode) -> Unit): ContentBundle =
        ContentBundle(bundle.manifest, (bundle.oracle.deepCopy() as ObjectNode).also(change), bundle.publicFiles, bundle.privateFiles)

    /** A runtime verifier double for tests only; real runtime verification does not exist yet (T06/T08). */
    class ScriptedVerifier(override val kind: String = "test-scripted", private val result: CheckResult = CheckResult.PASS) : RuntimeVerifier {
        override fun verify(bundle: ContentBundle) = UnavailableRuntimeVerifier.CHECKS.map { Check(it, result, "scripted for tests") }
    }

    fun tree(text: String): JsonNode = json.readTree(text)
}
