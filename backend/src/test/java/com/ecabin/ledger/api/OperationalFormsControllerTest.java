package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.WorkflowStage;
import com.ecabin.ledger.api.FormModels.DuplicateFormRequest;
import com.ecabin.ledger.api.FormModels.FormSummary;
import com.ecabin.ledger.api.FormModels.RenameFormRequest;
import com.ecabin.ledger.api.FormModels.DeleteFormRequest;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.OperationalFormService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;

class OperationalFormsControllerTest {
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
