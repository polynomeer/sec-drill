package secdrill.controlplane.privacy

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import secdrill.controlplane.support.IntegrationTest
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The running application role (`control_app`) must never de-identify by itself (14, 19, ADR 0013): it cannot
 * execute the erasure functions and cannot delete identity linkage. 42501 is PostgreSQL's insufficient-privilege.
 */
@IntegrationTest
class PrivacyPrivilegeTest {
    @Autowired private lateinit var dataSource: JdbcConnectionDetails

    private fun deniedAs(role: String, sql: String, vararg args: Any): String? =
        DriverManager.getConnection(dataSource.jdbcUrl, dataSource.username, dataSource.password).use { c ->
            c.createStatement().execute("SET ROLE $role")
            runCatching { c.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.execute() } }
                .exceptionOrNull()?.let { (it as SQLException).sqlState }
        }

    @Test
    fun `control_app cannot run the erasure functions or delete identity linkage`() {
        assertEquals("42501", deniedAs("control_app", "SELECT erase_deletion_request(?, ?, now())", UUID.randomUUID(), "a".repeat(64)))
        assertEquals("42501", deniedAs("control_app", "SELECT reapply_tombstones()"))
        assertEquals("42501", deniedAs("control_app", "DELETE FROM user_identities"))
    }

    @Test
    fun `the eraser role may execute the functions`() {
        // EXECUTE is granted; the call still fails on the missing request, not on privilege.
        assertEquals(null, deniedAs("privacy_eraser", "SELECT reapply_tombstones()"))
    }
}
