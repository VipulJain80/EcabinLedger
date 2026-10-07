package com.ecabin.ledger.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class PermissionServiceTest {
    private final PermissionService permissions = new PermissionService();
    private AuthenticatedUser user(AppRole role) { return new AuthenticatedUser(UUID.randomUUID(), "external-subject", "Demo User", role); }

    @Test
    void viewerIsReadOnly() {
        assertDoesNotThrow(() -> permissions.require(user(AppRole.VIEWER), Permission.VIEW));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.VIEWER), Permission.CLOSE));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.VIEWER), Permission.ADMINISTER_ORGANIZATION));
    }

    @Test
    void qualityRoleCanVerifyButCannotAssignCorrectiveAction() {
        assertDoesNotThrow(() -> permissions.require(user(AppRole.QUALITY_COMPLIANCE), Permission.VERIFY));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.QUALITY_COMPLIANCE), Permission.ASSIGN_ACTION));
    }

    @Test
    void onlyOrganizationAdminCanAdministerMemberships() {
        assertDoesNotThrow(() -> permissions.require(user(AppRole.ADMIN), Permission.ADMINISTER_ORGANIZATION));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.SUPERVISOR), Permission.ADMINISTER_ORGANIZATION));
    }
}
