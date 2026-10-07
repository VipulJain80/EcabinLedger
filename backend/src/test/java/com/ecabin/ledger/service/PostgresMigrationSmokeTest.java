package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Explicit opt-in smoke test for applying the real migrations to the configured local PostgreSQL schema. */
class PostgresMigrationSmokeTest {
    private static final String SCHEMA = "ecabin_ledger";
    private static final Set<String> REQUIRED_TABLES = Set.of("operators", "fleets", "aircraft", "operator_memberships",
        "operator_membership_teams", "defects", "inspections", "corrective_actions", "verifications",
        "closure_approvals", "attachments", "notifications", "defect_events", "flyway_schema_history");
    private static final Set<String> SOFT_DELETE_TABLES = Set.of("operators", "fleets", "aircraft", "operator_memberships",
        "operator_membership_teams", "defects", "inspections", "corrective_actions", "verifications",
        "closure_approvals", "attachments", "notifications");

    @Test
    void createsProjectSchemaAndAppliesFlywayMigrations() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("ECABIN_DB_VERIFY")),
            "Set ECABIN_DB_VERIFY=true to apply migrations to the explicitly configured PostgreSQL schema.");
        String url = requiredEnv("SPRING_DATASOURCE_URL");
        String username = requiredEnv("SPRING_DATASOURCE_USERNAME");
        String password = requiredEnv("SPRING_DATASOURCE_PASSWORD");
        verifySchemaIsSafeToUse(url, username, password);

        Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .cleanDisabled(true)
            .load()
            .migrate();

        try (var connection = DriverManager.getConnection(url, username, password);
             var query = connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=?")) {
            query.setString(1, SCHEMA);
            try (var rows = query.executeQuery()) {
                Set<String> actual = new java.util.HashSet<>();
                while (rows.next()) actual.add(rows.getString(1));
                assertTrue(actual.containsAll(REQUIRED_TABLES), "The project schema must contain all migrated operational tables.");
                assertEquals(4, appliedMigrationCount(connection), "All versioned migrations must be recorded as applied.");
            }
            try (var queryActive = connection.prepareStatement("SELECT table_name FROM information_schema.columns WHERE table_schema=? AND column_name='isactive' AND data_type='smallint' AND column_default='1'")) {
                queryActive.setString(1, SCHEMA);
                try (var rows = queryActive.executeQuery()) {
                    Set<String> flagged = new java.util.HashSet<>();
                    while (rows.next()) flagged.add(rows.getString(1));
                    assertTrue(flagged.containsAll(SOFT_DELETE_TABLES), "Every mutable business table must have SMALLINT isactive DEFAULT 1.");
                }
            }
        }

        if ("true".equalsIgnoreCase(System.getenv("ECABIN_SEED_DEMO"))) {
            var dataSource = new DriverManagerDataSource(url, username, password);
            var seed = new ResourceDatabasePopulator(new ClassPathResource("db/dev-seed.sql"));
            seed.execute(dataSource);
            seed.execute(dataSource); // The demo fixture must be safe to apply more than once.
            var jdbc = new JdbcTemplate(dataSource);
            assertEquals("Air India Express (DEMO)", jdbc.queryForObject(
                "SELECT name FROM operators WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", String.class));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM aircraft WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM defects WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM defect_events WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND event_type='DEMO_SEEDED'", Integer.class));
        }
    }

    private static void verifySchemaIsSafeToUse(String url, String username, String password) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password);
             var query = connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=?")) {
            query.setString(1, SCHEMA);
            try (var rows = query.executeQuery()) {
                Set<String> existing = new java.util.HashSet<>();
                while (rows.next()) existing.add(rows.getString(1));
                if (!existing.isEmpty()) {
                    assertTrue(existing.contains("flyway_schema_history"),
                        "Refusing to migrate a non-empty schema without this application's Flyway history.");
                }
            }
        }
    }

    private static int appliedMigrationCount(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history WHERE success=true AND version IS NOT NULL")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required DB verification setting is missing: " + name);
        return value;
    }
}
