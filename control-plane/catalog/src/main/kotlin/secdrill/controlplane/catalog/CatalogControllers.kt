package secdrill.controlplane.catalog

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.ResourceNotFoundException
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.identity.OperatorPrincipal
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Internal content API on the operator chain (15: `/ops/scenario-versions/{id}/approve`). No operator UI yet. */
@RestController
class ContentOpsController(private val content: ContentService, private val properties: ContentProperties, private val json: JsonMapper) {
    @PostMapping("/ops/v1/content/bundles")
    fun register(@AuthenticationPrincipal principal: OperatorPrincipal, request: HttpServletRequest, @RequestBody body: ByteArray): ResponseEntity<RegisteredVersion> {
        if (body.size > properties.maxUploadBytes) throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Bundle upload is too large")
        val tree = runCatching { json.readTree(body) }.getOrNull() ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON")
        return ResponseEntity.status(HttpStatus.CREATED).body(content.register(principal, tree))
    }

    @PostMapping("/ops/v1/scenario-versions/{id}/validations")
    fun validate(@AuthenticationPrincipal principal: OperatorPrincipal, @PathVariable id: UUID) =
        ResponseEntity.status(HttpStatus.CREATED).body(content.validate(principal, id))

    @PostMapping("/ops/v1/scenario-versions/{id}/approve")
    fun approve(@AuthenticationPrincipal principal: OperatorPrincipal, @PathVariable id: UUID): ResponseEntity<Void> {
        content.approve(principal, id)
        return ResponseEntity.noContent().build()
    }

    data class QuarantineRequest(val reason: String = "")

    @PostMapping("/ops/v1/scenario-versions/{id}/quarantine")
    fun quarantine(@AuthenticationPrincipal principal: OperatorPrincipal, @PathVariable id: UUID, @RequestBody body: JsonNode): ResponseEntity<Void> {
        content.quarantine(principal, id, body["reason"]?.asString() ?: "")
        return ResponseEntity.noContent().build()
    }
}

/** OpenAPI `ScenarioDetail`. Built field by field from the public manifest only; the oracle is never read here. */
data class ScenarioDetailView(
    val id: UUID,
    val scenarioVersionId: UUID,
    val title: String,
    val brief: String,
    val modes: List<String>,
    val competencyTags: List<String>,
    val challenges: List<ChallengeView>,
    val allowedTargets: List<String>,
    val estimatedMinutes: Int,
    val patchPaths: List<String>,
    val actions: List<String>,
    val completionRequirements: Map<String, List<String>>,
)

data class ChallengeView(val id: UUID, val objective: String, val kind: String)

/** `GET /v1/scenarios/{id}` (15): only PUBLISHED versions; drafts, unapproved and quarantined versions are 404. */
@RestController
class ScenarioController(private val jdbc: JdbcClient, private val json: JsonMapper) {
    @GetMapping("/v1/scenarios/{id}")
    fun detail(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @RequestParam(required = false) versionId: UUID?): ScenarioDetailView {
        val sql = "SELECT id, public_manifest::text FROM scenario_versions WHERE scenario_id = ? AND status = 'PUBLISHED'" +
            (if (versionId != null) " AND id = ?" else "") + " ORDER BY version_no DESC LIMIT 1"
        val args = listOfNotNull(id, versionId)
        val (version, manifestText) = jdbc.sql(sql).params(args).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
            .optional().orElseThrow { ResourceNotFoundException() }
        val manifest = json.readTree(manifestText)
        fun strings(node: JsonNode?) = node?.values()?.map { it.asString() } ?: emptyList()
        return ScenarioDetailView(
            id = id,
            scenarioVersionId = version,
            title = manifest["title"].asString(),
            brief = manifest["brief"].asString(),
            modes = strings(manifest["modes"]),
            competencyTags = strings(manifest["competencyTags"]),
            challenges = manifest["challenges"].values().map { ChallengeView(UUID.fromString(it["id"].asString()), it["objective"].asString(), it["kind"].asString()) },
            allowedTargets = strings(manifest["scope"]["allowedTargets"]),
            estimatedMinutes = manifest["estimatedMinutes"].asInt(),
            patchPaths = strings(manifest["patch"]?.get("allowedPaths")),
            actions = strings(manifest["actions"]),
            completionRequirements = manifest["completionRequirements"]?.properties()?.associate { it.key to strings(it.value) } ?: emptyMap(),
        )
    }
}

