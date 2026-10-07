package com.ecabin.ledger.security;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class PermissionService {
    private static final Map<AppRole, Set<Permission>> GRANTS = grants();

    private static Map<AppRole, Set<Permission>> grants() {
        Map<AppRole, Set<Permission>> result = new EnumMap<>(AppRole.class);
        result.put(AppRole.ADMIN, EnumSet.allOf(Permission.class));
        result.put(AppRole.SUPERVISOR, EnumSet.of(Permission.VIEW, Permission.REPORT, Permission.REVIEW, Permission.INSPECT, Permission.ASSIGN_ACTION, Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN, Permission.AUDIT));
        result.put(AppRole.INSPECTOR, EnumSet.of(Permission.VIEW, Permission.REPORT, Permission.INSPECT));
        result.put(AppRole.MAINTENANCE_TECHNICIAN, EnumSet.of(Permission.VIEW, Permission.PERFORM_ACTION));
        result.put(AppRole.QUALITY_COMPLIANCE, EnumSet.of(Permission.VIEW, Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN, Permission.AUDIT));
        result.put(AppRole.VIEWER, EnumSet.of(Permission.VIEW));
        return Map.copyOf(result);
    }

    public void require(AuthenticatedUser user, Permission permission) {
        if (!GRANTS.getOrDefault(user.role(), Set.of()).contains(permission))
            throw new AccessDeniedException("Your application role does not allow this operation.");
    }
}
