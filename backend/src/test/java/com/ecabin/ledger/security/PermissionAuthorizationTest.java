package com.ecabin.ledger.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class PermissionAuthorizationTest {
    private final UUID organizationId = UUID.randomUUID();
    private final AuthenticatedUser operator = new AuthenticatedUser(organizationId, "enterprise-subject", "Demo User", "AUDITOR");

    @Test
    void rejectsNonJwtAuthenticationAtProtectedApiGate() {
        var decision = authorization(Set.of(Permission.REPORT)).anyOf(Permission.REPORT).check(
            () -> new UsernamePasswordAuthenticationToken("user", "password"), context());

        assertFalse(decision.isGranted());
    }

    @Test
    void allowsOnlyWhenResolvedTenantRoleHasRequiredPermission() {
        JwtAuthenticationToken authentication = jwtAuthentication();

        var decision = authorization(Set.of(Permission.REPORT)).anyOf(Permission.REPORT).check(() -> authentication, context());

        assertTrue(decision.isGranted());
    }

    @Test
    void deniesAuthenticatedMemberWhoseRoleDoesNotHavePermission() {
        JwtAuthenticationToken authentication = jwtAuthentication();

        var decision = authorization(Set.of(Permission.REPORT)).anyOf(Permission.ADMINISTER_ORGANIZATION).check(() -> authentication, context());

        assertFalse(decision.isGranted());
    }

    private RequestAuthorizationContext context() {
        return new RequestAuthorizationContext(new MockHttpServletRequest());
    }

    private PermissionAuthorization authorization(Set<Permission> grants) {
        CurrentUserResolver users = new CurrentUserResolver(null) {
            @Override public AuthenticatedUser require(JwtAuthenticationToken authentication) { return operator; }
        };
        PermissionService permissions = new PermissionService() {
            @Override public Set<Permission> permissionsFor(UUID tenant, String role) { return grants; }
        };
        return new PermissionAuthorization(users, permissions);
    }

    private JwtAuthenticationToken jwtAuthentication() {
        Instant now = Instant.now();
        Jwt token = Jwt.withTokenValue("test-token").header("alg", "none").subject("enterprise-subject")
            .claim("operator_id", organizationId.toString()).issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(token);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
