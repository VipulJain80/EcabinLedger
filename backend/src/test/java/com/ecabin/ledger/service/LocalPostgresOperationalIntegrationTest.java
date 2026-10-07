package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ecabin.ledger.api.ApiModels.ActionCompletionRequest;
import com.ecabin.ledger.api.ApiModels.ApprovalRequest;
import com.ecabin.ledger.api.ApiModels.CreateDefectRequest;
import com.ecabin.ledger.api.ApiModels.CorrectiveActionRequest;
import com.ecabin.ledger.api.ApiModels.DefectView;
import com.ecabin.ledger.api.ApiModels.InspectionRequest;
import com.ecabin.ledger.api.ApiModels.TransitionRequest;
import com.ecabin.ledger.api.ApiModels.VerificationRequest;
import com.ecabin.ledger.security.AppRole;
import com.ecabin.ledger.security.CurrentUserResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Runs against the explicitly supplied ecabin_ledger schema; all test rows are rolled back. */
@EnabledIfEnvironmentVariable(named = "ECABIN_DB_VERIFY", matches = "(?i)true")
@SpringBootTest(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://gateway.invalid/",
    "app.security.audience=ecabin-test-api",
    "spring.flyway.schemas=ecabin_ledger",
    "spring.flyway.default-schema=ecabin_ledger"
})
class LocalPostgresOperationalIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnv("SPRING_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> requiredEnv("SPRING_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnv("SPRING_DATASOURCE_PASSWORD"));
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefectService defects;
    @Autowired OperationalWorkflowService workflow;
    @Autowired CurrentUserResolver users;

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
        assertEquals(AppRole.SUPERVISOR, principal.role());

        String tailNumber = jdbc.queryForObject("SELECT tail_number FROM aircraft WHERE operator_id=?", String.class, orgA);
        DefectView defect = defects.create(orgA, principal.externalUserId(), new CreateDefectRequest(
            tailNumber, "CABIN", "SEAT", "FWD CABIN",
            14, "14A", "Seat", "Seat cover torn", "Cover torn along seam", "MEDIUM"));

        assertEquals(0, defects.list(orgB, null, null, null, 25).items().size());
        assertThrows(ResponseStatusException.class, () -> defects.get(orgB, defect.id()));
        assertThrows(ResponseStatusException.class, () -> defects.events(orgB, defect.id()));
        assertThrows(ResponseStatusException.class, () -> defects.transition(orgB, defect.id(), "reporter-b",
            new TransitionRequest("UNDER_REVIEW", "cross-tenant attempt", null, null)));

        defects.transition(orgA, defect.id(), "reporter-a", new TransitionRequest("UNDER_REVIEW", "Reviewed", null, null));
        defects.transition(orgA, defect.id(), "reporter-a", new TransitionRequest("INSPECTION_REQUIRED", "Inspection requested", null, null));
        defects.transition(orgA, defect.id(), "inspector-a", new TransitionRequest("INSPECTION_IN_PROGRESS", "Inspection started", null, null));
        workflow.completeInspection(orgA, defect.id(), "inspector-a", new InspectionRequest("PASS", "Seat cover inspection complete", "No structural damage."));
        var action = workflow.assignAction(orgA, defect.id(), "reporter-a",
            new CorrectiveActionRequest("tech-a", null, "Replace seat cover", "MEDIUM", Instant.now().plusSeconds(86400)));
        workflow.startAction(orgA, defect.id(), action.id(), "tech-a");
        workflow.completeAction(orgA, defect.id(), action.id(), "tech-a", new ActionCompletionRequest("Cover replaced and inspected."));
        workflow.verify(orgA, defect.id(), "quality-b", new VerificationRequest("PASS", "Repair confirmed", "EVIDENCE-DEMO-001"));
        defects.transition(orgA, defect.id(), "reporter-a", new TransitionRequest("APPROVAL_REQUIRED", "Quality verified", null, null));
        workflow.approve(orgA, defect.id(), "approver-c", new ApprovalRequest("APPROVED", "Closure reviewed", "CLOSURE-DEMO-001"));
        assertEquals("CLOSED", defects.get(orgA, defect.id()).status());
        assertEquals(11, defects.events(orgA, defect.id()).size());
        jdbc.update("UPDATE defects SET isactive=2 WHERE operator_id=? AND id=?", orgA, defect.id());
        assertThrows(ResponseStatusException.class, () -> defects.get(orgA, defect.id()));
    }

    private UUID createTenant(String suffix) {
        UUID org = UUID.randomUUID();
        UUID fleet = UUID.randomUUID();
        UUID aircraft = UUID.randomUUID();
        jdbc.update("INSERT INTO operators(id,name) VALUES(?,?)", org, "Integration Demo " + suffix);
        jdbc.update("INSERT INTO fleets(id,operator_id,name) VALUES(?,?,?)", fleet, org, "Fleet " + suffix);
        jdbc.update("INSERT INTO aircraft(id,operator_id,fleet_id,tail_number,aircraft_type) VALUES(?,?,?,?,?)",
            aircraft, org, fleet, "N" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(), "A320-200");
        return org;
    }

    private void addMember(UUID org, String userId, String role) {
        jdbc.update("INSERT INTO operator_memberships(operator_id,external_user_id,display_name,role) VALUES(?,?,?,?)",
            org, userId, userId + " (DEMO)", role);
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required DB verification setting is missing: " + key);
        return value;
    }
}
