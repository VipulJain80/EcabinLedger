package com.ecabin.ledger.api;

import static com.ecabin.ledger.api.ApiModels.NavigationItem;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ConfigurationService;
import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Builds the visible application navigation from the same server-side grants used by API authorization. */
@RestController
@RequestMapping("/api/v1")
public class NavigationController {
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final ConfigurationService configuration;

    public NavigationController(CurrentUserResolver users, PermissionService permissions, ConfigurationService configuration) {
        this.users = users;
        this.permissions = permissions;
        this.configuration = configuration;
    }

    @GetMapping("/navigation")
    public List<NavigationItem> navigation(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        return configuration.navigation(user.organizationId(), permissions.permissionsFor(user.organizationId(), user.role()));
    }
}
