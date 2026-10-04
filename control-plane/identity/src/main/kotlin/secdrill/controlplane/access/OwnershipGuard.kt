package secdrill.controlplane.access

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.kernel.UserId
import java.util.UUID

/** Learner-owned resource types addressed by public API ids (FR-01). */
enum class OwnedResource { SESSION, SUBMISSION, ARTIFACT, EVIDENCE, REPORT }

/** Missing and other-owner resources are indistinguishable to the caller (15: 404 hides existence). */
class ResourceNotFoundException : RuntimeException("resource not found")

fun interface OwnershipLookup {
    /** Owner of the resource, or null if it does not exist or is not addressable (e.g. deleted artifact). */
    fun ownerOf(type: OwnedResource, id: UUID): UserId?
}

/**
 * Common owner guard. Every learner API that takes a resource id must call [requireOwned] before reading or
 * changing it; the endpoints arrive with T04 (Session, Report), T05 (Submission) and T09 (Evidence, Artifact).
 */
@Component
class OwnershipGuard(private val lookup: OwnershipLookup) {
    fun requireOwned(principal: LearnerPrincipal, type: OwnedResource, id: UUID) {
        if (lookup.ownerOf(type, id) != principal.userId) throw ResourceNotFoundException()
    }
}

/**
 * Resolves owners through the Session that holds each resource. REPORT is addressed by its Session id
 * (`GET /sessions/{id}/report`) until a reports table exists (T12).
 */
@Component
class JdbcOwnershipLookup(private val jdbc: JdbcClient) : OwnershipLookup {
    override fun ownerOf(type: OwnedResource, id: UUID): UserId? {
        val sql = when (type) {
            OwnedResource.SESSION, OwnedResource.REPORT -> "SELECT owner_id FROM sessions WHERE id = ?"
            OwnedResource.SUBMISSION -> "SELECT s.owner_id FROM submissions x JOIN sessions s ON s.id = x.session_id WHERE x.id = ?"
            OwnedResource.ARTIFACT ->
                "SELECT s.owner_id FROM artifacts x JOIN sessions s ON s.id = x.session_id WHERE x.id = ? AND x.deleted_at IS NULL"
            OwnedResource.EVIDENCE -> "SELECT s.owner_id FROM evidence x JOIN sessions s ON s.id = x.session_id WHERE x.id = ?"
        }
        return jdbc.sql(sql).param(id).query(UUID::class.java).optional().map(::UserId).orElse(null)
    }
}
