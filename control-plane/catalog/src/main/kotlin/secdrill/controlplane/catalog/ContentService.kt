package secdrill.controlplane.catalog

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.content.BundleDigester
import secdrill.content.BundleSignature
import secdrill.content.Check
import secdrill.content.CheckResult
import secdrill.content.ContentBundle
import secdrill.content.ContentValidation
import secdrill.content.RuntimeVerifier
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.identity.OperatorPrincipal
import secdrill.controlplane.platform.SystemAudit
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import secdrill.kernel.OperatorRole
import secdrill.kernel.ScenarioVersionStatus
import secdrill.kernel.Uuids
import secdrill.kernel.ValidationStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

data class RegisteredVersion(val scenarioVersionId: UUID, val status: ScenarioVersionStatus, val bundleDigest: String, val contentDigest: String, val oracleDigest: String)

data class ReportView(val id: UUID, val scenarioVersionId: UUID, val bundleDigest: String, val verifierKind: String, val status: ValidationStatus, val checks: List<Check>)

/**
 * Content lifecycle (08, 19): AUTHOR registers a signed bundle (DRAFT) -> validation report (PASS -> VALIDATED)
 * -> a REVIEWER who is not the author approves (PUBLISHED, immutable) -> quarantine blocks it. The database
 * enforces the same gates (V5 trigger and approval constraints). Oracle and private files go only to the private
 * store; the public manifest is the only content the learner API reads.
 */
