package com.ecabin.ledger.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.EnumSet;

@Component
public class PermissionService {
    private final JdbcTemplate jdbc;
    public PermissionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Compatibility constructor for isolated unit tests; the application uses tenant-backed grants. */
    public PermissionService() { this.jdbc = null; }

    public void require(AuthenticatedUser user, Permission permission) {
        if (!permissionsFor(user.organizationId(), user.role()).contains(permission))
            throw new AccessDeniedException("Your application role does not allow this operation.");
    }

    public Set<Permission> permissionsFor(java.util.UUID organizationId, String role) {
        if (jdbc == null) return legacyPermissions(role);
        String granted = jdbc.query("SELECT permissions FROM operator_roles WHERE operator_id=? AND role_key=? AND isactive=1", rs -> rs.next() ? rs.getString(1) : "", organizationId, role);
        try {
            return Arrays.stream(granted.split("\\|")).filter(value -> !value.isBlank()).map(Permission::valueOf).collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException ex) {
            return Set.of();
        }
    }

    public Set<Permission> permissionsFor(AppRole role) { return legacyPermissions(role.name()); }

    private Set<Permission> legacyPermissions(String role) {
        return switch (role) {
            case "ADMIN" -> Set.copyOf(EnumSet.allOf(Permission.class));
            case "SUPERVISOR" -> Set.of(Permission.VIEW, Permission.REPORT, Permission.REVIEW, Permission.INSPECT, Permission.ASSIGN_ACTION, Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN, Permission.AUDIT);
            case "INSPECTOR" -> Set.of(Permission.VIEW, Permission.REPORT, Permission.INSPECT);
            case "MAINTENANCE_TECHNICIAN" -> Set.of(Permission.VIEW, Permission.PERFORM_ACTION);
            case "QUALITY_COMPLIANCE" -> Set.of(Permission.VIEW, Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN, Permission.AUDIT);
            case "VIEWER" -> Set.of(Permission.VIEW);
            default -> Set.of();
        };
    }
}
