package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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

/** GET/POST-only tenant administration API. Every operation is organization-scoped and ADMIN-authorized. */
@RestController
@RequestMapping("/api/v1/configuration")
public class ConfigurationController {
    public record DeactivateRequest(@NotBlank @Size(max = 1000) String reason) {}

    private final ConfigurationService configuration;
    private final CurrentUserResolver users;
    private final PermissionService permissions;

    public ConfigurationController(ConfigurationService configuration, CurrentUserResolver users, PermissionService permissions) {
        this.configuration = configuration; this.users = users; this.permissions = permissions;
    }

    @GetMapping("/menu")
    public List<ConfigurationService.MenuItem> menu(JwtAuthenticationToken auth) {
        requireAdmin(auth);
        return configuration.menu();
    }

    @GetMapping("/{resource}")
    public List<Map<String, Object>> list(JwtAuthenticationToken auth, @PathVariable String resource,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        AuthenticatedUser user = requireAdmin(auth);
        return configuration.list(user.organizationId(), resource, includeInactive);
    }

    @PostMapping("/{resource}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> save(JwtAuthenticationToken auth, @PathVariable String resource,
            @Valid @RequestBody ConfigurationService.SaveRequest request) {
        AuthenticatedUser user = requireAdmin(auth);
        return configuration.save(user.organizationId(), user.externalUserId(), resource, request);
    }

    @PostMapping("/{resource}/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(JwtAuthenticationToken auth, @PathVariable String resource, @PathVariable String id,
            @Valid @RequestBody DeactivateRequest request) {
        AuthenticatedUser user = requireAdmin(auth);
        configuration.deactivate(user.organizationId(), user.externalUserId(), resource, id, request.reason());
    }

    @PostMapping("/{resource}/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(JwtAuthenticationToken auth, @PathVariable String resource, @PathVariable String id,
            @Valid @RequestBody DeactivateRequest request) {
        AuthenticatedUser user = requireAdmin(auth);
        configuration.activate(user.organizationId(), user.externalUserId(), resource, id, request.reason());
    }

    private AuthenticatedUser requireAdmin(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
        return user;
    }
}
