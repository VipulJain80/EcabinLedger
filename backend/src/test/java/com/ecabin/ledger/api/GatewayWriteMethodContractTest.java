package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class GatewayWriteMethodContractTest {
    @Test
    void reportAdministrationMutationsUsePostOnly() throws Exception {
        assertPostMapping(ReportAdministrationController.class.getMethod("update",
            org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken.class,
            java.util.UUID.class, ReportAdministrationModels.UpdateReportRequest.class), "/reports/{reportId}");
        assertPostMapping(ReportAdministrationController.class.getMethod("activation",
            org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken.class,
            java.util.UUID.class, ReportAdministrationModels.ActivationRequest.class), "/reports/{reportId}/activation");
        assertPostMapping(ReportAdministrationController.class.getMethod("roleAccess",
            org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken.class,
            java.util.UUID.class, ReportAdministrationModels.RoleAccessRequest.class), "/reports/{reportId}/role-access");
    }

    @Test
    void workflowStatusMutationUsesPostOnly() throws Exception {
        assertPostMapping(WorkflowStatusController.class.getMethod("update",
            org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken.class,
            java.util.UUID.class, ApiModels.WorkflowStatusUpdateRequest.class), "/{id}");
    }

    private static void assertPostMapping(Method method, String expectedPath) {
        PostMapping post = method.getAnnotation(PostMapping.class);
        assertNotNull(post, () -> method.getName() + " must use POST for gateway compatibility");
        assertArrayEquals(new String[] {expectedPath}, post.value());
        assertNull(method.getAnnotation(PutMapping.class));
        assertNull(method.getAnnotation(PatchMapping.class));
        assertNull(method.getAnnotation(DeleteMapping.class));
    }
}
