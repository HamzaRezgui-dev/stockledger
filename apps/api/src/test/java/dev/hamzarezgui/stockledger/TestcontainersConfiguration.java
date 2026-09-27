package dev.hamzarezgui.stockledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres for integration tests.
 *
 * <p>Not an in-memory database, and not H2 in Postgres-compatibility mode. Every
 * guarantee under test here belongs to Postgres specifically — Serializable
 * Snapshot Isolation detecting a read-write conflict, {@code plpgsql} triggers
 * rejecting mutation, {@code scale()} in a CHECK constraint, {@code is not
 * distinct from} semantics. A substitute would pass while proving nothing.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /**
     * Pinned to the same image and flags as {@code compose.yaml}, so a test
     * cannot pass against a different Postgres than development uses.
     *
     * <p>{@code max_pred_locks_per_transaction} is raised for the same reason as
     * in compose: SSI tracks predicate locks in shared memory and escalates to
     * coarser, relation-level locks when it runs out, which manufactures false
     * conflicts. A concurrency test that trips that would be measuring
     * bookkeeping pressure rather than the real anomaly.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("stockledger")
                .withUsername("stockledger")
                .withPassword("stockledger")
                .withCommand(
                        "postgres",
                        "-c", "max_pred_locks_per_transaction=512",
                        "-c", "max_connections=200",
                        // Tests run against a throwaway container, so durability
                        // buys nothing and costs a real fsync per commit.
                        "-c", "fsync=off",
                        "-c", "full_page_writes=off");
    }
}
