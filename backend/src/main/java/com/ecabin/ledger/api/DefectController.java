package com.ecabin.ledger.api;

import static com.ecabin.ledger.api.ApiModels.*;

import com.ecabin.ledger.service.DefectService;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DefectController {
    private final DefectService service;
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    public DefectController(DefectService service, CurrentUserResolver users, PermissionService permissions) { this.service = service; this.users = users; this.permissions = permissions; }

    @GetMapping("/defects")
    public DefectPage list(JwtAuthenticationToken auth,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String location,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "25") int limit) {
        AuthenticatedUser user = users.require(auth); permissions.require(user, Permission.VIEW);
        return service.list(user.organizationId(), status, location, cursor, limit);
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummary summary(JwtAuthenticationToken auth) { AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.VIEW); return service.summary(user.organizationId()); }

    @GetMapping("/me")
    public CurrentUserView me(JwtAuthenticationToken auth) { AuthenticatedUser user=users.require(auth); return new CurrentUserView(service.organizationName(user.organizationId()), user.displayName(), user.role().name()); }

    @PostMapping("/defects") @ResponseStatus(HttpStatus.CREATED)
    public DefectView create(JwtAuthenticationToken auth, @Valid @RequestBody CreateDefectRequest request) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.REPORT);
        return service.create(user.organizationId(), user.externalUserId(), request);
    }

    @GetMapping("/defects/{id}")
    public DefectView get(JwtAuthenticationToken auth, @PathVariable UUID id) { AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.VIEW); return service.get(user.organizationId(), id); }

    @PostMapping("/defects/{id}/transitions")
    public DefectView transition(JwtAuthenticationToken auth, @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        AuthenticatedUser user=users.require(auth);
        String target=request.status().toUpperCase();
        Permission permission=switch(target) {
            case "UNDER_REVIEW", "INSPECTION_REQUIRED", "REJECTED", "CANCELLED" -> Permission.REVIEW;
            case "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE" -> Permission.INSPECT;
            case "ACTION_ASSIGNED" -> Permission.ASSIGN_ACTION;
            case "IN_PROGRESS", "AWAITING_VERIFICATION" -> Permission.PERFORM_ACTION;
            case "VERIFIED" -> Permission.VERIFY;
            case "APPROVAL_REQUIRED" -> Permission.APPROVE;
            case "CLOSED" -> Permission.CLOSE;
            case "REOPENED" -> Permission.REOPEN;
            default -> throw new IllegalArgumentException("Unsupported status.");
        };
        if (target.equals("INSPECTION_REQUIRED") && "INSPECTION_IN_PROGRESS".equals(service.get(user.organizationId(), id).status())) permission=Permission.INSPECT;
        permissions.require(user, permission);
        return service.transition(user.organizationId(), id, user.externalUserId(), request);
    }

    @GetMapping("/defects/{id}/events")
    public List<DefectEventView> events(JwtAuthenticationToken auth, @PathVariable UUID id) { AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.AUDIT); return service.events(user.organizationId(), id); }
}
