package com.ecabin.ledger.api;

import com.ecabin.ledger.api.ApiModels.WorkflowStatusPage;
import com.ecabin.ledger.api.ApiModels.WorkflowStatusView;
import com.ecabin.ledger.api.ApiModels.WorkflowStatusUpdateRequest;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.WorkflowStatusService;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/workflow-statuses")
public class WorkflowStatusController {
    private static final Set<Permission> READ_PERMISSIONS = Set.of(Permission.VIEW, Permission.DEFECT_VIEW_ALL,
        Permission.REPORT, Permission.REVIEW, Permission.INSPECT, Permission.ASSIGN_ACTION,
        Permission.PERFORM_ACTION, Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN);

    private final WorkflowStatusService statuses;
    private final CurrentUserResolver users;
    private final PermissionService permissions;

    public WorkflowStatusController(WorkflowStatusService statuses, CurrentUserResolver users, PermissionService permissions) {
        this.statuses = statuses;
        this.users = users;
        this.permissions = permissions;
    }

    @GetMapping
    public WorkflowStatusPage list(JwtAuthenticationToken auth,
            @RequestParam(required = false) String moduleCode,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "sortOrder") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        requireReader(auth);
        return statuses.list(moduleCode, activeOnly, page, size, search, sortBy, sortDirection);
    }

    @GetMapping("/{id}")
    public WorkflowStatusView get(JwtAuthenticationToken auth, @PathVariable UUID id) {
        requireReader(auth);
        return statuses.get(id);
    }

    @PostMapping("/{id}")
    public WorkflowStatusView update(JwtAuthenticationToken auth, @PathVariable UUID id,
            @Valid @RequestBody WorkflowStatusUpdateRequest request) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
        return statuses.update(id, request, user.organizationId(), user.externalUserId());
    }

    private AuthenticatedUser requireReader(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        if (permissions.permissionsFor(user.organizationId(), user.role()).stream().noneMatch(READ_PERMISSIONS::contains)) {
            throw new AccessDeniedException("Workflow status read permission is required.");
        }
        return user;
    }
}
