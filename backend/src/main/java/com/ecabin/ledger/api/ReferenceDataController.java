package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ConfigurationService;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, tenant-scoped lookup data used by operational forms. */
@RestController
@RequestMapping("/api/v1/reference-data")
public class ReferenceDataController {
    private final ConfigurationService configuration;
    private final CurrentUserResolver users;
    private final PermissionService permissions;

    public ReferenceDataController(ConfigurationService configuration, CurrentUserResolver users, PermissionService permissions) {
        this.configuration = configuration; this.users = users; this.permissions = permissions;
    }

    @GetMapping
    public Map<String, Object> get(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        permissions.require(user, Permission.VIEW);
        return Map.of(
            "categories", configuration.list(user.organizationId(), "defect-categories", false),
            "zones", configuration.list(user.organizationId(), "cabin-zones", false),
            "components", configuration.list(user.organizationId(), "components", false),
            "aircraftTypes", configuration.aircraftTypes(user.organizationId())
        );
    }
}
