package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.AreaMaintenanceRoutingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administrative area/team routing API. Tenant and actor always come from the validated principal. */
@RestController
@RequestMapping("/api/v1/configuration/area-team-routing")
public class AreaMaintenanceRoutingController {
    public record MappingBody(UUID id,@NotNull UUID areaId,@NotNull UUID maintenanceTeamId,@NotBlank @Size(max=1000) String reason) {}
    public record ChangeBody(@NotBlank @Size(max=1000) String reason) {}
    public record WorkflowBody(@NotBlank String assignmentMode,@NotBlank @Size(max=1000) String reason) {}
    public record ClosureTeamBody(@NotNull UUID teamId,@NotBlank @Size(max=1000) String reason) {}
    private final AreaMaintenanceRoutingService routing;
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    public AreaMaintenanceRoutingController(AreaMaintenanceRoutingService routing,CurrentUserResolver users,PermissionService permissions,JdbcTemplate jdbc){this.routing=routing;this.users=users;this.permissions=permissions;this.jdbc=jdbc;}

    @GetMapping("/mappings") public List<Map<String,Object>> mappings(JwtAuthenticationToken auth){var u=admin(auth);return routing.mappings(u.organizationId());}
    @GetMapping("/eligible-teams") public List<Map<String,Object>> eligibleTeams(JwtAuthenticationToken auth){var u=admin(auth);return routing.eligibleTeams(u.organizationId());}
    @GetMapping("/workflow") public Map<String,Object> workflow(JwtAuthenticationToken auth){var u=admin(auth);return routing.workflow(u.organizationId());}
    @GetMapping("/closure-team") public Map<String,Object> closureTeam(JwtAuthenticationToken auth){var u=admin(auth);return routing.closureRouting(u.organizationId());}
    @GetMapping("/exceptions") public List<Map<String,Object>> exceptions(JwtAuthenticationToken auth){var u=admin(auth);return routing.exceptions(u.organizationId());}
    @GetMapping("/history") public List<Map<String,Object>> history(JwtAuthenticationToken auth){var u=admin(auth);return jdbc.query("SELECT id,event_type AS \"eventType\",entity_type AS \"entityType\",entity_id AS \"entityId\",actor_id AS actor,note AS reason,occurred_at AS \"occurredAt\",new_state AS details FROM defect_events_dtl WHERE operator_id=? AND entity_type IN ('AREA_TEAM_MAPPING','DEFECT_WORKFLOW','ORGANIZATION') ORDER BY occurred_at DESC,id DESC LIMIT 200",new ColumnMapRowMapper(),u.organizationId());}
    @PostMapping("/mappings") public Map<String,Object> save(JwtAuthenticationToken auth,@Valid @RequestBody MappingBody body){var u=admin(auth);return routing.saveMapping(u.organizationId(),u.externalUserId(),new AreaMaintenanceRoutingService.MappingRequest(body.id(),body.areaId(),body.maintenanceTeamId(),body.reason()));}
    @PostMapping("/mappings/{id}/activate") @ResponseStatus(HttpStatus.NO_CONTENT) public void activate(JwtAuthenticationToken auth,@PathVariable UUID id,@Valid @RequestBody ChangeBody body){var u=admin(auth);routing.setMappingActive(u.organizationId(),u.externalUserId(),id,true,body.reason());}
    @PostMapping("/mappings/{id}/deactivate") @ResponseStatus(HttpStatus.NO_CONTENT) public void deactivate(JwtAuthenticationToken auth,@PathVariable UUID id,@Valid @RequestBody ChangeBody body){var u=admin(auth);routing.setMappingActive(u.organizationId(),u.externalUserId(),id,false,body.reason());}
    @PostMapping("/workflow") public Map<String,Object> setWorkflow(JwtAuthenticationToken auth,@Valid @RequestBody WorkflowBody body){var u=admin(auth);return routing.setWorkflow(u.organizationId(),u.externalUserId(),new AreaMaintenanceRoutingService.WorkflowRequest(body.assignmentMode(),body.reason()));}
    @PostMapping("/closure-team") public Map<String,Object> setClosureTeam(JwtAuthenticationToken auth,@Valid @RequestBody ClosureTeamBody body){var u=admin(auth);return routing.setClosureTeam(u.organizationId(),u.externalUserId(),body.teamId(),body.reason());}
    @PostMapping("/exceptions/{defectId}/retry") public Map<String,Object> retry(JwtAuthenticationToken auth,@PathVariable UUID defectId){var u=admin(auth);boolean assigned=routing.retry(u.organizationId(),defectId,u.externalUserId());return Map.of("assigned",assigned,"message",assigned?"Defect was assigned to the configured Maintenance team.":"No eligible active mapping is available. The defect remains unassigned.");}

    private AuthenticatedUser admin(JwtAuthenticationToken auth){AuthenticatedUser user=users.require(auth);permissions.require(user,Permission.ADMINISTER_ORGANIZATION);return user;}
}
