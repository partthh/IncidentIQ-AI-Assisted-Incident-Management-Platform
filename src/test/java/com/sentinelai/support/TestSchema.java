package com.sentinelai.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dedicated PostgreSQL schema per test class.
 *
 * <p>This exists because a shared schema made the suite order-dependent, and the
 * failure mode was actively misleading. Two separate problems shared one root:
 *
 * <ul>
 *   <li><b>Leaked background workers.</b> Spring caches {@code ApplicationContext}s
 *       for the JVM's lifetime. A scheduler started by an earlier test class keeps its
 *       threads running after that class finishes, so a later class that hand-steps
 *       its own queue finds its rows already claimed — the assertion fails with
 *       {@code RUNNING} instead of {@code QUEUED}, naming a bug in the queue that does
 *       not exist. Turning the worker off in the affected class cannot help, because
 *       the worker that interferes was built from a context where it was on.</li>
 *   <li><b>Leaked rows.</b> Incidents left open by one class hit another class's
 *       per-service active-incident cap; queued analyses left by one class were drained
 *       by another. Both were worked around with inflated limits, which is the wrong
 *       direction: raising a limit to stop a test interfering with itself hides the
 *       interference rather than removing it.</li>
 * </ul>
 *
 * <p>A schema per class removes the interference entirely, and makes a leaked worker
 * harmless: it can only ever drain rows in a database this test does not read. The
 * cluster itself is still shared, because starting a PostgreSQL server per class would
 * cost far more than it saves.
 *
 * <p>Names are derived from the class rather than randomised, so a rerun reuses the
 * same schema and a failing run is easy to inspect by hand.
 */
public final class TestSchema {

    private static final Logger log = LoggerFactory.getLogger(TestSchema.class);

    /**
     * Bound by {@link TestSchemaExtension} before the Spring context is built.
     * Written from JUnit's before-all phase and read when property values are
     * resolved, which always happens later — no test instance exists until after
     * every before-all callback has run.
     */
    private static volatile Class<?> boundClass;

    private TestSchema() {
    }

    static void bind(Class<?> testClass) {
        boundClass = testClass;
    }

    /** PostgreSQL truncates identifiers at 63 bytes; keep well inside that. */
    public static String name() {
        Class<?> testClass = boundClass;
        if (testClass == null) {
            throw new IllegalStateException(
                    "No test class bound. Subclasses of PostgresIntegrationTest must inherit "
                            + "@ExtendWith(TestSchemaExtension.class) from it.");
        }
        String simple = testClass.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        simple = simple.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        // Nested classes arrive as Outer$Inner; both parts carry information.
        String full = testClass.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_$]", "_");
        String candidate = "sentinel_test_" + full;
        if (candidate.length() <= 60) {
            return candidate;
        }
        // Fall back to the short form rather than truncating mid-identifier.
        return "sentinel_test_" + simple;
    }

    /**
     * JDBC URL targeting this class's schema.
     *
     * <p>{@code currentSchema} is merged into the URL's own query string, which the
     * embedded server populates with its own settings — appending a second {@code ?}
     * would produce a URL PostgreSQL cannot parse.
     */
    public static String jdbcUrl() {
        String base = EmbeddedPostgresHolder.jdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "currentSchema=" + name();
    }

    /**
     * Drops every schema left behind by an earlier run.
     *
     * <p>The embedded cluster's data directory is reused across runs, so without a
     * sweep each run would inherit the previous run's tables. Sweeping here — once,
     * when the server has just started — rather than after each class is deliberate: a
     * Spring context cached from a finished class keeps its scheduled worker running,
     * and dropping that class's schema underneath it turns every subsequent tick into a
     * "relation does not exist" stack trace. An empty, still-present schema is quiet,
     * and it is unreachable by any test that follows.
     */
    public static void dropAll() {
        String prefix = "sentinel_test_";
        try (Connection connection = DriverManager.getConnection(
                EmbeddedPostgresHolder.jdbcUrl(),
                EmbeddedPostgresHolder.username(),
                EmbeddedPostgresHolder.password());
             Statement statement = connection.createStatement()) {

            List<String> stale = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(
                    "select schema_name from information_schema.schemata "
                            + "where schema_name like '" + prefix + "%'")) {
                while (rows.next()) {
                    stale.add(rows.getString(1));
                }
            }
            for (String schema : stale) {
                // Identifiers cannot be bound as parameters, so the value is quoted
                // rather than interpolated raw. It comes from information_schema, so it
                // is already a real identifier, but quoting costs nothing and means this
                // stays safe if the filter above is ever loosened.
                statement.execute("drop schema if exists \"" + schema.replace("\"", "\"\"") + "\" cascade");
            }
            if (!stale.isEmpty()) {
                log.info("Dropped {} test schema(s) left by an earlier run", stale.size());
            }
        } catch (SQLException ex) {
            // A failed sweep must not fail the suite. Leftover tables are harmless:
            // Flyway revalidates each schema as it is used.
            log.warn("Could not sweep stale test schemas: {}", ex.getMessage());
        }
    }
}