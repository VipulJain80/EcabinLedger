package com.ecabin.ledger.api;

import static com.ecabin.ledger.api.ApiModels.*;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.OperationalWorkflowService;
import com.ecabin.ledger.service.DefectService;
import com.ecabin.ledger.service.MaintenanceActionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/defects/{defectId}")
public class OperationalWorkflowController {
    private final OperationalWorkflowService workflow;
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final DefectService defects;
    private final MaintenanceActionService maintenanceActions;

    public OperationalWorkflowController(OperationalWorkflowService workflow, CurrentUserResolver users, PermissionService permissions,DefectService defects,MaintenanceActionService maintenanceActions) {
        this.workflow = workflow; this.users = users; this.permissions = permissions; this.defects=defects; this.maintenanceActions=maintenanceActions;
    }

    @PostMapping("/inspections") @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRecord inspect(JwtAuthenticationToken auth, @PathVariable UUID defectId, @Valid @RequestBody InspectionRequest request) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.INSPECT); visibleDefect(user,defectId);
        return workflow.completeInspection(user.organizationId(), defectId, user.externalUserId(), request);
    }

    @PostMapping("/actions") @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRecord assignAction(JwtAuthenticationToken auth, @PathVariable UUID defectId, @Valid @RequestBody CorrectiveActionRequest request) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.ASSIGN_ACTION); visibleDefect(user,defectId);
        return workflow.assignAction(user.organizationId(), defectId, user.externalUserId(), request);
    }

    @PostMapping("/actions/{actionId}/reassign")
    public WorkflowRecord reassignAction(JwtAuthenticationToken auth, @PathVariable UUID defectId, @PathVariable UUID actionId, @Valid @RequestBody CorrectiveActionReassignmentRequest request) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.ASSIGN_ACTION); visibleDefect(user,defectId);
        return workflow.reassignAction(user.organizationId(), defectId, actionId, user.externalUserId(), request);
    }

    @PostMapping("/actions/{actionId}/start")
    public WorkflowRecord startAction(JwtAuthenticationToken auth, @PathVariable UUID defectId, @PathVariable UUID actionId) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.PERFORM_ACTION); visibleDefect(user,defectId);
        return workflow.startAction(user.organizationId(), defectId, actionId, user.externalUserId());
    }

    @PostMapping("/actions/{actionId}/complete")
    public WorkflowRecord completeAction(JwtAuthenticationToken auth, @PathVariable UUID defectId, @PathVariable UUID actionId, @Valid @RequestBody ActionCompletionRequest request) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.PERFORM_ACTION); visibleDefect(user,defectId);
        return workflow.completeAction(user.organizationId(), defectId, actionId, user.externalUserId(), request);
    }

    @PostMapping("/actions/{actionId}/maintenance-form")
    public WorkflowRecord maintenanceForm(JwtAuthenticationToken auth,@PathVariable UUID defectId,@PathVariable UUID actionId,@Valid @RequestBody MaintenanceActionUpdateRequest request){
        AuthenticatedUser user=users.require(auth);permissions.require(user,Permission.PERFORM_ACTION);visibleDefect(user,defectId);
        return maintenanceActions.update(user.organizationId(),defectId,actionId,user.externalUserId(),request);
    }

    @org.springframework.web.bind.annotation.GetMapping("/maintenance-sla-policy")
    public MaintenanceSlaEffective maintenanceSlaPolicy(JwtAuthenticationToken auth,@PathVariable UUID defectId,@org.springframework.web.bind.annotation.RequestParam String status){
        AuthenticatedUser user=users.require(auth);permissions.require(user,Permission.PERFORM_ACTION);visibleDefect(user,defectId);
        return maintenanceActions.effectivePolicy(user.organizationId(),defectId,user.externalUserId(),status);
    }

    @PostMapping("/verifications") @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRecord verify(JwtAuthenticationToken auth, @PathVariable UUID defectId, @Valid @RequestBody VerificationRequest request) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.VERIFY); visibleDefect(user,defectId);
        return workflow.verify(user.organizationId(), defectId, user.externalUserId(), request);
    }

    @PostMapping("/approvals") @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRecord approve(JwtAuthenticationToken auth, @PathVariable UUID defectId, @Valid @RequestBody ApprovalRequest request) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.APPROVE); defects.get(user.organizationId(),defectId);
        return workflow.approve(user.organizationId(), defectId, user.externalUserId(), request);
    }

    private void visibleDefect(AuthenticatedUser user,UUID defectId){
        if(permissions.isMaintenanceTeamScoped(user.organizationId(),user.role()))defects.getForMaintenanceMember(user.organizationId(),user.externalUserId(),defectId);
        else if(permissions.isReporterScoped(user.organizationId(),user.role()))defects.getForReporter(user.organizationId(),user.externalUserId(),defectId);
        else if(permissions.isReviewerTeamScoped(user.organizationId(),user.role()))defects.getForReviewerMember(user.organizationId(),user.externalUserId(),defectId);
        else defects.get(user.organizationId(),defectId);
    }
}
