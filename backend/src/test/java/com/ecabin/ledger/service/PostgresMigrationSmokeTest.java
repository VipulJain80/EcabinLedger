package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
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
    private static final Set<String> REQUIRED_TABLES = Set.of("operators_mst", "fleets_mst", "aircraft_mst", "operator_memberships_mst",
        "operator_membership_teams_mst", "defects_dtl", "inspections_dtl", "corrective_actions_dtl", "verifications_dtl",
        "closure_approvals_dtl", "attachments_dtl", "notifications_dtl", "defect_events_dtl", "maintenance_teams_mst",
        "cabin_areas_mst", "defect_categories_mst", "cabin_components_mst", "defect_conditions_mst", "component_conditions_mst", "configuration_imports_dtl", "navigation_menu_items_mst", "operator_roles_mst", "defect_severities_mst", "permissions_mst", "operational_forms_dtl", "operational_form_versions_dtl", "operational_form_submissions_dtl", "api_rate_limit_policies_mst", "api_rate_limit_usage_dtl", "api_rate_limit_public_usage_dtl", "api_rate_limit_policy_events_dtl", "flyway_schema_history");
    private static final Set<String> EXPECTED_APPLICATION_TABLES = Set.of("operators_mst", "fleets_mst", "aircraft_mst", "operator_memberships_mst",
        "operator_membership_teams_mst", "maintenance_teams_mst", "cabin_areas_mst", "defect_categories_mst",
        "cabin_components_mst", "defect_conditions_mst", "component_conditions_mst", "operator_roles_mst", "navigation_menu_items_mst",
        "defects_dtl", "defect_events_dtl", "inspections_dtl", "corrective_actions_dtl", "attachments_dtl", "notifications_dtl",
        "configuration_imports_dtl", "defect_severities_mst", "permissions_mst", "operational_forms_dtl", "operational_form_versions_dtl", "operational_form_submissions_dtl",
        "verifications_dtl", "closure_approvals_dtl", "api_rate_limit_policies_mst", "api_rate_limit_usage_dtl", "api_rate_limit_public_usage_dtl", "api_rate_limit_policy_events_dtl");
    private static final Set<String> SOFT_DELETE_TABLES = Set.of("operators_mst", "fleets_mst", "aircraft_mst", "operator_memberships_mst",
        "operator_membership_teams_mst", "defects_dtl", "inspections_dtl", "corrective_actions_dtl", "verifications_dtl",
        "closure_approvals_dtl", "attachments_dtl", "notifications_dtl", "maintenance_teams_mst",
        "defect_categories_mst", "cabin_areas_mst", "cabin_components_mst", "defect_conditions_mst", "component_conditions_mst", "navigation_menu_items_mst", "operator_roles_mst", "defect_severities_mst", "permissions_mst", "operational_forms_dtl", "api_rate_limit_policies_mst");

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
                Set<String> applicationTables = new java.util.HashSet<>(actual);
                applicationTables.remove("flyway_schema_history");
                assertEquals(EXPECTED_APPLICATION_TABLES, applicationTables, "Every application table must have exactly one business-purpose suffix, while Flyway history remains unchanged.");
                assertTrue(!actual.contains("legacy_cabin_zones_archive"), "The retired cabin-zones table and its temporary archive must be removed.");
                assertTrue(!actual.contains("cabin_zones_mst"), "The retired cabin-zones master must not remain under its application name.");
                assertTrue(actual.stream().noneMatch(name -> name.endsWith("_mst_mst") || name.endsWith("_dtl_dtl") || name.endsWith("_mst_dtl") || name.endsWith("_dtl_mst")), "No table may have a duplicated or conflicting suffix.");
                assertEquals(27, appliedMigrationCount(connection), "All versioned migrations must be recorded as applied.");
            }
            try (var objects = connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.table_constraints WHERE table_schema=? AND constraint_type='FOREIGN KEY'), (SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='defect_events_dtl' AND trigger_name='defect_events_append_only'), (SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='operational_form_versions_dtl' AND trigger_name='operational_form_version_immutable'), (SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='operational_form_submissions_dtl' AND trigger_name='operational_form_submission_immutable')")) {
                for (int i=1;i<=4;i++) objects.setString(i,SCHEMA);
                try (var rows=objects.executeQuery()) { rows.next(); assertTrue(rows.getInt(1)>0,"Tenant and workflow foreign keys must remain attached after table renames."); assertEquals(2,rows.getInt(2),"Append-only audit triggers for UPDATE and DELETE must remain attached to the renamed event table."); assertEquals(2,rows.getInt(3),"Published-version immutability triggers for UPDATE and DELETE must remain attached."); assertEquals(2,rows.getInt(4),"Submission immutability triggers for UPDATE and DELETE must remain attached."); }
            }
            verifyAuditAppendOnly(connection);
            try (var auditColumns = connection.prepareStatement("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=? AND (table_name,column_name) IN (( 'fleets_mst','created_by'),('fleets_mst','created_at'),('fleets_mst','updated_by'),('fleets_mst','updated_at'),('operational_forms_dtl','created_by'),('operational_forms_dtl','created_at'),('operational_forms_dtl','updated_by'),('operational_forms_dtl','updated_at'),('operator_membership_teams_mst','created_by'),('operator_membership_teams_mst','created_at'),('operator_membership_teams_mst','updated_by'),('operator_membership_teams_mst','updated_at'))")) {
                auditColumns.setString(1, SCHEMA);
                try (var rows=auditColumns.executeQuery()) {
                    Set<String> found=new java.util.HashSet<>();
                    while(rows.next())found.add(rows.getString(1)+"."+rows.getString(2));
                    assertEquals(12,found.size(),"Mutable business tables must expose their audit actor and timestamp fields.");
                }
            }
            try (var immutableColumns = connection.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name IN ('defect_events_dtl','operational_form_submissions_dtl','verifications_dtl','closure_approvals_dtl') AND column_name IN ('created_by','updated_by')")) {
                immutableColumns.setString(1, SCHEMA);
                try (var rows=immutableColumns.executeQuery()) { rows.next(); assertEquals(0,rows.getInt(1),"Append-only events and immutable decisions must retain their existing actor/time fields without duplicate mutable metadata."); }
            }
            try (var formAnswerColumns=connection.prepareStatement("SELECT count(*) FILTER (WHERE column_name='answers_json'),count(*) FILTER (WHERE column_name IN ('snapshot_defect_category','snapshot_component_id','snapshot_condition_id','snapshot_zone','snapshot_row_number','snapshot_seat_reference','snapshot_title','snapshot_description','snapshot_severity_id')) FROM information_schema.columns WHERE table_schema=? AND table_name='operational_form_submissions_dtl'")) {
                formAnswerColumns.setString(1,SCHEMA);
                try(var rows=formAnswerColumns.executeQuery()){rows.next();assertEquals(0,rows.getInt(1),"Form answers must not be stored in JSON/JSONB columns.");assertEquals(9,rows.getInt(2),"The immutable defect-report snapshot must use fixed typed relational columns.");}
            }
            try (var queryActive = connection.prepareStatement("SELECT table_name FROM information_schema.columns WHERE table_schema=? AND column_name='isactive' AND data_type='smallint' AND column_default LIKE '1%'")) {
                queryActive.setString(1, SCHEMA);
                try (var rows = queryActive.executeQuery()) {
                    Set<String> flagged = new java.util.HashSet<>();
                    while (rows.next()) flagged.add(rows.getString(1));
                    assertTrue(flagged.containsAll(SOFT_DELETE_TABLES), "Every mutable business table must have SMALLINT isactive DEFAULT 1.");
                }
            }
            try (var counts = connection.createStatement(); var rows = counts.executeQuery("SELECT (SELECT count(*) FROM " + SCHEMA + ".operators_mst), (SELECT count(*) FROM " + SCHEMA + ".navigation_menu_items_mst WHERE isactive=1)")) {
                rows.next();
                if (rows.getInt(1) > 0) assertTrue(rows.getInt(2) >= 5, "Existing organizations must receive the default navigation tree.");
            }
            try (var catalog = connection.createStatement(); var rows = catalog.executeQuery("SELECT (SELECT count(*) FROM " + SCHEMA + ".permissions_mst WHERE isactive=1 AND btrim(description)<>''), (SELECT count(DISTINCT permission_code) FROM " + SCHEMA + ".permissions_mst WHERE isactive=1), (SELECT count(*) FROM " + SCHEMA + ".defect_severities_mst WHERE severity_code IN ('LOW','MEDIUM','HIGH','CRITICAL') AND isactive=1), (SELECT count(*) FROM " + SCHEMA + ".defects_dtl WHERE severity_id IS NOT NULL AND severity IS NULL), (SELECT count(*) FROM " + SCHEMA + ".permissions_mst WHERE isactive=1 AND lower(description) LIKE '%allow%' AND lower(description) LIKE '%not%' AND btrim(module)<>'' )")) {
                rows.next();
                assertEquals(12, rows.getInt(1), "Each supported built-in permission must have a nonempty description.");
                assertEquals(12, rows.getInt(2), "Built-in permission codes must be unique and complete.");
                long organizations;
                try (var orgs = connection.createStatement(); var orgRows = orgs.executeQuery("SELECT count(*) FROM " + SCHEMA + ".operators_mst WHERE isactive=1")) { orgRows.next(); organizations = orgRows.getLong(1); }
                assertTrue(rows.getLong(3) >= organizations * 4, "Every active organization receives the four additive starter severity labels.");
                assertEquals(0, rows.getInt(4), "Linking legacy severity values must preserve their existing text snapshots.");
                assertEquals(12, rows.getInt(5), "Each permission description must state its allowance and a clear limitation, and include a functional module.");
            }
            try (var queryMenus = connection.prepareStatement("SELECT count(*) FROM " + SCHEMA + ".operators_mst WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1"); var organizations = queryMenus.executeQuery()) {
                organizations.next();
                if (organizations.getInt(1) > 0) try (var master = connection.createStatement(); var rows = master.executeQuery("SELECT count(*) FROM " + SCHEMA + ".navigation_menu_items_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND menu_key='menu-items' AND isactive=1 AND required_permissions='ADMINISTER_ORGANIZATION'")) {
                    rows.next(); assertEquals(1, rows.getInt(1), "The demo administrator must have the active menu-master submenu.");
                }
            }
        }

        if ("true".equalsIgnoreCase(System.getenv("ECABIN_SEED_DEMO"))) {
            String seedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
            var dataSource = new DriverManagerDataSource(seedUrl, username, password);
            var seed = new ResourceDatabasePopulator(new ClassPathResource("db/dev-seed.sql"));
            var jdbc = new JdbcTemplate(dataSource);
            List<String> originalAbcMembership = abcMembershipSnapshot(jdbc);
            seed.execute(dataSource);
            List<String> abcMembershipAfterFirstSeed = abcMembershipSnapshot(jdbc);
            seed.execute(dataSource); // The demo fixture must be safe to apply more than once.
            assertEquals(abcMembershipAfterFirstSeed, abcMembershipSnapshot(jdbc),
                "A repeated demo seed must not replace or modify the existing abc@gmail.com membership.");
            if (!originalAbcMembership.isEmpty()) assertEquals(originalAbcMembership, abcMembershipAfterFirstSeed,
                "Seeding must preserve the pre-existing abc@gmail.com identity, tenant, display name, role, and status.");
            assertEquals("Skyways Service (DEMO)", jdbc.queryForObject(
                "SELECT name FROM operators_mst WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", String.class));
            Integer demoFormCount=jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id AND v.status='PUBLISHED' WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.form_key LIKE 'DEMO_REPORT_%' AND f.isactive=1",Integer.class);
            assertTrue(demoFormCount>0,"The local demo must provide a published, relationally mapped defect-report form where explicit component-condition mappings exist.");
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.form_key LIKE 'DEMO_REPORT_%' AND field.value->>'type' NOT IN ('SECTION','FILE_UPLOAD','IMAGE_UPLOAD') AND field.value->>'binding' IS NULL",Integer.class),"Demo report fields must have an approved persistence binding.");
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM aircraft_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM defects_dtl WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND event_type='DEMO_SEEDED'", Integer.class));
            assertEquals(1, abcMembershipAfterFirstSeed.size(), "The existing local demo identity must remain present exactly once.");
            assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='admin.test@ecabin.local'", String.class));
            assertEquals("AUDITOR", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='auditor.test@ecabin.local'", String.class));
            assertEquals("REVIEWER", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='reviewer.test@ecabin.local'", String.class));
            assertEquals("MAINTENANCE_TECHNICIAN", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='maintenance.test@ecabin.local'", String.class));
            var userResolver = new com.ecabin.ledger.security.CurrentUserResolver(jdbc);
            assertEquals("ADMIN", resolveLocalUser(userResolver, "admin.test@ecabin.local").role());
            assertEquals("AUDITOR", resolveLocalUser(userResolver, "auditor.test@ecabin.local").role());
            assertEquals("REVIEWER", resolveLocalUser(userResolver, "reviewer.test@ecabin.local").role());
            assertEquals("MAINTENANCE_TECHNICIAN", resolveLocalUser(userResolver, "maintenance.test@ecabin.local").role());
            var permissions = new com.ecabin.ledger.security.PermissionService(jdbc);
            UUID demoOperator = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            ConfigurationService service = new ConfigurationService(jdbc, new ObjectMapper());
            assertEquals(Set.of(com.ecabin.ledger.security.Permission.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                new java.util.HashSet<>(jdbc.queryForList("SELECT permission_code FROM permissions_mst WHERE isactive=1", String.class)), "The read-only database catalog must match the server-authoritative Permission enum.");
            assertEquals(12, service.table(demoOperator, "permissions", 0, 100, "", "ACTIVE", null, null, null, "code", "ASC").totalElements());
            assertTrue(service.table(demoOperator, "role-permissions", 0, 100, "", "ACTIVE", null, null, null, "name", "ASC").totalElements() >= 6);
            assertEquals(7, service.table(demoOperator, "severities", 0, 100, "", "ACTIVE", null, null, null, "sortOrder", "ASC").totalElements());
            assertEquals("Low", service.list(demoOperator, "severities", false).get(0).get("name"));
            assertEquals(Set.of(com.ecabin.ledger.security.Permission.VIEW,com.ecabin.ledger.security.Permission.REPORT,com.ecabin.ledger.security.Permission.AUDIT),permissions.permissionsFor(demoOperator, "AUDITOR"));
            assertEquals(Set.of(com.ecabin.ledger.security.Permission.VIEW, com.ecabin.ledger.security.Permission.REVIEW), permissions.permissionsFor(demoOperator, "REVIEWER"));
            assertTrue(permissions.permissionsFor(demoOperator, "MAINTENANCE_TECHNICIAN").contains(com.ecabin.ledger.security.Permission.PERFORM_ACTION));
            var reviewerNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "REVIEWER"));
            var auditorNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "AUDITOR"));
            var maintenanceNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "MAINTENANCE_TECHNICIAN"));
            var adminNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "ADMIN"));
            assertTrue(flattenNavigation(reviewerNavigation).contains("review-queue"), "The Reviewer account should receive the backend-authorized Reviewer queue menu.");
            assertTrue(!flattenNavigation(reviewerNavigation).contains("roles") && !flattenNavigation(reviewerNavigation).contains("menu-items"), "Reviewer must not receive organization administration screens.");
            assertTrue(flattenNavigation(auditorNavigation).contains("audit"), "The Auditor account should receive the backend-authorized audit menu.");
            assertTrue(!flattenNavigation(auditorNavigation).contains("review-queue"), "Auditor must not receive Reviewer queue access without REVIEW.");
            assertTrue(!flattenNavigation(maintenanceNavigation).contains("audit"), "Maintenance must not receive the audit menu without AUDIT.");
            assertTrue(flattenNavigation(adminNavigation).contains("roles") && flattenNavigation(adminNavigation).contains("menu-items"), "The separate tenant ADMIN account should receive organization administration menus.");
            assertTrue(flattenNavigation(adminNavigation).containsAll(Set.of("severities", "permissions", "role-permissions")), "The administrator navigation should expose severity and permission administration views.");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst t ON t.operator_id=omt.operator_id AND t.name=omt.team_name WHERE omt.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND omt.external_user_id='reviewer.test@ecabin.local' AND omt.team_name='Cabin Review' AND omt.isactive=1 AND t.team_type='REVIEWER' AND t.isactive=1", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst t ON t.operator_id=omt.operator_id AND t.name=omt.team_name WHERE omt.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND omt.external_user_id='maintenance.test@ecabin.local' AND omt.team_name='Cabin Maintenance' AND omt.isactive=1 AND t.team_type='MAINTENANCE' AND t.isactive=1", Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM maintenance_teams_mst WHERE operator_id=? AND name='Cabin Review' AND team_type='REVIEWER' AND isactive=1",Integer.class,demoOperator));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM maintenance_teams_mst WHERE operator_id=? AND name='Cabin Maintenance' AND team_type='MAINTENANCE' AND isactive=1",Integer.class,demoOperator));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operators_mst o JOIN maintenance_teams_mst t ON t.operator_id=o.id AND t.id=o.default_reviewer_team_id WHERE o.id=? AND t.name='Cabin Review' AND t.team_type='REVIEWER' AND t.isactive=1",Integer.class,demoOperator));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id IN ('admin.test@ecabin.local','auditor.test@ecabin.local','reviewer.test@ecabin.local','maintenance.test@ecabin.local') AND role='SUPERADMIN'", Integer.class));
            assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM defect_categories_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM cabin_areas_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM cabin_components_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals("Defect Summary", jdbc.queryForObject("SELECT label FROM navigation_menu_items_mst WHERE operator_id=? AND menu_key='home'", String.class, demoOperator));
            for (String resource : List.of("organizations", "fleets", "aircraft", "areas", "defect-categories", "defect-conditions", "components", "maintenance-teams", "users", "menu-items", "roles", "severities", "permissions", "role-permissions")) {
                ConfigurationService.TablePage page = service.table(demoOperator, resource, 0, 25, "", "ALL", null, null, null, null, null);
                assertTrue(page.totalPages() >= 1, "Every administration master should return consistent paging metadata: " + resource);
                ByteArrayOutputStream workbook = new ByteArrayOutputStream();
                service.exportExcel(demoOperator, resource, "", "ACTIVE", null, null, null, null, "ASC", null, workbook);
                assertTrue(workbook.size() > 100, "Each administration master should export an XLSX workbook: " + resource);
                assertEquals(0x50, Byte.toUnsignedInt(workbook.toByteArray()[0]), "An XLSX export must begin with ZIP signature: " + resource);
            }
            assertTrue(new com.ecabin.ledger.security.PermissionService(jdbc).permissionsFor(demoOperator, "INSPECTOR").contains(com.ecabin.ledger.security.Permission.INSPECT), "Permissions must resolve from the tenant role master.");
            var fleetStatus = new com.ecabin.ledger.service.DefectService(jdbc).fleetStatus(demoOperator, 0, 1, "DEMO-32001", "msnNumber", "ASC");
            assertEquals(1, fleetStatus.totalElements());
            assertEquals("DEMO-A320-01", fleetStatus.items().get(0).tailNumber());
            assertEquals(2, fleetStatus.items().get(0).openDefects());
            ByteArrayOutputStream fleetWorkbook = new ByteArrayOutputStream();
            new com.ecabin.ledger.service.DefectService(jdbc).exportFleetStatus(demoOperator, "", "tailNumber", "ASC", null, fleetWorkbook);
            assertEquals(0x50, Byte.toUnsignedInt(fleetWorkbook.toByteArray()[0]), "Fleet Status should export as XLSX.");
            ConfigurationService.TablePage firstAircraftPage = service.table(demoOperator, "aircraft", 0, 1, "", "ACTIVE", null, null, null, "tailNumber", "ASC");
            ByteArrayOutputStream allAircraft = new ByteArrayOutputStream();
            service.exportExcel(demoOperator, "aircraft", "", "ACTIVE", null, null, null, "tailNumber", "ASC", List.of("tailNumber","aircraftType"), allAircraft);
            String sheetXml;
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(allAircraft.toByteArray()))) {
                java.util.zip.ZipEntry entry; String contents = "";
                while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("xl/worksheets/sheet1.xml")) contents = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                sheetXml = contents;
            }
            long exportedRows = java.util.regex.Pattern.compile("<row ").matcher(sheetXml).results().count() - 1;
            assertEquals(firstAircraftPage.totalElements(), exportedRows, "Export must include all filtered records, independent of the current page size.");
            assertTrue(sheetXml.indexOf("Registration") < sheetXml.indexOf("Aircraft type"), "Visible export headers must retain the displayed order.");
            assertTrue(!sheetXml.contains("MSN Number")&&!sheetXml.contains("Open Defects"), "Hidden and unrelated fields must not appear in the requested aircraft export.");
            assertEquals(1, service.table(demoOperator, "aircraft", 0, 25, "DEMO-A320-01", "ACTIVE", null, null, "A320-200", "tailNumber", "DESC").totalElements(), "Search and aircraft-type filters must be applied by PostgreSQL.");
        }
    }

    private static List<String> abcMembershipSnapshot(JdbcTemplate jdbc) {
        return jdbc.query("SELECT operator_id::text,external_user_id,display_name,role,isactive::text FROM operator_memberships_mst WHERE external_user_id='abc@gmail.com' ORDER BY operator_id",
            (rs, row) -> String.join("\u001f", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    private static Set<String> flattenNavigation(List<com.ecabin.ledger.api.ApiModels.NavigationItem> items) {
        Set<String> keys = new java.util.HashSet<>();
        for (var item : items) {
            keys.add(item.key());
            keys.addAll(flattenNavigation(item.children()));
        }
        return keys;
    }

    private static com.ecabin.ledger.security.AuthenticatedUser resolveLocalUser(
            com.ecabin.ledger.security.CurrentUserResolver users, String email) {
        var now = java.time.Instant.now();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
            .header("alg", "HS256").subject(email).issuedAt(now).expiresAt(now.plusSeconds(60))
            .claim("operator_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").build();
        return users.require(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
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

    private static void verifyAuditAppendOnly(java.sql.Connection connection) throws Exception {
        connection.setSchema(SCHEMA);
        boolean autoCommit=connection.getAutoCommit();
        connection.setAutoCommit(false);
        UUID operator=UUID.randomUUID(), event=UUID.randomUUID();
        try {
            try (var create=connection.prepareStatement("INSERT INTO "+SCHEMA+".operators_mst(id,name) VALUES(?,?)")) {
                create.setObject(1,operator); create.setString(2,"Migration smoke test tenant"); create.executeUpdate();
            }
            try (var insert=connection.prepareStatement("INSERT INTO "+SCHEMA+".defect_events_dtl(id,operator_id,event_type,note,actor_id,entity_id) VALUES(?,?,'MIGRATION_SMOKE','append-only check','smoke-test',?)")) {
                insert.setObject(1,event); insert.setObject(2,operator); insert.setObject(3,UUID.randomUUID()); insert.executeUpdate();
            }
            var updateSavepoint=connection.setSavepoint();
            try (var update=connection.prepareStatement("UPDATE "+SCHEMA+".defect_events_dtl SET note='mutation rejected' WHERE id=?")) {
                update.setObject(1,event); assertThrows(SQLException.class,update::executeUpdate,"Audit updates must be rejected after table rename.");
            } finally { connection.rollback(updateSavepoint); }
            var deleteSavepoint=connection.setSavepoint();
            try (var delete=connection.prepareStatement("DELETE FROM "+SCHEMA+".defect_events_dtl WHERE id=?")) {
                delete.setObject(1,event); assertThrows(SQLException.class,delete::executeUpdate,"Audit deletes must be rejected after table rename.");
            } finally { connection.rollback(deleteSavepoint); }
        } finally {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required DB verification setting is missing: " + name);
        return value;
    }
}
