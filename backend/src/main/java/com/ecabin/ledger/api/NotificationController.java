package com.ecabin.ledger.api;

import static com.ecabin.ledger.api.NotificationModels.*;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.NotificationService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notifications;
    private final CurrentUserResolver users;
    private final PermissionService permissions;

    public NotificationController(NotificationService notifications, CurrentUserResolver users, PermissionService permissions) {
        this.notifications = notifications; this.users = users; this.permissions = permissions;
    }

    @GetMapping
    public Inbox inbox(JwtAuthenticationToken auth) {
        AuthenticatedUser user = authorized(auth);
        return notifications.inbox(user.organizationId(), user.externalUserId(),
            permissions.isMaintenanceTeamScoped(user.organizationId(), user.role()),
            permissions.isReporterScoped(user.organizationId(), user.role()),
            permissions.isReviewerTeamScoped(user.organizationId(), user.role()));
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(JwtAuthenticationToken auth, @PathVariable UUID notificationId) {
        AuthenticatedUser user = authorized(auth);
        notifications.markRead(user.organizationId(), user.externalUserId(), notificationId,
            permissions.isMaintenanceTeamScoped(user.organizationId(), user.role()),
            permissions.isReporterScoped(user.organizationId(), user.role()),
            permissions.isReviewerTeamScoped(user.organizationId(), user.role()));
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(JwtAuthenticationToken auth) {
        AuthenticatedUser user = authorized(auth);
        notifications.markAllRead(user.organizationId(), user.externalUserId(),
            permissions.isMaintenanceTeamScoped(user.organizationId(), user.role()),
            permissions.isReporterScoped(user.organizationId(), user.role()),
            permissions.isReviewerTeamScoped(user.organizationId(), user.role()));
    }

    private AuthenticatedUser authorized(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.VIEW);
        return user;
    }
}
