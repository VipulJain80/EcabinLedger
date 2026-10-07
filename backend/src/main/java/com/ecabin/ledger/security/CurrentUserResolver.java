package com.ecabin.ledger.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves application identity using only claims from a Spring-validated token and active DB membership. */
@Component
public class CurrentUserResolver {
    private final JdbcTemplate jdbc;
    public CurrentUserResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AuthenticatedUser require(JwtAuthenticationToken authentication) {
        try {
            UUID organizationId = UUID.fromString(authentication.getToken().getClaimAsString("operator_id"));
            String externalUserId = authentication.getToken().getSubject();
            if (externalUserId == null || externalUserId.isBlank()) throw new AccessDeniedException("The validated token has no subject.");
            return jdbc.query("SELECT o.name AS organization_name, m.display_name, m.role " +
                "FROM operator_memberships m JOIN operators o ON o.id=m.operator_id " +
                "WHERE m.operator_id=? AND m.external_user_id=? AND m.isactive=1 AND o.isactive=1",
                (rs, row) -> new AuthenticatedUser(organizationId, externalUserId,
                    rs.getString("display_name") == null ? externalUserId : rs.getString("display_name"),
                    AppRole.valueOf(rs.getString("role"))), organizationId, externalUserId)
                .stream().findFirst().orElseThrow(() -> new AccessDeniedException("No active organization membership exists for this user."));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new AccessDeniedException("The validated token has no valid organization assignment.");
        }
    }
}
