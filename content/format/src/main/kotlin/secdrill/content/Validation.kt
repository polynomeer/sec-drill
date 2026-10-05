package secdrill.content

import secdrill.kernel.Phase
import secdrill.kernel.Uuids
import secdrill.kernel.ValidationStatus
import tools.jackson.databind.JsonNode
import java.security.PublicKey

enum class CheckResult { PASS, FAIL, NOT_RUN }

/** One validation check. `detail` is written for authors and reviewers and never quotes oracle values. */
data class Check(val name: String, val result: CheckResult, val detail: String)

data class ValidationReport(
    val bundleDigest: String,
    val validatorVersion: String,
    val verifierKind: String,
    val status: ValidationStatus,
    val checks: List<Check>,
)

/**
 * Runtime verification of a bundle (08, 20): the reference solution passes, every core mutant is detected and the
 * declared seed set is solvable. Needs the strong runtime and graders (T06, T08), which do not exist yet.
 */
interface RuntimeVerifier {
    /** Identifies the verifier in reports; only kinds the Control Plane accepts can lead to a PASS. */
    val kind: String
    fun verify(bundle: ContentBundle): List<Check>
}

/** The only verifier available today: reports every runtime check as NOT_RUN, so no bundle can pass the gate. */
object UnavailableRuntimeVerifier : RuntimeVerifier {
    override val kind = "none"
    val CHECKS = listOf("runtime.reference-solution", "runtime.core-mutants-detected", "runtime.seed-set")
    override fun verify(bundle: ContentBundle) = CHECKS.map { Check(it, CheckResult.NOT_RUN, "no strong runtime verifier is available (T06/T08)") }
}

/**
 * Static checks that need no runtime: structure, version match, rubric, public/private separation, publish-gate
 * placeholders and the signature. Mirrors contracts/scenario-manifest.schema.json and private-oracle.schema.json.
 */
object ContentValidation {
    const val VALIDATOR_VERSION = "content-validator/1"
    private const val MAX_FILES = 100
    private const val MAX_TOTAL_BYTES = 20 * 1024 * 1024
    private val filePath = Regex("^(public|private)/[A-Za-z0-9_][A-Za-z0-9_./-]{0,200}$")
    private val imageDigest = Regex("^sha256:[a-f0-9]{64}$")
    private val secretRef = Regex("^secret-ref:[a-z0-9][a-z0-9/-]{2,120}$")
    private val manifestKeys = setOf(
        "schemaVersion", "scenarioId", "scenarioVersionId", "version", "title", "family", "modes", "phases", "competencyTags", "brief",
        "difficulty", "estimatedMinutes", "scope", "runtime", "versions", "challenges", "patch", "actions", "completionRequirements",
        "transferScenarioFamily", "publishable",
    )
    private val requiredManifestKeys = manifestKeys - setOf("patch", "actions", "transferScenarioFamily")
    private val oracleKeys = setOf(
        "schemaVersion", "visibility", "scenarioVersionId", "verifier", "flag", "hiddenTests", "mutants", "detection", "weights", "gate",
        "referencePatchRef", "publishable", "hints",
    )
    private val requiredOracleKeys = oracleKeys - setOf("flag", "detection", "hints")
    private val oracleOnlyKeys = setOf("oracle", "hiddenTests", "mutants", "referencePatchRef", "solution", "answer", "flag", "secretRef", "weights", "hints")

    fun run(bundle: ContentBundle, signature: BundleSignature?, trustedKeys: Map<String, PublicKey>, verifier: RuntimeVerifier): ValidationReport {
        val digest = BundleDigester.digests(bundle).bundleDigest
        val checks = buildList {
            add(files(bundle))
            add(manifestStructure(bundle.manifest))
            add(oracleStructure(bundle.oracle))
            add(check("bundle.version-match", bundle.scenarioVersionId != null && bundle.oracle["scenarioVersionId"]?.asString() == bundle.scenarioVersionId,
                "manifest and oracle name the same scenarioVersionId"))
            add(weights(bundle.oracle))
            add(separation(bundle))
            add(check("gate.image-digest", imageDigest.matches(bundle.manifest["runtime"]?.get("imageDigest")?.asString() ?: ""),
                "runtime.imageDigest must be a real sha256 image digest, not a placeholder"))
            add(oracleReferences(bundle))
            add(check("gate.publishable", bundle.manifest["publishable"]?.asBoolean() == true && bundle.oracle["publishable"]?.asBoolean() == true,
                "both manifest and oracle must declare publishable=true"))
            add(signatureCheck(signature, digest, trustedKeys))
            addAll(verifier.verify(bundle))
        }
        val status = when {
            checks.any { it.result == CheckResult.FAIL } -> ValidationStatus.FAIL
            checks.any { it.result == CheckResult.NOT_RUN } -> ValidationStatus.INCOMPLETE
            else -> ValidationStatus.PASS
        }
        return ValidationReport(digest, VALIDATOR_VERSION, verifier.kind, status, checks)
    }

