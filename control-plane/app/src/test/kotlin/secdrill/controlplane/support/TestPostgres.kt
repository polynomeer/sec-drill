package secdrill.controlplane.support

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** postgres:18.6-alpine pinned by index digest (D-02). Update tag and digest together. */
object TestPostgres {
    val image: DockerImageName = DockerImageName
        .parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
        .asCompatibleSubstituteFor("postgres")

    fun container(): PostgreSQLContainer = PostgreSQLContainer(image).withStartupTimeout(STARTUP_TIMEOUT)

    fun migrate(container: PostgreSQLContainer) {
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
