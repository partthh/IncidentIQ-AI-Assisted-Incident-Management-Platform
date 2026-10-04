package com.sentinelai.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a real PostgreSQL server for the duration of the test JVM.
 *
 * <p>Docker is not assumed to be present, so tests execute against actual
 * PostgreSQL binaries rather than an H2 compatibility mode. That matters because
 * the behaviour under test <em>is</em> PostgreSQL behaviour: unique-violation
 * driven idempotency, {@code RETURNING}-based atomic counters, jsonb mapping
 * and jsonb containment queries. An in-memory substitute would let tests pass
 * that fail in production.
 *
 * <p>Started once per JVM and shared. Isolation between test classes comes from a
 * schema per class ({@link TestSchema}) rather than a server per class: the expensive
 * part is booting PostgreSQL, and a shared server with separate schemas gives the same
 * guarantees at a fraction of the cost.
 */
public final class EmbeddedPostgresHolder {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresHolder.class);

    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";
    /**
     * The cluster's default database. Using it directly avoids having to create a
     * separate database first; the whole cluster is thrown away when the JVM exits.
     */
    private static final String DATABASE = "postgres";

    private static EmbeddedPostgres server;
    private static String jdbcUrl;

    private EmbeddedPostgresHolder() {
    }

    public static synchronized String jdbcUrl() {
        start();
        return jdbcUrl;
    }

    public static String username() {
        return USER;
    }

    public static String password() {
        return PASSWORD;
    }

    private static void start() {
        if (server != null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder()
                    .setConnectConfig("user", USER)
                    .setConnectConfig("password", PASSWORD)
                    .setServerConfig("max_connections", "200")
                    .setServerConfig("log_min_messages", "warning");
            File dataDirectory = dataDirectory();
            if (dataDirectory != null) {
                builder.setDataDirectory(dataDirectory);
            }
            server = builder.start();
            // Argument order is (username, databaseName).
            jdbcUrl = server.getJdbcUrl(USER, DATABASE);
            log.info("Embedded PostgreSQL ready in {} ms at {}", System.currentTimeMillis() - startedAt, jdbcUrl);
            // The data directory outlives the JVM, so start from a known set of schemas.
            TestSchema.dropAll();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start embedded PostgreSQL for tests", e);
        }
    }

    /**
     * Keeps the cluster's data directory out of the OneDrive-synced workspace,
     * where file locking and partial writes are unreliable.
     */
    private static File dataDirectory() {
        String configured = System.getProperty("sentinel.test.pg.dataDir");
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        Path base = Path.of(System.getProperty("java.io.tmpdir"), "sentinel-embedded-pg");
        File dir = base.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Could not create embedded PostgreSQL data directory {}; using the default", dir);
            return null;
        }
        return dir;
    }
}
