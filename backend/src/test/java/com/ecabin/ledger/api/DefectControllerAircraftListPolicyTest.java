package com.ecabin.ledger.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.DefectService;
import com.ecabin.ledger.service.OperationalFormService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class DefectControllerAircraftListPolicyTest {
    @Test
    void aircraftSpecificListLimitsAuditorsAndAllowsTenantWideReviewerMaintenanceAndAdminScopes() {
        assertAircraftListScope("AUDITOR", Set.of(Permission.VIEW, Permission.REPORT), true);
        assertAircraftListScope("AUDITOR_WITH_DEFECT_VIEW_ALL", Set.of(Permission.VIEW, Permission.REPORT, Permission.DEFECT_VIEW_ALL), true);
        assertAircraftListScope("REVIEWER", Set.of(Permission.VIEW, Permission.REVIEW), false);
        assertAircraftListScope("MAINTENANCE", Set.of(Permission.VIEW, Permission.PERFORM_ACTION), false);
        assertAircraftListScope("ADMIN", Set.of(Permission.VIEW, Permission.ADMINISTER_ORGANIZATION), false);
        assertAircraftListScope("DEFECT_READER", Set.of(Permission.VIEW, Permission.DEFECT_VIEW_ALL), false);
    }

    @Test
    void reviewerCanLoadAircraftContextEvenWhenNoCaseIsRoutedToTheirTeam() {
        DefectService service = mock(DefectService.class);
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        PermissionService permissions = mock(PermissionService.class);
        JwtAuthenticationToken auth = mock(JwtAuthenticationToken.class);
        UUID tenant = UUID.randomUUID();
        UUID aircraft = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(tenant, "reviewer", "Reviewer", "REVIEWER");
        when(users.require(auth)).thenReturn(user);
        when(permissions.requireDefectRead(user)).thenReturn(Set.of(Permission.VIEW, Permission.REVIEW));
        when(permissions.isMaintenanceTeamScoped(tenant, "REVIEWER")).thenReturn(false);
        when(permissions.isReporterScoped(tenant, "REVIEWER")).thenReturn(false);
        when(service.aircraft(eq(tenant), eq("reviewer"), eq(aircraft), eq(false), eq(false), eq(false)))
            .thenReturn(new ApiModels.FleetStatusRow(aircraft, "MSN", "REG", "A320", 0));
        DefectController controller = new DefectController(service, users, permissions, mock(OperationalFormService.class));

        controller.aircraft(auth, aircraft);

        verify(service).aircraft(tenant, "reviewer", aircraft, false, false, false);
    }

    @Test
    void aircraftListDetailUsesTheSameTenantAndReporterScopeAsTheAircraftList() {
        for (var entry : java.util.List.of(
                new Object[]{"AUDITOR", Set.of(Permission.VIEW, Permission.REPORT), true},
                new Object[]{"REVIEWER", Set.of(Permission.VIEW, Permission.REVIEW), false},
                new Object[]{"MAINTENANCE", Set.of(Permission.VIEW, Permission.PERFORM_ACTION), false},
                new Object[]{"ADMIN", Set.of(Permission.VIEW, Permission.ADMINISTER_ORGANIZATION), false})) {
            String role = (String) entry[0];
            @SuppressWarnings("unchecked") Set<Permission> grants = (Set<Permission>) entry[1];
            boolean reporterScope = (boolean) entry[2];
            DefectService service = mock(DefectService.class);
            CurrentUserResolver users = mock(CurrentUserResolver.class);
            PermissionService permissions = mock(PermissionService.class);
            JwtAuthenticationToken auth = mock(JwtAuthenticationToken.class);
            UUID tenant = UUID.randomUUID(), aircraft = UUID.randomUUID(), defect = UUID.randomUUID();
            AuthenticatedUser user = new AuthenticatedUser(tenant, role.toLowerCase(), role, role);
            when(users.require(auth)).thenReturn(user);
            when(permissions.requireDefectRead(user)).thenReturn(grants);
            when(permissions.isReporterScoped(tenant, role)).thenReturn(reporterScope);
            DefectController controller = new DefectController(service, users, permissions, mock(OperationalFormService.class));

            controller.aircraftListDefect(auth, aircraft, defect);

            verify(service).getForAircraftList(tenant, role.toLowerCase(), aircraft, defect, reporterScope);
            verify(service, never()).get(tenant, defect);
        }
    }

    @Test
    void genericDetailUsesTheSameTenantReadPolicyAsTheAircraftList() {
        assertGenericDetailPolicy("REVIEWER", "reviewer", Set.of(Permission.VIEW, Permission.REVIEW), false);
        assertGenericDetailPolicy("MAINTENANCE", "maintenance", Set.of(Permission.VIEW, Permission.PERFORM_ACTION), false);
        assertGenericDetailPolicy("ADMIN", "admin", Set.of(Permission.VIEW, Permission.ADMINISTER_ORGANIZATION), false);
        assertGenericDetailPolicy("VIEWER", "viewer", Set.of(Permission.VIEW), false);
        assertGenericDetailPolicy("AUDITOR", "auditor", Set.of(Permission.VIEW, Permission.REPORT), true);
        assertGenericDetailPolicy("BROAD_AUDITOR", "broad-auditor", Set.of(Permission.VIEW, Permission.REPORT, Permission.DEFECT_VIEW_ALL), false);
    }

    private void assertGenericDetailPolicy(String role, String subject, Set<Permission> grants, boolean reporterScoped) {
        DefectService service = mock(DefectService.class);
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        PermissionService permissions = mock(PermissionService.class);
        JwtAuthenticationToken auth = mock(JwtAuthenticationToken.class);
        UUID tenant = UUID.randomUUID(), defect = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(tenant, subject, role, role);
        when(users.require(auth)).thenReturn(user);
        when(permissions.requireDefectRead(user)).thenReturn(grants);
        when(permissions.isReporterScoped(tenant, role)).thenReturn(reporterScoped);
        DefectController controller = new DefectController(service, users, permissions, mock(OperationalFormService.class));

        controller.get(auth, defect);

        if (reporterScoped) verify(service).getForReporter(tenant, subject, defect);
        else verify(service).get(tenant, defect);
        verify(service, never()).getForAircraftList(tenant, subject, null, defect, false);
    }

    private void assertAircraftListScope(String role, Set<Permission> grants, boolean expectedReporterScope) {
        DefectService service = mock(DefectService.class);
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        PermissionService permissions = mock(PermissionService.class);
        JwtAuthenticationToken auth = mock(JwtAuthenticationToken.class);
        UUID tenant = UUID.randomUUID();
        UUID aircraft = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(tenant, role.toLowerCase(), role, role);
        when(users.require(auth)).thenReturn(user);
        when(permissions.requireDefectRead(user)).thenReturn(grants);
        when(permissions.isReporterScoped(tenant, role)).thenReturn(expectedReporterScope);
        DefectController controller = new DefectController(service, users, permissions, mock(OperationalFormService.class));

        controller.aircraftDefects(auth, aircraft, 0, 10, null, null, "reportedAt", "DESC");

        verify(service).aircraftDefects(tenant, role.toLowerCase(), aircraft, false, expectedReporterScope, false,
            0, 10, null, null, "reportedAt", "DESC");
    }
}
