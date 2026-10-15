package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.UserLoginMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/administration/login-monitoring")
public class UserLoginMonitoringController {
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final UserLoginMonitoringService monitoring;

    public UserLoginMonitoringController(CurrentUserResolver users, PermissionService permissions, UserLoginMonitoringService monitoring) {
        this.users = users; this.permissions = permissions; this.monitoring = monitoring;
    }

    @Operation(summary = "Get tenant-scoped user login summary", description = "Requires an active membership with ADMINISTER_ORGANIZATION.")
    @GetMapping("/summary")
    public UserLoginMonitoringService.Summary summary(JwtAuthenticationToken auth) {
        return monitoring.summary(admin(auth));
    }

    @Operation(summary = "Search tenant-scoped user login activity")
    @GetMapping("/users")
    public UserLoginMonitoringService.UserPage users(JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "ALL") String activity,
            @RequestParam(defaultValue = "lastLoginAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        return monitoring.users(admin(auth), page, size, search, status, activity, sortBy, sortDirection);
    }

    @Operation(summary = "Get paginated login/logout history for a tenant membership")
    @GetMapping("/users/{externalUserId}/events")
    public UserLoginMonitoringService.EventPage events(JwtAuthenticationToken auth, @PathVariable String externalUserId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return monitoring.events(admin(auth), externalUserId, page, size);
    }

    private AuthenticatedUser admin(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
        return user;
    }
}