@Service
class ContentService(
    private val jdbc: JdbcClient,
    private val store: ArtifactStore,
    private val verifier: RuntimeVerifier,
    private val properties: ContentProperties,
    private val audit: SystemAudit,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val base64 = Base64.getDecoder()
    private val encoder = Base64.getEncoder()

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)

    private fun requireRole(principal: OperatorPrincipal, vararg roles: OperatorRole) {
        if (principal.role !in roles) throw ApiException(ErrorCode.FORBIDDEN, "Role is not allowed for this content action")
    }

    @Transactional
    fun register(principal: OperatorPrincipal, upload: JsonNode): RegisteredVersion {
        requireRole(principal, OperatorRole.AUTHOR)
        val bundle = parseUpload(upload)
        val signature = upload["signature"]?.takeIf { it.isObject }?.let { runCatching { BundleSignature.fromJson(it) }.getOrNull() }
        val manifest = bundle.manifest
        val versionId = uuid(manifest["scenarioVersionId"], "manifest.scenarioVersionId")
        val scenarioId = uuid(manifest["scenarioId"], "manifest.scenarioId")
        val versionNo = manifest["version"]?.takeIf { it.isIntegralNumber }?.asInt() ?: invalid("manifest.version must be an integer")
        val versions = manifest["versions"] ?: invalid("manifest.versions is required")
        val digests = runCatching { BundleDigester.digests(bundle) }.getOrElse { invalid("content documents may not contain floating-point numbers") }

        if (jdbc.sql("SELECT count(*) FROM scenario_versions WHERE id = ?").param(versionId).query(Int::class.java).single() > 0) {
            throw ApiException(ErrorCode.INVALID_STATE, "Scenario version already exists; publish changes as a new version")
        }
        val family = manifest["family"]?.asString() ?: invalid("manifest.family is required")
        // One scenario per incident family: a family owned by another scenario is a conflict, not a server error.
        jdbc.sql("INSERT INTO scenarios(id, slug, title) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
            .params(scenarioId, family, manifest["title"]?.asString() ?: family).update()
        val existing = jdbc.sql("SELECT slug FROM scenarios WHERE id = ?").param(scenarioId).query(String::class.java).optional().orElse(null)
        if (existing != family) {
            throw ApiException(ErrorCode.INVALID_STATE, "Scenario family does not match the existing scenario")
        }

        val publicKey = "content/$versionId/${UUID.randomUUID()}"
        val privateKey = "content/$versionId/${UUID.randomUUID()}"
        store.put(publicKey, json.writeValueAsBytes(mapOf("manifest" to manifest, "files" to encode(bundle.publicFiles), "signature" to signature)))
        store.put(privateKey, json.writeValueAsBytes(mapOf("oracle" to bundle.oracle, "files" to encode(bundle.privateFiles), "publicBlob" to publicKey)))
        jdbc.sql(
            """INSERT INTO scenario_versions(id, scenario_id, version_no, status, content_digest, oracle_digest, oracle_key, rubric_version,
               engine_version, randomization_version, public_manifest, author_id, bundle_digest, signature_key_id, created_at)
               VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
        ).params(
            listOf(
                versionId, scenarioId, versionNo, digests.contentDigest, digests.oracleDigest, privateKey,
                versions["rubric"]?.asString() ?: invalid("manifest.versions.rubric is required"),
                versions["engine"]?.asString() ?: invalid("manifest.versions.engine is required"),
                versions["randomization"]?.asString() ?: invalid("manifest.versions.randomization is required"),
                manifest.toString(), principal.operatorId, digests.bundleDigest, signature?.keyId, now(),
            ),
        ).update()
        audit.record("content registration", "version $versionId registered by ${principal.operatorId} digest ${digests.bundleDigest}")
        return RegisteredVersion(versionId, ScenarioVersionStatus.DRAFT, digests.bundleDigest, digests.contentDigest, digests.oracleDigest)
    }

    @Transactional
    fun validate(principal: OperatorPrincipal, versionId: UUID): ReportView {
        requireRole(principal, OperatorRole.AUTHOR, OperatorRole.REVIEWER)
        val version = loadVersion(versionId)
        if (version.status != ScenarioVersionStatus.DRAFT) throw ApiException(ErrorCode.INVALID_STATE, "Only draft versions are validated")
        val (bundle, signature) = loadBundle(version)
        val recomputed = BundleDigester.digests(bundle)
        val integrity = Check(
            "storage.integrity",
            if (recomputed.bundleDigest == version.bundleDigest && recomputed.contentDigest == version.contentDigest && recomputed.oracleDigest == version.oracleDigest) CheckResult.PASS else CheckResult.FAIL,
            "stored bundle matches the registered digests",
        )
        val base = ContentValidation.run(bundle, signature, properties.publicKeys(), verifier)
        val accepted = Check(
            "runtime.verifier-accepted",
            if (verifier.kind in properties.acceptedVerifiers) CheckResult.PASS else CheckResult.NOT_RUN,
            "runtime results count only from accepted verifiers (${properties.acceptedVerifiers.joinToString()})",
        )
        val checks = listOf(integrity) + base.checks + accepted
        val status = when {
            checks.any { it.result == CheckResult.FAIL } -> ValidationStatus.FAIL
            checks.any { it.result == CheckResult.NOT_RUN } -> ValidationStatus.INCOMPLETE
            else -> ValidationStatus.PASS
        }
        val reportId = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO content_validation_reports(id, scenario_version_id, bundle_digest, validator_version, verifier_kind, status, checks, requested_by, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
        ).params(reportId, versionId, version.bundleDigest, base.validatorVersion, verifier.kind, status.name, json.writeValueAsString(checks), principal.operatorId, now()).update()
        if (status == ValidationStatus.PASS) {
            jdbc.sql("UPDATE scenario_versions SET status = 'VALIDATED' WHERE id = ?").param(versionId).update()
        }
        audit.record("content validation", "version $versionId report $reportId status $status by ${principal.operatorId}")
        return ReportView(reportId, versionId, version.bundleDigest, verifier.kind, status, checks)
    }

    @Transactional
    fun approve(principal: OperatorPrincipal, versionId: UUID) {
        requireRole(principal, OperatorRole.REVIEWER)
        val version = loadVersion(versionId)
        if (version.status != ScenarioVersionStatus.VALIDATED) throw ApiException(ErrorCode.INVALID_STATE, "Only validated versions can be approved")
        if (version.authorId == principal.operatorId) throw ApiException(ErrorCode.FORBIDDEN, "Authors cannot approve their own versions")
        val report = jdbc.sql(
            "SELECT id FROM content_validation_reports WHERE scenario_version_id = ? AND bundle_digest = ? AND status = 'PASS' ORDER BY created_at DESC LIMIT 1",
        ).params(versionId, version.bundleDigest).query(UUID::class.java).optional()
            .orElseThrow { ApiException(ErrorCode.MISSING_GATES, "No passing validation report for this bundle") }
        val now = now()
        jdbc.sql("INSERT INTO content_approvals(scenario_version_id, author_id, reviewer_id, report_id, approved_at) VALUES (?, ?, ?, ?, ?)")
            .params(versionId, version.authorId!!, principal.operatorId, report, now).update()
        json.readTree(version.manifest)["challenges"].values().forEach { challenge ->
            jdbc.sql("INSERT INTO challenges(id, version_id, challenge_key, kind, public_spec) VALUES (?, ?, ?, ?, ?::jsonb)").params(
                Uuids.parse(challenge["id"].asString()), versionId, challenge["key"].asString(), challenge["kind"].asString(),
                json.writeValueAsString(mapOf("objective" to challenge["objective"].asString(), "basePoints" to challenge["basePoints"].asInt())),
            ).update()
        }
        jdbc.sql("UPDATE scenario_versions SET status = 'PUBLISHED', published_at = ? WHERE id = ?").params(now, versionId).update()
        audit.record("content approval", "version $versionId published by reviewer ${principal.operatorId}")
    }

    @Transactional
    fun quarantine(principal: OperatorPrincipal, versionId: UUID, reason: String) {
        requireRole(principal, OperatorRole.OPERATOR, OperatorRole.SECURITY_ADMIN, OperatorRole.REVIEWER)
        val trimmed = reason.trim()
        if (trimmed.length !in 3..500) invalid("reason must be 3-500 characters")
        val version = loadVersion(versionId)
        if (version.status == ScenarioVersionStatus.QUARANTINED) throw ApiException(ErrorCode.INVALID_STATE, "Version is already quarantined")
        jdbc.sql("UPDATE scenario_versions SET status = 'QUARANTINED', quarantined_at = ?, quarantine_reason = ? WHERE id = ?")
            .params(now(), trimmed, versionId).update()
        audit.record("content quarantine", "version $versionId quarantined by ${principal.operatorId}")
    }

    private fun parseUpload(upload: JsonNode): ContentBundle {
        val manifest = upload["manifest"]?.takeIf { it.isObject } ?: invalid("manifest object is required")
        val oracle = upload["oracle"]?.takeIf { it.isObject } ?: invalid("oracle object is required")
        return ContentBundle(manifest, oracle, decode(upload["publicFiles"]), decode(upload["privateFiles"]))
    }

    private fun decode(node: JsonNode?): Map<String, ByteArray> {
        if (node == null || node.isNull) return emptyMap()
        if (!node.isObject) invalid("files must be an object of path to base64")
        return node.properties().associate { (path, value) ->
            path to (runCatching { base64.decode(value.asString()) }.getOrNull() ?: invalid("file content must be base64"))
        }
    }

    private fun encode(files: Map<String, ByteArray>) = files.mapValues { encoder.encodeToString(it.value) }

    private fun loadBundle(version: StoredVersion): Pair<ContentBundle, BundleSignature?> {
        val private = json.readTree(store.get(version.oracleKey) ?: error("private bundle blob missing"))
        val public = json.readTree(store.get(private["publicBlob"].asString()) ?: error("public bundle blob missing"))
        val signature = public["signature"]?.takeIf { it.isObject }?.let(BundleSignature::fromJson)
        return ContentBundle(json.readTree(version.manifest), private["oracle"], decode(public["files"]), decode(private["files"])) to signature
    }

    private fun loadVersion(id: UUID): StoredVersion = jdbc.sql(
        "SELECT status, author_id, bundle_digest, content_digest, oracle_digest, oracle_key, public_manifest::text FROM scenario_versions WHERE id = ?",
    ).param(id).query { rs, _ ->
        StoredVersion(
            ScenarioVersionStatus.valueOf(rs.getString(1)), rs.getObject(2, UUID::class.java), rs.getString(3) ?: error("version has no bundle digest"), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7),
        )
    }.optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND, "Resource not found") }

    private fun uuid(node: JsonNode?, field: String): UUID =
        node?.takeIf { it.isString }?.let { runCatching { Uuids.parse(it.asString()) }.getOrNull() } ?: invalid("$field must be a UUID")

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.VALIDATION_FAILED, message)

    private data class StoredVersion(
        val status: ScenarioVersionStatus, val authorId: UUID?, val bundleDigest: String, val contentDigest: String,
        val oracleDigest: String, val oracleKey: String, val manifest: String,
    )
}
