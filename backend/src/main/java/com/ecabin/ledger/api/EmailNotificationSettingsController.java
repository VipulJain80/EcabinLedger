package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.EmailNotificationSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/email-notification-settings")
public class EmailNotificationSettingsController {
    public record SwitchRequest(@NotNull Boolean enabled) {}

    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final EmailNotificationSettingsService settings;

    public EmailNotificationSettingsController(CurrentUserResolver users, PermissionService permissions,
                                               EmailNotificationSettingsService settings) {
        this.users = users;
        this.permissions = permissions;
        this.settings = settings;
    }

    @GetMapping
    public EmailNotificationSettingsService.Settings get(JwtAuthenticationToken auth) {
        return settings.get(administrator(auth));
    }

    @PostMapping("/global")
    public EmailNotificationSettingsService.Settings updateGlobal(JwtAuthenticationToken auth,
            @Valid @RequestBody SwitchRequest request) {
        return settings.updateGlobal(administrator(auth), request.enabled());
    }

    @PostMapping("/events/{eventKey}")
    public EmailNotificationSettingsService.Settings updateEvent(JwtAuthenticationToken auth,
            @PathVariable String eventKey, @Valid @RequestBody SwitchRequest request) {
        return settings.updateEvent(administrator(auth), eventKey, request.enabled());
    }

    private AuthenticatedUser administrator(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
        return user;
    }
}