    private fun check(name: String, ok: Boolean, detail: String) = Check(name, if (ok) CheckResult.PASS else CheckResult.FAIL, detail)

    private fun files(bundle: ContentBundle): Check {
        val all = bundle.publicFiles + bundle.privateFiles
        val badPath = all.keys.firstOrNull { !filePath.matches(it) || ".." in it.split('/') || "//" in it }
        val wrongSide = bundle.publicFiles.keys.any { !it.startsWith("public/") } || bundle.privateFiles.keys.any { !it.startsWith("private/") }
        val ok = badPath == null && !wrongSide && all.size <= MAX_FILES && all.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES
        return check("bundle.files", ok, "file paths are relative under public/ or private/, at most $MAX_FILES files and 20 MiB")
    }

    private fun manifestStructure(manifest: JsonNode): Check {
        val problems = mutableListOf<String>()
        if (!manifest.isObject) return check("manifest.structure", false, "manifest must be an object")
        val keys = manifest.propertyNames().toSet()
        (requiredManifestKeys - keys).forEach { problems += "missing $it" }
        (keys - manifestKeys).forEach { problems += "unknown $it" }
        if (manifest["schemaVersion"]?.asInt() != 1) problems += "schemaVersion must be 1"
        listOf("scenarioId", "scenarioVersionId").forEach { key ->
            if (runCatching { Uuids.parse(manifest[key]?.asString() ?: "") }.isFailure) problems += "$key must be a UUID"
        }
        val modes = manifest["modes"]?.values()?.map { it.asString() } ?: emptyList()
        if (modes.isEmpty() || !setOf("CTF", "WARGAME", "PURPLE").containsAll(modes)) problems += "modes must be MVP entry modes"
        val phases = manifest["phases"]?.values()?.map { it.asString() } ?: emptyList()
        if (!Phase.entries.map { it.name }.containsAll(phases)) problems += "unknown phase"
        val scope = manifest["scope"]
        if (scope?.get("syntheticDataOnly")?.asBoolean() != true || scope["externalNetwork"]?.asBoolean() != false) {
            problems += "scope must be synthetic-only without external network"
        }
        val runtime = manifest["runtime"]
        val limits = mapOf("vcpus" to 2, "memoryMiB" to 2048, "diskMiB" to 4096, "pids" to 256, "idleTtlSeconds" to 900, "hardTtlSeconds" to 3600)
        limits.forEach { (key, max) -> if ((runtime?.get(key)?.asInt() ?: Int.MAX_VALUE) !in 1..max) problems += "runtime.$key must be within 1..$max" }
        if (runtime?.get("profile")?.asString() != "lab-strong") problems += "runtime.profile must be lab-strong"
        val challenges = manifest["challenges"]?.values() ?: emptyList()
        if (challenges.isEmpty()) problems += "at least one challenge"
        challenges.forEach { challenge ->
            if (runCatching { Uuids.parse(challenge["id"]?.asString() ?: "") }.isFailure) problems += "challenge id must be a UUID"
            if (challenge["kind"]?.asString() !in setOf("FLAG", "OBJECTIVE")) problems += "challenge kind must be FLAG or OBJECTIVE"
        }
        if (manifest["difficulty"]?.asString() !in setOf("BEGINNER", "INTERMEDIATE", "ADVANCED")) problems += "difficulty"
        if ((manifest["estimatedMinutes"]?.asInt() ?: 0) !in 1..600) problems += "estimatedMinutes within 1..600"
        return check("manifest.structure", problems.isEmpty(), if (problems.isEmpty()) "manifest matches the public contract" else problems.joinToString("; "))
    }

