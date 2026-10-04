package secdrill.controlplane.support

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.IssuedLogin
import secdrill.controlplane.identity.UserIdentityService
import secdrill.controlplane.identity.web.AuthCookies
import secdrill.kernel.UserId
import java.time.Duration
import java.util.UUID

/** Synthetic rows for tests. Sessions are inserted directly until the Session API exists (T04). */
@Component
class Fixtures(
    private val jdbc: JdbcClient,
    private val identities: UserIdentityService,
    private val sessions: AuthSessionService,
) {
    private val digest = "c".repeat(64)

    data class Learner(val userId: UserId, val login: IssuedLogin) {
        val cookies get() = mapOf(AuthCookies.ACCESS to login.accessToken)
    }

    fun learner(): Learner {
        val user = identities.findOrCreate("https://idp.test", "learner-${UUID.randomUUID()}")
        return Learner(user, sessions.start(user))
    }

    fun activeSession(owner: UserId): UUID {
        val scenario = UUID.randomUUID()
        val version = UUID.randomUUID()
        val session = UUID.randomUUID()
        jdbc.sql("INSERT INTO scenarios(id, slug, title) VALUES (?, ?, 'Synthetic')").params(scenario, "s-$scenario").update()
        jdbc.sql(
            """INSERT INTO scenario_versions(id, scenario_id, version_no, status, content_digest, oracle_digest, oracle_key,
               rubric_version, engine_version, randomization_version, public_manifest) VALUES (?, ?, 1, 'DRAFT', ?, ?, 'o', 'r', 'e', 'x', '{}')""",
        ).params(version, scenario, digest, digest).update()
        jdbc.sql(
            """INSERT INTO sessions(id, owner_id, scenario_version_id, mode, status, phase, seed, rubric_version, engine_version, randomization_version)
               VALUES (?, ?, ?, 'CTF', 'ACTIVE', 'ATTACK', '\x01', 'r', 'e', 'x')""",
        ).params(session, owner.value, version).update()
        return session
    }

    fun count(sql: String, vararg args: Any): Int = jdbc.sql(sql).params(*args).query(Int::class.java).single()

    fun string(sql: String, vararg args: Any): String? = jdbc.sql(sql).params(*args).query(String::class.java).optional().orElse(null)

    /** Polls until [condition] holds; consumers run asynchronously. */
    fun await(timeout: Duration = Duration.ofSeconds(15), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within $timeout" }
            Thread.sleep(50)
        }
    }

    companion object {
        fun flagBody(expectedVersion: Long, flag: String = "SYNTHETIC-FLAG-${UUID.randomUUID()}") =
            """{"kind":"FLAG","expectedVersion":$expectedVersion,"content":{"challengeId":"10000000-0000-4000-8000-000000000003","flag":"$flag"}}"""
    }
}
