package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.FormForArea;
import com.ecabin.ledger.api.FormModels.FormOption;
import com.ecabin.ledger.api.FormModels.WorkflowStage;
import com.ecabin.ledger.api.FormModels.DuplicateFormRequest;
import com.ecabin.ledger.api.FormModels.FormSummary;
import com.ecabin.ledger.api.FormModels.RenameFormRequest;
import com.ecabin.ledger.api.FormModels.DeleteFormRequest;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.service.OperationalFormService;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;

class OperationalFormsControllerTest {
    @Test
    void maintenancePermissionCanReadPublishedWorkflowDefinitionAndOptions() {
        var forms=mock(OperationalFormService.class);
        var users=mock(CurrentUserResolver.class);
        var permissions=mock(PermissionService.class);
        var auth=mock(JwtAuthenticationToken.class);
        UUID tenant=UUID.randomUUID(),formId=UUID.randomUUID(),versionId=UUID.randomUUID(),areaId=UUID.randomUUID();
        var user=new AuthenticatedUser(tenant,"assigned-maintainer","Maintenance","MAINTENANCE_TECHNICIAN");
        var definition=new FormForArea(formId,"MAINT_ACTION","Maintenance Action",areaId,FormPurpose.CORRECTIVE_ACTION,
            WorkflowStage.ACTION_ASSIGNED,versionId,1,null);
        var option=new FormOption(UUID.randomUUID(),"LATCH","Latch");
        when(users.require(auth)).thenReturn(user);
        when(permissions.permissionsFor(tenant,"MAINTENANCE_TECHNICIAN")).thenReturn(Set.of(Permission.PERFORM_ACTION));
        when(forms.published(tenant,formId,versionId,areaId)).thenReturn(definition);
        when(forms.options(tenant,formId,versionId,"component",areaId,null)).thenReturn(List.of(option));
        var controller=new OperationalFormsController(forms,users,permissions,null,null,null);

        assertEquals(definition,controller.published(auth,formId,versionId,areaId));
        assertEquals(List.of(option),controller.options(auth,formId,versionId,"component",areaId,null));
    }

    @Test
    void closureReviewFormsCannotBeResolvedByAreaAlone() {
        OperationalFormsController controller = new OperationalFormsController(null, null, null, null, null, null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.forArea(
                null, UUID.randomUUID(), FormPurpose.CLOSURE_APPROVAL, WorkflowStage.APPROVAL_REQUIRED));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void formManagementMutationsRequireExistingOrganizationAdministrationPermission() {
        var forms=mock(OperationalFormService.class);
        var users=mock(CurrentUserResolver.class);
        var auth=mock(JwtAuthenticationToken.class);
        UUID tenant=UUID.randomUUID();
        when(users.require(auth)).thenReturn(new AuthenticatedUser(tenant,"reviewer","Reviewer","VIEWER"));
        var controller=new OperationalFormsController(forms,users,new PermissionService(),null,null,null);

        assertThrows(AccessDeniedException.class,()->controller.duplicate(auth,UUID.randomUUID(),
            new DuplicateFormRequest("GALLEY_COPY","Galley Copy",UUID.randomUUID(),FormPurpose.DEFECT_REPORT,WorkflowStage.INITIAL_REPORT,null)));
        assertThrows(AccessDeniedException.class,()->controller.rename(auth,UUID.randomUUID(),new RenameFormRequest("Renamed","reason")));
        assertThrows(AccessDeniedException.class,()->controller.delete(auth,UUID.randomUUID(),new DeleteFormRequest("reason")));
        assertThrows(AccessDeniedException.class,()->controller.deleteDraftVersion(auth,UUID.randomUUID(),UUID.randomUUID(),new DeleteFormRequest("reason")));
        assertThrows(AccessDeniedException.class,()->controller.formMappings(auth));
        verifyNoInteractions(forms);
    }

    @Test
    void authorizedFormManagementMutationUsesTenantAndActorResolvedFromPrincipal() {
        var forms=mock(OperationalFormService.class);
        var users=mock(CurrentUserResolver.class);
        var auth=mock(JwtAuthenticationToken.class);
        UUID tenant=UUID.randomUUID(),formId=UUID.randomUUID(),areaId=UUID.randomUUID();
        when(users.require(auth)).thenReturn(new AuthenticatedUser(tenant,"tenant-admin","Admin","ADMIN"));
        var expected=new FormSummary(formId,"GALLEY_COPY","Galley Copy",areaId,"Galley",FormPurpose.DEFECT_REPORT,
            WorkflowStage.INITIAL_REPORT,1,null,null,1,UUID.randomUUID(),java.time.Instant.now());
        var request=new DuplicateFormRequest("GALLEY_COPY","Galley Copy",areaId,FormPurpose.DEFECT_REPORT,WorkflowStage.INITIAL_REPORT,null);
        when(forms.duplicate(tenant,"tenant-admin",formId,request)).thenReturn(expected);
        var controller=new OperationalFormsController(forms,users,new PermissionService(),null,null,null);

        assertEquals(expected,controller.duplicate(auth,formId,request));
        verify(forms).duplicate(tenant,"tenant-admin",formId,request);
        UUID versionId=UUID.randomUUID();
        DeleteFormRequest deleteRequest=new DeleteFormRequest("discard obsolete draft");
        controller.deleteDraftVersion(auth,formId,versionId,deleteRequest);
        verify(forms).deleteDraftVersion(tenant,"tenant-admin",formId,versionId,deleteRequest);
    }
}