    private fun oracleStructure(oracle: JsonNode): Check {
        val problems = mutableListOf<String>()
        if (!oracle.isObject) return check("oracle.structure", false, "oracle must be an object")
        val keys = oracle.propertyNames().toSet()
        (requiredOracleKeys - keys).forEach { problems += "missing $it" }
        (keys - oracleKeys).forEach { problems += "unknown $it" }
        if (oracle["visibility"]?.asString() != "GRADER_ONLY") problems += "visibility must be GRADER_ONLY"
        if ((oracle["hiddenTests"]?.size() ?: 0) == 0) problems += "at least one hidden test"
        if ((oracle["mutants"]?.size() ?: 0) == 0) problems += "at least one mutant"
        val gate = oracle["gate"]
        if (gate?.get("platformFailure")?.asString() != "INCONCLUSIVE") problems += "platform failure must be INCONCLUSIVE"
        if (gate?.get("mandatorySecurityTests")?.asString() != "ALL" || gate["mandatoryRegressionTests"]?.asString() != "ALL") problems += "all mandatory gates"
        oracle["flag"]?.let { if (it["plaintextLogging"]?.asBoolean() != false) problems += "flag plaintext logging must be false" }
        oracle["hints"]?.let { hints ->
            val levels = hints.values().map { (it["challengeId"]?.asString() ?: "") to (it["level"]?.asInt() ?: 0) }
            if (levels.any { it.second !in 1..4 } || levels.size != levels.toSet().size) problems += "hints need levels 1-4, once per challenge"
            if (hints.values().any { it["text"]?.isString != true || it["text"].asString().isEmpty() }) problems += "every hint needs text"
        }
        return check("oracle.structure", problems.isEmpty(), if (problems.isEmpty()) "oracle matches the private contract" else problems.joinToString("; "))
    }

    private fun weights(oracle: JsonNode): Check {
        val values = oracle["weights"]?.properties()?.map { it.value } ?: emptyList()
        return check("oracle.weights", values.isNotEmpty() && values.all { it.isIntegralNumber && it.asInt() >= 0 } && values.sumOf { it.asInt() } == 100,
            "rubric weights are non-negative integers totalling 100")
    }

    /** Oracle values that must never appear in the public half (08, 09). */
    fun sensitiveValues(oracle: JsonNode): Set<String> = buildSet {
        oracle["hiddenTests"]?.values()?.forEach { test -> test["id"]?.asString()?.let(::add) }
        oracle["mutants"]?.values()?.forEach { add(it.asString()) }
        oracle["verifier"]?.get("requires")?.values()?.forEach { add(it.asString()) }
        oracle["flag"]?.get("secretRef")?.asString()?.let(::add)
        oracle["referencePatchRef"]?.asString()?.let(::add)
        oracle["hints"]?.values()?.forEach { hint -> hint["text"]?.asString()?.let(::add) }
    }.filter { it.length >= 6 }.toSet()

    private fun separation(bundle: ContentBundle): Check {
        val problems = mutableListOf<String>()
        fun keysOf(node: JsonNode): Set<String> = when {
            node.isObject -> node.properties().flatMap { listOf(it.key) + keysOf(it.value) }.toSet()
            node.isArray -> node.values().flatMap(::keysOf).toSet()
            else -> emptySet()
        }
        if ((keysOf(bundle.manifest) intersect oracleOnlyKeys).isNotEmpty()) problems += "manifest contains oracle-only fields"
        val publicText = buildList {
            add(bundle.manifest.toString())
            bundle.publicFiles.values.forEach { add(String(it, Charsets.UTF_8)) }
            addAll(bundle.publicFiles.keys)
        }
        if (sensitiveValues(bundle.oracle).any { secret -> publicText.any { secret in it } }) problems += "public half contains oracle values"
        val privateDigests = bundle.privateFiles.values.map { secdrill.kernel.Digests.sha256Hex(it) }.toSet()
        if (bundle.publicFiles.values.any { secdrill.kernel.Digests.sha256Hex(it) in privateDigests }) problems += "a private file is duplicated in public/"
        return check("separation.public-private", problems.isEmpty(),
            if (problems.isEmpty()) "no oracle fields, values or files in the public half" else problems.joinToString("; "))
    }

    private fun oracleReferences(bundle: ContentBundle): Check {
        val problems = mutableListOf<String>()
        val text = bundle.oracle.toString()
        if ("PLACEHOLDER" in text.uppercase()) problems += "oracle still contains placeholders"
        bundle.oracle["flag"]?.get("secretRef")?.asString()?.let { if (!secretRef.matches(it)) problems += "flag.secretRef must be a secret-ref: reference" }
        val patch = bundle.oracle["referencePatchRef"]?.asString() ?: ""
        if (!patch.startsWith("private/") || patch !in bundle.privateFiles) problems += "referencePatchRef must name a file under private/"
        return check("gate.oracle-references", problems.isEmpty(),
            if (problems.isEmpty()) "oracle references resolve inside the private half" else problems.joinToString("; "))
    }

    private fun signatureCheck(signature: BundleSignature?, digest: String, trustedKeys: Map<String, PublicKey>): Check = when {
        signature == null -> Check("signature", CheckResult.FAIL, "bundle is not signed")
        BundleSigner.verify(signature, digest, trustedKeys) -> Check("signature", CheckResult.PASS, "valid signature by trusted key ${signature.keyId}")
        else -> Check("signature", CheckResult.FAIL, "signature does not verify against a trusted key for this digest")
    }
}