/** OpenAPI `Scenario` (catalog item). */
data class ScenarioView(
    val id: UUID, val scenarioVersionId: UUID, val title: String, val difficulty: String,
    val modes: List<String>, val competencyTags: List<String>, val estimatedMinutes: Int,
)

data class ScenarioPageView(val items: List<ScenarioView>, val nextCursor: String?)

/**
 * `GET /v1/scenarios` (15): the latest PUBLISHED version of each scenario, newest publication first. The cursor is
 * opaque: the last sort key (publishedAt, id) and the filter digest, HMAC-signed with a per-process key, so it cannot
 * be forged or reused with other filters. Cursors do not survive a restart (MVP; a configured key comes with T14).
 */
@RestController
class ScenarioCatalogController(private val jdbc: JdbcClient, private val json: JsonMapper) {
    private val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
    private val encoder = java.util.Base64.getUrlEncoder().withoutPadding()

    private fun sign(body: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
        return encoder.encodeToString(mac.doFinal(body.toByteArray()))
    }

    @GetMapping("/v1/scenarios")
    fun list(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @RequestParam(required = false) mode: String?,
        @RequestParam(required = false) difficulty: String?,
        @RequestParam(required = false) competency: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): ScenarioPageView {
        if (limit !in 1..100) throw ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 100")
        if (mode != null && secdrill.kernel.Mode.entries.none { it.name == mode }) throw ApiException(ErrorCode.VALIDATION_FAILED, "unknown mode")
        if (difficulty != null && difficulty !in setOf("BEGINNER", "INTERMEDIATE", "ADVANCED")) throw ApiException(ErrorCode.VALIDATION_FAILED, "unknown difficulty")
        val filter = secdrill.kernel.Digests.canonical(mapOf("mode" to mode, "difficulty" to difficulty, "competency" to competency))
        val after = cursor?.let { decode(it, filter) }
        val rows = jdbc.sql(
            """SELECT * FROM (SELECT DISTINCT ON (scenario_id) scenario_id, id, published_at, public_manifest::text AS manifest
               FROM scenario_versions WHERE status = 'PUBLISHED' ORDER BY scenario_id, version_no DESC) latest""",
        ).query { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, java.time.OffsetDateTime::class.java).toInstant(), rs.getString(4)) }
            .list()
            // Ordered here, ties broken on the canonical UUID text so the cursor comparison uses the same order.
            .sortedWith(compareByDescending<Row> { it.publishedAt }.thenByDescending { it.scenarioId.toString() })
            .filter { after == null || it.publishedAt < after.first || (it.publishedAt == after.first && it.scenarioId.toString() < after.second.toString()) }
            .mapNotNull { row -> view(row)?.let { row to it } }
            .filter { (_, v) -> (mode == null || mode in v.modes) && (difficulty == null || v.difficulty == difficulty) && (competency == null || competency in v.competencyTags) }
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().first.let { encodeCursor(it.publishedAt, it.scenarioId, filter) } else null
        return ScenarioPageView(page.map { it.second }, next)
    }

    private fun view(row: Row): ScenarioView? = runCatching {
        val manifest = json.readTree(row.manifest)
        ScenarioView(row.scenarioId, row.versionId, manifest["title"].asString(), manifest["difficulty"].asString(),
            manifest["modes"].values().map { it.asString() }, manifest["competencyTags"].values().map { it.asString() }, manifest["estimatedMinutes"].asInt())
    }.getOrNull()

    private fun encodeCursor(publishedAt: java.time.Instant, scenarioId: UUID, filter: String): String {
        val body = "v1|${publishedAt.epochSecond}|${publishedAt.nano}|$scenarioId|$filter"
        return encoder.encodeToString(body.toByteArray()) + "." + sign(body)
    }

    private fun decode(cursor: String, filter: String): Pair<java.time.Instant, UUID> {
        val invalid = ApiException(ErrorCode.VALIDATION_FAILED, "cursor is invalid for this query")
        val body = runCatching { String(java.util.Base64.getUrlDecoder().decode(cursor.substringBefore("."))) }.getOrNull() ?: throw invalid
        if (!java.security.MessageDigest.isEqual(sign(body).toByteArray(), cursor.substringAfter(".", "").toByteArray())) throw invalid
        val parts = body.split("|")
        if (parts.size != 5 || parts[0] != "v1" || parts[4] != filter) throw invalid
        return java.time.Instant.ofEpochSecond(parts[1].toLong(), parts[2].toLong()) to UUID.fromString(parts[3])
    }

    private data class Row(val scenarioId: UUID, val versionId: UUID, val publishedAt: java.time.Instant, val manifest: String)
}
