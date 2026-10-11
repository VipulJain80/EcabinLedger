package com.ecabin.ledger.api;

import com.ecabin.ledger.api.ApiModels.ReviewerQueuePage;
import com.ecabin.ledger.api.ApiModels.DefectView;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ReviewerQueueService;
import com.ecabin.ledger.service.DefectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReviewerQueueController {
    public record RoutingRequest(@NotNull UUID teamId,@NotBlank @Size(max=1000) String reason) {}
    private final ReviewerQueueService reviewers;
    private final DefectService defects;
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    public ReviewerQueueController(ReviewerQueueService reviewers,DefectService defects,CurrentUserResolver users,PermissionService permissions){this.reviewers=reviewers;this.defects=defects;this.users=users;this.permissions=permissions;}

    @GetMapping("/review-queue")
    public ReviewerQueuePage queue(JwtAuthenticationToken auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(required=false) String status,@RequestParam(required=false) String search,@RequestParam(defaultValue="reportedAt") String sortBy,@RequestParam(defaultValue="DESC") String sortDirection){
        AuthenticatedUser u=users.require(auth);var grants=permissions.permissionsFor(u.organizationId(),u.role());boolean admin=grants.contains(Permission.ADMINISTER_ORGANIZATION);
        if(!admin){permissions.require(u,Permission.VIEW);permissions.require(u,Permission.REVIEW);}
        return reviewers.queue(u.organizationId(),u.externalUserId(),admin,page,size,status,search,sortBy,sortDirection);
    }

    @GetMapping("/reviewer-teams")
    public java.util.List<ApiModels.ReviewerTeamOption> reviewerTeams(JwtAuthenticationToken auth){AuthenticatedUser u=users.require(auth);permissions.require(u,Permission.REVIEW);return reviewers.reviewerTeams(u.organizationId());}

    @GetMapping("/configuration/reviewer-routing/default")
    public Map<String,Object> routing(JwtAuthenticationToken auth){AuthenticatedUser u=admin(auth);return reviewers.routing(u.organizationId());}

    @PostMapping("/configuration/reviewer-routing/default")
    public Map<String,Object> setRouting(JwtAuthenticationToken auth,@Valid @RequestBody RoutingRequest request){AuthenticatedUser u=admin(auth);return reviewers.setRouting(u.organizationId(),u.externalUserId(),request.teamId(),request.reason());}

    @PostMapping("/defects/{id}/review-routing")
    public DefectView reroute(JwtAuthenticationToken auth,@PathVariable UUID id,@Valid @RequestBody RoutingRequest request){AuthenticatedUser u=users.require(auth);permissions.require(u,Permission.REVIEW);boolean admin=permissions.permissionsFor(u.organizationId(),u.role()).contains(Permission.ADMINISTER_ORGANIZATION);reviewers.reroute(u.organizationId(),id,u.externalUserId(),admin,request.teamId(),request.reason());return defects.get(u.organizationId(),id);}

    @PostMapping("/defects/{id}/review-decision")
    public ApiModels.WorkflowRecord decide(JwtAuthenticationToken auth,@PathVariable UUID id,@Valid @RequestBody ApiModels.ReviewerDecisionRequest request){
        AuthenticatedUser u=users.require(auth);permissions.require(u,Permission.REVIEW);
        return reviewers.decide(u.organizationId(),id,u.externalUserId(),request);
    }

    private AuthenticatedUser admin(JwtAuthenticationToken auth){AuthenticatedUser u=users.require(auth);permissions.require(u,Permission.ADMINISTER_ORGANIZATION);return u;}
}
