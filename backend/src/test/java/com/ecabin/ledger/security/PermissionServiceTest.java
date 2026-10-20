package com.ecabin.ledger.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import com.ecabin.ledger.api.NavigationController;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.service.ConfigurationService;
import com.ecabin.ledger.api.ApiModels.NavigationItem;
import java.util.List;

class PermissionServiceTest {
    private final PermissionService permissions = new PermissionService();
    private AuthenticatedUser user(AppRole role) { return new AuthenticatedUser(UUID.randomUUID(), "external-subject", "Demo User", role.name()); }

    @Test
    void viewerIsReadOnly() {
        assertDoesNotThrow(() -> permissions.require(user(AppRole.VIEWER), Permission.VIEW));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.VIEWER), Permission.CLOSE));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.VIEWER), Permission.ADMINISTER_ORGANIZATION));
    }

    @Test
    void defectViewAllAllowsOnlyTheSeparateDefectReadCheck() {
        UUID tenant = UUID.randomUUID();
        AuthenticatedUser readAll = new AuthenticatedUser(tenant, "read-all", "Read All", "CUSTOM_READER");
        PermissionService custom = new PermissionService() {
            @Override public java.util.Set<Permission> permissionsFor(UUID operatorId, String role) {
                assertEquals(tenant, operatorId);
                assertEquals("CUSTOM_READER", role);
                return java.util.Set.of(Permission.DEFECT_VIEW_ALL);
            }
        };

        assertTrue(PermissionService.hasDefectViewAll(custom.requireDefectRead(readAll)));
        assertThrows(AccessDeniedException.class, () -> custom.require(readAll, Permission.VIEW));
        assertThrows(AccessDeniedException.class, () -> custom.require(readAll, Permission.REPORT));
        assertThrows(AccessDeniedException.class, () -> custom.require(readAll, Permission.PERFORM_ACTION));
        assertThrows(AccessDeniedException.class, () -> custom.require(readAll, Permission.REVIEW));
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

    @Test
    void reportPermissionsAreNotImpliedByTheLegacyAdminFallback() {
        assertTrue(Set.of(Permission.values()).containsAll(Set.of(Permission.REPORT_VIEW, Permission.REPORT_EXECUTE, Permission.REPORT_EXPORT)));
        Set<Permission> adminPermissions = permissions.permissionsFor(AppRole.ADMIN);
        assertFalse(adminPermissions.contains(Permission.REPORT_VIEW));
        assertFalse(adminPermissions.contains(Permission.REPORT_EXECUTE));
        assertFalse(adminPermissions.contains(Permission.REPORT_EXPORT));
    }

    @Test
    void maintenanceVisibilityComesFromCapabilitiesRatherThanRoleNames() {
        UUID tenant = UUID.randomUUID();
        assertTrue(permissions.isMaintenanceTeamScoped(tenant, AppRole.MAINTENANCE_TECHNICIAN.name()));
        assertFalse(permissions.isMaintenanceTeamScoped(tenant, AppRole.SUPERVISOR.name()));
        assertFalse(permissions.isMaintenanceTeamScoped(tenant, AppRole.VIEWER.name()));
        assertDoesNotThrow(() -> permissions.require(user(AppRole.MAINTENANCE_TECHNICIAN), Permission.PERFORM_ACTION));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.MAINTENANCE_TECHNICIAN), Permission.CLOSE));
        assertThrows(AccessDeniedException.class, () -> permissions.require(user(AppRole.MAINTENANCE_TECHNICIAN), Permission.ASSIGN_ACTION));
    }

    @Test
    void reviewerCapabilitiesAreTeamScopedUnlessExplicitOrganizationAdminIsGranted() {
        UUID tenant=UUID.randomUUID();
        assertTrue(permissions.isReviewerTeamScoped(tenant,AppRole.SUPERVISOR.name()));
        assertFalse(permissions.isReviewerTeamScoped(tenant,AppRole.ADMIN.name()));
        assertFalse(permissions.isReviewerTeamScoped(tenant,AppRole.VIEWER.name()));
    }

    @Test
    void navigationIsBuiltFromPermissionsRatherThanFrontendRoleRules() {
        AuthenticatedUser inspector = user(AppRole.INSPECTOR);
        CurrentUserResolver users = new CurrentUserResolver(null) {
            @Override public AuthenticatedUser require(JwtAuthenticationToken ignored) { return inspector; }
        };
        ConfigurationService configuration = new ConfigurationService(null, null) {
            @Override public List<NavigationItem> navigation(UUID operatorId, java.util.Set<Permission> grants) {
                assertEquals(inspector.organizationId(), operatorId);
                assertEquals(permissions.permissionsFor(AppRole.INSPECTOR), grants);
                return List.of(
            new NavigationItem("home", "Home", "⌂", "home", List.of()),
            new NavigationItem("operations", "Operations", "▦", "operations", List.of(new NavigationItem("defects", "Defect register", "▦", "defects", List.of()), new NavigationItem("inspections", "Inspections", "◷", "operations", List.of()))));
            }
        };
        NavigationController navigation = new NavigationController(users, permissions, configuration);
        Jwt token = Jwt.withTokenValue("test-only").header("alg", "none").subject("inspector").build();

        var items = navigation.navigation(new JwtAuthenticationToken(token));
        var keys = items.stream().map(item -> item.key()).collect(java.util.stream.Collectors.toSet());

        assertTrue(keys.contains("home"));
        assertTrue(items.stream().flatMap(item -> item.children().stream()).anyMatch(item -> item.key().equals("defects")));
        assertFalse(keys.contains("configuration"));
        assertFalse(items.stream().flatMap(item -> item.children().stream()).anyMatch(item -> item.key().equals("corrective-actions")));
        assertTrue(items.stream().noneMatch(item -> item.key().equals("configuration")));
    }
}
