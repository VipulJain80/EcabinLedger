package com.ecabin.ledger.api;

import static com.ecabin.ledger.api.ApiModels.*;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.MaintenanceSlaPolicyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/configuration/maintenance-sla-policies")
public class MaintenanceSlaPolicyController {
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final MaintenanceSlaPolicyService policies;
    public MaintenanceSlaPolicyController(CurrentUserResolver users,PermissionService permissions,MaintenanceSlaPolicyService policies){this.users=users;this.permissions=permissions;this.policies=policies;}
    @GetMapping public List<MaintenanceSlaPolicy> list(JwtAuthenticationToken auth){AuthenticatedUser user=admin(auth);return policies.list(user.organizationId());}
    @PostMapping @ResponseStatus(HttpStatus.OK) public MaintenanceSlaPolicy update(JwtAuthenticationToken auth,@Valid @RequestBody MaintenanceSlaPolicyUpdate request){AuthenticatedUser user=admin(auth);return policies.update(user.organizationId(),user.externalUserId(),request);}
    private AuthenticatedUser admin(JwtAuthenticationToken auth){AuthenticatedUser user=users.require(auth);permissions.require(user,Permission.ADMINISTER_ORGANIZATION);return user;}
}
