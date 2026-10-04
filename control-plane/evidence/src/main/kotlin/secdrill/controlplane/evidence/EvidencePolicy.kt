package secdrill.controlplane.evidence

import secdrill.kernel.EvidenceSource
import secdrill.kernel.TrustLevel

/**
 * Which trust levels a source may claim (10, ADR 0005). Mirrors the `evidence_trust_matches_source` CHECK so the
 * application fails fast with a clear error before the database rejects the row.
 * Learner claims are always USER_REPORTED and nothing else is; official results never come from the learner.
 */
object EvidencePolicy {
    private val allowed: Map<TrustLevel, Set<EvidenceSource>> = mapOf(
        TrustLevel.SERVER_VERIFIED to setOf(EvidenceSource.CONTROL, EvidenceSource.VERIFIER, EvidenceSource.SUPERVISOR),
        TrustLevel.OBSERVED to setOf(EvidenceSource.COLLECTOR, EvidenceSource.SUPERVISOR),
        TrustLevel.SIMULATED to setOf(EvidenceSource.SIMULATOR, EvidenceSource.SUPERVISOR),
        TrustLevel.USER_REPORTED to setOf(EvidenceSource.USER),
    )

    fun permits(source: EvidenceSource, trust: TrustLevel): Boolean = source in allowed.getValue(trust)

    /**
     * Ledger rows hold identifiers, digests and small enums only (10, 16). Keys that name raw secrets or content are
     * refused; such data belongs in a private Artifact referenced by digest.
     */
    private val forbiddenKey = Regex("(?i)^(flag|token|secret|password|cookie|credential|source|content|raw.*|code|patch)$")

    fun requireSafePayload(payload: Map<String, Any?>) {
        fun walk(value: Any?, path: String) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, item) ->
                    require(key is String && !forbiddenKey.matches(key)) { "evidence payload key '$path$key' is not allowed in the ledger" }
                    walk(item, "$path$key.")
                }
                is List<*> -> value.forEach { walk(it, path) }
                is String -> require(value.length <= 512) { "evidence payload value at '$path' is too long for the ledger" }
            }
        }
        walk(payload, "")
    }
}
