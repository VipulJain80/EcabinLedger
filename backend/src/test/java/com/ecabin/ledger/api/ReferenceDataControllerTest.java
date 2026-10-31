package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ClosureReviewAccessService;
import com.ecabin.ledger.service.ConfigurationService;
import com.ecabin.ledger.service.OperationalFormService;
import com.ecabin.ledger.service.WorkflowStatusService;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ReferenceDataControllerTest {
    @Test
    void refreshUsesAuthenticatedOrganizationAndOnlyRefreshesRequestedResource() {
        ConfigurationService configuration=mock(ConfigurationService.class);
        CurrentUserResolver users=mock(CurrentUserResolver.class);
        PermissionService permissions=mock(PermissionService.class);
        JwtAuthenticationToken auth=mock(JwtAuthenticationToken.class);
        UUID organizationId=UUID.randomUUID();
        AuthenticatedUser user=new AuthenticatedUser(organizationId,"actor","Actor","AUDITOR");
        when(users.require(auth)).thenReturn(user);
        when(permissions.permissionsFor(organizationId,"AUDITOR")).thenReturn(EnumSet.of(Permission.VIEW));
        var controller=new ReferenceDataController(configuration,users,permissions,mock(OperationalFormService.class),mock(WorkflowStatusService.class),mock(ClosureReviewAccessService.class));
        var request=new ReferenceDataController.RefreshRequest(Set.of(ReferenceDataController.RefreshResource.CATEGORIES),null,null,null,null,null,null,null,null,null,null,null,null,null,null);

        var response=controller.refresh(auth,request);

        assertEquals("refreshed",((java.util.Map<?,?>)response.get("resources")).get("CATEGORIES") instanceof java.util.Map<?,?> result ? result.get("status") : null);
        verify(configuration).refreshList(organizationId,"defect-categories");
    }

    @Test
    void refreshRejectsUsersWithoutTheReferenceReadPermissionBeforeLoadingData() {
        ConfigurationService configuration=mock(ConfigurationService.class);
        CurrentUserResolver users=mock(CurrentUserResolver.class);
        PermissionService permissions=mock(PermissionService.class);
        JwtAuthenticationToken auth=mock(JwtAuthenticationToken.class);
        AuthenticatedUser user=new AuthenticatedUser(UUID.randomUUID(),"actor","Actor","AUDITOR");
        when(users.require(auth)).thenReturn(user);
        when(permissions.permissionsFor(user.organizationId(),user.role())).thenReturn(EnumSet.noneOf(Permission.class));
        var controller=new ReferenceDataController(configuration,users,permissions,mock(OperationalFormService.class),mock(WorkflowStatusService.class),mock(ClosureReviewAccessService.class));
        var request=new ReferenceDataController.RefreshRequest(Set.of(ReferenceDataController.RefreshResource.CATEGORIES),null,null,null,null,null,null,null,null,null,null,null,null,null,null);

        assertThrows(AccessDeniedException.class,()->controller.refresh(auth,request));
        verifyNoInteractions(configuration);
    }
}
