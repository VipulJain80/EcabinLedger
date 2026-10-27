package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecabin.ledger.api.ApiModels.ActionCompletionRequest;
import com.ecabin.ledger.api.ApiModels.MaintenanceActionUpdateRequest;
import com.ecabin.ledger.api.ApiModels.MaintenanceSlaPolicyUpdate;
import com.ecabin.ledger.api.ApiModels.ApprovalRequest;
import com.ecabin.ledger.api.ApiModels.CreateDefectRequest;
import com.ecabin.ledger.api.ApiModels.CorrectiveActionRequest;
import com.ecabin.ledger.api.ApiModels.CorrectiveActionReassignmentRequest;
import com.ecabin.ledger.api.ApiModels.DefectView;
import com.ecabin.ledger.api.ApiModels.InspectionRequest;
import com.ecabin.ledger.api.ApiModels.TransitionRequest;
import com.ecabin.ledger.api.ApiModels.VerificationRequest;
import com.ecabin.ledger.api.ApiModels.ReviewerDecisionRequest;
import com.ecabin.ledger.api.FormModels.CreateFormRequest;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.SubmitFormRequest;
import com.ecabin.ledger.api.FormModels.TextRules;
import com.ecabin.ledger.api.FormModels.OptionSource;
import com.ecabin.ledger.api.FormModels.OptionFilter;
import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.WorkflowStage;
import com.ecabin.ledger.service.ConfigurationService.SaveRequest;
import com.ecabin.ledger.security.AppRole;
import com.ecabin.ledger.security.CurrentUserResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.BDDMockito.given;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.mock.web.MockMultipartFile;

