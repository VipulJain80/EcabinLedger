package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecabin.ledger.api.ApiModels.DefectPage;
import com.ecabin.ledger.api.ApiModels.DefectView;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Disposable PostgreSQL tests for the reviewed Defect Register routine. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://gateway.invalid/",
    "app.security.audience=ecabin-test-api"
})
class DefectRegisterReportPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPORT_FN = "defect_register_report_v2";
    private static final String LOOKUP_FN = "defect_workflow_status_lookup_v2";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefectService defects;
    @Autowired PermissionService permissions;

    private UUID tenant;
    private UUID aircraft;
    private UUID area;
    private UUID reviewerTeam;

    private record Result(long total, String rowId, JsonNode row) {}

    @BeforeEach
    void createTenantFixture() {
        tenant = UUID.randomUUID();
        UUID fleet = UUID.randomUUID();
        aircraft = UUID.randomUUID();
        jdbc.update("INSERT INTO operators_mst(id,name) VALUES(?,?)", tenant, "Report scope test " + tenant);
        jdbc.update("INSERT INTO fleets_mst(id,operator_id,name) VALUES(?,?,?)", fleet, tenant, "Test Fleet");
        jdbc.update("INSERT INTO aircraft_mst(id,operator_id,fleet_id,tail_number,aircraft_type) VALUES(?,?,?,?,?)",
            aircraft, tenant, fleet, "TEST-" + tenant.toString().substring(0, 8), "A320");
        area = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY'", UUID.class, tenant);
        reviewerTeam = UUID.randomUUID();
        jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name,team_type) VALUES(?,?,?,'REVIEWER')",
            reviewerTeam, tenant, "Report Reviewer " + reviewerTeam.toString().substring(0, 6));
    }

    @Test
    void migrationActivatesReviewedTemplateWithoutCreatingTenantReportsOrRoleGrants() {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='57' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='58' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='60' AND success", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT is_active FROM report_routines_mst WHERE routine_key='DEFECT_REGISTER_V2'", Boolean.class));
        assertTrue(jdbc.queryForObject("SELECT is_active FROM report_templates_mst WHERE template_code='DEFECT_REGISTER'", Boolean.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reports_mst WHERE report_code='DEFECT_REGISTER'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM report_role_access_mst a JOIN reports_mst r ON r.id=a.report_id WHERE r.report_code='DEFECT_REGISTER'", Integer.class));
        assertEquals(List.of("defectId","area","cabinZone","component","condition","severity","status","caseCreatedDate","caseClosedDate"),
            jdbc.queryForList("SELECT column_key FROM report_template_columns_mst WHERE template_id=(SELECT id FROM report_templates_mst WHERE template_code='DEFECT_REGISTER') ORDER BY sort_order", String.class));
        assertEquals(List.of("fromDate","toDate","area","status"),
            jdbc.queryForList("SELECT filter_key FROM report_template_filters_mst WHERE template_id=(SELECT id FROM report_templates_mst WHERE template_code='DEFECT_REGISTER') ORDER BY sort_order", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM report_lookup_sources_mst WHERE lookup_key='DEFECT_WORKFLOW_STATUS' AND is_active", Integer.class));
        assertFalse(jdbc.queryForObject("SELECT required_permissions FROM navigation_menu_items_mst WHERE operator_id=? AND menu_key='reports'", String.class, tenant).contains("REPORT_EXECUTE"));
    }

    @Test
    void functionReturnsExactConfiguredRowKeysAndUsesInclusiveIstDateBoundariesAndTrueTotals() throws Exception {
        String actor = "report-viewer-" + tenant;
        addRoleAndMember(actor, "VIEW|REPORT");
        UUID first = addDefect("FIRST", actor, "GALLEY", "REPORTED", null, null,
            OffsetDateTime.parse("2026-10-01T00:00:00+05:30").toInstant());
        UUID lastMoment = addDefect("LAST", actor, "GALLEY", "REPORTED", null, null,
            OffsetDateTime.parse("2026-10-01T23:59:59.999+05:30").toInstant());
        addDefect("NEXT_DAY", actor, "GALLEY", "REPORTED", null, null,
            OffsetDateTime.parse("2026-10-02T00:00:00+05:30").toInstant());

        List<Result> page = call(tenant, actor, "REPORT_TEST", Map.of("from_date","2026-10-01","to_date","2026-10-01"), 0, 100, "[]");
        assertEquals(2, page.get(0).total());
        assertEquals(Set.of(first.toString(), lastMoment.toString()), page.stream().map(Result::rowId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("defectId","area","cabinZone","component","condition","severity","status","caseCreatedDate","caseClosedDate"),
            JSON.convertValue(page.get(0).row(), Map.class).keySet());
        assertEquals("FIRST", page.stream().filter(row -> row.row().get("defectId").asText().equals("FIRST")).findFirst().orElseThrow().row().get("defectId").asText());
        assertEquals("New", page.get(0).row().get("status").asText());

        List<Result> beyond = call(tenant, actor, "REPORT_TEST", Map.of("from_date","2026-10-01","to_date","2026-10-01"), 2, 1, "[]");
        assertEquals(1, beyond.size());
        assertEquals(2, beyond.get(0).total());
        assertNull(beyond.get(0).rowId());
        assertNull(beyond.get(0).row());
    }

    @Test
    void areaAndStatusFiltersCanNarrowTheSameVisibleRowsAndConfiguredSortIsStable() {
        String actor = "filter-viewer-" + tenant;
        addRoleAndMember(actor, "VIEW|REPORT");
        UUID galleyOpen = addDefect("GALLEY_OPEN", actor, "GALLEY", "REPORTED", null, null, Instant.parse("2026-10-10T10:00:00Z"));
        addDefect("CABIN_OPEN", actor, "CABIN", "REPORTED", null, null, Instant.parse("2026-10-10T11:00:00Z"));
        UUID galleyClosed = addDefect("GALLEY_CLOSED", actor, "GALLEY", "CLOSED", null, null, Instant.parse("2026-10-10T12:00:00Z"));

        List<Result> galley = call(tenant, actor, "FILTER_TEST", Map.of("area", "GALLEY"), 0, 100, "[]");
        assertEquals(Set.of(galleyOpen.toString(), galleyClosed.toString()), rowIds(galley));
        List<Result> closed = call(tenant, actor, "FILTER_TEST", Map.of("status", "CLOSED"), 0, 100, "[]");
        assertEquals(Set.of(galleyClosed.toString()), rowIds(closed));
        List<Result> combined = call(tenant, actor, "FILTER_TEST", Map.of("area", "GALLEY", "status", "CLOSED"), 0, 100,
            "[{\"key\":\"defectId\",\"direction\":\"ASC\"}]");
        assertEquals(Set.of(galleyClosed.toString()), rowIds(combined));
        assertEquals("GALLEY_CLOSED", combined.get(0).row().get("defectId").asText());
    }

    @Test
    void stableIdentityAndTenantPredicateKeepRowsBoundToTheirSourceDefects() {
        String actor = "tenant-viewer-" + tenant;
        addRoleAndMember(actor, "VIEW");
        UUID local = addDefect("LOCAL_CASE", actor, "GALLEY", "REPORTED", null, null, Instant.parse("2026-10-10T10:00:00Z"));

        UUID otherTenant = UUID.randomUUID();
        UUID otherFleet = UUID.randomUUID();
        UUID otherAircraft = UUID.randomUUID();
        jdbc.update("INSERT INTO operators_mst(id,name) VALUES(?,?)", otherTenant, "Isolation fixture " + otherTenant);
        jdbc.update("INSERT INTO fleets_mst(id,operator_id,name) VALUES(?,?,?)", otherFleet, otherTenant, "Other Fleet");
        jdbc.update("INSERT INTO aircraft_mst(id,operator_id,fleet_id,tail_number,aircraft_type) VALUES(?,?,?,?,?)",
            otherAircraft, otherTenant, otherFleet, "OTHER-" + otherTenant.toString().substring(0, 8), "A320");
        UUID otherArea = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY'", UUID.class, otherTenant);
        UUID foreignId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO defects_dtl(id,operator_id,aircraft_id,reference,location,category,area_id,zone,title,description,
              component,condition_name_snapshot,severity,status,reported_by,reported_at,updated_at,isactive)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'LOW','REPORTED',?,now(),now(),1)
            """, foreignId, otherTenant, otherAircraft, "FOREIGN_CASE", "GALLEY", "GALLEY", otherArea, "G2", "Foreign",
            "Synthetic isolation fixture", "Latch", "Broken", "foreign-actor");

        List<Result> rows = call(tenant, actor, "TENANT_TEST", Map.of(), 0, 100, "[]");
        assertEquals(Set.of(local.toString()), rowIds(rows));
        assertFalse(rowIds(rows).contains(foreignId.toString()));
    }

    @Test
    void statusLookupReturnsOnlyActiveConfiguredDefectStatusLabels() {
        String actor = "lookup-viewer-" + tenant;
        addRoleAndMember(actor, "VIEW|REPORT");
        List<Map<String,Object>> statuses = jdbc.query(
            "SELECT option_value,display_label FROM defect_workflow_status_lookup_v2(?,?,?,'DEFECT_VISIBLE_SCOPE_V1',100)",
            (rs, n) -> Map.of("value", rs.getString(1), "label", rs.getString(2)), tenant, actor, "REPORT_TEST");
        assertFalse(statuses.isEmpty());
        assertTrue(statuses.stream().anyMatch(row -> "REPORTED".equals(row.get("value")) && "New".equals(row.get("label"))));
        assertEquals(statuses.size(), statuses.stream().map(row -> row.get("value")).distinct().count());
    }

    @Test
    void functionVisibilityMatchesExistingDefectListForReporterReviewerMaintenanceCombinedAndBroadRoles() {
        String reporter = "reporter-" + tenant;
        String reviewer = "reviewer-" + tenant;
        String maintenance = "maintenance-" + tenant;
        String combined = "combined-" + tenant;
        String broad = "broad-" + tenant;
        String ordinary = "ordinary-view-" + tenant;
        addRoleAndMember(reporter, "VIEW|REPORT");
        addRoleAndMember(reviewer, "VIEW|REVIEW");
        addRoleAndMember(maintenance, "VIEW|PERFORM_ACTION");
        addRoleAndMember(combined, "VIEW|REVIEW|PERFORM_ACTION");
        addRoleAndMember(broad, "DEFECT_VIEW_ALL");
        addRoleAndMember(ordinary, "VIEW");

        String reviewerTeamName = jdbc.queryForObject("SELECT name FROM maintenance_teams_mst WHERE operator_id=? AND id=?", String.class, tenant, reviewerTeam);
        UUID maintenanceTeam = UUID.randomUUID();
        String maintenanceTeamName = "Report Maintenance " + maintenanceTeam.toString().substring(0, 6);
        jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name,team_type) VALUES(?,?,?,'MAINTENANCE')", maintenanceTeam, tenant, maintenanceTeamName);
        jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name) VALUES(?,?,?),(?,?,?),(?,?,?)",
            tenant, reviewer, reviewerTeamName, tenant, combined, reviewerTeamName, tenant, maintenance, maintenanceTeamName);
        addDefect("REPORTER_OWN", reporter, "GALLEY", "REPORTED", null, null, Instant.parse("2026-10-10T10:00:00Z"));
        addDefect("REPORTER_OTHER", ordinary, "GALLEY", "REPORTED", null, null, Instant.parse("2026-10-10T11:00:00Z"));
        addDefect("REVIEWER_CASE", ordinary, "GALLEY", "REPORTED", reviewerTeam, null, Instant.parse("2026-10-10T12:00:00Z"));
        addDefect("MAINTENANCE_CASE", ordinary, "GALLEY", "ACTION_ASSIGNED", null, maintenanceTeamName, Instant.parse("2026-10-10T13:00:00Z"));
        addDefect("CURRENT_TENANT_CASE", ordinary, "GALLEY", "REPORTED", null, null, Instant.parse("2026-10-10T14:00:00Z"));

        assertListParity(reporter);
        assertListParity(reviewer);
        assertListParity(maintenance);
        assertListParity(combined);
        assertListParity(broad);
        assertListParity(ordinary);
    }

    private void assertListParity(String actor) {
        // Resolve the tenant role assigned to this fixture actor, as the production controller does.
        String role = jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=?", String.class, tenant, actor);
        Set<Permission> grants = permissions.permissionsFor(tenant, role);
        boolean viewAll = PermissionService.hasDefectViewAll(grants);
        boolean teamScoped = !viewAll && permissions.isMaintenanceTeamScoped(tenant, role);
        boolean reporterScoped = !viewAll && permissions.isReporterScoped(tenant, role);
        boolean reviewerScoped = !viewAll && permissions.isReviewerTeamScoped(tenant, role);
        DefectPage expected = defects.list(tenant, actor, null, null, null, "ALL", null, null, null,
            reporterScoped, teamScoped, reviewerScoped, null, 100);
        Set<String> expectedIds = expected.items().stream().map(DefectView::id).map(UUID::toString).collect(java.util.stream.Collectors.toSet());
        Set<String> actualIds = call(tenant, actor, role, Map.of(), 0, 100, "[]").stream()
            .filter(row -> row.rowId()!=null).map(Result::rowId).collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedIds, actualIds, "Report visibility must match DefectService.list for role " + role);
    }

    private UUID addDefect(String reference, String reporter, String location, String status,
            UUID reviewerTeamId, String assignedTeam, Instant reportedAt) {
        UUID id = UUID.randomUUID();
        UUID defectArea = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code=?", UUID.class, tenant, location);
        jdbc.update("""
            INSERT INTO defects_dtl(id,operator_id,aircraft_id,reference,location,category,area_id,zone,title,description,
              component,condition_name_snapshot,severity,status,reported_by,assigned_team,reviewer_team_id,reported_at,updated_at,isactive)
            VALUES(?,?,?,?,?, ?,?,?,?, ?,?,?, 'LOW',?,?,?,?,?,now(),1)
            """, id, tenant, aircraft, reference, location, location, defectArea, "G1", "Test " + reference,
            "Synthetic report fixture", "Latch", "Broken", status, reporter, assignedTeam, reviewerTeamId, reportedAt);
        return id;
    }

    private void addRoleAndMember(String actor, String rolePermissions) {
        String role = actor.substring(0, Math.min(actor.indexOf('-'), 48)).toUpperCase(java.util.Locale.ROOT) + "_TEST";
        jdbc.update("INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,isactive) VALUES(?,?,?,?,1)", tenant, role, role, rolePermissions);
        jdbc.update("INSERT INTO operator_memberships_mst(operator_id,external_user_id,display_name,role,isactive) VALUES(?,?,?,?,1)", tenant, actor, actor, role);
    }

    private List<Result> call(UUID tenantId, String actor, String role, Map<String,Object> filters,
            int page, int size, String sortJson) {
        try {
            String filtersJson = JSON.writeValueAsString(filters);
            return jdbc.query("SELECT total_rows,row_id,row_data FROM " + REPORT_FN + "(?,?,?,?,?::jsonb,?,?,?::jsonb)",
                (rs,n) -> {
                    String row = rs.getString("row_data");
                    JsonNode rowData = null;
                    if (row != null) {
                        try { rowData = JSON.readTree(row); }
                        catch (java.io.IOException ex) { throw new IllegalStateException("Malformed row JSON", ex); }
                    }
                    return new Result(rs.getLong("total_rows"), rs.getString("row_id"), rowData);
                }, tenantId, actor, role, "DEFECT_VISIBLE_SCOPE_V1", filtersJson, page, size, sortJson);
        } catch (Exception ex) { throw new AssertionError("Report function invocation failed", ex); }
    }

    private Set<String> rowIds(List<Result> results) {
        return results.stream().filter(row -> row.rowId() != null).map(Result::rowId)
            .collect(java.util.stream.Collectors.toSet());
    }
}
