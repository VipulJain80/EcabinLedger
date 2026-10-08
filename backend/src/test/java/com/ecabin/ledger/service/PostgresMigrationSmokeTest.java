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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Explicit opt-in smoke test for applying the real migrations to the configured local PostgreSQL schema. */
class PostgresMigrationSmokeTest {
    private static final String SCHEMA = "ecabin_ledger";
    private static final Set<String> REQUIRED_TABLES = Set.of("operators", "fleets", "aircraft", "operator_memberships",
        "operator_membership_teams", "defects", "inspections", "corrective_actions", "verifications",
        "closure_approvals", "attachments", "notifications", "defect_events", "maintenance_teams",
        "cabin_zones", "defect_categories", "cabin_components", "navigation_menu_items", "operator_roles", "flyway_schema_history");
    private static final Set<String> SOFT_DELETE_TABLES = Set.of("operators", "fleets", "aircraft", "operator_memberships",
        "operator_membership_teams", "defects", "inspections", "corrective_actions", "verifications",
        "closure_approvals", "attachments", "notifications", "maintenance_teams", "cabin_zones",
        "defect_categories", "cabin_components", "navigation_menu_items", "operator_roles");

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
                assertEquals(8, appliedMigrationCount(connection), "All versioned migrations must be recorded as applied.");
                assertTrue(actual.contains("operator_roles"), "Tenant role master must be migrated.");
            }
            try (var queryActive = connection.prepareStatement("SELECT table_name FROM information_schema.columns WHERE table_schema=? AND column_name='isactive' AND data_type='smallint' AND column_default LIKE '1%'")) {
                queryActive.setString(1, SCHEMA);
                try (var rows = queryActive.executeQuery()) {
                    Set<String> flagged = new java.util.HashSet<>();
                    while (rows.next()) flagged.add(rows.getString(1));
                    assertTrue(flagged.containsAll(SOFT_DELETE_TABLES), "Every mutable business table must have SMALLINT isactive DEFAULT 1.");
                }
            }
            try (var counts = connection.createStatement(); var rows = counts.executeQuery("SELECT (SELECT count(*) FROM " + SCHEMA + ".operators), (SELECT count(*) FROM " + SCHEMA + ".navigation_menu_items WHERE isactive=1)")) {
                rows.next();
                if (rows.getInt(1) > 0) assertTrue(rows.getInt(2) >= 5, "Existing organizations must receive the default navigation tree.");
            }
            try (var queryMenus = connection.prepareStatement("SELECT count(*) FROM " + SCHEMA + ".operators WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1"); var organizations = queryMenus.executeQuery()) {
                organizations.next();
                if (organizations.getInt(1) > 0) try (var master = connection.createStatement(); var rows = master.executeQuery("SELECT count(*) FROM " + SCHEMA + ".navigation_menu_items WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND menu_key='menu-items' AND isactive=1 AND required_permissions='ADMINISTER_ORGANIZATION'")) {
                    rows.next(); assertEquals(1, rows.getInt(1), "The demo administrator must have the active menu-master submenu.");
                }
            }
        }

        if ("true".equalsIgnoreCase(System.getenv("ECABIN_SEED_DEMO"))) {
            var dataSource = new DriverManagerDataSource(url, username, password);
            var seed = new ResourceDatabasePopulator(new ClassPathResource("db/dev-seed.sql"));
            seed.execute(dataSource);
            seed.execute(dataSource); // The demo fixture must be safe to apply more than once.
            var jdbc = new JdbcTemplate(dataSource);
            assertEquals("Skyways Service (DEMO)", jdbc.queryForObject(
                "SELECT name FROM operators WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", String.class));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM aircraft WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM defects WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM defect_events WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND event_type='DEMO_SEEDED'", Integer.class));
            assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM operator_memberships WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='abc@gmail.com'", String.class));
            assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM defect_categories WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(13, jdbc.queryForObject("SELECT count(*) FROM cabin_zones WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM cabin_components WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            ConfigurationService service = new ConfigurationService(jdbc, new ObjectMapper());
            UUID demoOperator = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            assertEquals("Defect Summary", jdbc.queryForObject("SELECT label FROM navigation_menu_items WHERE operator_id=? AND menu_key='home'", String.class, demoOperator));
            for (String resource : List.of("organizations", "fleets", "aircraft", "cabin-zones", "defect-categories", "components", "maintenance-teams", "users", "menu-items", "roles")) {
                ConfigurationService.TablePage page = service.table(demoOperator, resource, 0, 25, "", "ALL", null, null, null, null, null);
                assertTrue(page.totalPages() >= 1, "Every administration master should return consistent paging metadata: " + resource);
                ByteArrayOutputStream workbook = new ByteArrayOutputStream();
                service.exportExcel(demoOperator, resource, "", "ACTIVE", null, null, null, null, "ASC", workbook);
                assertTrue(workbook.size() > 100, "Each administration master should export an XLSX workbook: " + resource);
                assertEquals(0x50, Byte.toUnsignedInt(workbook.toByteArray()[0]), "An XLSX export must begin with ZIP signature: " + resource);
            }
            assertTrue(new com.ecabin.ledger.security.PermissionService(jdbc).permissionsFor(demoOperator, "INSPECTOR").contains(com.ecabin.ledger.security.Permission.INSPECT), "Permissions must resolve from the tenant role master.");
            var fleetStatus = new com.ecabin.ledger.service.DefectService(jdbc).fleetStatus(demoOperator, 0, 1, "DEMO-32001", "msnNumber", "ASC");
            assertEquals(1, fleetStatus.totalElements());
            assertEquals("DEMO-A320-01", fleetStatus.items().get(0).tailNumber());
            assertEquals(2, fleetStatus.items().get(0).openDefects());
            ByteArrayOutputStream fleetWorkbook = new ByteArrayOutputStream();
            new com.ecabin.ledger.service.DefectService(jdbc).exportFleetStatus(demoOperator, "", "tailNumber", "ASC", fleetWorkbook);
            assertEquals(0x50, Byte.toUnsignedInt(fleetWorkbook.toByteArray()[0]), "Fleet Status should export as XLSX.");
            ConfigurationService.TablePage firstAircraftPage = service.table(demoOperator, "aircraft", 0, 1, "", "ACTIVE", null, null, null, "tailNumber", "ASC");
            ByteArrayOutputStream allAircraft = new ByteArrayOutputStream();
            service.exportExcel(demoOperator, "aircraft", "", "ACTIVE", null, null, null, "tailNumber", "ASC", allAircraft);
            String sheetXml;
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(allAircraft.toByteArray()))) {
                java.util.zip.ZipEntry entry; String contents = "";
                while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("xl/worksheets/sheet1.xml")) contents = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                sheetXml = contents;
            }
            long exportedRows = java.util.regex.Pattern.compile("<row ").matcher(sheetXml).results().count() - 1;
            assertEquals(firstAircraftPage.totalElements(), exportedRows, "Export must include all filtered records, independent of the current page size.");
            assertEquals(1, service.table(demoOperator, "aircraft", 0, 25, "DEMO-A320-01", "ACTIVE", null, null, "A320-200", "tailNumber", "DESC").totalElements(), "Search and aircraft-type filters must be applied by PostgreSQL.");
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
