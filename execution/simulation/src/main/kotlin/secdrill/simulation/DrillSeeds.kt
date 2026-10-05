package secdrill.simulation

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Sub-seeds derived from the Session seed (23). The Session seed is server-only, so a learner cannot compute the
 * holdout data from the training data; each purpose gets an independent value.
 */
object DrillSeeds {
    fun of(sessionSeed: ByteArray, purpose: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update("secdrill-drill-seed-v1\n$purpose\n".toByteArray())
            update(sessionSeed)
        }.digest()
        return ByteBuffer.wrap(digest, 0, 8).long
    }

    const val TRAINING = "detection-training"
    const val HOLDOUT = "detection-holdout"
    const val INCIDENT = "incident-model"
}
