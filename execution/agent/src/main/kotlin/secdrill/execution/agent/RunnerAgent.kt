package secdrill.execution.agent

import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.CleanupReceipt
import secdrill.execution.protocol.LabAction
import secdrill.execution.protocol.LabAssignment
import secdrill.execution.protocol.LabControl
import secdrill.execution.protocol.ObservedLab
import secdrill.execution.protocol.ProvisionDecision
import secdrill.execution.protocol.ProvisionedLab
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration

/**
 * Runner Agent loop (11, 17). Holds no Control DB credentials: it reaches Control only through [LabControl].
 * [enforceLocalTtl] works from runtime labels alone, so hard TTL holds even while Control is unreachable.
 */
class RunnerAgent(private val control: LabControl, private val runtime: RuntimeAdapter, private val clock: Clock = Clock.systemUTC()) {
    /** Handles at most one assignment; returns it (or null when there was nothing to do). */
    fun runOnce(): LabAssignment? {
        val assignment = control.claim() ?: return null
        if (control.start(assignment) == Ack.STALE) return assignment
        when (assignment.action) {
            LabAction.PROVISION -> provision(assignment)
            LabAction.CLEANUP -> cleanup(assignment)
        }
        return assignment
    }

    private fun provision(assignment: LabAssignment) {
        val lab = try {
            runtime.provision(assignment.lab)
        } catch (error: Exception) {
            // Partial resources may remain after a failed create; ask Control for a cleanup instead of guessing.
            control.provisionFailed(assignment, resourcesMayExist = true)
            return
        }
        if (control.provisioned(assignment, lab) == ProvisionDecision.STALE) {
            // Lease lost (e.g. expired while creating): reconciliation will remove it if Control does not want it.
            return
        }
    }

    private fun cleanup(assignment: LabAssignment) {
        val receipt = try {
            runtime.terminate(assignment.lab.labId, assignment.lab.generation)
        } catch (error: Exception) {
            control.cleanupFailed(assignment)
            return
        }
        control.terminated(assignment, receipt)
    }

    /** Removes runtimes past their hard expiry without asking Control (17: Control outage must not extend a Lab). */
    fun enforceLocalTtl(): List<CleanupReceipt> = runtime.list()
        .filter { !it.hardExpiresAt.isAfter(clock.instant()) }
        .map { runtime.terminate(it.labId, it.generation) }

    /** Runner startup / periodic orphan reconciliation (17). */
    fun reconcile(): List<CleanupReceipt> {
        val owned = runtime.list()
        val unwanted = control.reconcile(owned.map { it.observed() }).map { it.labId to it.generation }.toSet()
        return owned.filter { (it.labId to it.generation) in unwanted }.map { runtime.terminate(it.labId, it.generation) }
    }
}

/** [LabControl] over the internal API with a workload bearer credential (19). */
class HttpLabControl(private val baseUrl: String, private val credential: String) : LabControl {
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

    override fun claim(): LabAssignment? = post("/internal/v1/lab-jobs/claim", null).let { if (it.statusCode() == 204) null else json.readValue(it.body()) }
    override fun start(assignment: LabAssignment) = ack("/internal/v1/lab-jobs/start", mapOf("assignment" to assignment))
    override fun heartbeat(assignment: LabAssignment) = ack("/internal/v1/lab-jobs/heartbeat", mapOf("assignment" to assignment))
    override fun provisioned(assignment: LabAssignment, lab: ProvisionedLab) = ProvisionDecision.valueOf(
        json.readTree(post("/internal/v1/lab-jobs/provisioned", mapOf("assignment" to assignment, "lab" to lab)).body())["decision"].asString(),
    )
    override fun provisionFailed(assignment: LabAssignment, resourcesMayExist: Boolean) =
        ack("/internal/v1/lab-jobs/provision-failed", mapOf("assignment" to assignment, "resourcesMayExist" to resourcesMayExist))
    override fun terminated(assignment: LabAssignment, receipt: CleanupReceipt) =
        ack("/internal/v1/lab-jobs/terminated", mapOf("assignment" to assignment, "receipt" to receipt))
    override fun cleanupFailed(assignment: LabAssignment) = ack("/internal/v1/lab-jobs/cleanup-failed", mapOf("assignment" to assignment))
    override fun reconcile(observed: List<ObservedLab>): List<ObservedLab> =
        json.readValue<Map<String, List<ObservedLab>>>(post("/internal/v1/labs/reconcile", mapOf("observed" to observed)).body()).getValue("terminate")
}
