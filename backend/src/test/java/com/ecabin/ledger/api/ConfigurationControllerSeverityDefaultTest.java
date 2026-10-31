package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ConfigurationImportService;
import com.ecabin.ledger.service.ConfigurationService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.mockito.Mockito.mock;

class ConfigurationControllerSeverityDefaultTest {
    @Test
    void settingDefaultUsesAuthenticatedOrganizationAndActor() {
        ConfigurationService configuration=mock(ConfigurationService.class);
        CurrentUserResolver users=mock(CurrentUserResolver.class);
        PermissionService permissions=mock(PermissionService.class);
        JwtAuthenticationToken auth=mock(JwtAuthenticationToken.class);
        UUID organizationId=UUID.randomUUID(),severityId=UUID.randomUUID();
        AuthenticatedUser user=new AuthenticatedUser(organizationId,"organization-admin","Admin","ADMIN");
        Map<String,Object> expected=Map.of("id",severityId.toString(),"isDefault",true);
        when(users.require(auth)).thenReturn(user);
        when(configuration.setDefaultSeverity(organizationId,"organization-admin",severityId.toString())).thenReturn(expected);
        ConfigurationController controller=new ConfigurationController(configuration,users,permissions,mock(ConfigurationImportService.class));

        assertEquals(expected,controller.setDefaultSeverity(auth,severityId.toString()));
        verify(permissions).require(user,Permission.ADMINISTER_ORGANIZATION);
        verify(configuration).setDefaultSeverity(organizationId,"organization-admin",severityId.toString());
    }

    @Test
    void defaultSeverityMutationRejectsNonAdministratorsBeforeCallingService() {
        ConfigurationService configuration=mock(ConfigurationService.class);
        CurrentUserResolver users=mock(CurrentUserResolver.class);
        PermissionService permissions=mock(PermissionService.class);
        JwtAuthenticationToken auth=mock(JwtAuthenticationToken.class);
        AuthenticatedUser user=new AuthenticatedUser(UUID.randomUUID(),"reviewer","Reviewer","REVIEWER");
        when(users.require(auth)).thenReturn(user);
        doThrow(new AccessDeniedException("denied")).when(permissions).require(user,Permission.ADMINISTER_ORGANIZATION);
        ConfigurationController controller=new ConfigurationController(configuration,users,permissions,mock(ConfigurationImportService.class));

        assertThrows(AccessDeniedException.class,()->controller.setDefaultSeverity(auth,UUID.randomUUID().toString()));
        verifyNoInteractions(configuration);
    }
}