/** Runs against the explicitly supplied ecabin_ledger schema; all test rows are rolled back. */
@EnabledIfEnvironmentVariable(named = "ECABIN_DB_VERIFY", matches = "(?i)true")
@SpringBootTest(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://gateway.invalid/",
    "app.security.audience=ecabin-test-api",
    "spring.flyway.schemas=ecabin_ledger",
    "spring.flyway.default-schema=ecabin_ledger"
})
@AutoConfigureMockMvc
class LocalPostgresOperationalIntegrationTest {
    @Autowired DefectReferenceService defectReferences;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void postgresAllocatesConcurrentDefectReferencesAtomicallyPerTenantDateAndArea() throws Exception {
        UUID tenant = jdbc.queryForObject("SELECT id FROM operators_mst ORDER BY id LIMIT 1", UUID.class);
        UUID areaId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(java.util.Locale.ROOT);
        String shortcode = "T" + suffix;
        String code = "REF_TEST_" + suffix;
        jdbc.update("INSERT INTO cabin_areas_mst(id,operator_id,code,name,shortcode) VALUES(?,?,?,'Reference counter test area',?)", areaId, tenant, code, shortcode);

        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 24; i++) tasks.add(() -> new TransactionTemplate(transactionManager).execute(status -> defectReferences.next(tenant, areaId)));
            var references = new HashSet<String>();
            for (var future : pool.invokeAll(tasks)) references.add(future.get());
            assertEquals(24, references.size(), "Concurrent allocations for the same tenant/date/area must never collide.");
            var byDate = references.stream().collect(java.util.stream.Collectors.groupingBy(value -> value.substring(0, 8)));
            byDate.forEach((date, values) -> {
                var sequences = values.stream().map(value -> Long.parseLong(value.substring(value.lastIndexOf('-') + 1))).sorted().toList();
                for (int i = 0; i < sequences.size(); i++) assertEquals(i + 1L, sequences.get(i), "Each business date starts its area sequence at 01 and increments without gaps within committed allocations.");
            });
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM defect_reference_counters_dtl WHERE operator_id=? AND area_shortcode=?", tenant, shortcode);
            jdbc.update("DELETE FROM cabin_areas_mst WHERE operator_id=? AND id=?", tenant, areaId);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnv("SPRING_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> requiredEnv("SPRING_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnv("SPRING_DATASOURCE_PASSWORD"));
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefectService defects;
    @Autowired ConfigurationService configuration;
    @Autowired OperationalWorkflowService workflow;
    @Autowired OperationalFormService forms;
    @Autowired CurrentUserResolver users;
    @Autowired ConfigurationImportService imports;
    @Autowired ReviewerQueueService reviewerQueue;
    @Autowired MaintenanceSlaPolicyService maintenanceSlaPolicies;
    @Autowired MaintenanceActionService maintenanceActions;
    @Autowired AreaMaintenanceRoutingService areaMaintenanceRouting;
    @Autowired com.ecabin.ledger.security.PermissionService resolvedPermissions;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @Transactional
    @Rollback
    void closedCaseDateComesFromImmutableCloseEventAndIsReturnedInDefectTables() {
        UUID org=createTenant("CASE-CLOSE-DATE");
        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'",UUID.class,org);
        UUID component=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,org);
        UUID condition=createCondition(org,component);
        UUID aircraft=jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=?",UUID.class,org);
        String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,org);
        DefectView defect=defects.create(org,"close-date-reporter",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",null,null,null,"Close date fixture","Synthetic PostgreSQL integration fixture.","MEDIUM"));
        Instant closedAt=Instant.parse("2026-10-17T10:15:30Z");
        jdbc.update("UPDATE defects_dtl SET status='CLOSED',updated_at=? WHERE operator_id=? AND id=?",java.sql.Timestamp.from(closedAt),org,defect.id());
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,actor_id,occurred_at) VALUES(?,?,'CLOSURE_APPROVED','APPROVAL_REQUIRED','CLOSED','Integration fixture closed.',?,?)",org,defect.id(),"closure-reviewer",java.sql.Timestamp.from(closedAt));

        assertEquals(closedAt,defects.get(org,defect.id()).closedAt());
        var aircraftPage=defects.aircraftDefects(org,"",aircraft,false,false,0,10,"CLOSED","","reportedAt","DESC");
        assertEquals(closedAt,aircraftPage.items().stream().filter(row->row.id().equals(defect.id())).findFirst().orElseThrow().closedAt());
        var defectPage=defects.list(org,"close-date-reporter","CLOSED",null,null,"ALL",null,null,null,false,false,false,null,10);
        assertEquals(closedAt,defectPage.items().stream().filter(row->row.id().equals(defect.id())).findFirst().orElseThrow().closedAt());

        DefectView legacyClosed=defects.create(org,"close-date-reporter",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"AFT CABIN",null,null,null,"Legacy close-date fixture","Synthetic PostgreSQL integration fixture without a close event.","MEDIUM"));
        Instant legacyClosedAt=Instant.parse("2026-10-17T11:15:30Z");
        jdbc.update("UPDATE defects_dtl SET status='CLOSED',updated_at=? WHERE operator_id=? AND id=?",java.sql.Timestamp.from(legacyClosedAt),org,legacyClosed.id());

        assertEquals(legacyClosedAt,defects.get(org,legacyClosed.id()).closedAt());
        var legacyAircraftRow=defects.aircraftDefects(org,"",aircraft,false,false,0,10,"CLOSED","Legacy close-date fixture","reportedAt","DESC").items().stream().filter(row->row.id().equals(legacyClosed.id())).findFirst().orElseThrow();
        assertEquals(legacyClosedAt,legacyAircraftRow.closedAt());
        var legacyDefectRow=defects.list(org,"close-date-reporter","CLOSED",null,"Legacy close-date fixture","ALL",null,null,null,false,false,false,null,10).items().stream().filter(row->row.id().equals(legacyClosed.id())).findFirst().orElseThrow();
        assertEquals(legacyClosedAt,legacyDefectRow.closedAt());
    }

    @Test
    @Transactional
    @Rollback
    void permissionCatalogIsAdminReadableButImmutableAndOrdinaryRolesCannotAssignAdminAuthority() throws Exception {
        UUID org=createTenant("PERMISSION-CATALOG");
        addMember(org,"permission-admin","ADMIN");
        addMember(org,"permission-viewer","VIEWER");
        given(jwtDecoder.decode("permission-admin-token")).willReturn(testJwt("permission-admin",org));
        given(jwtDecoder.decode("permission-viewer-token")).willReturn(testJwt("permission-viewer",org));
        mockMvc.perform(get("/api/v1/configuration/permissions").header("Authorization","Bearer permission-admin-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("ADMINISTER_ORGANIZATION"))).andExpect(content().string(containsString("DEFECT_VIEW_ALL")));
        mockMvc.perform(get("/api/v1/configuration/role-permissions").header("Authorization","Bearer permission-admin-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("VIEW")));
        mockMvc.perform(get("/api/v1/configuration/permissions").header("Authorization","Bearer permission-viewer-token"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/configuration/permissions").header("Authorization","Bearer permission-admin-token")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/configuration/role-permissions").header("Authorization","Bearer permission-admin-token")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/configuration/roles").header("Authorization","Bearer permission-admin-token")
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(new SaveRequest(null,java.util.Map.of(
                "roleKey","DEFECT_READER","name","Defect Reader","description","Tenant-wide defect visibility only.","permissions","DEFECT_VIEW_ALL")))))
            .andExpect(status().isOk()).andExpect(content().string(containsString("DEFECT_VIEW_ALL")));
        assertThrows(IllegalArgumentException.class,()->configuration.save(org,"permission-admin","roles",new SaveRequest(null,java.util.Map.of(
            "roleKey","CUSTOM_ADMIN","name","Custom Admin","description","Attempted privilege escalation.","permissions","VIEW|ADMINISTER_ORGANIZATION"))));
        UUID administratorRoleId = jdbc.queryForObject("SELECT id FROM operator_roles_mst WHERE operator_id=? AND role_key='ADMIN'", UUID.class, org);
        var updatedAdministrator = configuration.save(org, "permission-admin", "roles", new SaveRequest(administratorRoleId.toString(), java.util.Map.of(
            "name", "Administrator", "description", "Updated organization administrator role.", "permissions", "VIEW|REPORT_VIEW|ADMINISTER_ORGANIZATION")));
        assertEquals("ADMIN", updatedAdministrator.get("roleKey"), "Editing Administrator must preserve its stable role key.");
        assertEquals("VIEW|REPORT_VIEW|ADMINISTER_ORGANIZATION", updatedAdministrator.get("permissions"));
        assertThrows(IllegalArgumentException.class, () -> configuration.save(org, "permission-admin", "roles", new SaveRequest(administratorRoleId.toString(), java.util.Map.of(
            "name", "Administrator", "description", "Must retain organization administration.", "permissions", "VIEW|REPORT_VIEW"))));
    }

    @Test
    @Transactional
    @Rollback
    void tenantRateLimitPolicyIsAuditedEnforcedImmediatelyAndIsolated() throws Exception {
        UUID orgA=createTenant("RATE-LIMIT-A"), orgB=createTenant("RATE-LIMIT-B");
        addMember(orgA,"rate-admin-a","ADMIN"); addMember(orgA,"rate-viewer-a","VIEWER"); addMember(orgB,"rate-admin-b","ADMIN");
        given(jwtDecoder.decode("rate-admin-a-token")).willReturn(testJwt("rate-admin-a",orgA));
        given(jwtDecoder.decode("rate-viewer-a-token")).willReturn(testJwt("rate-viewer-a",orgA));
        given(jwtDecoder.decode("rate-admin-b-token")).willReturn(testJwt("rate-admin-b",orgB));

        String request="""
            {"routeKey":"ORDINARY_READS","name":"Tenant read override","description":"Integration policy","scopeType":"TENANT","maxRequests":1,"windowSeconds":60,"reason":"Verify runtime limit update"}
            """;
        var created=mockMvc.perform(post("/api/v1/rate-limits/policies").header("Authorization","Bearer rate-admin-a-token")
            .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn();
        UUID policyId=UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        assertNotNull(policyId);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_policy_events_dtl WHERE operator_id=? AND policy_id=? AND event_type='CREATED' AND reason='Verify runtime limit update'",Integer.class,orgA,policyId));
        mockMvc.perform(get("/api/v1/rate-limits/policies").header("Authorization","Bearer rate-admin-a-token")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/rate-limits/policies").header("Authorization","Bearer rate-admin-a-token")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        String update="""
            {"routeKey":"ORDINARY_READS","name":"Tenant read override","description":"Updated integration policy","scopeType":"TENANT","maxRequests":3,"windowSeconds":60,"reason":"Raise test limit immediately"}
            """;
        mockMvc.perform(post("/api/v1/rate-limits/policies/"+policyId).header("Authorization","Bearer rate-admin-a-token")
            .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/rate-limits/policies").header("Authorization","Bearer rate-admin-a-token")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/rate-limits/policies").header("Authorization","Bearer rate-admin-a-token")).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/v1/rate-limits/policies/"+policyId).header("Authorization","Bearer rate-admin-b-token")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/rate-limits/policies").header("Authorization","Bearer rate-viewer-a-token")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/rate-limits/policies").header("Authorization","Bearer rate-viewer-a-token")
            .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_policy_events_dtl WHERE operator_id=? AND policy_id=? AND event_type='UPDATED' AND reason='Raise test limit immediately'",Integer.class,orgA,policyId));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_usage_dtl WHERE operator_id=? AND policy_id=? AND rejected_count>0",Integer.class,orgA,policyId)>0);
    }

    @Test
    @Transactional
    @Rollback
    void maintenanceSlaPoliciesAreTenantScopedAuditedAndEffectiveImmediately() throws Exception {
        UUID orgA=createTenant("MAINT-SLA-A"),orgB=createTenant("MAINT-SLA-B");
        addMember(orgA,"sla-admin-a","ADMIN");addMember(orgA,"sla-viewer-a","VIEWER");addMember(orgB,"sla-admin-b","ADMIN");
        given(jwtDecoder.decode("sla-admin-a-token")).willReturn(testJwt("sla-admin-a",orgA));
        given(jwtDecoder.decode("sla-viewer-a-token")).willReturn(testJwt("sla-viewer-a",orgA));
        given(jwtDecoder.decode("sla-admin-b-token")).willReturn(testJwt("sla-admin-b",orgB));

        var defaults=maintenanceSlaPolicies.list(orgA);
        assertEquals(3,defaults.size());
        assertEquals(15,defaults.get(0).maxFutureDays());
        assertEquals(180,defaults.get(1).maxFutureDays());
        assertFalse(defaults.get(2).configured());
        mockMvc.perform(get("/api/v1/configuration/maintenance-sla-policies").header("Authorization","Bearer sla-admin-a-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("CLOSE_SUBMITTED")));
        mockMvc.perform(get("/api/v1/configuration/maintenance-sla-policies").header("Authorization","Bearer sla-viewer-a-token"))
            .andExpect(status().isForbidden());
        String update="""
            {"statusKey":"CLOSE_SUBMITTED","dateRequired":true,"allowFuture":true,"maxFutureDays":45,"allowPast":false,"maxPastDays":null,"businessTimezone":"Asia/Kolkata","reason":"Configure closure submission target date"}
            """;
        mockMvc.perform(post("/api/v1/configuration/maintenance-sla-policies").header("Authorization","Bearer sla-admin-a-token")
            .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk());
        assertTrue(maintenanceSlaPolicies.requireConfigured(orgA,"CLOSE_SUBMITTED").configured());
        assertEquals(45,maintenanceSlaPolicies.requireConfigured(orgA,"CLOSE_SUBMITTED").maxFutureDays());
        assertFalse(maintenanceSlaPolicies.requireConfigured(orgB,"CLOSE_SUBMITTED").configured());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND event_type='MAINTENANCE_SLA_POLICY_UPDATED' AND note=?",Integer.class,orgA,"Configure closure submission target date"));
        mockMvc.perform(get("/api/v1/configuration/maintenance-sla-policies").header("Authorization","Bearer sla-admin-b-token"))
            .andExpect(status().isOk()).andExpect(content().string(not(containsString("Asia/Kolkata"))));
    }

    @Test
    @Transactional
    @Rollback
    void severityMasterCrudIsTenantScopedAndExistingDefectsKeepTheirSeveritySnapshot() {
        UUID orgA=createTenant("SEVERITY-A"),orgB=createTenant("SEVERITY-B");
        assertEquals(7,jdbc.queryForObject("SELECT count(*) FROM defect_severities_mst WHERE operator_id=? AND isactive=1",Integer.class,orgA),"New organizations receive the four base severities and three additive Galley labels.");
        jdbc.update("INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,required_permissions,icon,route_key,display_order) VALUES(?,'configuration','Administration','ADMINISTER_ORGANIZATION','⚙','configuration',50)",orgA);
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM navigation_menu_items_mst WHERE operator_id=? AND parent_id=(SELECT id FROM navigation_menu_items_mst WHERE operator_id=? AND menu_key='configuration') AND menu_key IN ('severities','permissions','role-permissions')",Integer.class,orgA,orgA),"New Administration roots receive the severity and permission views.");
        var saved=configuration.save(orgA,"severity-admin","severities",new SaveRequest(null,java.util.Map.of(
            "code","CABIN_PRIORITY","name","Cabin priority","description","Organization-defined display label, not an airworthiness threshold.","sortOrder","5")));
        configuration.save(orgB,"other-severity-admin","severities",new SaveRequest(null,java.util.Map.of(
            "code","ORG_B_ONLY","name","Organization B only","description","Must remain in organization B.","sortOrder","50")));
        String severityId=String.valueOf(saved.get("id"));
        assertEquals(1,configuration.list(orgA,"severities",false).stream().filter(row->"CABIN_PRIORITY".equals(row.get("code"))).count());
        assertEquals(0,configuration.list(orgB,"severities",false).stream().filter(row->"CABIN_PRIORITY".equals(row.get("code"))).count());
        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'",UUID.class,orgA);
        UUID component=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,orgA);
        UUID condition=createCondition(orgA,component);
        String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,orgA);
        DefectView defect=defects.create(orgA,"severity-reporter",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",12,"12A",null,"Custom severity finding","Preserve the configured severity reference.","CABIN_PRIORITY"));
        assertEquals("Cabin priority",defect.severityName());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defects_dtl WHERE operator_id=? AND id=? AND severity_id=? AND severity='CABIN_PRIORITY'",Integer.class,orgA,defect.id(),UUID.fromString(severityId)));
        assertThrows(IllegalArgumentException.class,()->defects.create(orgA,"severity-reporter",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",13,"13A",null,"Foreign severity","Must reject a severity that exists only for another tenant.","ORG_B_ONLY")));
        configuration.deactivate(orgA,"severity-admin","severities",severityId,"Retired for the integration check.");
        assertThrows(IllegalArgumentException.class,()->defects.create(orgA,"severity-reporter",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",14,"14A",null,"Inactive severity","Inactive tenant severity must be rejected.","CABIN_PRIORITY")));
        assertEquals("Cabin priority",defects.get(orgA,defect.id()).severityName(),"Soft-deactivation must not erase the historical human-readable severity.");
        configuration.activate(orgA,"severity-admin","severities",severityId,"Re-enabled for the integration check.");
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='SEVERITIES' AND entity_id=?",Integer.class,orgA,UUID.fromString(severityId)));
        assertThrows(RuntimeException.class,()->configuration.save(orgA,"severity-admin","severities",new SaveRequest(null,java.util.Map.of(
            "code","CABIN_PRIORITY","name","Duplicate","description","Duplicate unique code check.","sortOrder","6"))));
    }

    @Test
    @Transactional
    @Rollback
    void aircraftDefectListRouteAllowsAuthorizedAuditorAndKeepsTenantAndPermissionFailuresDistinct() throws Exception {
        UUID org=createTenant("AIRCRAFT-HTTP"),other=createTenant("AIRCRAFT-HTTP-OTHER");
        jdbc.update("INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,system_role) VALUES(?, 'AUDITOR','Test Auditor','VIEW|REPORT|AUDIT',false),(?, 'AUDIT_ONLY','Audit Only','AUDIT',false),(?, 'REVIEWER_SCOPE','Reviewer Scope','VIEW|REVIEW',false),(?, 'MAINTENANCE_SCOPE','Maintenance Scope','VIEW|PERFORM_ACTION',false),(?, 'ADMIN_SCOPE','Admin Scope','VIEW|ADMINISTER_ORGANIZATION',false)",org,org,org,org,org);
        addMember(org,"http-auditor","AUDITOR");addMember(org,"no-view","AUDIT_ONLY");
        addMember(org,"http-reviewer","REVIEWER_SCOPE");addMember(org,"http-maintenance","MAINTENANCE_SCOPE");addMember(org,"http-admin","ADMIN_SCOPE");
        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'",UUID.class,org);
        UUID component=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,org);
        UUID condition=createCondition(org,component);String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,org);
        DefectView defect=defects.create(org,"http-auditor",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",14,"14A",null,"Seat finding","Reported for HTTP route coverage.","LOW"));
        DefectView anotherReport=defects.create(org,"another-reporter",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"AFT CABIN",15,"15A",null,"Other seat finding","Must remain outside the report-only user's list.","LOW"));
        UUID aircraft=jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=?",UUID.class,org);
        UUID foreignAircraft=jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=?",UUID.class,other);
        given(jwtDecoder.decode("auditor-token")).willReturn(testJwt("http-auditor",org));
        given(jwtDecoder.decode("no-view-token")).willReturn(testJwt("no-view",org));
        given(jwtDecoder.decode("reviewer-scope-token")).willReturn(testJwt("http-reviewer",org));
        given(jwtDecoder.decode("maintenance-scope-token")).willReturn(testJwt("http-maintenance",org));
        given(jwtDecoder.decode("admin-scope-token")).willReturn(testJwt("http-admin",org));
        assertTrue(new com.ecabin.ledger.security.PermissionService(jdbc).permissionsFor(org,"AUDITOR").contains(com.ecabin.ledger.security.Permission.VIEW));
        assertEquals("AUDITOR",users.require(new JwtAuthenticationToken(testJwt("http-auditor",org))).role());
        assertTrue(resolvedPermissions.permissionsFor(org,"AUDITOR").contains(com.ecabin.ledger.security.Permission.VIEW));
        mockMvc.perform(get("/api/v1/me").header("Authorization","Bearer auditor-token")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/aircraft/{id}/defects",aircraft).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(defect.reference()))).andExpect(content().string(not(containsString(anotherReport.reference()))));
        for (String token : List.of("reviewer-scope-token", "maintenance-scope-token", "admin-scope-token")) {
            mockMvc.perform(get("/api/v1/aircraft/{id}/defects",aircraft).header("Authorization","Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(containsString(defect.reference())))
                .andExpect(content().string(containsString(anotherReport.reference())));
            mockMvc.perform(get("/api/v1/defects/{id}",anotherReport.id()).header("Authorization","Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(containsString(anotherReport.reference())));
            mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/defects/{id}",aircraft,anotherReport.id()).header("Authorization","Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(containsString(anotherReport.reference())));
            mockMvc.perform(get("/api/v1/aircraft/{id}/defects",foreignAircraft).header("Authorization","Bearer " + token))
                .andExpect(status().isNotFound());
        }
        addMember(other,"detail-foreign-reporter","AUDITOR");
        UUID foreignArea=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'",UUID.class,other);
        UUID foreignComponent=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,other);
        UUID foreignCondition=createCondition(other,foreignComponent);
        String foreignTail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,other);
        DefectView foreign=defects.create(other,"detail-foreign-reporter",new CreateDefectRequest(foreignTail,"CABIN","SEAT",foreignArea,foreignComponent,foreignCondition,"FWD CABIN",16,"16A",null,"Foreign tenant case","Must not be exposed by detail authorization.","LOW"));
        for (String token : List.of("reviewer-scope-token", "maintenance-scope-token", "admin-scope-token")) {
            mockMvc.perform(get("/api/v1/defects/{id}",foreign.id()).header("Authorization","Bearer " + token))
                .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/v1/aircraft/{id}",aircraft).header("Authorization","Bearer reviewer-scope-token"))
            .andExpect(status().isOk());
        // The Defect Log Book route keeps its existing role scopes: the auditor sees
        // their own case, while team-scoped roles without a matching team see neither.
        mockMvc.perform(get("/api/v1/defects?limit=25").header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(defect.reference())))
            .andExpect(content().string(not(containsString(anotherReport.reference()))));
        mockMvc.perform(get("/api/v1/defects/{id}",defect.id()).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(defect.reference())));
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/defects/{id}",aircraft,defect.id()).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(defect.reference())));
        mockMvc.perform(get("/api/v1/defects/{id}",anotherReport.id()).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/defects/{id}",aircraft,anotherReport.id()).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isNotFound());
        for (String token : List.of("reviewer-scope-token", "maintenance-scope-token")) {
            mockMvc.perform(get("/api/v1/defects?limit=25").header("Authorization","Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(defect.reference()))));
        }
        mockMvc.perform(get("/api/v1/aircraft/{id}/defects",foreignAircraft).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/aircraft/{id}/defects",aircraft).header("Authorization","Bearer no-view-token"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/aircraft/{id}/defects",aircraft)).andExpect(status().isUnauthorized());

        var form=forms.create(org,"http-admin",new CreateFormRequest("HTTP_REPORT_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Local observation form",area));
        var draft=forms.saveDraft(org,"http-admin",form.id(),form.draftVersionId(),reportConfiguration());
        var published=forms.publish(org,"http-admin",form.id(),draft.id());
        mockMvc.perform(get("/api/v1/areas/{areaId}/forms",area).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(form.id().toString()))).andExpect(content().string(containsString(published.id().toString())));
        mockMvc.perform(get("/api/v1/forms/{formId}/versions/{versionId}/definition",form.id(),published.id()).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("\"name\":\"Local observation form\""))).andExpect(content().string(containsString("\"label\":\"Location\""))).andExpect(content().string(containsString("\"type\":\"TEXT\"")));
        var reportAnswers=reportAnswers(org,component,condition,"FWD CABIN / ROW 16","API report","Defect created through the authenticated report API.");
        CreateDefectRequest reportRequest=new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN / ROW 16 / SEAT 16A",null,null,null,"API report","Defect created through the authenticated report API.","MEDIUM",aircraft,form.id(),published.id(),reportAnswers);
        var reportResponse=mockMvc.perform(post("/api/v1/defects").header("Authorization","Bearer auditor-token").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(reportRequest))).andExpect(status().isCreated()).andReturn();
        DefectView apiCreated=objectMapper.readValue(reportResponse.getResponse().getContentAsString(),DefectView.class);
        var submittedAnswers=new java.util.HashMap<String,Object>(reportAnswers);
        var submittedForm=new SubmitFormRequest(form.id(),published.id(),com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT,submittedAnswers);
        mockMvc.perform(post("/api/v1/defects/{id}/form-submissions",apiCreated.id()).header("Authorization","Bearer auditor-token").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(submittedForm))).andExpect(status().isCreated());
        assertEquals(1,forms.submissions(org,apiCreated.id()).size());
        mockMvc.perform(get("/api/v1/defects/{id}/events",apiCreated.id()).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("submittedDetails")))
            .andExpect(content().string(containsString("API report")))
            .andExpect(content().string(not(containsString("answers_json"))))
            .andExpect(content().string(not(containsString("newState"))))
            .andExpect(content().string(not(containsString("previousState"))));
        mockMvc.perform(get("/api/v1/defects/{id}/operational-forms?purpose=DEFECT_REPORT",apiCreated.id()).header("Authorization","Bearer auditor-token"))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        var wrongRegistration=new CreateDefectRequest("N999ZZ","CABIN","SEAT",area,component,condition,"FWD CABIN / ROW 17 / SEAT 17A",null,null,null,"Wrong aircraft","Registration cannot replace the selected aircraft.","MEDIUM",aircraft,form.id(),published.id(),reportAnswers(org,component,condition,"FWD CABIN / ROW 17 / SEAT 17A","Wrong aircraft","Registration cannot replace the selected aircraft."));
        mockMvc.perform(post("/api/v1/defects").header("Authorization","Bearer auditor-token").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(wrongRegistration))).andExpect(status().isBadRequest());
        var foreignAircraftReport=new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN / ROW 18 / SEAT 18A",null,null,null,"Foreign aircraft","Aircraft identifiers remain tenant-scoped.","MEDIUM",foreignAircraft,form.id(),published.id(),reportAnswers(org,component,condition,"FWD CABIN / ROW 18 / SEAT 18A","Foreign aircraft","Aircraft identifiers remain tenant-scoped."));
        mockMvc.perform(post("/api/v1/defects").header("Authorization","Bearer auditor-token").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(foreignAircraftReport))).andExpect(status().isNotFound());
        UUID otherArea=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY'",UUID.class,org);
        var galleyForm=forms.create(org,"http-admin",new CreateFormRequest("HTTP_GALLEY_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Galley observation form",otherArea));
        var galleyDraft=forms.saveDraft(org,"http-admin",galleyForm.id(),galleyForm.draftVersionId(),reportConfiguration());
        var galleyPublished=forms.publish(org,"http-admin",galleyForm.id(),galleyDraft.id());
        var wrongFormArea=new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",19,"19A",null,"Wrong form mapping","A form published for another area cannot be selected.","MEDIUM",aircraft,galleyForm.id(),galleyPublished.id());
        mockMvc.perform(post("/api/v1/defects").header("Authorization","Bearer auditor-token").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(wrongFormArea))).andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @Rollback
    void defectViewAllReadsTenantWideDefectsWithoutGrantingMaintenanceWrites() throws Exception {
        UUID org=createTenant("VIEW-ALL"),otherOrg=createTenant("VIEW-ALL-OTHER");
        jdbc.update("INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,system_role) VALUES(?, 'DEFECT_READER','Defect reader','DEFECT_VIEW_ALL|PERFORM_ACTION',false)",org);
        addMember(org,"view-all-maintenance","DEFECT_READER");
        addMember(org,"view-all-reporter-a","AUDITOR");
        addMember(org,"view-all-reporter-b","AUDITOR");
        addMember(org,"view-all-other-team-tech","MAINTENANCE_TECHNICIAN");
        addMember(org,"view-only-maintenance","MAINTENANCE_TECHNICIAN");
        addMember(otherOrg,"view-all-foreign-reporter","AUDITOR");
        given(jwtDecoder.decode("view-all-token")).willReturn(testJwt("view-all-maintenance",org));
        given(jwtDecoder.decode("view-only-token")).willReturn(testJwt("view-only-maintenance",org));

        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'",UUID.class,org);
        UUID component=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,org);
        UUID condition=createCondition(org,component);
        String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,org);
        DefectView first=defects.create(org,"view-all-reporter-a",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",14,"14A",null,"Created by another auditor","Tenant-wide visibility remains read-only.","LOW"));
        DefectView assigned=defects.create(org,"view-all-reporter-b",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"AFT CABIN",15,"15A",null,"Assigned to another team","Reader is not a member of this maintenance team.","MEDIUM"));

        String otherTeam="Assigned Maintenance Team";
        jdbc.update("INSERT INTO maintenance_teams_mst(operator_id,name) VALUES(?,?)",org,otherTeam);
        jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name) VALUES(?,?,?)",org,"view-all-other-team-tech",otherTeam);
        UUID actionId=UUID.randomUUID();
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_team,description,priority,status) VALUES(?,?,?,?,?,?,'MEDIUM','ASSIGNED')",actionId,org,assigned.id(),"view-all-maintenance",otherTeam,"Existing work owned by another team");
        jdbc.update("UPDATE defects_dtl SET status='ACTION_ASSIGNED',assigned_to=?,assigned_team=? WHERE operator_id=? AND id=?",otherTeam,otherTeam,org,assigned.id());
        UUID foreignArea=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'",UUID.class,otherOrg);
        UUID foreignComponent=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,otherOrg);
        UUID foreignCondition=createCondition(otherOrg,foreignComponent);
        String foreignTail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,otherOrg);
        DefectView foreign=defects.create(otherOrg,"view-all-foreign-reporter",new CreateDefectRequest(foreignTail,"CABIN","SEAT",foreignArea,foreignComponent,foreignCondition,"FWD CABIN",16,"16A",null,"Foreign tenant case","Must remain inaccessible.","LOW"));
        UUID aircraft=jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=? AND tail_number=?",UUID.class,org,tail);

        mockMvc.perform(get("/api/v1/defects?limit=25").header("Authorization","Bearer view-all-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(first.reference()))).andExpect(content().string(containsString(assigned.reference())));
        mockMvc.perform(get("/api/v1/defects/{id}",assigned.id()).header("Authorization","Bearer view-all-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(otherTeam)));
        mockMvc.perform(get("/api/v1/defects/{id}/events",first.id()).header("Authorization","Bearer view-all-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("DEFECT_REPORTED")));
        mockMvc.perform(get("/api/v1/defects/{id}/form-submissions",assigned.id()).header("Authorization","Bearer view-all-token"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/aircraft/{id}/defects",aircraft).header("Authorization","Bearer view-all-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString(assigned.reference())));
        mockMvc.perform(get("/api/v1/dashboard/summary").header("Authorization","Bearer view-all-token"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/defects/{id}/operational-forms?purpose=CORRECTIVE_ACTION",first.id()).header("Authorization","Bearer view-all-token"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/defects/{id}",foreign.id()).header("Authorization","Bearer view-all-token"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/defects/{id}",first.id()).header("Authorization","Bearer view-only-token"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/navigation").header("Authorization","Bearer view-all-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("\"key\":\"defects\""))).andExpect(content().string(not(containsString("\"key\":\"reports\""))));

        int eventCount=jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,first.id());
        mockMvc.perform(post("/api/v1/defects/{id}/transitions",first.id()).header("Authorization","Bearer view-all-token").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ACTION_ASSIGNED\",\"note\":\"Attempted read-only workflow bypass\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/defects/{id}/form-submissions",first.id()).header("Authorization","Bearer view-all-token").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new SubmitFormRequest(UUID.randomUUID(),UUID.randomUUID(),FormPurpose.CORRECTIVE_ACTION,Map.of()))))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/defects/{id}/actions",first.id()).header("Authorization","Bearer view-all-token").contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignedTeam\":\"Assigned Maintenance Team\",\"description\":\"Attempted assignment\",\"priority\":\"MEDIUM\"}"))
            .andExpect(status().isForbidden());
        assertEquals("REPORTED",jdbc.queryForObject("SELECT status FROM defects_dtl WHERE operator_id=? AND id=?",String.class,org,first.id()));
        assertEquals(eventCount,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,first.id()),"Read-only access must not append workflow events.");

        jdbc.update("UPDATE operator_roles_mst SET permissions='PERFORM_ACTION' WHERE operator_id=? AND role_key='DEFECT_READER'",org);
        mockMvc.perform(get("/api/v1/defects?limit=25").header("Authorization","Bearer view-all-token"))
            .andExpect(status().isForbidden());
    }

    private Jwt testJwt(String subject,UUID org){return Jwt.withTokenValue("mocked").header("alg","RS256").subject(subject).claim("operator_id",org.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();}

    @Test
    @Transactional
    @Rollback
    void closureTeamRouteSaveWritesValidAuditEvent() {
        UUID org=createTenant("CLOSURE-ROUTE");
        addMember(org,"closure-route-admin","ADMIN");
        UUID team=UUID.randomUUID();
        jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name,team_type) VALUES(?,?,?,'CLOSURE')",team,org,"Closure Route Team");
        jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name) VALUES(?,?,?)",org,"closure-route-admin","Closure Route Team");

        var saved=areaMaintenanceRouting.setClosureTeam(org,"closure-route-admin",team,"Configure the organization closure route");

        assertEquals(team,saved.get("teamId"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='ORGANIZATION' AND event_type='CLOSURE_ROUTE_CONFIGURED' AND actor_id=? AND new_state->>'teamId'=?",Integer.class,org,"closure-route-admin",team.toString()));
        assertTrue("CLOSURE_ROUTE_CONFIGURED".length()<=32,"Audit event type must fit defect_events_dtl.event_type.");
    }

    @Test
    @Transactional
    @Rollback
    void reviewerApprovalAssignsDirectlyToMaintenanceWithoutInspection() {
        UUID org=createTenant("REVIEW-FLOW");
        jdbc.update("INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,system_role) VALUES(?, 'AUDITOR','Test Auditor','VIEW|REPORT|AUDIT',false),(?, 'REVIEWER','Test Reviewer','VIEW|REVIEW',false)",org,org);
        addMember(org,"auditor.test@ecabin.local","AUDITOR");addMember(org,"reviewer.test@ecabin.local","REVIEWER");addMember(org,"maintenance.test@ecabin.local","MAINTENANCE_TECHNICIAN");addMember(org,"inspector.test@ecabin.local","INSPECTOR");
        jdbc.update("INSERT INTO maintenance_teams_mst(operator_id,name,team_type) VALUES(?, 'Cabin Review','REVIEWER'),(?, 'Cabin Maintenance','MAINTENANCE')",org,org);
        jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name) VALUES(?,?,?),(?,?,?)",org,"reviewer.test@ecabin.local","Cabin Review",org,"maintenance.test@ecabin.local","Cabin Maintenance");
        UUID maintenanceTeam=jdbc.queryForObject("SELECT id FROM maintenance_teams_mst WHERE operator_id=? AND name='Cabin Maintenance' AND team_type='MAINTENANCE'",UUID.class,org);
        UUID reviewerTeam=jdbc.queryForObject("SELECT id FROM maintenance_teams_mst WHERE operator_id=? AND name='Cabin Review'",UUID.class,org);
        jdbc.update("UPDATE operators_mst SET default_reviewer_team_id=? WHERE id=?",reviewerTeam,org);
        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY' AND isactive=1",UUID.class,org);
        UUID component=UUID.randomUUID();jdbc.update("INSERT INTO cabin_components_mst(id,operator_id,area_id,code,name) VALUES(?,?,?,?,?)",component,org,area,"FLOW_CMP_"+component.toString().substring(0,8),"Seat assembly");
        UUID condition=createCondition(org,component);String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,org);
        var galleyForm=forms.create(org,"http-admin",new CreateFormRequest("REVIEW_GALLEY_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Reviewer workflow report",area));
        var galleyDraft=forms.saveDraft(org,"http-admin",galleyForm.id(),galleyForm.draftVersionId(),reportConfiguration());
        var galleyPublished=forms.publish(org,"http-admin",galleyForm.id(),galleyDraft.id());
        var decisionForm=forms.create(org,"http-admin",new CreateFormRequest("REVIEW_DECISION_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Galley Reviewer Decision",area,FormPurpose.REVIEW_DECISION,WorkflowStage.REPORTED));
        var decisionDraft=forms.saveDraft(org,"http-admin",decisionForm.id(),decisionForm.draftVersionId(),reviewDecisionConfiguration());
        var decisionPublished=forms.publish(org,"http-admin",decisionForm.id(),decisionDraft.id());
        UUID reportRetryKey=UUID.randomUUID();
        UUID aircraftId=jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=? AND tail_number=? AND isactive=1",UUID.class,org,tail);
        var approvedAnswers=galleyReportAnswers(org,component,condition,"Galley 2","Galley finding","Reported by local Auditor.","HIGH");
        CreateDefectRequest reportPayload=new CreateDefectRequest(tail,"GALLEY","GALLEY",area,component,condition,"Galley 2",null,null,null,"Galley — Seat assembly — Galley 2","Condition: Test condition","HIGH",aircraftId,galleyForm.id(),galleyPublished.id(),approvedAnswers);
        DefectView approved=defects.create(org,"auditor.test@ecabin.local",reportPayload,reportRetryKey);
        assertEquals(approved.id(),defects.create(org,"auditor.test@ecabin.local",reportPayload,reportRetryKey).id(),"A timed-out report retry must return the existing defect.");
        assertThrows(ResponseStatusException.class,()->defects.create(org,"reviewer.test@ecabin.local",reportPayload,reportRetryKey),"A retry key cannot be reused by another actor.");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defects_dtl WHERE operator_id=? AND report_idempotency_key=?",Integer.class,org,reportRetryKey));
        assertEquals(reviewerTeam,jdbc.queryForObject("SELECT reviewer_team_id FROM defects_dtl WHERE operator_id=? AND id=?",UUID.class,org,approved.id()));
        assertTrue(reviewerQueue.queue(org,"reviewer.test@ecabin.local",false,0,25,"ALL",null,"reportedAt","DESC").items().stream().anyMatch(row->row.id().equals(approved.id())));
        assertTrue(forms.publishedForDefect(org,approved.id(),FormPurpose.DEFECT_REPORT).isEmpty(),"Initial report forms remain in Report Defect and are not actionable forms on an already-created case.");
        assertTrue(forms.publishedForDefect(org,approved.id(),FormPurpose.REVIEW_DECISION).stream().anyMatch(form->form.id().equals(decisionForm.id())),"The published Reviewer form resolves for the created Galley case.");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->reviewerQueue.decide(org,approved.id(),"maintenance.test@ecabin.local",new ReviewerDecisionRequest("APPROVE",maintenanceTeam,"should be denied")));
        assertTrue(reviewerQueue.decisionOptions(org,approved.id(),"reviewer.test@ecabin.local").maintenanceTeams().stream().anyMatch(team->team.id().equals(maintenanceTeam)),"Reviewer options are available from the case's typed defect details even when the legacy report-submission row is absent.");
        assertThrows(ResponseStatusException.class,()->reviewerQueue.decide(org,approved.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("REJECT",null,"Fix the report",null,decisionForm.id(),UUID.randomUUID())),"A stale or unpublished decision form version cannot be submitted.");
        forms.submit(org,"auditor.test@ecabin.local",approved.id(),new SubmitFormRequest(galleyForm.id(),galleyPublished.id(),approvedAnswers));
        assertTrue(forms.publishedForDefect(org,approved.id(),com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT).isEmpty(),"An initial report version must not be offered again after submission.");
        assertThrows(ResponseStatusException.class,()->forms.submit(org,"auditor.test@ecabin.local",approved.id(),new SubmitFormRequest(galleyForm.id(),galleyPublished.id(),approvedAnswers)),"A duplicate direct form submission must be rejected by the backend.");
        var decisionOptions=reviewerQueue.decisionOptions(org,approved.id(),"reviewer.test@ecabin.local");
        assertTrue(decisionOptions.maintenanceTeams().stream().anyMatch(team->team.id().equals(maintenanceTeam)));
        assertThrows(IllegalArgumentException.class,()->reviewerQueue.decide(org,approved.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("APPROVE",null,null,null,decisionForm.id(),decisionPublished.id())));
        assertThrows(IllegalStateException.class,()->defects.transition(org,approved.id(),"reviewer.test@ecabin.local",new TransitionRequest("ACTION_ASSIGNED","Attempt to bypass the decision operation.",null,null)),"Generic status changes cannot perform Reviewer approval or create Maintenance assignment.");
        assertThrows(IllegalStateException.class,()->defects.transition(org,approved.id(),"reviewer.test@ecabin.local",new TransitionRequest("INSPECTION_REQUIRED","Attempt to bypass the Reviewer decision.",null,null)),"A case cannot bypass the shared Reviewer decision through the generic status transition.");
        var approval=reviewerQueue.decide(org,approved.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("APPROVE",maintenanceTeam,null,null,decisionForm.id(),decisionPublished.id()));
        assertEquals("ACTION_ASSIGNED",approval.status());
        assertEquals("ACTION_ASSIGNED",defects.get(org,approved.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='ASSIGNED' AND assigned_team='Cabin Maintenance'",Integer.class,org,approved.id()));
        assertEquals("Cabin Maintenance",jdbc.queryForObject("SELECT assigned_team FROM defects_dtl WHERE operator_id=? AND id=?",String.class,org,approved.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM inspections_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,approved.id()),"Reviewer approval must not create an inspection record.");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND event_type='REVIEW_APPROVED' AND actor_id='reviewer.test@ecabin.local' AND from_status='REPORTED' AND to_status='ACTION_ASSIGNED' AND note='Reviewer approved the report and assigned it to Maintenance.' AND new_state->>'maintenanceTeamId'=?",Integer.class,org,approved.id(),maintenanceTeam.toString()));
        var approvalEvent=defects.events(org,approved.id()).stream().filter(event->event.eventType().equals("REVIEW_APPROVED")).findFirst().orElseThrow();
        assertEquals("APPROVE",approvalEvent.details().decision());
        assertEquals("Cabin Maintenance",approvalEvent.details().maintenanceTeam());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->reviewerQueue.decide(org,approved.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("REJECT",null,"must be rejected before repeat", "conflicting retry",decisionForm.id(),decisionPublished.id())));
        assertEquals(1,defects.maintenanceWorkQueue(org,"maintenance.test@ecabin.local",0,25,null,null,"assignmentDate","DESC").totalElements());
        assertEquals("Cabin Maintenance",jdbc.queryForObject("SELECT assigned_team FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1",String.class,org,approved.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notifications_dtl WHERE operator_id=? AND defect_id=? AND recipient_user_id='maintenance.test@ecabin.local' AND notification_type='ACTION_ASSIGNED'",Integer.class,org,approved.id()));

        DefectView rejected=defects.create(org,"auditor.test@ecabin.local",new CreateDefectRequest(tail,"GALLEY","GALLEY",area,component,condition,"Galley 1",null,null,null,"Galley — Seat assembly — Galley 1","Condition: Test condition","LOW",aircraftId,null,null));
        var rejectedAnswers=galleyReportAnswers(org,component,condition,"Galley 1","Duplicate finding","Reviewed report.","LOW");
        forms.submit(org,"auditor.test@ecabin.local",rejected.id(),new SubmitFormRequest(galleyForm.id(),galleyPublished.id(),rejectedAnswers));
        int priorAuditEvents=jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,rejected.id());
        var rejection=reviewerQueue.decide(org,rejected.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("REJECT",null,"The location is unclear.",null,decisionForm.id(),decisionPublished.id()));
        assertEquals("REWORK",rejection.status());
        assertEquals("REWORK",defects.get(org,rejected.id()).status());
        assertEquals("auditor.test@ecabin.local",jdbc.queryForObject("SELECT reported_by FROM defects_dtl WHERE operator_id=? AND id=?",String.class,org,rejected.id()));
        assertEquals("auditor.test@ecabin.local",jdbc.queryForObject("SELECT assigned_user_id FROM defects_dtl WHERE operator_id=? AND id=?",String.class,org,rejected.id()));
        assertEquals(rejected.id(),defects.getForReporter(org,"auditor.test@ecabin.local",rejected.id()).id());
        int rejectionEventCount=jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,rejected.id());
        assertThrows(ResponseStatusException.class,()->defects.transition(org,rejected.id(),"reviewer.test@ecabin.local",new TransitionRequest("REWORK","Attempt to bypass the dedicated Reviewer rejection operation.",null,null)));
        assertThrows(ResponseStatusException.class,()->defects.transition(org,rejected.id(),"reviewer.test@ecabin.local",new TransitionRequest("REJECTED","Attempt to use the legacy terminal rejection status.",null,null)));
        assertEquals(rejectionEventCount,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,rejected.id()),"Generic status changes must not alter the defect or append alternate rejection events.");
        assertFalse(reviewerQueue.queue(org,"reviewer.test@ecabin.local",false,0,25,"ALL",null,"reportedAt","DESC").items().stream().anyMatch(row->row.id().equals(rejected.id())),"Rejected reports leave the review queue.");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1",Integer.class,org,rejected.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND event_type='REVIEW_REJECTED' AND note='Rejected: The location is unclear.' AND to_status='REWORK' AND new_state->>'rejectionReason'='The location is unclear.' AND new_state->>'originalReporter'='auditor.test@ecabin.local' AND new_state->>'assignedUserId'='auditor.test@ecabin.local' AND new_state ? 'assignedTeam' AND new_state->>'assignedTeam' IS NULL",Integer.class,org,rejected.id()));
        assertEquals(priorAuditEvents+1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,rejected.id()),"The rejection appends one event without overwriting earlier history.");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND id=? AND from_status='REPORTED' AND previous_state ? 'assignedUserId' AND previous_state ? 'assignedTeam'",Integer.class,org,rejected.id(),rejection.id()),"The immutable event records the prior status and assignment fields.");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notifications_dtl WHERE operator_id=? AND defect_id=? AND recipient_user_id='auditor.test@ecabin.local' AND notification_type='REVIEW_REJECTED' AND source_event_id=?",Integer.class,org,rejected.id(),rejection.id()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->reviewerQueue.decide(org,rejected.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("APPROVE",maintenanceTeam,null,"must conflict",decisionForm.id(),decisionPublished.id())));
        assertThrows(IllegalArgumentException.class,()->reviewerQueue.decide(org,rejected.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("REJECT",null,"  ",null,decisionForm.id(),decisionPublished.id())),"Reject requires a nonblank reason.");

        var returnedForms=forms.publishedForDefect(org,rejected.id(),FormPurpose.DEFECT_REPORT);
        assertTrue(returnedForms.stream().anyMatch(form->form.id().equals(galleyForm.id())),"The original Auditor receives the published report form for correction.");
        var correctionDecisionForm=forms.create(org,"http-admin",new CreateFormRequest("REVIEW_DECISION_REWORK_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Galley Reviewer Decision After Correction",area,FormPurpose.REVIEW_DECISION,WorkflowStage.UNDER_REVIEW));
        var correctionDecisionDraft=forms.saveDraft(org,"http-admin",correctionDecisionForm.id(),correctionDecisionForm.draftVersionId(),reviewDecisionConfiguration());
        var correctionDecisionPublished=forms.publish(org,"http-admin",correctionDecisionForm.id(),correctionDecisionDraft.id());
        var correctedAnswers=galleyReportAnswers(org,component,condition,"Galley 1","Corrected finding","The original report has been clarified.","LOW");
        forms.submit(org,"auditor.test@ecabin.local",rejected.id(),new SubmitFormRequest(galleyForm.id(),galleyPublished.id(),correctedAnswers));
        assertEquals("UNDER_REVIEW",defects.get(org,rejected.id()).status());
        assertEquals("auditor.test@ecabin.local",jdbc.queryForObject("SELECT reported_by FROM defects_dtl WHERE operator_id=? AND id=?",String.class,org,rejected.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM defects_dtl WHERE operator_id=? AND id=? AND (assigned_user_id IS NOT NULL OR assigned_team IS NOT NULL)",Integer.class,org,rejected.id()));
        assertTrue(reviewerQueue.queue(org,"reviewer.test@ecabin.local",false,0,25,"ALL",null,"reportedAt","DESC").items().stream().anyMatch(row->row.id().equals(rejected.id())),"The corrected case returns to the Reviewer queue.");
        var secondRejection=reviewerQueue.decide(org,rejected.id(),"reviewer.test@ecabin.local",new ReviewerDecisionRequest("REJECT",null,"Please clarify the location again.",null,correctionDecisionForm.id(),correctionDecisionPublished.id()));
        assertEquals("REWORK",secondRejection.status());
        assertEquals("auditor.test@ecabin.local",jdbc.queryForObject("SELECT assigned_user_id FROM defects_dtl WHERE operator_id=? AND id=?",String.class,org,rejected.id()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND event_type='REVIEW_REJECTED'",Integer.class,org,rejected.id()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM notifications_dtl WHERE operator_id=? AND defect_id=? AND recipient_user_id='auditor.test@ecabin.local' AND notification_type='REVIEW_REJECTED'",Integer.class,org,rejected.id()));
    }

    @Test
    @Transactional
    @Rollback
    void resolvesServerPrincipalIsolatesTenantAndCompletesAuditedWorkflow() {
        UUID orgA = createTenant("A");
        UUID orgB = createTenant("B");
        addMember(orgA, "reporter-a", "SUPERVISOR");
        addMember(orgA, "inspector-a", "INSPECTOR");
        addMember(orgA, "tech-a", "MAINTENANCE_TECHNICIAN");
        addMember(orgA, "quality-b", "QUALITY_COMPLIANCE");
        addMember(orgA, "approver-c", "SUPERVISOR");

        Jwt principalToken = Jwt.withTokenValue("test-only")
            .header("alg", "RS256")
            .subject("reporter-a")
            .claim("operator_id", orgA.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
        var principal = users.require(new JwtAuthenticationToken(principalToken));
        assertEquals(orgA, principal.organizationId());
        assertEquals(AppRole.SUPERVISOR.name(), principal.role());

        String tailNumber = jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?", String.class, orgA);
        UUID areaId = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'", UUID.class, orgA);
        UUID componentId = jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'", UUID.class, orgA);
        UUID conditionId = createCondition(orgA, componentId);
        DefectView defect = defects.create(orgA, principal.externalUserId(), new CreateDefectRequest(
            tailNumber, "CABIN", "SEAT", areaId, componentId, conditionId, "FWD CABIN",
            14, "14A", null, "Seat cover torn", "Cover torn along seam", "MEDIUM"));
        UUID entryAreaId = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='ENTRY_AREA'",UUID.class,orgA);
        assertThrows(ResponseStatusException.class, () -> configuration.save(orgA,"reporter-a","components",new SaveRequest(componentId.toString(),java.util.Map.of("code","TRAY_TABLE","name","Tray Table","areaId",entryAreaId.toString()))));

        assertEquals(1, defects.list(orgA, "reporter-a", null, null, defect.reference(), "DEFECT_ID", null, null, null, false, null, 25).items().size());
        assertEquals(1, defects.list(orgA, "reporter-a", null, null, defect.tailNumber(), "AIRCRAFT_REGISTRATION", "A320-200", null, null, false, null, 25).items().size());
        assertEquals(0, defects.list(orgA, "reporter-b", null, null, null, "ALL", null, null, null, true, null, 25).items().size());

        assertEquals(0, defects.list(orgB, null, null, null, 25).items().size());
        assertThrows(ResponseStatusException.class, () -> defects.get(orgB, defect.id()));
        assertThrows(ResponseStatusException.class, () -> defects.events(orgB, defect.id()));
        assertThrows(ResponseStatusException.class, () -> defects.transition(orgB, defect.id(), "reporter-b",
            new TransitionRequest("UNDER_REVIEW", "cross-tenant attempt", null, null)));

        defects.transition(orgA, defect.id(), "reporter-a", new TransitionRequest("UNDER_REVIEW", "Reviewed", null, null));
        defects.transition(orgA, defect.id(), "reporter-a", new TransitionRequest("INSPECTION_REQUIRED", "Inspection requested", null, null));
        defects.transition(orgA, defect.id(), "inspector-a", new TransitionRequest("INSPECTION_IN_PROGRESS", "Inspection started", null, null));
        workflow.completeInspection(orgA, defect.id(), "inspector-a", new InspectionRequest("PASS", "Seat cover inspection complete", "No structural damage."));
        var inspectionEvent=defects.events(orgA,defect.id()).stream().filter(event->event.eventType().equals("INSPECTION_COMPLETED")).findFirst().orElseThrow();
        assertTrue(inspectionEvent.details().submittedDetails().stream().anyMatch(item->item.label().equals("Inspection notes")&&item.value().equals("No structural damage.")));
        var action = workflow.assignAction(orgA, defect.id(), "reporter-a",
            new CorrectiveActionRequest("tech-a", null, "Replace seat cover", "MEDIUM", Instant.now().plusSeconds(86400)));
        workflow.startAction(orgA, defect.id(), action.id(), "tech-a");
        workflow.completeAction(orgA, defect.id(), action.id(), "tech-a", new ActionCompletionRequest("Cover replaced and inspected."));
        workflow.verify(orgA, defect.id(), "quality-b", new VerificationRequest("PASS", "Repair confirmed", "EVIDENCE-DEMO-001"));
        defects.transition(orgA, defect.id(), "reporter-a", new TransitionRequest("APPROVAL_REQUIRED", "Quality verified", null, null));
        workflow.approve(orgA, defect.id(), "approver-c", new ApprovalRequest("APPROVED", "Closure reviewed", "CLOSURE-DEMO-001"));
        assertEquals("CLOSED", defects.get(orgA, defect.id()).status());
        var defectEvents = defects.events(orgA, defect.id());
        assertEquals(12, defectEvents.size());
        assertTrue(defectEvents.stream().anyMatch(event -> "REVIEW_ROUTING_FAILED".equals(event.eventType())));
        jdbc.update("UPDATE defects_dtl SET isactive=2 WHERE operator_id=? AND id=?", orgA, defect.id());
        assertThrows(ResponseStatusException.class, () -> defects.get(orgA, defect.id()));
    }

    @Test
    @Transactional
    @Rollback
    void closureTeamMembersWithApprovePermissionSeeOnlyRoutedClosureCasesInSharedQueue() {
        UUID org=createTenant("CLOSURE-QUEUE");addMember(org,"closure-reviewer","SUPERVISOR");
        UUID team=UUID.randomUUID();jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name,team_type) VALUES(?,?,?,'CLOSURE')",team,org,"Configured Closure Team");
        jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name) VALUES(?,?,?)",org,"closure-reviewer","Configured Closure Team");
        jdbc.update("UPDATE operators_mst SET default_closure_team_id=? WHERE id=?",team,org);
        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'",UUID.class,org);
        UUID component=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,org);
        UUID condition=createCondition(org,component);String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,org);
        DefectView defect=defects.create(org,"closure-reviewer",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN",null,null,null,"Closure queue fixture","Synthetic test fixture","MEDIUM"));
        jdbc.update("UPDATE defects_dtl SET reviewer_team_id=?,status='APPROVAL_REQUIRED' WHERE operator_id=? AND id=?",team,org,defect.id());
        var queue=reviewerQueue.queue(org,"closure-reviewer",false,0,25,"APPROVAL_REQUIRED",null,"reportedAt","DESC");
        assertTrue(queue.items().stream().anyMatch(item->item.id().equals(defect.id())),"An explicitly configured closure-team member with APPROVE should see closure-stage cases in the shared queue.");
    }

    @Test
    @Transactional
    @Rollback
    void maintenanceCloseSubmissionRoutesDirectlyToClosureQueueWithoutVerification() {
        UUID org=createTenant("MAINT-CLOSE-ROUTE");
        addMember(org,"close-route-tech","MAINTENANCE_TECHNICIAN");
        addMember(org,"close-route-reviewer","SUPERVISOR");
        String maintenanceTeam="Close Route Maintenance";
        UUID maintenanceTeamId=UUID.randomUUID();
        jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name,team_type) VALUES(?,?,?,'MAINTENANCE')",maintenanceTeamId,org,maintenanceTeam);
        jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name) VALUES(?,?,?),(?,?,?)",org,"close-route-tech",maintenanceTeam,org,"close-route-reviewer","Closure Route Team");
        UUID closureTeamId=UUID.randomUUID();
        jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name,team_type) VALUES(?,?,?,'CLOSURE')",closureTeamId,org,"Closure Route Team");
        jdbc.update("UPDATE operators_mst SET default_closure_team_id=? WHERE id=?",closureTeamId,org);

        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY' AND isactive=1",UUID.class,org);
        UUID component=UUID.randomUUID();
        jdbc.update("INSERT INTO cabin_components_mst(id,operator_id,area_id,code,name) VALUES(?,?,?,?,?)",component,org,area,"CLOSE_CMP_"+component.toString().substring(0,8),"Close route component");
        UUID condition=createCondition(org,component);
        String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,org);
        DefectView defect=defects.create(org,"close-route-tech",new CreateDefectRequest(tail,"GALLEY","GALLEY",area,component,condition,"G1",null,null,null,"Close routing test","Completion routing fixture","MEDIUM"));
        UUID actionId=UUID.randomUUID();
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_team,description,priority,status,created_at) VALUES(?,?,?,?,?,?,?,'IN_PROGRESS',clock_timestamp())",actionId,org,defect.id(),"close-route-tech",maintenanceTeam,"Complete maintenance work","MEDIUM");
        jdbc.update("UPDATE defects_dtl SET status='IN_PROGRESS',assigned_to=?,assigned_team=?,reviewer_team_id=? WHERE operator_id=? AND id=?",maintenanceTeam,maintenanceTeam,closureTeamId,org,defect.id());

        var form=forms.create(org,"form-admin",new CreateFormRequest("MAINT_CLOSE_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Maintenance close routing form",area,FormPurpose.CORRECTIVE_ACTION,WorkflowStage.IN_PROGRESS));
        var draft=forms.saveDraft(org,"form-admin",form.id(),form.draftVersionId(),new FormConfiguration(List.of(
            new FieldDefinition("status","Maintenance status",FieldType.DROPDOWN,null,true,0,null,null,null,OptionSource.MAINTENANCE_ACTION_STATUSES,null,null,DefectFieldBinding.MAINTENANCE_STATUS),
            new FieldDefinition("slaTargetDate","SLA Target Date",FieldType.DATE,null,true,1,null,null,null,null,null,null,DefectFieldBinding.SLA_TARGET_DATE),
            new FieldDefinition("remarks","Remarks",FieldType.TEXTAREA,null,false,2,new TextRules(null,2000,null,null,null),null,null,null,null,null,DefectFieldBinding.MAINTENANCE_REMARK))));
        var published=forms.publish(org,"form-admin",form.id(),draft.id());
        maintenanceSlaPolicies.update(org,"form-admin",new MaintenanceSlaPolicyUpdate("CLOSE_SUBMITTED",false,false,null,false,null,"UTC","Verify direct Close routing"));

        LocalDate targetDate=LocalDate.now(ZoneOffset.UTC);
        var result=maintenanceActions.update(org,defect.id(),actionId,"close-route-tech",new MaintenanceActionUpdateRequest(form.id(),published.id(),"CLOSE_SUBMITTED",targetDate,"Corrective work complete."));
        assertEquals("APPROVAL_REQUIRED",result.status());
        assertEquals("APPROVAL_REQUIRED",defects.get(org,defect.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND id=? AND status='COMPLETED' AND completed_by_user_id=? AND completion_notes=?",Integer.class,org,defect.id(),actionId,"close-route-tech","Corrective work complete."));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM verifications_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,defect.id()),"Maintenance Close must not create a verification task.");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM inspections_dtl WHERE operator_id=? AND defect_id=?",Integer.class,org,defect.id()),"Maintenance Close must not create an inspection task.");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND event_type='ACTION_CLOSE_SUBMITTED' AND from_status='IN_PROGRESS' AND to_status='APPROVAL_REQUIRED' AND actor_id=?",Integer.class,org,defect.id(),"close-route-tech"));
        var closeEvent=defects.events(org,defect.id()).stream().filter(event->event.eventType().equals("ACTION_CLOSE_SUBMITTED")).findFirst().orElseThrow();
        assertEquals("CLOSE_SUBMITTED",closeEvent.details().maintenanceStatus());
        assertEquals(targetDate.toString(),closeEvent.details().slaTargetDate());
        assertEquals("Corrective work complete.",closeEvent.details().maintenanceRemark());
        assertEquals("IN_PROGRESS",closeEvent.details().previousActionStatus());
        assertEquals("COMPLETED",closeEvent.details().actionStatus());
        assertTrue(reviewerQueue.queue(org,"close-route-reviewer",false,0,25,"APPROVAL_REQUIRED",null,"reportedAt","DESC").items().stream().anyMatch(item->item.id().equals(defect.id())),"Closure Team members with APPROVE access must see the submitted case.");
        assertThrows(Exception.class,()->maintenanceActions.update(org,defect.id(),actionId,"another-user",new MaintenanceActionUpdateRequest(form.id(),published.id(),"CLOSE_SUBMITTED",null,"duplicate")),"Only the assigned Maintenance member may submit completion.");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND status='COMPLETED'",Integer.class,org,defect.id()),"A repeated or unauthorized request must not create a second completion record.");

        var closureForm=forms.create(org,"form-admin",new CreateFormRequest("CLOSURE_ROUTE_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Closure routing form",area,FormPurpose.CLOSURE_APPROVAL,WorkflowStage.APPROVAL_REQUIRED));
        var closureDraft=forms.saveDraft(org,"form-admin",closureForm.id(),closureForm.draftVersionId(),new FormConfiguration(List.of(
            new FieldDefinition("decision","Closure decision",FieldType.DROPDOWN,null,true,0,null,null,null,OptionSource.CLOSURE_DECISIONS,null,null,DefectFieldBinding.CLOSURE_DECISION),
            new FieldDefinition("remarks","Remarks",FieldType.TEXTAREA,null,false,1,new TextRules(null,2000,null,null,null),null,null,null,null,null,DefectFieldBinding.CLOSURE_REMARK,true,false,null,new com.ecabin.ledger.api.FormModels.RequiredRule("decision","DECLINE")))));
        var closurePublished=forms.publish(org,"form-admin",closureForm.id(),closureDraft.id());
        var closed=workflow.approve(org,defect.id(),"close-route-reviewer",new ApprovalRequest("ACCEPT","Closure review passed",null,closureForm.id(),closurePublished.id()));
        assertEquals("CLOSED",closed.status());
        var closureEvent=defects.events(org,defect.id()).stream().filter(event->event.eventType().startsWith("CLOSURE_")).findFirst().orElseThrow();
        assertEquals("ACCEPT",closureEvent.details().decision());

        DefectView declined=defects.create(org,"close-route-tech",new CreateDefectRequest(tail,"GALLEY","GALLEY",area,component,condition,"G2",null,null,null,"Closure decline routing test","Decline routing fixture","MEDIUM"));
        UUID completedId=UUID.randomUUID();
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_team,description,priority,status,completed_by_user_id,completed_at,created_at) VALUES(?,?,?,?,?,?,'MEDIUM','COMPLETED',?,clock_timestamp(),clock_timestamp())",completedId,org,declined.id(),"close-route-tech",maintenanceTeam,"Completed work to return","close-route-tech");
        jdbc.update("UPDATE defects_dtl SET status='APPROVAL_REQUIRED',reviewer_team_id=? WHERE operator_id=? AND id=?",closureTeamId,org,declined.id());
        var returned=workflow.approve(org,declined.id(),"close-route-reviewer",new ApprovalRequest("DECLINE","Additional work is required",null,closureForm.id(),closurePublished.id()));
        assertEquals("IN_PROGRESS",returned.status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='IN_PROGRESS' AND assigned_team=?",Integer.class,org,declined.id(),maintenanceTeam),"Closure rejection must follow the existing return-to-Maintenance path.");
    }

    @Test
    @Transactional
    @Rollback
    void sharedTeamQueueAndReassignmentFollowCurrentActiveMembership() {
        UUID org = createTenant("TEAM-QUEUE");
        UUID otherOrg = createTenant("TEAM-QUEUE-OTHER");
        addMember(org, "team-reviewer", "SUPERVISOR");
        addMember(org, "team-inspector", "INSPECTOR");
        addMember(org, "team-tech-a", "MAINTENANCE_TECHNICIAN");
        addMember(org, "team-tech-b", "MAINTENANCE_TECHNICIAN");
        addMember(org, "team-multi", "MAINTENANCE_TECHNICIAN");
        addMember(org, "team-outsider", "MAINTENANCE_TECHNICIAN");
        String teamA = "Cabin Team A";
        String teamB = "Cabin Team B";
        jdbc.update("INSERT INTO maintenance_teams_mst(operator_id,name) VALUES(?,?),(?,?)", org, teamA, org, teamB);
        UUID teamAId=jdbc.queryForObject("SELECT id FROM maintenance_teams_mst WHERE operator_id=? AND name=?",UUID.class,org,teamA);
        UUID teamBId=jdbc.queryForObject("SELECT id FROM maintenance_teams_mst WHERE operator_id=? AND name=?",UUID.class,org,teamB);
        UUID otherTenantTeamId=UUID.randomUUID();
        jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name) VALUES(?,?,?)",otherTenantTeamId,otherOrg,"Other Tenant Maintenance Team");
        UUID inactiveTeamId=UUID.randomUUID();
        jdbc.update("INSERT INTO maintenance_teams_mst(id,operator_id,name,isactive) VALUES(?,?,?,0)",inactiveTeamId,org,"Inactive Maintenance Team");
        jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name) VALUES(?,?,?),(?,?,?),(?,?,?),(?,?,?)",
            org, "team-tech-a", teamA, org, "team-multi", teamA, org, "team-tech-b", teamB, org, "team-multi", teamB);

        UUID area = UUID.randomUUID();
        jdbc.update("INSERT INTO cabin_areas_mst(id,operator_id,code,name,shortcode) VALUES(?,?,?,'Forward cabin',?)", area, org, "TEAM_AREA_" + area.toString().substring(0, 8), "T" + area.toString().substring(0, 5).toUpperCase(java.util.Locale.ROOT));
        UUID component = UUID.randomUUID();
        jdbc.update("INSERT INTO cabin_components_mst(id,operator_id,area_id,code,name) VALUES(?,?,?,?,?)", component, org, area, "TEAM_CMP_" + component.toString().substring(0, 8), "Seat assembly");
        UUID condition = createCondition(org, component);
        String tail = jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?", String.class, org);
        DefectView defect = defects.create(org, "team-reviewer", new CreateDefectRequest(tail, "CABIN", "SEAT", area, component, condition, "FWD CABIN", 14, "14A", null, "Seat finding", "Team assignment test finding.", "MEDIUM"));
        assertTrue(defect.reference().matches("\\d{8}-T[A-F0-9]{5}-01"), "Defect creation must use the validated area's shortcode and begin its independent UTC-date sequence at 01.");
        defects.transition(org, defect.id(), "team-reviewer", new TransitionRequest("UNDER_REVIEW", "Reviewed.", null, null));
        defects.transition(org, defect.id(), "team-reviewer", new TransitionRequest("INSPECTION_REQUIRED", "Inspection requested.", null, null));
        defects.transition(org, defect.id(), "team-inspector", new TransitionRequest("INSPECTION_IN_PROGRESS", "Inspection started.", null, null));
        workflow.completeInspection(org, defect.id(), "team-inspector", new InspectionRequest("PASS", "Condition inspected.", null));
        var assigned = workflow.assignAction(org, defect.id(), "team-reviewer", new CorrectiveActionRequest(null, teamA, "Replace seat cushion.", "MEDIUM", Instant.now().plusSeconds(86400)));

        assertEquals(1, defects.maintenanceWorkQueue(org, "team-tech-a", 0, 10, null, null, null, null).totalElements());
        assertEquals(1, defects.maintenanceWorkQueue(org, "team-multi", 0, 10, null, null, null, null).items().size(), "membership in two teams must not duplicate the current assignment");
        assertEquals(0, defects.maintenanceWorkQueue(org, "team-outsider", 0, 10, null, null, null, null).totalElements());
        assertEquals(0, defects.maintenanceWorkQueue(otherOrg, "team-tech-a", 0, 10, null, null, null, null).totalElements());
        assertEquals(defect.id(), defects.getForMaintenanceMember(org, "team-tech-a", defect.id()).id());
        assertThrows(ResponseStatusException.class, () -> defects.getForMaintenanceMember(org, "team-outsider", defect.id()));
        UUID aircraftId = jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=? AND tail_number=?", UUID.class, org, tail);
        UUID otherTenantAircraftId = jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=?", UUID.class, otherOrg);
        assertEquals(defect.id(), defects.getForAircraftList(org, "team-outsider", aircraftId, defect.id(), false).id(),
            "A Maintenance user may open an item exposed by the aircraft-specific tenant-wide list even when it is not assigned to their team.");
        assertEquals(defect.id(), defects.getForAircraftList(org, "team-reviewer", aircraftId, defect.id(), false).id(),
            "Reviewer detail visibility from the aircraft-specific list follows that list's tenant-wide policy.");
        assertEquals(defect.id(), defects.getForAircraftList(org, "team-reviewer", aircraftId, defect.id(), true).id(),
            "Auditor-scope detail remains limited to its original reporter.");
        assertThrows(ResponseStatusException.class, () -> defects.getForAircraftList(org, "team-outsider", aircraftId, defect.id(), true),
            "A Reporter-scoped user cannot open another user's item through the aircraft-list detail route.");
        assertThrows(ResponseStatusException.class, () -> defects.getForAircraftList(org, "team-outsider", otherTenantAircraftId, defect.id(), false),
            "The detail lookup remains bound to both the authenticated tenant and selected aircraft.");
        assertThrows(ResponseStatusException.class, () -> defects.getForAircraftList(org, "team-outsider", aircraftId, UUID.randomUUID(), false),
            "A nonexistent id receives the same non-disclosing not-found response.");
        assertEquals(1, defects.aircraftDefects(org, "team-tech-a", aircraftId, true, false, 0, 10, null, null, "reportedAt", "DESC").totalElements());
        assertEquals(0, defects.aircraftDefects(org, "team-outsider", aircraftId, true, false, 0, 10, null, null, "reportedAt", "DESC").totalElements());
        assertEquals(1, defects.fleetDefectSummary(org, "team-tech-a", true, false).inMaintenance());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications_dtl WHERE operator_id=? AND defect_id=? AND recipient_user_id='team-multi' AND notification_type='ACTION_ASSIGNED'", Integer.class, org, defect.id()));

        var availableTeams=workflow.reassignmentOptions(org,defect.id(),assigned.id());
        assertEquals(java.util.Set.of(teamAId,teamBId),availableTeams.stream().map(com.ecabin.ledger.api.ApiModels.MaintenanceTeamOption::id).collect(java.util.stream.Collectors.toSet()));
        assertFalse(availableTeams.stream().anyMatch(option->option.id().equals(otherTenantTeamId)||option.id().equals(inactiveTeamId)),"Only active maintenance teams from the authenticated tenant are listed.");

        var reassigned = workflow.reassignAction(org, defect.id(), assigned.id(), "team-reviewer", new CorrectiveActionReassignmentRequest(teamBId, null, "Specialist team requested."));
        assertEquals(0, defects.maintenanceWorkQueue(org, "team-tech-a", 0, 10, null, null, null, null).totalElements());
        assertEquals(1, defects.maintenanceWorkQueue(org, "team-tech-b", 0, 10, null, null, null, null).totalElements());
        assertEquals(1, defects.maintenanceWorkQueue(org, "team-multi", 0, 10, null, null, null, null).totalElements());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM notifications_dtl WHERE operator_id=? AND defect_id=? AND recipient_user_id='team-multi' AND notification_type='ACTION_ASSIGNED'", Integer.class, org, defect.id()), "one in-app notification is created for the member per assignment, not per duplicate team row");
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM corrective_actions_dtl WHERE operator_id=? AND id=?", String.class, org, assigned.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND event_type='ACTION_REASSIGNED' AND previous_state->>'team'=? AND new_state->>'team'=?", Integer.class, org, defect.id(), teamA, teamB));
        assertThrows(IllegalStateException.class, () -> workflow.startAction(org, defect.id(), assigned.id(), "team-tech-a"));

        jdbc.update("UPDATE operator_membership_teams_mst SET isactive=2 WHERE operator_id=? AND external_user_id='team-tech-b' AND team_name=?", org, teamB);
        assertEquals(0, defects.maintenanceWorkQueue(org, "team-tech-b", 0, 10, null, null, null, null).totalElements());
        assertThrows(ResponseStatusException.class, () -> defects.getForMaintenanceMember(org, "team-tech-b", defect.id()));
        jdbc.update("UPDATE operator_membership_teams_mst SET isactive=1 WHERE operator_id=? AND external_user_id='team-tech-b' AND team_name=?", org, teamB);
        jdbc.update("UPDATE operator_memberships_mst SET isactive=2 WHERE operator_id=? AND external_user_id='team-tech-b'", org);
        assertEquals(0, defects.maintenanceWorkQueue(org, "team-tech-b", 0, 10, null, null, null, null).totalElements());
        assertThrows(ResponseStatusException.class, () -> defects.getForMaintenanceMember(org, "team-tech-b", defect.id()));
        jdbc.update("UPDATE operator_memberships_mst SET isactive=1 WHERE operator_id=? AND external_user_id='team-tech-b'", org);
        workflow.startAction(org, defect.id(), reassigned.id(), "team-tech-b");
        assertThrows(IllegalArgumentException.class,()->workflow.reassignAction(org,defect.id(),reassigned.id(),"team-reviewer",new CorrectiveActionReassignmentRequest(teamBId,null,"Same team should not be accepted.")));
        assertThrows(IllegalArgumentException.class,()->workflow.reassignAction(org,defect.id(),reassigned.id(),"team-reviewer",new CorrectiveActionReassignmentRequest(otherTenantTeamId,null,"Cross-tenant team should not be accepted.")));
        assertThrows(IllegalArgumentException.class,()->workflow.reassignAction(org,defect.id(),reassigned.id(),"team-reviewer",new CorrectiveActionReassignmentRequest(inactiveTeamId,null,"Inactive teams should not be accepted.")));
        workflow.completeAction(org, defect.id(), reassigned.id(), "team-tech-b", new ActionCompletionRequest("Replacement complete."));
        assertEquals(defect.id(), defects.getForAircraftList(org, "team-outsider", aircraftId, defect.id(), false).id(),
            "The aircraft-list detail remains available after assignment changes and Maintenance completes its workflow stage.");
        assertEquals(0, defects.maintenanceWorkQueue(org, "team-tech-b", 0, 10, null, null, null, null).totalElements());
    }

    @Test
    @Transactional
    @Rollback
    void versionsFormsImmutablyAndSubmitsTenantBoundAnswersAgainstPublishedVersion() {
        UUID orgA=createTenant("FORM_A"),orgB=createTenant("FORM_B");
        UUID area=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN' AND isactive=1",UUID.class,orgA);
        UUID component=UUID.randomUUID();jdbc.update("INSERT INTO cabin_components_mst(id,operator_id,area_id,code,name) VALUES(?,?,?,?,?)",component,orgA,area,"CMP_"+component.toString().substring(0,8),"Seat assembly");
        UUID condition=createCondition(orgA,component);
        String tail=jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE operator_id=?",String.class,orgA);
        UUID aircraftId=jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=? AND tail_number=? AND isactive=1",UUID.class,orgA,tail);
        DefectView defect=defects.create(orgA,"form-reporter",new CreateDefectRequest(tail,"CABIN","SEAT",area,component,condition,"FWD CABIN / ROW 14 / SEAT 14A",null,null,null,"Test title","Test defect description","MEDIUM",aircraftId,null,null));

        var created=forms.create(orgA,"form-admin",new CreateFormRequest("CABIN_CHECK_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Cabin check",area));
        var definition=reportConfiguration();
        var draft=forms.saveDraft(orgA,"form-admin",created.id(),created.draftVersionId(),definition);
        var published=forms.publish(orgA,"form-admin",created.id(),draft.id());
        assertEquals("PUBLISHED",published.status());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->forms.saveDraft(orgA,"form-admin",created.id(),published.id(),definition));
        assertEquals(1,forms.publishedForArea(orgA,area).stream().filter(form->form.id().equals(created.id())&&form.purpose()==com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT&&form.workflowStage()==com.ecabin.ledger.api.FormModels.WorkflowStage.INITIAL_REPORT).count());
        var inspectionForm=forms.create(orgA,"form-admin",new CreateFormRequest("CABIN_INSPECT_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Cabin inspection",area,com.ecabin.ledger.api.FormModels.FormPurpose.INSPECTION,com.ecabin.ledger.api.FormModels.WorkflowStage.INSPECTION_REQUIRED));
        var inspectionDraft=forms.saveDraft(orgA,"form-admin",inspectionForm.id(),inspectionForm.draftVersionId(),new FormConfiguration(List.of(
            new FieldDefinition("evidence","Inspection evidence",FieldType.FILE_UPLOAD,null,false,0,null,null,null,null,null,
                new com.ecabin.ledger.api.FormModels.FileRules(List.of("pdf"),List.of("application/pdf"),20L*1024*1024,0,3,30L*1024*1024),DefectFieldBinding.EVIDENCE_ATTACHMENTS))));
        forms.publish(orgA,"form-admin",inspectionForm.id(),inspectionDraft.id());
        assertEquals(30L*1024*1024,forms.version(orgA,inspectionForm.id(),inspectionDraft.id()).configuration().fields().get(0).fileRules().maxTotalSizeBytes());
        assertEquals("31457280",jdbc.queryForObject("SELECT fields_json #>> '{fields,0,fileRules,maxTotalSizeBytes}' FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? AND id=?",String.class,orgA,inspectionForm.id(),inspectionDraft.id()));
        var clonedInspection=forms.duplicate(orgA,"form-admin",inspectionForm.id(),new com.ecabin.ledger.api.FormModels.DuplicateFormRequest("INSPECTION_COPY_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Inspection copy",area,FormPurpose.INSPECTION,WorkflowStage.INSPECTION_REQUIRED,inspectionDraft.id()));
        assertEquals(30L*1024*1024,forms.version(orgA,clonedInspection.id(),clonedInspection.draftVersionId()).configuration().fields().get(0).fileRules().maxTotalSizeBytes());
        assertEquals(inspectionForm.id(),forms.publishedForArea(orgA,area,com.ecabin.ledger.api.FormModels.FormPurpose.INSPECTION,com.ecabin.ledger.api.FormModels.WorkflowStage.INSPECTION_REQUIRED).get(0).id());
        assertThrows(IllegalArgumentException.class,()->forms.publishedForDefect(orgA,defect.id(),com.ecabin.ledger.api.FormModels.FormPurpose.INSPECTION),"A form mapping cannot override a defect's actual REPORTED status.");
        var result=forms.submit(orgA,"form-reporter",defect.id(),new SubmitFormRequest(created.id(),published.id(),reportAnswers(orgA,component,condition,"FWD CABIN / ROW 14 / SEAT 14A","Test title","Test defect description")));
        assertEquals(published.id(),result.versionId());
        assertEquals(com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT,result.purpose());
        assertEquals(com.ecabin.ledger.api.FormModels.WorkflowStage.INITIAL_REPORT,result.workflowStage());
        assertEquals(created.id(),result.formId());
        assertEquals(published.id(),result.versionId());
        assertEquals("FWD CABIN / ROW 14 / SEAT 14A",result.snapshot().zone());
        assertEquals(1,forms.submissions(orgA,defect.id()).size());
        assertThrows(ResponseStatusException.class,()->forms.submissions(orgB,defect.id()));
        assertThrows(ResponseStatusException.class,()->forms.submit(orgA,"form-reporter",defect.id(),new SubmitFormRequest(created.id(),published.id(),java.util.Map.of("finding","OK","component",component.toString(),"condition",UUID.randomUUID().toString()))));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='FORM_SUBMISSION' AND entity_id=?",Integer.class,orgA,result.id()));
    }

    @Test
    @Transactional
    @Rollback
    void legacyGalleySeveritySourceUsesCanonicalTenantOptionsAndCopiesToCabinWithoutChangingHistory() {
        UUID orgA=createTenant("SEVERITY_COPY_A"),orgB=createTenant("SEVERITY_COPY_B");
        UUID galleyArea=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY' AND isactive=1",UUID.class,orgA);
        UUID cabinArea=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN' AND isactive=1",UUID.class,orgA);

        var source=forms.create(orgA,"form-admin",new CreateFormRequest("GALLEY_SEVERITY_SOURCE","Galley severity source",galleyArea));
        var sourceDraft=forms.saveDraft(orgA,"form-admin",source.id(),source.draftVersionId(),reportConfiguration());
        var sourcePublished=forms.publish(orgA,"form-admin",source.id(),sourceDraft.id());
        jdbc.update("UPDATE operational_form_versions_dtl SET fields_json=jsonb_set(fields_json,'{fields,6,optionSource}',to_jsonb('GALLEY_SEVERITIES'::text)) WHERE operator_id=? AND form_id=? AND id=? AND status='PUBLISHED'",orgA,source.id(),sourcePublished.id());
        assertEquals("GALLEY_SEVERITIES",jdbc.queryForObject("SELECT fields_json #>> '{fields,6,optionSource}' FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? AND id=?",String.class,orgA,source.id(),sourcePublished.id()));

        var severityField=forms.version(orgA,source.id(),sourcePublished.id()).configuration().fields().get(6);
        var galleyOptions=forms.options(orgA,source.id(),sourcePublished.id(),severityField.key(),galleyArea,null);
        var expectedActiveSeverities=jdbc.query("SELECT id,severity_code AS code,severity_name AS name FROM defect_severities_mst WHERE operator_id=? AND isactive=1 ORDER BY sort_order,severity_code LIMIT 500",(rs,n)->new com.ecabin.ledger.api.FormModels.FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),orgA);
        assertEquals(expectedActiveSeverities,galleyOptions,"Legacy published Galley forms must use the full active tenant severity catalog and its canonical ordering.");
        UUID foreignSeverity=jdbc.queryForObject("SELECT id FROM defect_severities_mst WHERE operator_id=? AND severity_code='MEDIUM' AND isactive=1",UUID.class,orgB);
        assertFalse(galleyOptions.stream().anyMatch(option->option.id().equals(foreignSeverity)),"Severity options remain tenant-scoped.");

        var firstCopy=forms.duplicate(orgA,"form-admin",source.id(),new com.ecabin.ledger.api.FormModels.DuplicateFormRequest("CABIN_SEVERITY_COPY_A","Cabin severity copy A",cabinArea,FormPurpose.DEFECT_REPORT,WorkflowStage.INITIAL_REPORT,sourcePublished.id()));
        var copiedVersion=forms.version(orgA,firstCopy.id(),firstCopy.draftVersionId());
        assertEquals(OptionSource.DEFECT_SEVERITIES,copiedVersion.configuration().fields().get(6).optionSource());
        assertEquals("GALLEY_SEVERITIES",jdbc.queryForObject("SELECT fields_json #>> '{fields,6,optionSource}' FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? AND id=?",String.class,orgA,source.id(),sourcePublished.id()),"Copying must not rewrite the original published version.");

        var secondCopy=forms.duplicate(orgA,"form-admin",source.id(),new com.ecabin.ledger.api.FormModels.DuplicateFormRequest("CABIN_SEVERITY_COPY_B","Cabin severity copy B",cabinArea,FormPurpose.DEFECT_REPORT,WorkflowStage.INITIAL_REPORT,sourcePublished.id()));
        assertEquals(OptionSource.DEFECT_SEVERITIES,forms.version(orgA,secondCopy.id(),secondCopy.draftVersionId()).configuration().fields().get(6).optionSource());
        var optionalSeverity=reportConfiguration().fields().stream().map(field->field.binding()==DefectFieldBinding.SEVERITY
            ?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),false,field.order(),field.textRules(),field.numericRules(),field.dateRules(),field.optionSource(),field.optionFilter(),field.fileRules(),field.binding(),field.visible(),field.readOnly(),field.visibleWhen(),field.requiredWhen())
            :field).toList();
        assertThrows(IllegalArgumentException.class,()->forms.saveDraft(orgA,"form-admin",secondCopy.id(),secondCopy.draftVersionId(),new FormConfiguration(optionalSeverity)),"Severity remains mandatory under the existing domain policy.");

        var legacyDraft=forms.saveDraft(orgA,"form-admin",firstCopy.id(),copiedVersion.id(),forms.version(orgA,source.id(),sourcePublished.id()).configuration());
        assertEquals(OptionSource.DEFECT_SEVERITIES,legacyDraft.configuration().fields().get(6).optionSource(),"Saving a draft derived from legacy configuration writes the canonical source.");
        var cabinPublished=forms.publish(orgA,"form-admin",firstCopy.id(),legacyDraft.id());
        var cabinOptions=forms.options(orgA,firstCopy.id(),cabinPublished.id(),"severity_ref",cabinArea,null);
        assertEquals(expectedActiveSeverities,cabinOptions,"Galley and Cabin use identical severity records and ordering.");

        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,"form-admin");
        UUID extraSeverity=UUID.randomUUID();
        jdbc.update("INSERT INTO defect_severities_mst(id,operator_id,severity_code,severity_name,description,sort_order) VALUES(?,?,?,'Cabin extra','Integration fixture',5)",extraSeverity,orgA,"EXTRA_"+extraSeverity.toString().substring(0,8).toUpperCase());
        assertTrue(forms.options(orgA,firstCopy.id(),cabinPublished.id(),"severity_ref",cabinArea,null).stream().anyMatch(option->option.id().equals(extraSeverity)),"New active tenant severity rows appear without an area-specific code list.");

        UUID component=UUID.randomUUID();
        jdbc.update("INSERT INTO cabin_components_mst(id,operator_id,area_id,code,name) VALUES(?,?,?,?,?)",component,orgA,cabinArea,"CMP_"+component.toString().substring(0,8),"Cabin fixture component");
        UUID condition=createCondition(orgA,component);
        var answers=new java.util.HashMap<>(reportAnswers(orgA,component,condition,"FWD CABIN","Fixture title","Fixture description"));
        answers.put("severity_ref",foreignSeverity.toString());
        assertThrows(IllegalArgumentException.class,()->forms.resolveDefectReport(orgA,cabinArea,firstCopy.id(),cabinPublished.id(),answers),"A cross-tenant severity UUID must be rejected during submission resolution.");
        answers.put("severity_ref",extraSeverity.toString());
        jdbc.update("UPDATE defect_severities_mst SET isactive=2 WHERE operator_id=? AND id=?",orgA,extraSeverity);
        assertThrows(IllegalArgumentException.class,()->forms.resolveDefectReport(orgA,cabinArea,firstCopy.id(),cabinPublished.id(),answers),"An inactive severity UUID must be rejected during submission resolution.");

        var invalidAreaCopy=forms.create(orgA,"form-admin",new CreateFormRequest("GALLEY_REMARKS_SOURCE","Galley remarks source",galleyArea));
        var compactGalley=new FormConfiguration(List.of(
            new FieldDefinition("aircraftId","Aircraft Registration",FieldType.AIRCRAFT_SELECT,null,true,0,null,null,null,OptionSource.AIRCRAFT,null,null,DefectFieldBinding.AIRCRAFT_ID),
            new FieldDefinition("location","Galley location",FieldType.TEXT,null,true,1,null,null,null,null,null,null,DefectFieldBinding.ZONE),
            new FieldDefinition("component_ref","Component",FieldType.DROPDOWN,null,true,2,null,null,null,OptionSource.COMPONENTS,null,null,DefectFieldBinding.COMPONENT_ID),
            new FieldDefinition("condition_ref","Condition",FieldType.DROPDOWN,null,true,3,null,null,null,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component_ref"),null,DefectFieldBinding.CONDITION_ID),
            new FieldDefinition("severity_ref","Severity",FieldType.DROPDOWN,null,true,4,null,null,null,OptionSource.DEFECT_SEVERITIES,null,null,DefectFieldBinding.SEVERITY),
            new FieldDefinition("remarks","Remarks",FieldType.TEXTAREA,null,false,5,new TextRules(null,4000,false,false,null),null,null,null,null,null,DefectFieldBinding.REMARKS),
            new FieldDefinition("evidence","Evidence",FieldType.IMAGE_UPLOAD,null,true,6,null,null,null,null,null,new com.ecabin.ledger.api.FormModels.FileRules(List.of("jpg"),List.of("image/jpeg"),5_242_880L,1,3),DefectFieldBinding.EVIDENCE_ATTACHMENTS)));
        var invalidSourceDraft=forms.saveDraft(orgA,"form-admin",invalidAreaCopy.id(),invalidAreaCopy.draftVersionId(),compactGalley);
        var invalidSourcePublished=forms.publish(orgA,"form-admin",invalidAreaCopy.id(),invalidSourceDraft.id());
        var targetAreaFailure=assertThrows(IllegalArgumentException.class,()->forms.duplicate(orgA,"form-admin",invalidAreaCopy.id(),new com.ecabin.ledger.api.FormModels.DuplicateFormRequest("INVALID_CABIN_COPY","Invalid Cabin copy",cabinArea,FormPurpose.DEFECT_REPORT,WorkflowStage.INITIAL_REPORT,invalidSourcePublished.id())));
        assertTrue(targetAreaFailure.getMessage().contains("Remarks")&&targetAreaFailure.getMessage().contains("not applicable to the selected cabin area"),"Cross-area duplication must retain actionable target-area binding validation.");
        assertEquals("GALLEY_SEVERITIES",jdbc.queryForObject("SELECT fields_json #>> '{fields,6,optionSource}' FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? AND id=?",String.class,orgA,source.id(),sourcePublished.id()));
    }

    @Test
    @Transactional
    @Rollback
    void configurationCrudIsTenantScopedAndAudited() {
        UUID orgA = createTenant("CONFIG-A");
        UUID orgB = createTenant("CONFIG-B");
        addMember(orgA, "config-admin", "ADMIN");

        var fleet = configuration.save(orgA, "config-admin", "fleets", new SaveRequest(null, java.util.Map.of("name", "Cabin Fleet")));
        String fleetId = fleet.get("id").toString();
        assertEquals(1, configuration.list(orgA, "fleets", false).stream().filter(row -> "Cabin Fleet".equals(row.get("name"))).count());
        assertEquals(0, configuration.list(orgB, "fleets", false).stream().filter(row -> "Cabin Fleet".equals(row.get("name"))).count());

        var aircraft = configuration.save(orgA, "config-admin", "aircraft", new SaveRequest(null, java.util.Map.of(
            "fleetId", fleetId, "tailNumber", "CFG-A320", "aircraftType", "A320-200")));
        assertEquals("CFG-A320", aircraft.get("tailNumber"));

        var category = configuration.save(orgA, "config-admin", "defect-categories", new SaveRequest(null, java.util.Map.of(
            "code", "CABIN_FIXTURE", "name", "Cabin fixture", "description", "Demo category")));
        String categoryId = category.get("id").toString();
        var categoryAuditId = UUID.fromString(categoryId);
        assertThrows(IllegalArgumentException.class, () -> configuration.save(orgA, "config-admin", "defect-categories", new SaveRequest(categoryId,
            java.util.Map.of("code","CABIN_FIXTURE","name","Cabin fixture","created_by","spoofed-actor"))));
        configuration.deactivate(orgA, "config-deactivator", "defect-categories", categoryId, "Retired in integration fixture");
        assertEquals(0, configuration.list(orgA, "defect-categories", false).stream().filter(row -> categoryId.equals(row.get("id").toString())).count());
        assertEquals(1, configuration.list(orgA, "defect-categories", true).stream().filter(row -> categoryId.equals(row.get("id").toString())).count());
        var metadata = jdbc.queryForMap("SELECT created_by,created_at,updated_by,updated_at FROM defect_categories_mst WHERE operator_id=? AND id=?", orgA, categoryAuditId);
        assertEquals("config-admin", metadata.get("created_by"));
        assertEquals("config-deactivator", metadata.get("updated_by"));
        assertNotNull(metadata.get("created_at"));
        assertNotNull(metadata.get("updated_at"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='DEFECT_CATEGORIES' AND entity_id=?", Integer.class, orgA, categoryAuditId));
    }

    @Test
    @Transactional
    @Rollback
    void userTeamMembershipsUseActiveTenantTeamIdsAndPreserveMembershipHistory() {
        UUID orgA = createTenant("USER-TEAM-A");
        UUID orgB = createTenant("USER-TEAM-B");
        addMember(orgA, "team-admin", "ADMIN");
        var maintenance = configuration.save(orgA, "team-admin", "maintenance-teams", new SaveRequest(null,
            java.util.Map.of("name", "Cabin Maintenance", "teamType", "MAINTENANCE")));
        var reviewer = configuration.save(orgA, "team-admin", "maintenance-teams", new SaveRequest(null,
            java.util.Map.of("name", "Reviewer Team", "teamType", "REVIEWER")));
        var foreignTeam = configuration.save(orgB, "other-admin", "maintenance-teams", new SaveRequest(null,
            java.util.Map.of("name", "Other Organization Team", "teamType", "MAINTENANCE")));
        String maintenanceId = String.valueOf(maintenance.get("id"));
        String reviewerId = String.valueOf(reviewer.get("id"));
        String foreignTeamId = String.valueOf(foreignTeam.get("id"));
        var inactiveTeam = configuration.save(orgA, "team-admin", "maintenance-teams", new SaveRequest(null,
            java.util.Map.of("name", "Retired Team", "teamType", "MAINTENANCE")));
        String inactiveTeamId = String.valueOf(inactiveTeam.get("id"));
        configuration.deactivate(orgA, "team-admin", "maintenance-teams", inactiveTeamId, "No longer used in this test.");

        var created = configuration.save(orgA, "team-admin", "users", new SaveRequest(null, java.util.Map.of(
            "externalUserId", "multi-team-user", "displayName", "Multi Team User", "role", "INSPECTOR",
            "teamIds", maintenanceId + "|" + reviewerId)));
        assertEquals("INSPECTOR", created.get("role"), "team membership must not grant or replace an application role");
        assertEquals(java.util.Set.of(maintenanceId, reviewerId), java.util.Set.of(String.valueOf(created.get("teamIds")).split("\\|")));
        assertEquals("Cabin Maintenance|Reviewer Team", created.get("teamNames"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operator_membership_teams_mst WHERE operator_id=? AND external_user_id=? AND isactive=1", Integer.class, orgA, "multi-team-user"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='TEAM_MEMBERSHIP' AND event_type='TEAM_MEMBERSHIP_ADDED'", Integer.class, orgA));

        assertThrows(IllegalArgumentException.class, () -> configuration.save(orgA, "team-admin", "users", new SaveRequest("multi-team-user", java.util.Map.of(
            "displayName", "Multi Team User", "role", "INSPECTOR", "teamIds", foreignTeamId))));
        assertThrows(IllegalArgumentException.class, () -> configuration.save(orgA, "team-admin", "users", new SaveRequest("multi-team-user", java.util.Map.of(
            "displayName", "Multi Team User", "role", "INSPECTOR", "teamIds", UUID.randomUUID().toString()))));
        assertThrows(IllegalArgumentException.class, () -> configuration.save(orgA, "team-admin", "users", new SaveRequest("multi-team-user", java.util.Map.of(
            "displayName", "Multi Team User", "role", "INSPECTOR", "teamIds", inactiveTeamId))));

        var updated = configuration.save(orgA, "team-admin", "users", new SaveRequest("multi-team-user", java.util.Map.of(
            "displayName", "Multi Team User", "role", "INSPECTOR", "teamIds", reviewerId)));
        assertEquals(reviewerId, updated.get("teamIds"));
        assertEquals(2, jdbc.queryForObject("SELECT isactive FROM operator_membership_teams_mst WHERE operator_id=? AND external_user_id=? AND team_name='Cabin Maintenance'", Integer.class, orgA, "multi-team-user"));
        assertEquals(1, jdbc.queryForObject("SELECT isactive FROM operator_membership_teams_mst WHERE operator_id=? AND external_user_id=? AND team_name='Reviewer Team'", Integer.class, orgA, "multi-team-user"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='TEAM_MEMBERSHIP' AND event_type='TEAM_MEMBERSHIP_REMOVED'", Integer.class, orgA));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='TEAM_MEMBERSHIP' AND event_type='TEAM_MEMBERSHIP_ADDED'", Integer.class, orgA), "unchanged memberships should not be re-added");

        configuration.save(orgA, "team-admin", "users", new SaveRequest("multi-team-user", java.util.Map.of(
            "displayName", "Multi Team User", "role", "INSPECTOR", "teamIds", "")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operator_membership_teams_mst WHERE operator_id=? AND external_user_id=? AND isactive=1", Integer.class, orgA, "multi-team-user"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='TEAM_MEMBERSHIP' AND event_type='TEAM_MEMBERSHIP_REMOVED'", Integer.class, orgA));
    }

    @Test
    @Transactional
    @Rollback
    void componentsRequireAnActiveAreaFromTheSameTenantAndKeepAreaUseSafe() {
        UUID orgA = createTenant("AREA-A");
        UUID orgB = createTenant("AREA-B");
        UUID cabinA = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'", UUID.class, orgA);
        UUID cabinB = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='CABIN'", UUID.class, orgB);
        var activeAreaPage=configuration.table(orgA,"areas",0,25,"Cabin","ACTIVE",null,null,null,"name","ASC");
        assertTrue(activeAreaPage.items().stream().anyMatch(row->cabinA.equals(row.get("id"))));
        assertFalse(activeAreaPage.items().stream().anyMatch(row->cabinB.equals(row.get("id"))),"Area Master results must never cross organization boundaries.");
        var extraArea=configuration.save(orgA,"admin-a","areas",new SaveRequest(null,java.util.Map.of("code","TABLE_TEST_AREA","name","Table test area","description","Area Master table regression fixture")));
        assertEquals(1,configuration.table(orgA,"areas",0,25,"Table test area","ACTIVE",null,null,null,"name","ASC").totalElements());
        configuration.deactivate(orgA,"admin-a","areas",String.valueOf(extraArea.get("id")),"Remove the unused table fixture");
        assertEquals(0,configuration.table(orgA,"areas",0,25,"Table test area","ACTIVE",null,null,null,"name","ASC").totalElements());
        assertEquals(1,configuration.table(orgA,"areas",0,25,"Table test area","INACTIVE",null,null,null,"name","ASC").totalElements());
        var component = configuration.save(orgA, "admin-a", "components", new SaveRequest(null,
            java.util.Map.of("code","SEAT_COVER_TEST","name","Seat Cover","areaId",cabinA.toString(),"description","Synthetic integration fixture")));
        assertEquals(cabinA, component.get("areaId"));
        assertThrows(ResponseStatusException.class, () -> configuration.deactivate(orgA,"admin-a","areas",cabinA.toString(),"Area is still in use"));
        assertThrows(IllegalArgumentException.class, () -> configuration.save(orgA, "admin-a", "components", new SaveRequest(null,
            java.util.Map.of("code","CROSS_TENANT_TEST","name","Cross Tenant","areaId",cabinB.toString()))));

        UUID entryArea = jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='ENTRY_AREA'", UUID.class, orgA);
        UUID aircraftArea = jdbc.queryForObject("SELECT area_id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'", UUID.class, orgA);
        UUID aircraftId = jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=?", UUID.class, orgA);
        String tail = jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE id=?", String.class, aircraftId);
        UUID trayTableId = jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'",UUID.class,orgA);
        UUID conditionId = createCondition(orgA, trayTableId);
        var invalidDefect = new CreateDefectRequest(tail,"CABIN","SEAT",entryArea,trayTableId,conditionId,"FWD CABIN",14,"14A",null,"Invalid component area","Must be rejected","LOW");
        assertNotEquals(entryArea, aircraftArea);
        assertThrows(IllegalArgumentException.class, () -> defects.create(orgA,"reporter",invalidDefect));
    }

    @Test
    @Transactional
    @Rollback
    void conditionMasterIsTenantScopedAndBoundToActiveComponents() {
        UUID orgA = createTenant("CONDITION-A");
        UUID orgB = createTenant("CONDITION-B");
        UUID componentA = jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'", UUID.class, orgA);
        UUID componentB = jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='TRAY_TABLE'", UUID.class, orgB);
        var condition = configuration.save(orgA, "condition-admin", "defect-conditions", new SaveRequest(null, java.util.Map.of(
            "code", "SURFACE_TEST", "name", "Surface damage", "description", "Synthetic fixture", "componentIds", componentA.toString())));
        UUID conditionId = UUID.fromString(condition.get("id").toString());
        assertEquals(1, configuration.conditionsForComponent(orgA, componentA).stream().filter(option -> option.id().equals(conditionId)).count());
        assertEquals(0, configuration.conditionsForComponent(orgB, componentB).stream().filter(option -> option.code().equals("SURFACE_TEST")).count());
        assertThrows(IllegalArgumentException.class, () -> configuration.save(orgB, "condition-admin", "defect-conditions", new SaveRequest(null, java.util.Map.of(
            "code", "CROSS_TENANT", "name", "Must fail", "componentIds", componentA.toString()))));

        UUID aircraftId = jdbc.queryForObject("SELECT id FROM aircraft_mst WHERE operator_id=?", UUID.class, orgA);
        String tail = jdbc.queryForObject("SELECT tail_number FROM aircraft_mst WHERE id=?", String.class, aircraftId);
        UUID area = jdbc.queryForObject("SELECT area_id FROM cabin_components_mst WHERE id=?", UUID.class, componentA);
        var created = defects.create(orgA, "reporter", new CreateDefectRequest(tail, "CABIN", "SEAT", area, componentA, conditionId,
            "FWD CABIN", 2, "2A", null, "Surface finding", "Synthetic fixture only", "LOW"));
        assertEquals("Surface damage", created.conditionName());
        assertThrows(IllegalArgumentException.class, () -> defects.create(orgA, "reporter", new CreateDefectRequest(tail, "CABIN", "SEAT", area,
            componentA, UUID.randomUUID(), "FWD CABIN", 2, "2A", null, "Unknown condition", "Must fail", "LOW")));

        String csv = "condition_code,condition_name,description,applicable_component_codes\r\nCSV_DAMAGE,Surface damage,Demo observation only,TRAY_TABLE\r\n";
        var file = new MockMultipartFile("file", "conditions.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var preview = imports.validate(orgA, "condition-admin", "defect-conditions", file);
        assertEquals(1, preview.validRows());
        assertEquals(1, imports.commit(orgA, "condition-admin", "defect-conditions", preview.importId()).created());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM component_conditions_mst m JOIN defect_conditions_mst c ON c.operator_id=m.operator_id AND c.id=m.condition_id WHERE m.operator_id=? AND c.code='CSV_DAMAGE' AND m.component_id=? AND m.isactive=1", Integer.class, orgA, componentA));
    }

    @Test
    @Transactional
    @Rollback
    void csvPreviewDoesNotWriteUntilConfirmationAndCommitIsIdempotentAndAudited() {
        UUID operator = createTenant("IMPORT");
        String csv = "area_code,area_name,description\r\n"+
            "IMPORT_AREA,\"Entry, forward\",\"Text with \"\"quoted\"\" words\"\r\n"+
            "IMPORT_AREA,Duplicate,Duplicate code in file\r\n"+
            "CABIN,Existing,Already exists\r\n"+
            "EXTRA,Unexpected,Too,Many\r\n";
        var file = new MockMultipartFile("file","areas.csv","text/csv",csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var preview = imports.validate(operator,"admin-importer","areas",file);
        assertEquals(1, preview.validRows());
        assertEquals(3, preview.rejectedRows());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cabin_areas_mst WHERE operator_id=? AND code='IMPORT_AREA'",Integer.class,operator));
        var committed = imports.commit(operator,"admin-importer","areas",preview.importId());
        assertEquals(1, committed.created());
        assertEquals(3, committed.rejected());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cabin_areas_mst WHERE operator_id=? AND code='IMPORT_AREA' AND name='Entry, forward'",Integer.class,operator));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id=? AND entity_type='AREAS' AND event_type='MASTER_DATA_CREATED'",Integer.class,operator));
        assertEquals(committed, imports.commit(operator,"admin-importer","areas",preview.importId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cabin_areas_mst WHERE operator_id=? AND code='IMPORT_AREA'",Integer.class,operator));
    }

    private UUID createTenant(String suffix) {
        UUID org = UUID.randomUUID();
        UUID fleet = UUID.randomUUID();
        UUID aircraft = UUID.randomUUID();
        jdbc.update("INSERT INTO operators_mst(id,name) VALUES(?,?)", org, "Integration Demo " + suffix);
        jdbc.update("INSERT INTO fleets_mst(id,operator_id,name) VALUES(?,?,?)", fleet, org, "Fleet " + suffix);
        jdbc.update("INSERT INTO aircraft_mst(id,operator_id,fleet_id,tail_number,aircraft_type) VALUES(?,?,?,?,?)",
            aircraft, org, fleet, "N" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(), "A320-200");
        return org;
    }

    private FormConfiguration reportConfiguration() {
        return new FormConfiguration(List.of(
            new FieldDefinition("category_ref","Defect Category",FieldType.DROPDOWN,null,true,0,null,null,null,OptionSource.DEFECT_CATEGORIES,null,null,DefectFieldBinding.DEFECT_CATEGORY),
            new FieldDefinition("component_ref","Component",FieldType.DROPDOWN,null,true,1,null,null,null,OptionSource.COMPONENTS,null,null,DefectFieldBinding.COMPONENT_ID),
            new FieldDefinition("condition_ref","Defect Condition",FieldType.DROPDOWN,null,true,2,null,null,null,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component_ref"),null,DefectFieldBinding.CONDITION_ID),
            new FieldDefinition("zone","Location",FieldType.TEXT,null,true,3,new TextRules(1,80,false,false,null),null,null,null,null,null,DefectFieldBinding.ZONE),
            new FieldDefinition("title","Finding",FieldType.TEXT,null,true,4,new TextRules(1,160,false,false,null),null,null,null,null,null,DefectFieldBinding.TITLE),
            new FieldDefinition("description","Details",FieldType.TEXTAREA,null,true,5,new TextRules(1,4000,false,false,null),null,null,null,null,null,DefectFieldBinding.DESCRIPTION),
            new FieldDefinition("severity_ref","Severity",FieldType.DROPDOWN,null,true,6,null,null,null,OptionSource.DEFECT_SEVERITIES,null,null,DefectFieldBinding.SEVERITY),
            new FieldDefinition("aircraftId","Aircraft Registration",FieldType.AIRCRAFT_SELECT,null,true,7,null,null,null,OptionSource.AIRCRAFT,null,null,DefectFieldBinding.AIRCRAFT_ID)));
    }

    private FormConfiguration reviewDecisionConfiguration(){
        return new FormConfiguration(List.of(
            new FieldDefinition("decision","Decision",FieldType.DROPDOWN,null,true,0,null,null,null,OptionSource.REVIEW_DECISIONS,null,null,DefectFieldBinding.REVIEW_DECISION,true,false),
            new FieldDefinition("maintenanceTeam","Assign Team",FieldType.DROPDOWN,null,true,1,null,null,null,OptionSource.MAINTENANCE_TEAMS,null,null,DefectFieldBinding.REVIEW_MAINTENANCE_TEAM,true,false,new com.ecabin.ledger.api.FormModels.VisibilityRule("decision","APPROVE")),
            new FieldDefinition("rejectionReason","Rejection Reason",FieldType.TEXTAREA,null,true,2,new TextRules(null,2000,false,false,null),null,null,null,null,null,DefectFieldBinding.REVIEW_REJECTION_REASON,true,false,new com.ecabin.ledger.api.FormModels.VisibilityRule("decision","REJECT")),
            new FieldDefinition("remark","Remark",FieldType.TEXTAREA,null,false,3,new TextRules(null,2000,false,false,null),null,null,null,null,null,DefectFieldBinding.REVIEW_REMARK,true,false)));
    }

    private java.util.Map<String,Object> reportAnswers(UUID org,UUID component,UUID condition,String zone,String title,String description) {
        UUID category=jdbc.query("SELECT id FROM defect_categories_mst WHERE operator_id=? AND code='SEAT' AND isactive=1",rs->rs.next()?rs.getObject("id",UUID.class):null,org);
        if(category==null){category=UUID.randomUUID();jdbc.update("INSERT INTO defect_categories_mst(id,operator_id,code,name) VALUES(?,?,'SEAT','Passenger seat')",category,org);}
        UUID severity=jdbc.queryForObject("SELECT id FROM defect_severities_mst WHERE operator_id=? AND severity_code='MEDIUM' AND isactive=1",UUID.class,org);
        return java.util.Map.of("category_ref",category.toString(),"component_ref",component.toString(),"condition_ref",condition.toString(),"zone",zone,"title",title,"description",description,"severity_ref",severity.toString());
    }

    private java.util.Map<String,Object> galleyReportAnswers(UUID org,UUID component,UUID condition,String zone,String title,String description,String severityCode) {
        UUID category=jdbc.query("SELECT id FROM defect_categories_mst WHERE operator_id=? AND code='GALLEY' AND isactive=1",rs->rs.next()?rs.getObject("id",UUID.class):null,org);
        if(category==null){category=UUID.randomUUID();jdbc.update("INSERT INTO defect_categories_mst(id,operator_id,code,name) VALUES(?,?,'GALLEY','Galley finding')",category,org);}
        UUID severity=jdbc.queryForObject("SELECT id FROM defect_severities_mst WHERE operator_id=? AND severity_code=? AND isactive=1",UUID.class,org,severityCode);
        return java.util.Map.of("category_ref",category.toString(),"component_ref",component.toString(),"condition_ref",condition.toString(),"zone",zone,"title",title,"description",description,"severity_ref",severity.toString());
    }

    private void addMember(UUID org, String userId, String role) {
        jdbc.update("INSERT INTO operator_memberships_mst(operator_id,external_user_id,display_name,role) VALUES(?,?,?,?)",
            org, userId, userId + " (DEMO)", role);
    }

    private UUID createCondition(UUID org, UUID componentId) {
        UUID id = UUID.randomUUID();
        String code = "TEST_" + id.toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO defect_conditions_mst(id,operator_id,code,name) VALUES(?,?,?,'Test condition')", id, org, code);
        jdbc.update("INSERT INTO component_conditions_mst(operator_id,component_id,condition_id) VALUES(?,?,?)", org, componentId, id);
        return id;
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required DB verification setting is missing: " + key);
        return value;
    }
}
