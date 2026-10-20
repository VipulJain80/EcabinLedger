package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.ReportMetadataController;
import com.ecabin.ledger.api.ReportAdministrationModels.*;
import com.ecabin.ledger.api.ReportMetadataModels.ReportDefinition;
import com.ecabin.ledger.api.ReportApiException;
import com.ecabin.ledger.api.ReportQueryModels.QueryRequest;
import com.ecabin.ledger.api.ReportQueryModels.ExportRequest;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.util.zip.ZipFile;
import org.slf4j.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Disposable PostgreSQL coverage for report metadata constraints, authorization, and administration. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://gateway.invalid/",
    "app.security.audience=ecabin-test-api"
})
class ReportMetadataPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportMetadataService reports;
    @Autowired ReportAdministrationService administration;
    @Autowired ReportQueryService reportQueries;
    @Autowired ReportExportService reportExports;
    @Autowired CurrentUserResolver users;
    private UUID tenant;

    @BeforeEach
    void createTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO operators_mst(id,name) VALUES(?,?)", tenant, "Report test " + tenant);
    }

    @Test
    void migrationFromV49CreatesReportingMetadataAndGrantsAdminApplicationPermissionsWithoutReportGrants() {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='50' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='51' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='52' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='53' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='54' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='55' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='56' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='57' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='58' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='59' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='60' AND success", Integer.class));
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('49','50','51','52','53','54','55','56','57','58','59','60') AND success", Integer.class),
            "The reporting migrations must apply after the existing V49 schema without rewriting it.");
        assertEquals("REPORT_VIEW|ADMINISTER_ORGANIZATION", jdbc.queryForObject("SELECT required_permissions FROM navigation_menu_items_mst WHERE operator_id=? AND menu_key='reports' AND parent_id IS NULL", String.class, tenant));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='report_query_events_mst' AND column_name='exported_row_count'", Integer.class));
        assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name IN ('report_routines_mst','report_routine_parameters_mst','report_templates_mst','report_template_filters_mst','report_template_columns_mst','report_lookup_sources_mst','reports_mst','report_filters_mst','report_columns_mst','report_role_access_mst','report_query_events_mst')", Integer.class));
        for (String code : new String[] {"REPORT_VIEW", "REPORT_EXECUTE", "REPORT_EXPORT"}) {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM permissions_mst WHERE permission_code=?", Integer.class, code));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM permissions_mst WHERE permission_code='REPORT_EXPORT' AND lower(description) LIKE '%allow%' AND lower(description) LIKE '%not%' AND btrim(module)<>''", Integer.class),
            "The report export permission must describe both its allowance and its limitation.");
        String adminGrant = jdbc.queryForObject("SELECT permissions FROM operator_roles_mst WHERE operator_id=? AND role_key='ADMIN'", String.class, tenant);
        assertTrue(Arrays.asList(adminGrant.split("\\|")).containsAll(List.of("REPORT_VIEW", "REPORT_EXECUTE", "REPORT_EXPORT")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reports_mst WHERE operator_id=?", Integer.class, tenant));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM report_role_access_mst WHERE operator_id=?", Integer.class, tenant));
    }

    @Test
    void anAuthorizedRoleReadsItsTenantReportAndFilterAndColumnMetadata() {
        String role = createRole("REPORT_READER", "REPORT_VIEW");
        UUID report = createReport(tenant, "FLEET_OVERVIEW", 1, true, true);
        grant(tenant, report, role, true, false, false);
        jdbc.update("INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,configuration) VALUES(?,?, 'fromDate','From date','DATE','p_from_date','{}'::jsonb)", tenant, report);
        jdbc.update("INSERT INTO report_columns_mst(operator_id,report_id,column_key,label,data_type,sort_order,is_sortable) VALUES(?,?, 'defectId','Defect ID','TEXT',10,true)", tenant, report);

        ReportDefinition metadata = reports.report(report, user(tenant, role));

        assertEquals("FLEET_OVERVIEW", metadata.reportCode());
        assertEquals("fromDate", metadata.filters().get(0).filterKey());
        assertEquals("DATE", metadata.filters().get(0).filterType());
        assertEquals("defectId", metadata.columns().get(0).columnKey());
        JsonNode serialized = new ObjectMapper().valueToTree(metadata);
        assertFalse(serialized.toString().contains("p_from_date"), "Internal routine parameter names must not be exposed.");
        assertFalse(serialized.toString().contains("routine"), "Internal routine configuration must not be exposed.");
    }

    @Test
    void emptyListIsReturnedWhenTheRoleHasNoEnabledReportGrant() {
        String role = createRole("EMPTY_REPORT_READER", "REPORT_VIEW");
        assertTrue(reports.listReports(user(tenant, role)).isEmpty());
    }

    @Test
    void missingApplicationPermissionDeniesListEvenWhenReportGrantExists() {
        String role = createRole("VIEW_ONLY", "VIEW");
        UUID report = createReport(tenant, "RESTRICTED_REPORT", 1, true, true);
        grant(tenant, report, role, true, false, false);
        assertThrows(AccessDeniedException.class, () -> reports.listReports(user(tenant, role)));
    }

    @Test
    void missingReportLevelGrantHidesReportFromListAndDetail() {
        String role = createRole("REPORT_VIEW_NO_GRANT", "REPORT_VIEW");
        UUID report = createReport(tenant, "HIDDEN_REPORT", 1, true, true);
        assertTrue(reports.listReports(user(tenant, role)).isEmpty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> reports.report(report, user(tenant, role)));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void reportQueryRequiresBothApplicationPermissionsAndThePerReportExecuteGrant() {
        String viewOnly = createRole("QUERY_VIEW_ONLY", "REPORT_VIEW");
        UUID report = createReport(tenant, "QUERY_VIEW_ONLY_REPORT", 1, true, true);
        grant(tenant, report, viewOnly, true, true, false);
        assertThrows(AccessDeniedException.class,
            () -> reportQueries.query(report, new QueryRequest(Map.of(), 0, 25, List.of()), user(tenant, viewOnly), UUID.randomUUID()));

        String executeOnly = createRole("QUERY_EXECUTE_ONLY", "REPORT_EXECUTE");
        UUID secondReport = createReport(tenant, "QUERY_EXECUTE_ONLY_REPORT", 1, true, true);
        grant(tenant, secondReport, executeOnly, true, true, false);
        assertThrows(AccessDeniedException.class,
            () -> reportQueries.query(secondReport, new QueryRequest(Map.of(), 0, 25, List.of()), user(tenant, executeOnly), UUID.randomUUID()));

        String noExecuteGrant = createRole("QUERY_NO_EXECUTE_GRANT", "REPORT_VIEW|REPORT_EXECUTE");
        UUID thirdReport = createReport(tenant, "QUERY_NO_EXECUTE_GRANT_REPORT", 1, true, true);
        grant(tenant, thirdReport, noExecuteGrant, true, false, false);
        assertEquals(404, assertThrows(ReportApiException.class,
            () -> reportQueries.query(thirdReport, new QueryRequest(Map.of(), 0, 25, List.of()), user(tenant, noExecuteGrant), UUID.randomUUID())).status().value());
    }

    @Test
    void authorizedQueryRequiresReviewedRoutineAndAuditsWithoutStoringFilterValues() {
        String role = createRole("QUERY_READY_ROLE", "REPORT_VIEW|REPORT_EXECUTE");
        UUID report = createReport(tenant, "QUERY_BLOCKED_REPORT", 1, true, true);
        grant(tenant, report, role, true, true, false);
        UUID requestId = UUID.randomUUID();

        ReportApiException error = assertThrows(ReportApiException.class, () -> reportQueries.query(report,
            new QueryRequest(Map.of("search", "sensitive value"), 0, 25, List.of()), user(tenant, role), requestId));

        assertEquals("INVALID_REPORT_CONFIGURATION", error.code());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM report_query_events_mst WHERE operator_id=? AND report_id=? AND actor_id=? AND request_id=? AND event_type='QUERY' AND outcome='CONFIGURATION_BLOCKED' AND returned_row_count=0", Integer.class,
            tenant, report, "subject-" + role, requestId));
        assertFalse(jdbc.queryForObject("SELECT to_jsonb(e)::text FROM report_query_events_mst e WHERE request_id=?", String.class, requestId).contains("sensitive value"));
    }

    @Test
    void crossTenantReportQueryIsHiddenAndDoesNotCreateAnAuditRow() {
        String role = createRole("LOCAL_QUERY_ROLE", "REPORT_VIEW|REPORT_EXECUTE");
        UUID report = createReport(tenant, "CROSS_TENANT_QUERY", 1, true, true);
        grant(tenant, report, role, true, true, false);
        UUID otherTenant = createOtherTenant();
        String otherRole = createRole(otherTenant, "FOREIGN_QUERY_ROLE", "REPORT_VIEW|REPORT_EXECUTE");

        ReportApiException error = assertThrows(ReportApiException.class,
            () -> reportQueries.query(report, new QueryRequest(Map.of(), 0, 25, List.of()), user(otherTenant, otherRole), UUID.randomUUID()));

        assertEquals(404, error.status().value());
        assertEquals("not_found", error.code());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM report_query_events_mst WHERE operator_id=? AND report_id=?", Integer.class, otherTenant, report));
    }

    @Test
    void lookupValuesRequireVisibleReportFilterAndFailClosedWithoutApprovedProvider() {
        String role = createRole("LOOKUP_USER", "REPORT_VIEW");
        UUID report = createReport(tenant, "LOOKUP_VALUES_REPORT", 1, true, true);
        grant(tenant, report, role, true, false, false);
        UUID routine = jdbc.queryForObject("SELECT routine_id FROM reports_mst WHERE operator_id=? AND id=?", UUID.class, tenant, report);
        jdbc.update("UPDATE report_routines_mst SET routine_kind='LOOKUP' WHERE id=?", routine);
        UUID lookupId = UUID.randomUUID();
        jdbc.update("INSERT INTO report_lookup_sources_mst(id,lookup_key,routine_id,contract_version,is_active,value_type) VALUES(?,?,?,2,true,'TEXT')",
            lookupId, "AUTHORIZED_OPTIONS", routine);
        jdbc.update("INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,lookup_source_id) VALUES(?,?, 'option','Option','LOOKUP_SINGLE','p_option',?)",
            tenant, report, lookupId);
        UUID requestId = UUID.randomUUID();

        ReportApiException error = assertThrows(ReportApiException.class,
            () -> reportQueries.lookupOptions("AUTHORIZED_OPTIONS", user(tenant, role), requestId));

        assertEquals("INVALID_REPORT_CONFIGURATION", error.code());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM report_query_events_mst WHERE operator_id=? AND report_id=? AND event_type='LOOKUP' AND outcome='CONFIGURATION_BLOCKED' AND request_id=?", Integer.class,
            tenant, report, requestId));
    }

    @Test
    void lookupSourceNotUsedByAnyVisibleReportIsHidden() {
        String role = createRole("LOOKUP_WITHOUT_GRANT", "REPORT_VIEW");
        UUID report = createReport(tenant, "LOOKUP_PRIVATE_REPORT", 1, true, true);
        UUID lookupId = UUID.randomUUID();
        UUID routine = jdbc.queryForObject("SELECT routine_id FROM reports_mst WHERE operator_id=? AND id=?", UUID.class, tenant, report);
        jdbc.update("INSERT INTO report_lookup_sources_mst(id,lookup_key,routine_id,contract_version,is_active,value_type) VALUES(?,?,?,2,true,'TEXT')",
            lookupId, "HIDDEN_OPTIONS", routine);
        jdbc.update("INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,lookup_source_id) VALUES(?,?, 'option','Option','LOOKUP_SINGLE','p_option',?)",
            tenant, report, lookupId);
        assertEquals(404, assertThrows(ReportApiException.class,
            () -> reportQueries.lookupOptions("HIDDEN_OPTIONS", user(tenant, role), UUID.randomUUID())).status().value());
    }

    @Test
    void lookupUsedOnlyByAnotherTenantIsNotDiscoverable() {
        UUID otherTenant = createOtherTenant();
        String otherRole = createRole(otherTenant, "FOREIGN_LOOKUP_ROLE", "REPORT_VIEW");
        UUID report = createReport(otherTenant, "FOREIGN_LOOKUP_REPORT", 1, true, true);
        grant(otherTenant, report, otherRole, true, false, false);
        UUID routine = jdbc.queryForObject("SELECT routine_id FROM reports_mst WHERE operator_id=? AND id=?", UUID.class, otherTenant, report);
        UUID lookup = UUID.randomUUID();
        jdbc.update("INSERT INTO report_lookup_sources_mst(id,lookup_key,routine_id,contract_version,is_active,value_type) VALUES(?,?,?,2,true,'TEXT')",
            lookup, "FOREIGN_TENANT_OPTIONS", routine);
        jdbc.update("INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,lookup_source_id) VALUES(?,?, 'option','Option','LOOKUP_SINGLE','p_option',?)",
            otherTenant, report, lookup);
        String localRole = createRole("LOCAL_LOOKUP_VIEWER", "REPORT_VIEW");

        ReportApiException error = assertThrows(ReportApiException.class,
            () -> reportQueries.lookupOptions("FOREIGN_TENANT_OPTIONS", user(tenant, localRole), UUID.randomUUID()));

        assertEquals(404, error.status().value());
        assertEquals("not_found", error.code());
    }

    @Test
    void registeredReportFunctionReturnsTypedRowsTotalsAndReporterScopedTenantData() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS report_test");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_test.rows (id uuid PRIMARY KEY, operator_id uuid NOT NULL, owner_id text NOT NULL, reference text NOT NULL, amount numeric NOT NULL)");
        jdbc.update("DELETE FROM report_test.rows WHERE operator_id=?", tenant);
        UUID otherTenant=createOtherTenant();
        String role=createRole("REPORTER_CONTRACT", "REPORT_VIEW|REPORT_EXECUTE|REPORT");
        String actor="subject-"+role;
        jdbc.update("INSERT INTO operator_memberships_mst(operator_id,external_user_id,display_name,role,isactive) VALUES(?,?,?,?,1)",tenant,actor,actor,role);
        jdbc.update("INSERT INTO report_test.rows(id,operator_id,owner_id,reference,amount) VALUES(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
            UUID.randomUUID(),tenant,actor,"OWN-1",new java.math.BigDecimal("12.5"),
            UUID.randomUUID(),tenant,"another-user","PRIVATE-2",new java.math.BigDecimal("99"),
            UUID.randomUUID(),otherTenant,actor,"FOREIGN-3",new java.math.BigDecimal("100"));
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION report_test.report_page(p_tenant uuid,p_actor text,p_role text,p_scope text,
                p_filters jsonb,p_page integer,p_size integer,p_sort jsonb)
            RETURNS TABLE(total_rows bigint,row_id text,row_data jsonb)
            LANGUAGE sql STABLE AS $fn$
              WITH role_data AS (
                SELECT permissions FROM operator_roles_mst WHERE operator_id=p_tenant AND role_key=p_role AND isactive=1
              ), visible AS (
                SELECT d.id,d.reference,d.amount FROM report_test.rows d,role_data rd
                WHERE d.operator_id=p_tenant AND p_scope='DEFECT_VISIBLE_SCOPE_V1'
                  AND (NOT ((('|'||rd.permissions||'|') LIKE '%|REPORT|%')
                    AND NOT ((('|'||rd.permissions||'|') LIKE '%|REVIEW|%') OR (('|'||rd.permissions||'|') LIKE '%|INSPECT|%')
                      OR (('|'||rd.permissions||'|') LIKE '%|ASSIGN_ACTION|%') OR (('|'||rd.permissions||'|') LIKE '%|PERFORM_ACTION|%')
                      OR (('|'||rd.permissions||'|') LIKE '%|VERIFY|%') OR (('|'||rd.permissions||'|') LIKE '%|APPROVE|%')
                      OR (('|'||rd.permissions||'|') LIKE '%|CLOSE|%') OR (('|'||rd.permissions||'|') LIKE '%|REOPEN|%')
                      OR (('|'||rd.permissions||'|') LIKE '%|ADMINISTER_ORGANIZATION|%'))) OR d.owner_id=p_actor)
              ), counted AS (SELECT count(*)::bigint n FROM visible), page_rows AS (
                SELECT v.* FROM visible v ORDER BY v.id OFFSET (p_page*p_size) LIMIT p_size
              )
              SELECT c.n,p.id::text,jsonb_build_object('reference',p.reference,'amount',p.amount)
                FROM counted c CROSS JOIN page_rows p
              UNION ALL
              SELECT c.n,NULL::text,NULL::jsonb FROM counted c WHERE NOT EXISTS(SELECT 1 FROM page_rows)
            $fn$
            """);
        UUID routine=UUID.randomUUID();
        jdbc.update("INSERT INTO report_routines_mst(id,routine_key,schema_name,function_name,contract_version,is_active,routine_kind,scope_policy) VALUES(?,?,'report_test','report_page',2,true,'REPORT','DEFECT_VISIBLE_SCOPE_V1')",
            routine,"TEST_REPORT_PAGE_"+routine.toString().replace("-",""));
        jdbc.update("INSERT INTO report_routine_parameters_mst(routine_id,contract_version,parameter_key,parameter_type,is_required,is_filterable) VALUES(?,2,'p_from','DATE',false,true),(?,2,'p_to','DATE',false,true)",routine,routine);
        UUID report=UUID.randomUUID();
        jdbc.update("INSERT INTO reports_mst(id,operator_id,report_code,definition_version,name,routine_id,is_active,is_current) VALUES(?,?, 'TEST_PAGE',1,'Test page',?,true,true)",report,tenant,routine);
        grant(tenant,report,role,true,true,false);
        jdbc.update("INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,configuration) VALUES(?,?, 'fromDate','From date','DATE','p_from','{\"rangeGroup\":\"reportingPeriod\",\"rangeBound\":\"FROM\"}'::jsonb),(?,?, 'toDate','To date','DATE','p_to','{\"rangeGroup\":\"reportingPeriod\",\"rangeBound\":\"TO\"}'::jsonb)",tenant,report,tenant,report);
        jdbc.update("INSERT INTO report_columns_mst(operator_id,report_id,column_key,label,data_type,sort_order,is_visible,is_sortable) VALUES(?,?, 'reference','Reference','TEXT',1,true,true),(?,?, 'amount','Amount','NUMBER',2,true,true)",tenant,report,tenant,report);

        ReportApiException reversedRange=assertThrows(ReportApiException.class,()->reportQueries.query(report,
            new QueryRequest(Map.of("fromDate","2026-10-20","toDate","2026-10-19"),0,1,List.of()),
            new AuthenticatedUser(tenant,actor,actor,role),UUID.randomUUID()));
        assertEquals("INVALID_REPORT_FILTER",reversedRange.code());

        var result=reportQueries.query(report,new QueryRequest(Map.of("fromDate","2026-10-01","toDate","2026-10-31"),0,1,List.of()),new AuthenticatedUser(tenant,actor,actor,role),UUID.randomUUID());

        assertEquals(1,result.totalElements());
        assertEquals(1,result.totalPages());
        assertEquals(1,result.rows().size());
        assertEquals("OWN-1",result.rows().get(0).get("reference"));
        assertNotNull(result.rows().get(0).get("id"));
        assertEquals(List.of("reference","amount"),result.columns().stream().map(c->c.key()).toList());
    }

    @Test
    void lookupProviderReturnsBoundedOptionsToViewGrantedUsersWithoutExecuteGrant() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS report_test");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_test.lookup_values (operator_id uuid NOT NULL, option_value text NOT NULL, display_label text NOT NULL)");
        jdbc.update("DELETE FROM report_test.lookup_values WHERE operator_id=?",tenant);
        jdbc.update("INSERT INTO report_test.lookup_values(operator_id,option_value,display_label) VALUES(?,?,?),(?,?,?)",
            tenant,"OPEN","Open",tenant,"CLOSED","Closed");
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION report_test.lookup_values(p_tenant uuid,p_actor text,p_role text,p_scope text,p_max integer)
            RETURNS TABLE(option_value text,display_label text) LANGUAGE sql STABLE AS $fn$
              SELECT v.option_value,v.display_label FROM report_test.lookup_values v
              WHERE v.operator_id=p_tenant AND p_scope='DEFECT_VISIBLE_SCOPE_V1'
              ORDER BY v.option_value LIMIT p_max
            $fn$
            """);
        String role=createRole("LOOKUP_VIEW_ONLY","REPORT_VIEW");
        UUID report=createReport(tenant,"LOOKUP_PROVIDER_REPORT",1,true,true);grant(tenant,report,role,true,false,false);
        UUID routine=UUID.randomUUID();jdbc.update("INSERT INTO report_routines_mst(id,routine_key,schema_name,function_name,contract_version,is_active,routine_kind,scope_policy) VALUES(?,?,'report_test','lookup_values',2,true,'LOOKUP','DEFECT_VISIBLE_SCOPE_V1')",routine,"TEST_LOOKUP_"+routine.toString().replace("-",""));
        UUID lookup=UUID.randomUUID();jdbc.update("INSERT INTO report_lookup_sources_mst(id,lookup_key,routine_id,contract_version,is_active,value_type,max_results) VALUES(?,?,?,2,true,'TEXT',10)",lookup,"TEST_LOOKUP_"+lookup.toString().replace("-",""),routine);
        jdbc.update("INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,lookup_source_id) VALUES(?,?, 'state','State','LOOKUP_SINGLE','p_state',?)",tenant,report,lookup);

        var options=reportQueries.lookupOptions("TEST_LOOKUP_"+lookup.toString().replace("-",""),user(tenant,role),UUID.randomUUID());

        assertEquals(List.of("CLOSED","OPEN"),options.stream().map(value->value.value()).toList());
        assertEquals(List.of("Closed","Open"),options.stream().map(value->value.label()).toList());
    }

    @Test
    void crossTenantReportIdsAreIndistinguishableFromMissingReports() {
        UUID otherTenant = createOtherTenant();
        String otherRole = createRole(otherTenant, "OTHER_REPORT_READER", "REPORT_VIEW");
        String localRole = createRole("LOCAL_REPORT_READER", "REPORT_VIEW");
        UUID report = createReport(tenant, "TENANT_PRIVATE", 1, true, true);
        grant(tenant, report, localRole, true, false, false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> reports.report(report, user(otherTenant, otherRole)));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void inactiveAndNonCurrentDefinitionsAreExcluded() {
        String role = createRole("CURRENT_REPORT_READER", "REPORT_VIEW");
        UUID active = createReport(tenant, "ACTIVE_REPORT", 2, true, true);
        UUID inactive = createReport(tenant, "INACTIVE_REPORT", 1, false, true);
        UUID oldVersion = createReport(tenant, "VERSIONED_REPORT", 1, true, false);
        grant(tenant, active, role, true, false, false);
        grant(tenant, inactive, role, true, false, false);
        grant(tenant, oldVersion, role, true, false, false);

        var listed = reports.listReports(user(tenant, role));
        assertEquals(1, listed.size());
        assertEquals(active, listed.get(0).id());
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> reports.report(inactive, user(tenant, role))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> reports.report(oldVersion, user(tenant, role))).getStatusCode().value());
    }

    @Test
    void administratorCanReadActiveTemplateMetadataButAdministerOrganizationDoesNotGrantReportAccess() {
        UUID routine = createRoutine(tenant, "TEMPLATE_ROUTINE", true);
        UUID template = UUID.randomUUID();
        jdbc.update("INSERT INTO report_templates_mst(id,template_code,template_version,name,routine_id,is_active) VALUES(?,?,1,?,?,true)",
            template, "TEMPLATE_TEST", "Template test", routine);
        jdbc.update("INSERT INTO report_template_filters_mst(template_id,template_version,filter_key,label,filter_type,parameter_key,configuration) VALUES(?,1,'fromDate','From date','DATE','p_from_date','{}'::jsonb)", template);
        assertEquals("fromDate", reports.activeTemplates(user(tenant, "ADMIN")).get(0).filters().get(0).filterKey());

        UUID report = createReport(tenant, "ADMIN_PRIVATE_REPORT", 1, true, true);
        grant(tenant, report, "ADMIN", true, false, false);
        assertThrows(AccessDeniedException.class, () -> reports.listReports(user(tenant, "ADMIN")));
        assertThrows(AccessDeniedException.class, () -> reports.activeTemplates(user(tenant, "VIEWER")));
    }

    @Test
    void tenantSafeForeignKeysAndPermissionImplicationsAreEnforced() {
        UUID secondTenant = createOtherTenant();
        String reader = createRole("FK_READER", "REPORT_VIEW");
        String secondReader = createRole(secondTenant, "FK_READER", "REPORT_VIEW");
        UUID report = createReport(tenant, "FK_REPORT", 1, true, true);

        assertThrows(DataIntegrityViolationException.class,
            () -> grant(secondTenant, report, secondReader, true, false, false));
        assertThrows(DataIntegrityViolationException.class,
            () -> grant(tenant, report, reader, false, true, false));
        assertThrows(DataIntegrityViolationException.class,
            () -> grant(tenant, report, reader, true, false, true));
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO report_template_filters_mst(template_id,template_version,filter_key,label,filter_type,parameter_key,configuration) VALUES(?,1,'badSelect','Bad select','SINGLE_SELECT','p_bad','{}'::jsonb)", UUID.randomUUID()));
        assertThrows(DataIntegrityViolationException.class,
            () -> grant(tenant, report, "NO_SUCH_TENANT_ROLE", true, false, false));
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO report_routines_mst(routine_key,schema_name,function_name,contract_version,timeout_ms) VALUES('BAD_TIMEOUT','reporting','bad_timeout',1,0)"));
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO report_routines_mst(routine_key,schema_name,function_name,contract_version,max_page_size) VALUES('BAD_PAGE_SIZE','reporting','bad_page_size',1,501)"));
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO reports_mst(operator_id,report_code,definition_version,name,routine_id,source_template_id) VALUES(?, 'UNPAIRED_SOURCE',1,'Unpaired source',(SELECT id FROM report_routines_mst WHERE routine_key=?),?)",
                tenant, "ROUTINE_FK_REPORT_1", UUID.randomUUID()));

        UUID sourceRoutine = createRoutine(tenant, "TEMPLATE_SOURCE_ROUTINE", false);
        UUID sourceTemplate = UUID.randomUUID();
        jdbc.update("INSERT INTO report_templates_mst(id,template_code,template_version,name,routine_id) VALUES(?,?,1,?,?)",
            sourceTemplate, "DUPLICATE_SOURCE_TEMPLATE", "Duplicate source", sourceRoutine);
        jdbc.update("INSERT INTO reports_mst(operator_id,report_code,definition_version,name,routine_id,source_template_id,source_template_version,is_current) VALUES(?, 'SOURCE_COPY_ONE',1,'Copy one',?,?,1,false)",
            tenant, sourceRoutine, sourceTemplate);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO reports_mst(operator_id,report_code,definition_version,name,routine_id,source_template_id,source_template_version,is_current) VALUES(?, 'SOURCE_COPY_TWO',1,'Copy two',?,?,1,false)",
            tenant, sourceRoutine, sourceTemplate));
    }

    @Test
    void enablingATemplateCopiesTheSelectedVersionAndNeverCreatesRoleGrants() {
        UUID routine = createRoutine(tenant, "APPROVED_TEMPLATE_ROUTINE", true);
        UUID template = UUID.randomUUID();
        jdbc.update("INSERT INTO report_templates_mst(id,template_code,template_version,name,description,routine_id,is_active,sort_order) VALUES(?,?,2,?,?,?,true,20)",
            template, "TENANT_FLEET_TEMPLATE", "Fleet template v2", "Approved template description", routine);
        jdbc.update("INSERT INTO report_template_filters_mst(template_id,template_version,filter_key,label,filter_type,parameter_key,is_required,sort_order,configuration) VALUES(?,2,'fromDate','From date','DATE','p_from_date',true,5,'{}'::jsonb)", template);
        jdbc.update("INSERT INTO report_template_columns_mst(template_id,template_version,column_key,label,data_type,sort_order,is_sortable) VALUES(?,2,'defectId','Defect ID','TEXT',5,true)", template);

        AuthenticatedUser admin = adminUser(tenant);
        UUID requestId = UUID.randomUUID();
        MDC.put("requestId", requestId.toString());
        ReportAdminDetail enabled;
        try { enabled = administration.enableTemplate(template, 2, new EnableTemplateRequest(null), admin); }
        finally { MDC.remove("requestId"); }

        assertEquals("TENANT_FLEET_TEMPLATE", enabled.report().reportCode());
        assertEquals(template, enabled.report().sourceTemplateId());
        assertEquals(2, enabled.report().sourceTemplateVersion());
        assertEquals(routine, enabled.routineId());
        assertFalse(enabled.report().active());
        assertEquals("p_from_date", enabled.filters().get(0).parameterKey());
        assertEquals("defectId", enabled.columns().get(0).columnKey());
        assertTrue(enabled.roleAccess().isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM report_role_access_mst WHERE operator_id=? AND report_id=?", Integer.class, tenant, enabled.report().id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='REPORT_DEFINITION' AND entity_id=? AND event_type='REPORT_TEMPLATE_ENABLED' AND actor_id=? AND request_id=?", Integer.class,
            tenant, enabled.report().id(), admin.externalUserId(), requestId));
        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
            () -> administration.enableTemplate(template, 2, null, adminUser(tenant)));
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reports_mst WHERE operator_id=? AND source_template_id=? AND source_template_version=2", Integer.class, tenant, template));
    }

    @Test
    void administratorCanCreateEditActivateAndAuditAConfiguredReportWithOptimisticVersioning() {
        AuthenticatedUser admin = adminUser(tenant);
        UUID routine = createRoutine(tenant, "CUSTOM_REGISTERED_ROUTINE", true);
        CreateReportRequest create = new CreateReportRequest("CUSTOM_FLEET", "Fleet report", "Safe report description", routine, 30,
            List.of(new FilterInput("searchText", "Search text", "TEXT", "p_search_text", false, 10, Map.of("maxLength", 120, "placeholder", "Find a case"), null)),
            List.of(new ColumnInput("reference", "Defect ID", "TEXT", 10, true, true, true, Map.of())));

        ReportAdminDetail created = administration.create(create, admin);
        assertEquals(0, created.report().version());
        assertFalse(created.report().active());
        assertTrue(created.roleAccess().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_id=? AND event_type='REPORT_CREATED' AND actor_id=?", Integer.class,
            tenant, created.report().id(), admin.externalUserId()));

        UpdateReportRequest update = new UpdateReportRequest(0L, "Fleet report updated", null, 35,
            List.of(new FilterInput("searchText", "Search text", "TEXT", "p_search_text", false, 10, Map.of("maxLength", 120, "placeholder", "Find a case"), null)),
            List.of(new ColumnInput("reference", "Case ID", "TEXT", 10, true, true, true, Map.of())));
        ReportAdminDetail updated = administration.update(created.report().id(), update, admin);
        assertEquals(1, updated.report().version());
        assertEquals("Case ID", updated.columns().get(0).label());

        ResponseStatusException stale = assertThrows(ResponseStatusException.class,
            () -> administration.update(created.report().id(), update, admin));
        assertEquals(409, stale.getStatusCode().value());

        ReportAdminDetail activated = administration.setActive(created.report().id(), new ActivationRequest(1L, true), admin);
        assertTrue(activated.report().active());
        assertEquals(2, activated.report().version());
        ReportAdminDetail deactivated = administration.setActive(created.report().id(), new ActivationRequest(2L, false), admin);
        assertFalse(deactivated.report().active());
        assertEquals(3, deactivated.report().version());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_id=? AND event_type='REPORT_UPDATED'", Integer.class,
            tenant, created.report().id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_id=? AND event_type='REPORT_ACTIVATED'", Integer.class,
            tenant, created.report().id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_id=? AND event_type='REPORT_DEACTIVATED'", Integer.class,
            tenant, created.report().id()));
    }

    @Test
    void reportAdministrationRejectsUnauthorizedUsersInactiveRoutinesAndUnsafeMetadata() {
        AuthenticatedUser admin = adminUser(tenant);
        UUID activeRoutine = createRoutine(tenant, "VALIDATED_ROUTINE", true);
        UUID inactiveRoutine = createRoutine(tenant, "INACTIVE_ROUTINE", false);
        UUID validationRoutine = createRoutine(tenant, "VALIDATION_ONLY_ROUTINE", true);
        CreateReportRequest valid = new CreateReportRequest("SAFE_REPORT", "Safe report", null, activeRoutine, 1,
            List.of(new FilterInput("status", "Status", "SINGLE_SELECT", "p_status", false, 1,
                Map.of("options", List.of(Map.of("value", "OPEN", "label", "Open"))), null)),
            List.of(new ColumnInput("reference", "Reference", "TEXT", 1, true, true, false, Map.of())));
        AuthenticatedUser nonAdmin = addTenantUser(tenant, "REPORT_CONFIG_READER", "REPORT_VIEW");
        assertThrows(AccessDeniedException.class, () -> administration.create(valid, nonAdmin));

        CreateReportRequest inactive = new CreateReportRequest("INACTIVE_SOURCE", "Inactive source", null, inactiveRoutine, 1, valid.filters(), valid.columns());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> administration.create(inactive, admin)).getStatusCode().value());
        ReportAdminDetail existing = administration.create(new CreateReportRequest("DISABLED_ROUTINE", "Disabled later", null, activeRoutine, 1, List.of(), valid.columns()), admin);
        jdbc.update("UPDATE report_routines_mst SET is_active=false WHERE id=?", activeRoutine);
        ResponseStatusException inactiveAtActivation = assertThrows(ResponseStatusException.class,
            () -> administration.setActive(existing.report().id(), new ActivationRequest(0L, true), admin));
        assertEquals(409, inactiveAtActivation.getStatusCode().value());
        CreateReportRequest unsafe = new CreateReportRequest("UNSAFE_CONFIG", "Unsafe configuration", null, validationRoutine, 1,
            List.of(new FilterInput("searchText", "Search", "TEXT", "p_search", false, 1, Map.of("sql", "select 1"), null)), valid.columns());
        assertThrows(IllegalArgumentException.class, () -> administration.create(unsafe, admin));
        CreateReportRequest mismatchedParameter = new CreateReportRequest("TYPE_MISMATCH", "Type mismatch", null, validationRoutine, 1,
            List.of(new FilterInput("searchText", "Search", "TEXT", "p_from_date", false, 1, Map.of(), null)), valid.columns());
        assertThrows(IllegalArgumentException.class, () -> administration.create(mismatchedParameter, admin));
        CreateReportRequest duplicateColumns = new CreateReportRequest("DUPLICATE_COLUMNS", "Duplicate", null, validationRoutine, 1, List.of(),
            List.of(new ColumnInput("reference", "Reference", "TEXT", 1, true, true, true, Map.of()), new ColumnInput("reference", "Other", "TEXT", 2, true, false, false, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> administration.create(duplicateColumns, admin));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reports_mst WHERE operator_id=? AND report_code IN ('INACTIVE_SOURCE','UNSAFE_CONFIG','DUPLICATE_COLUMNS')", Integer.class, tenant));
    }

    @Test
    void roleAccessIsTenantScopedAndRequiresViewBeforeExecutionAndExport() {
        AuthenticatedUser admin = adminUser(tenant);
        UUID routine = createRoutine(tenant, "ROLE_GRANT_ROUTINE", true);
        ReportAdminDetail created = administration.create(new CreateReportRequest("ACCESS_REPORT", "Access report", null, routine, 1,
            List.of(), List.of(new ColumnInput("reference", "Reference", "TEXT", 1, true, true, true, Map.of()))), admin);
        UUID otherTenant = createOtherTenant();
        addTenantUser(otherTenant, "FOREIGN_REPORT_ROLE", "REPORT_VIEW");
        RoleAccessRequest foreignRole = new RoleAccessRequest(0L, List.of(new RoleGrantInput("FOREIGN_REPORT_ROLE", true, false, false)));
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> administration.replaceRoleAccess(created.report().id(), foreignRole, admin)).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> administration.replaceRoleAccess(created.report().id(),
            new RoleAccessRequest(0L, List.of(new RoleGrantInput("REPORT_ADMIN", false, true, false))), admin));
        assertThrows(IllegalArgumentException.class, () -> administration.replaceRoleAccess(created.report().id(),
            new RoleAccessRequest(0L, List.of(new RoleGrantInput("REPORT_ADMIN", true, false, true))), admin));

        addTenantUser(tenant, "REPORT_READER", "REPORT_VIEW");
        ReportAdminDetail granted = administration.replaceRoleAccess(created.report().id(),
            new RoleAccessRequest(0L, List.of(new RoleGrantInput("REPORT_READER", true, true, true))), admin);
        assertEquals(1, granted.report().version());
        assertEquals("REPORT_READER", granted.roleAccess().get(0).roleKey());
        assertTrue(granted.roleAccess().get(0).canExport());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_id=? AND event_type='REPORT_ACCESS_UPDATED'", Integer.class,
            tenant, created.report().id()));
    }

    @Test
    void lookupFiltersMustUseAnActiveRegisteredSourceAndCompatibleApprovedParameter() {
        AuthenticatedUser admin = adminUser(tenant);
        UUID routine = createRoutine(tenant, "LOOKUP_FILTER_ROUTINE", true);
        UUID lookupId = UUID.randomUUID();
        UUID lookupRoutine=createRoutine(tenant, "ACTIVE_LOOKUP_ROUTINE", true);
        jdbc.update("UPDATE report_routines_mst SET routine_kind='LOOKUP' WHERE id=?",lookupRoutine);
        jdbc.update("INSERT INTO report_lookup_sources_mst(id,lookup_key,routine_id,contract_version,is_active,value_type) VALUES(?,?,?,2,true,'TEXT')",
            lookupId, "ACTIVE_TEXT_LOOKUP", lookupRoutine);
        UUID inactiveLookup = UUID.randomUUID();
        UUID inactiveLookupRoutine=createRoutine(tenant, "INACTIVE_LOOKUP_ROUTINE", true);
        jdbc.update("UPDATE report_routines_mst SET routine_kind='LOOKUP' WHERE id=?",inactiveLookupRoutine);
        jdbc.update("INSERT INTO report_lookup_sources_mst(id,lookup_key,routine_id,contract_version,is_active,value_type) VALUES(?,?,?,2,false,'TEXT')",
            inactiveLookup, "INACTIVE_TEXT_LOOKUP", inactiveLookupRoutine);
        FilterInput active = new FilterInput("lookup", "Lookup", "LOOKUP_SINGLE", "p_search_text", false, 1, Map.of(), lookupId);
        ReportAdminDetail created = administration.create(new CreateReportRequest("LOOKUP_REPORT", "Lookup report", null, routine, 1,
            List.of(active), List.of(new ColumnInput("reference", "Reference", "TEXT", 1, true, true, true, Map.of()))), admin);
        assertEquals("ACTIVE_TEXT_LOOKUP", created.filters().get(0).lookupKey());
        CreateReportRequest inactive = new CreateReportRequest("INACTIVE_LOOKUP_REPORT", "Inactive lookup", null, routine, 1,
            List.of(new FilterInput("lookup", "Lookup", "LOOKUP_SINGLE", "p_search_text", false, 1, Map.of(), inactiveLookup)),
            List.of(new ColumnInput("reference", "Reference", "TEXT", 1, true, true, true, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> administration.create(inactive, admin));
    }

    @Test
    void adminReportListingIsTenantScopedSearchableAndPaginated() {
        AuthenticatedUser admin = adminUser(tenant);
        UUID routine = createRoutine(tenant, "LISTING_ROUTINE", true);
        for (int n = 0; n < 3; n++) administration.create(new CreateReportRequest("LIST_REPORT_" + n, "Fleet report " + n, null, routine, n,
            List.of(), List.of(new ColumnInput("reference", "Reference", "TEXT", 1, true, true, true, Map.of()))), admin);
        ReportPage page = administration.list(admin, 1, 1, "fleet", "INACTIVE");
        assertEquals(1, page.items().size());
        assertEquals(3, page.totalElements());
        assertFalse(page.items().get(0).active());
        UUID otherTenant = createOtherTenant();
        AuthenticatedUser otherAdmin = adminUser(otherTenant);
        UUID foreignRoutine = createRoutine(otherTenant, "FOREIGN_LIST_ROUTINE", true);
        ReportAdminDetail foreign = administration.create(new CreateReportRequest("FOREIGN_REPORT", "Fleet report", null, foreignRoutine, 1,
            List.of(), List.of(new ColumnInput("reference", "Reference", "TEXT", 1, true, true, true, Map.of()))), otherAdmin);
        assertEquals(0, administration.list(admin, 0, 25, "fleet", "ALL").items().stream().filter(row -> row.id().equals(foreign.report().id())).count());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> administration.get(foreign.report().id(), admin)).getStatusCode().value());
    }

    private AuthenticatedUser adminUser(UUID operatorId) {
        return addTenantUser(operatorId, adminUserId(operatorId), "ADMINISTER_ORGANIZATION");
    }

    private static String adminUserId(UUID operatorId) { return "report-admin-" + operatorId; }

    private AuthenticatedUser addTenantUser(UUID operatorId, String role, String grants) {
        String roleKey = role.matches("^[A-Z][A-Z0-9_]{0,47}$") ? role : "REPORT_ADMIN";
        jdbc.update("INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,system_role) VALUES(?,?,?,?,false) ON CONFLICT(operator_id,role_key) DO NOTHING",
            operatorId, roleKey, roleKey, grants);
        String subject = roleKey.equals("REPORT_ADMIN") ? adminUserId(operatorId) : "subject-" + roleKey + "-" + operatorId;
        jdbc.update("INSERT INTO operator_memberships_mst(operator_id,external_user_id,display_name,role,isactive) VALUES(?,?,?,?,1) ON CONFLICT(operator_id,external_user_id) DO NOTHING",
            operatorId, subject, subject, roleKey);
        return new AuthenticatedUser(operatorId, subject, subject, roleKey);
    }

    @Test
    void activeMembershipIsRequiredAtTheAuthenticatedBoundary() {
        String role = createRole("MEMBERSHIP_REPORT_READER", "REPORT_VIEW");
        String subject = "active-membership-" + UUID.randomUUID();
        jdbc.update("INSERT INTO operator_memberships_mst(operator_id,external_user_id,display_name,role,isactive) VALUES(?,?,?, ?,1)",
            tenant, subject, "Reader", role);
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "none").subject(subject)
            .claim("operator_id", tenant.toString()).build();
        assertEquals(role, users.require(new JwtAuthenticationToken(jwt)).role());
        jdbc.update("UPDATE operator_memberships_mst SET isactive=2 WHERE operator_id=? AND external_user_id=?", tenant, subject);
        assertThrows(AccessDeniedException.class, () -> users.require(new JwtAuthenticationToken(jwt)));
    }

    @Test
    void reportQueryLookupAndExportEndpointsAreRegistered() {
        var methods = Arrays.stream(com.ecabin.ledger.api.ReportQueryController.class.getDeclaredMethods()).toList();
        assertEquals(2, methods.stream().filter(method -> method.isAnnotationPresent(PostMapping.class)).count());
        assertEquals(1, methods.stream().filter(method -> method.isAnnotationPresent(GetMapping.class)).count());
        assertTrue(methods.stream().anyMatch(method -> method.getName().equals("export")));
    }

    @Test
    void exportRequiresAllApplicationAndReportGrantsAndProducesAllRowsAsXlsx() throws Exception {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS report_test");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_test.export_rows (id uuid PRIMARY KEY, operator_id uuid NOT NULL, value text NOT NULL)");
        jdbc.update("DELETE FROM report_test.export_rows WHERE operator_id=?", tenant);
        jdbc.update("INSERT INTO report_test.export_rows(id,operator_id,value) SELECT (md5('export-'||n)::uuid),?,('Row '||n) FROM generate_series(1,30) n", tenant);

        jdbc.execute("""
            CREATE OR REPLACE FUNCTION report_test.export_page(p_tenant uuid,p_actor text,p_role text,p_scope text,
                p_filters jsonb,p_page integer,p_size integer,p_sort jsonb)
            RETURNS TABLE(total_rows bigint,row_id text,row_data jsonb)
            LANGUAGE sql STABLE AS $fn$
              WITH visible AS (
                SELECT r.id,r.value FROM report_test.export_rows r
                WHERE r.operator_id=p_tenant AND p_scope='DEFECT_VISIBLE_SCOPE_V1'
              ), counted AS (SELECT count(*)::bigint n FROM visible), page_rows AS (
                SELECT v.* FROM visible v ORDER BY v.id OFFSET (p_page*p_size) LIMIT p_size
              )
              SELECT c.n,p.id::text,jsonb_build_object('value',p.value) FROM counted c CROSS JOIN page_rows p
              UNION ALL SELECT c.n,NULL::text,NULL::jsonb FROM counted c WHERE NOT EXISTS(SELECT 1 FROM page_rows)
            $fn$
            """);

        String role = createRole("REPORT_EXPORTER", "REPORT_VIEW|REPORT_EXECUTE|REPORT_EXPORT");
        UUID routine = UUID.randomUUID();
        jdbc.update("INSERT INTO report_routines_mst(id,routine_key,schema_name,function_name,contract_version,is_active,routine_kind,scope_policy) VALUES(?,?,'report_test','export_page',2,true,'REPORT','DEFECT_VISIBLE_SCOPE_V1')",
            routine, "TEST_EXPORT_" + routine.toString().replace("-", ""));
        UUID report = UUID.randomUUID();
        jdbc.update("INSERT INTO reports_mst(id,operator_id,report_code,definition_version,name,routine_id,is_active,is_current) VALUES(?,?, 'TEST_EXPORT',1,'Test export',?,true,true)", report, tenant, routine);
        grant(tenant, report, role, true, true, true);
        jdbc.update("INSERT INTO report_columns_mst(operator_id,report_id,column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable) VALUES(?,?, 'value','Value','TEXT',1,true,true,true)", tenant, report);
        AuthenticatedUser exporter = user(tenant, role);

        var plan = reportExports.prepare(report, new ExportRequest(Map.of(), List.of()), exporter, UUID.randomUUID());
        var file = reportExports.generate(plan);
        try (ZipFile workbook = new ZipFile(file.path().toFile())) {
            String worksheet = new String(workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(worksheet.contains("Value"));
            assertEquals(31, java.util.regex.Pattern.compile("<row ").matcher(worksheet).results().count(), "The export must include the header and all 30 rows across query pages.");
        } finally {
            Files.deleteIfExists(file.path());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM report_query_events_mst WHERE operator_id=? AND report_id=? AND event_type='EXPORT' AND outcome='SUCCESS' AND exported_row_count=30", Integer.class, tenant, report));

        String viewer = createRole("REPORT_EXPORT_VIEWER", "REPORT_VIEW|REPORT_EXECUTE");
        grant(tenant, report, viewer, true, true, false);
        assertThrows(AccessDeniedException.class, () -> reportExports.prepare(report,
            new ExportRequest(Map.of(), List.of()), user(tenant, viewer), UUID.randomUUID()));
    }

    private UUID createOtherTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operators_mst(id,name) VALUES(?,?)", id, "Other report tenant " + id);
        return id;
    }

    private String createRole(String key, String grants) { return createRole(tenant, key, grants); }
    private String createRole(UUID operatorId, String key, String grants) {
        jdbc.update("INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,system_role) VALUES(?,?,?,?,false)",
            operatorId, key, key, grants);
        return key;
    }

    private UUID createRoutine(UUID ignoredOperatorId, String key, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO report_routines_mst(id,routine_key,schema_name,function_name,contract_version,is_active,scope_policy) VALUES(?,?, 'reporting', ?,2,?,'DEFECT_VISIBLE_SCOPE_V1')",
            id, key, key.toLowerCase(), active);
        jdbc.update("INSERT INTO report_routine_parameters_mst(routine_id,contract_version,parameter_key,parameter_type,is_required,is_filterable) VALUES(?,2,'p_search_text','TEXT',false,true)", id);
        jdbc.update("INSERT INTO report_routine_parameters_mst(routine_id,contract_version,parameter_key,parameter_type,is_required,is_filterable) VALUES(?,2,'p_from_date','DATE',false,true)", id);
        jdbc.update("INSERT INTO report_routine_parameters_mst(routine_id,contract_version,parameter_key,parameter_type,is_required,is_filterable) VALUES(?,2,'p_status','TEXT',false,true)", id);
        return id;
    }

    private UUID createReport(UUID operatorId, String code, int version, boolean active, boolean current) {
        UUID routine = createRoutine(operatorId, "ROUTINE_" + code + "_" + version, false);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reports_mst(id,operator_id,report_code,definition_version,name,routine_id,is_active,is_current) VALUES(?,?,?,?,?,?,?,?)",
            id, operatorId, code, version, code, routine, active, current);
        return id;
    }

    private void grant(UUID operatorId, UUID reportId, String role, boolean view, boolean execute, boolean export) {
        jdbc.update("INSERT INTO report_role_access_mst(operator_id,report_id,role_key,can_view,can_execute,can_export) VALUES(?,?,?,?,?,?)",
            operatorId, reportId, role, view, execute, export);
    }

    private AuthenticatedUser user(UUID operatorId, String role) {
        String subject = "subject-" + role;
        jdbc.update("INSERT INTO operator_memberships_mst(operator_id,external_user_id,display_name,role,isactive) VALUES(?,?,?,?,1) ON CONFLICT(operator_id,external_user_id) DO NOTHING",
            operatorId, subject, subject, role);
        return new AuthenticatedUser(operatorId, subject, role, role);
    }
}
