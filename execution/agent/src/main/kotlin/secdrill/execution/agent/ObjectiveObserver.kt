package secdrill.execution.agent

import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.GradeAssignment
import secdrill.execution.protocol.GradeControl
import secdrill.execution.protocol.JobLease
import secdrill.execution.protocol.ObjectiveObservation
import secdrill.execution.protocol.ObjectiveTask
import secdrill.execution.protocol.PatchObservation
import secdrill.execution.protocol.PatchTask
import secdrill.kernel.SubmissionKind
import secdrill.kernel.Digests
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Independent objective observation for CTF challenges (09, 20). Reads the target's server-side access records from
 * inside the Lab through the runtime adapter. Learners reach the Lab only over HTTP and no route serves these
 * records, so they reflect what the server decided (token tenant, owning tenant, status), not learner output.
 *
 * Limit (local-trusted): the records live inside the Lab and the app writes them; a learner with code execution in
 * the Lab could forge them. Content without such a path is assumed; the strong runtime must move collection out of
 * the guest.
 */
class ObjectiveObserver(private val runtime: RuntimeAdapter) {
    companion object {
        const val RECORDS = "/tmp/secdrill/audit.jsonl"
        /** Requirements the Control Plane checks itself from its receipt; the observer ignores them. */
        private val controlRequirements = setOf("sessionChallengeBound")
        private val predicates: Map<String, (Map<String, Any?>) -> Boolean> = mapOf(
            "actorTenant != resourceTenant" to { r -> r["actorTenant"] is String && r["resourceTenant"] is String && r["actorTenant"] != r["resourceTenant"] },
            "actorTenantDiffersFromResource" to { r -> r["actorTenant"] is String && r["resourceTenant"] is String && r["actorTenant"] != r["resourceTenant"] },
            "syntheticOrderReturned" to { r -> r["route"] == "order-read" && r["status"] == 200 },
        )
    }

    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun observe(task: ObjectiveTask): ObjectiveObservation {
        val checks = task.requires.filterNot { it in controlRequirements }
        // An unknown requirement is a content problem, never the learner's: report "could not observe".
        if (checks.isEmpty() || checks.any { it !in predicates }) return ObjectiveObservation(null, 0, null)
        val result = runCatching { runtime.exec(task.labId, task.generation, listOf("sh", "-c", "cat $RECORDS 2>/dev/null; exit 0")) }.getOrNull()
            ?: return ObjectiveObservation(null, 0, null)
        if (result.exitCode != 0) return ObjectiveObservation(null, 0, null)
        val digest = Digests.sha256Hex(result.output.toByteArray(Charsets.UTF_8))
        val matching = result.output.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.readValue<Map<String, Any?>>(line) }.getOrNull() }
            .count { record -> checks.all { predicates.getValue(it)(record) } }
        // A truncated read that found nothing proves nothing.
        if (matching == 0 && result.truncated) return ObjectiveObservation(null, 0, digest)
        return ObjectiveObservation(matching > 0, matching, digest)
    }
}

/** Runs one patch grading task in a grading environment (T08). */
fun interface PatchGrader {
    fun grade(lease: JobLease, task: PatchTask): PatchObservation
}

/**
 * Grading loop of a runner (20). Holds no DB credentials; reaches Control over [GradeControl]. FLAG jobs need the
 * Lab this runner hosts; PATCH jobs are taken only when a [PatchGrader] is configured.
 */
class GradeAgent(private val control: GradeControl, private val observer: ObjectiveObserver, private val patches: PatchGrader? = null) {
    // FLAG and OBJECTIVE are both verified by observing this runner's Lab (09, ADR 0014); PATCH needs a grader.
    private val kinds = setOfNotNull(SubmissionKind.FLAG, SubmissionKind.OBJECTIVE, patches?.let { SubmissionKind.PATCH })

    fun runOnce(): GradeAssignment? {
        val assignment = control.claim(kinds) ?: return null
        if (control.start(assignment.lease) == Ack.STALE) return assignment
        val patch = assignment.patch
        if (patch != null) {
            val grader = checkNotNull(patches) { "received a PATCH job without a grader" }
            val observation = grader.grade(assignment.lease, patch)
            if (control.heartbeat(assignment.lease) == Ack.STALE) return assignment
            control.patchResult(assignment.lease, observation)
            return assignment
        }
        val observation = assignment.objective?.let(observer::observe) ?: ObjectiveObservation(false, 0, null)
        if (control.heartbeat(assignment.lease) == Ack.STALE) return assignment
        control.observed(assignment.lease, observation)
        return assignment
    }
}

/** [GradeControl] over the internal API with an AGENT credential (19). */
class HttpGradeControl(private val baseUrl: String, private val credential: String) : GradeControl {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun post(path: String, body: Any?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .header("Authorization", "Bearer $credential").header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(if (body == null) "{}" else json.writeValueAsString(body)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "control returned ${response.statusCode()} for $path" }
        return response
    }

    private fun ack(path: String, body: Any) = Ack.valueOf(json.readTree(post(path, body).body())["ack"].asString())

    override fun claim(kinds: Set<SubmissionKind>): GradeAssignment? =
        post("/internal/v1/grade-jobs/claim", mapOf("kinds" to kinds)).let { if (it.statusCode() == 204) null else json.readValue(it.body()) }
    override fun start(lease: JobLease) = ack("/internal/v1/grade-jobs/start", mapOf("lease" to lease))
    override fun heartbeat(lease: JobLease) = ack("/internal/v1/grade-jobs/heartbeat", mapOf("lease" to lease))
    override fun observed(lease: JobLease, observation: ObjectiveObservation) =
        ack("/internal/v1/grade-jobs/observed", mapOf("lease" to lease, "observation" to observation))
    override fun patchResult(lease: JobLease, observation: PatchObservation) =
        ack("/internal/v1/grade-jobs/patch-result", mapOf("lease" to lease, "observation" to observation))
}
