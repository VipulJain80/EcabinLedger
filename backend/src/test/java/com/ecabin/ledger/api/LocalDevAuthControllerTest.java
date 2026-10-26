package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ecabin.ledger.config.LocalAuthConfig;
import com.ecabin.ledger.service.UserLoginMonitoringService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.server.ResponseStatusException;

class LocalDevAuthControllerTest {
    private static final List<String> LOCAL_IDENTITIES = List.of(
        "abc@gmail.com", "admin.test@ecabin.local", "auditor.test@ecabin.local",
        "reviewer.test@ecabin.local", "maintenance.test@ecabin.local");

    @Test
    void issuesOnlyForTheFiveConfiguredActiveTenantIdentities() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate(true);
        JwtEncoder encoder = parameters -> new Jwt("local-demo-token", Instant.now(), Instant.now().plusSeconds(1800),
            Map.of("alg", "HS256"), Map.of("sub", parameters.getClaims().getSubject()));
        LocalDevAuthController controller = new LocalDevAuthController(jdbc, encoder, String.join(",", LOCAL_IDENTITIES));

        for (String email : LOCAL_IDENTITIES) {
            var response = controller.issueToken(new LocalDevAuthController.TokenRequest(email.toUpperCase()));
            assertEquals("local-demo-token", response.accessToken());
            long secondsRemaining = response.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
            assertTrue(secondsRemaining >= 1790 && secondsRemaining <= 1800);
        }
        assertEquals(LOCAL_IDENTITIES, jdbc.checkedSubjects);
    }

    @Test
    void refusesOtherEmailsAndMembershipsMissingFromTheDemoTenant() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate(false);
        JwtEncoder encoder = parameters -> { throw new AssertionError("Must not issue a token without membership."); };
        LocalDevAuthController controller = new LocalDevAuthController(jdbc, encoder, String.join(",", LOCAL_IDENTITIES));

        assertThrows(ResponseStatusException.class, () -> controller.issueToken(
            new LocalDevAuthController.TokenRequest("other@example.com")));
        assertEquals(0, jdbc.checkedSubjects.size());

        assertThrows(ResponseStatusException.class, () -> controller.issueToken(
            new LocalDevAuthController.TokenRequest("reviewer.test@ecabin.local")));
        assertEquals(List.of("reviewer.test@ecabin.local"), jdbc.checkedSubjects);
    }

    @Test
    void recordsLocalLoginOnlyAfterAnActiveAllowListedIdentityReceivesAToken() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate(true);
        JwtEncoder encoder = parameters -> new Jwt("local-demo-token", Instant.now(), Instant.now().plusSeconds(1800),
            Map.of("alg", "HS256"), Map.of("sub", parameters.getClaims().getSubject()));
        UserLoginMonitoringService monitoring = mock(UserLoginMonitoringService.class);
        LocalDevAuthController controller = new LocalDevAuthController(jdbc, encoder, monitoring, "abc@gmail.com");

        controller.issueToken(new LocalDevAuthController.TokenRequest("ABC@gmail.com"));

        verify(monitoring).recordSuccessfulLogin(java.util.UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "abc@gmail.com", UserLoginMonitoringService.LoginSource.LOCAL_DEMO);
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final boolean active;
        private final java.util.ArrayList<String> checkedSubjects = new java.util.ArrayList<>();
        private StubJdbcTemplate(boolean active) { this.active = active; }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            assertEquals("SELECT EXISTS (SELECT 1 FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=? AND isactive=1)", sql);
            assertEquals(java.util.UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), args[0]);
            checkedSubjects.add((String) args[1]);
            return requiredType.cast(active);
        }
    }
}
