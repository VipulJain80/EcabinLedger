package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecabin.ledger.api.ApiModels.CorrectiveActionReassignmentRequest;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.DefectService;
import com.ecabin.ledger.service.MaintenanceActionService;
import com.ecabin.ledger.service.OperationalWorkflowService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class OperationalWorkflowControllerTest {
    @Test
    void reassignmentAndTeamOptionsRequireAssignmentPermissionBeforeCallingServices() {
        var workflow = mock(OperationalWorkflowService.class);
        var users = mock(CurrentUserResolver.class);
        var permissions = mock(PermissionService.class);
        var defects = mock(DefectService.class);
        var maintenanceActions = mock(MaintenanceActionService.class);
        var auth = mock(JwtAuthenticationToken.class);
        var user = new AuthenticatedUser(UUID.randomUUID(), "maintenance-user", "Maintenance", "MAINTENANCE_TECHNICIAN");
        var controller = new OperationalWorkflowController(workflow, users, permissions, defects, maintenanceActions);
        UUID defectId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        when(users.require(auth)).thenReturn(user);
        doThrow(new AccessDeniedException("Denied")).when(permissions).require(user, Permission.ASSIGN_ACTION);

        assertThrows(AccessDeniedException.class, () -> controller.reassignAction(auth, defectId, actionId,
            new CorrectiveActionReassignmentRequest(UUID.randomUUID(), null, "Approved reassignment reason.")));
        assertThrows(AccessDeniedException.class, () -> controller.reassignmentOptions(auth, defectId, actionId));

        verify(users, org.mockito.Mockito.times(2)).require(auth);
        verify(permissions, org.mockito.Mockito.times(2)).require(user, Permission.ASSIGN_ACTION);
        verifyNoInteractions(workflow, defects, maintenanceActions);
    }
}
