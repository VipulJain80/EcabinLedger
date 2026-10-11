package com.ecabin.ledger.security;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/** Reusable request authorization backed by the validated principal and tenant role grants. */
@Component
public class PermissionAuthorization {
    private final CurrentUserResolver users;
    private final PermissionService permissions;

    public PermissionAuthorization(CurrentUserResolver users, PermissionService permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    public AuthorizationManager<RequestAuthorizationContext> authenticatedMember() {
        return (authentication, context) -> new AuthorizationDecision(user(authentication) != null);
    }

    public AuthorizationManager<RequestAuthorizationContext> anyOf(Permission... required) {
        Set<Permission> expected = Arrays.stream(required).collect(Collectors.toUnmodifiableSet());
        return (authentication, context) -> {
            AuthenticatedUser user = user(authentication);
            if (user == null) return new AuthorizationDecision(false);
            Set<Permission> grants = permissions.permissionsFor(user.organizationId(), user.role());
            return new AuthorizationDecision(expected.stream().anyMatch(grants::contains));
        };
    }

    private AuthenticatedUser user(Supplier<Authentication> source) {
        Authentication authentication = source.get();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) return null;
        // Resolving membership here makes the request gate tenant-aware and rejects inactive users.
        return users.require(jwt);
    }
}
