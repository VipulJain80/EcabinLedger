package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ConfigurationService;
import com.ecabin.ledger.api.ApiModels.ConditionOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
        AuthenticatedUser user = operationalReader(auth);
        return Map.of(
            "categories", configuration.list(user.organizationId(), "defect-categories", false),
            "areas", configuration.list(user.organizationId(), "areas", false),
            "components", configuration.list(user.organizationId(), "components", false),
            "severities", configuration.list(user.organizationId(), "severities", false),
            "fleets", configuration.list(user.organizationId(), "fleets", false),
            "aircraftTypes", configuration.aircraftTypes(user.organizationId())
        );
    }

    @GetMapping("/severity-badges")
    public List<Map<String, Object>> severityBadges(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        var grants = permissions.permissionsFor(user.organizationId(), user.role());
        if (grants.stream().noneMatch(Set.of(Permission.VIEW, Permission.DEFECT_VIEW_ALL, Permission.REPORT,
                Permission.REVIEW, Permission.INSPECT, Permission.ASSIGN_ACTION, Permission.PERFORM_ACTION,
                Permission.VERIFY, Permission.APPROVE, Permission.CLOSE, Permission.REOPEN)::contains)) {
            throw new org.springframework.security.access.AccessDeniedException("Severity display permission is required.");
        }
        return configuration.list(user.organizationId(), "severities", true);
    }

    @GetMapping("/components/{componentId}/conditions")
    public List<ConditionOption> conditionsForComponent(JwtAuthenticationToken auth, @PathVariable java.util.UUID componentId) {
        AuthenticatedUser user = operationalReader(auth);
        return configuration.conditionsForComponent(user.organizationId(), componentId);
    }

    private AuthenticatedUser operationalReader(JwtAuthenticationToken auth){AuthenticatedUser user=users.require(auth);var grants=permissions.permissionsFor(user.organizationId(),user.role());if(!grants.contains(Permission.VIEW)&&!grants.contains(Permission.REPORT))throw new org.springframework.security.access.AccessDeniedException("View or report permission is required.");return user;}
}
