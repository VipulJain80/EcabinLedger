package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.ConfigurationService;
import com.ecabin.ledger.service.OperationalFormService;
import com.ecabin.ledger.service.WorkflowStatusService;
import com.ecabin.ledger.service.ClosureReviewAccessService;
import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.WorkflowStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import com.ecabin.ledger.api.ApiModels.ConditionOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, tenant-scoped lookup data used by operational forms. */
@RestController
@RequestMapping("/api/v1/reference-data")
public class ReferenceDataController {
    public enum RefreshResource { CATEGORIES, AREAS, COMPONENTS, CONDITIONS, CONDITIONS_FOR_COMPONENT, SEVERITIES, FLEETS, AIRCRAFT_TYPES, PUBLISHED_FORMS, FORM_OPTIONS, WORKFLOW_STATUSES }
    public record RefreshRequest(@NotEmpty @Size(max=8) Set<RefreshResource> resources,
            java.util.UUID areaId, FormPurpose purpose, WorkflowStage stage,
            java.util.UUID formId, java.util.UUID versionId, @Size(max=80) String fieldKey, java.util.UUID componentId,
            String moduleCode, Boolean activeOnly, Integer page, Integer size, @Size(max=100) String search,
            String sortBy, String sortDirection) {}
    private final ConfigurationService configuration;
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final OperationalFormService forms;
    private final WorkflowStatusService workflowStatuses;
    private final ClosureReviewAccessService closureAccess;

    public ReferenceDataController(ConfigurationService configuration, CurrentUserResolver users, PermissionService permissions,
            OperationalFormService forms, WorkflowStatusService workflowStatuses, ClosureReviewAccessService closureAccess) {
        this.configuration = configuration; this.users = users; this.permissions = permissions; this.forms=forms; this.workflowStatuses=workflowStatuses; this.closureAccess=closureAccess;
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
        return configuration.severityBadges(user.organizationId());
    }

    @GetMapping("/components/{componentId}/conditions")
    public List<ConditionOption> conditionsForComponent(JwtAuthenticationToken auth, @PathVariable java.util.UUID componentId) {
        AuthenticatedUser user = operationalReader(auth);
        return configuration.conditionsForComponent(user.organizationId(), componentId);
    }

    /** Refreshes only enumerated resources for the authenticated organization; no tenant/cache key is accepted from callers. */
    @PostMapping("/refresh")
    public Map<String,Object> refresh(JwtAuthenticationToken auth, @Valid @RequestBody RefreshRequest request) {
        AuthenticatedUser user=users.require(auth);
        Map<String,Object> result=new java.util.LinkedHashMap<>();
        for(RefreshResource resource:request.resources()) {
            Object data=switch(resource) {
                case CATEGORIES -> { operationalReader(user); yield configuration.refreshList(user.organizationId(),"defect-categories"); }
                case AREAS -> { operationalReader(user); yield configuration.refreshList(user.organizationId(),"areas"); }
                case COMPONENTS -> { operationalReader(user); yield configuration.refreshList(user.organizationId(),"components"); }
                case CONDITIONS -> { permissions.require(user,Permission.ADMINISTER_ORGANIZATION); yield configuration.refreshList(user.organizationId(),"defect-conditions"); }
                case CONDITIONS_FOR_COMPONENT -> { operationalReader(user); if(request.componentId()==null)throw new IllegalArgumentException("componentId is required to refresh component conditions."); yield configuration.refreshConditionsForComponent(user.organizationId(),request.componentId()); }
                case SEVERITIES -> { operationalReader(user); configuration.refreshSeverities(user.organizationId()); yield Boolean.TRUE; }
                case FLEETS -> { operationalReader(user); yield configuration.refreshList(user.organizationId(),"fleets"); }
                case AIRCRAFT_TYPES -> { operationalReader(user); yield configuration.refreshAircraftTypes(user.organizationId()); }
                case PUBLISHED_FORMS -> { operationalRead(user); if(request.areaId()==null||request.purpose()==null||request.stage()==null)throw new IllegalArgumentException("areaId, purpose, and stage are required to refresh published forms."); yield forms.refreshPublishedForArea(user.organizationId(),request.areaId(),request.purpose(),request.stage()); }
                case FORM_OPTIONS -> refreshFormOptions(user,request);
                case WORKFLOW_STATUSES -> { requireWorkflowStatusReader(user); yield workflowStatuses.refreshList(request.moduleCode(),Boolean.TRUE.equals(request.activeOnly()),request.page()==null?0:request.page(),request.size()==null?100:request.size(),request.search(),request.sortBy()==null?"sortOrder":request.sortBy(),request.sortDirection()==null?"ASC":request.sortDirection()); }
            };
            result.put(resource.name(),Map.of("status","refreshed"));
        }
        return Map.of("refreshedAt",Instant.now().toString(),"resources",result);
    }

    private Object refreshFormOptions(AuthenticatedUser user,RefreshRequest request) {
        operationalRead(user);
        if(request.formId()==null||request.versionId()==null||request.fieldKey()==null||request.fieldKey().isBlank())throw new IllegalArgumentException("formId, versionId, and fieldKey are required to refresh form options.");
        var form=forms.published(user.organizationId(),request.formId(),request.versionId(),request.areaId());
        if(form.purpose()==FormModels.FormPurpose.REVIEW_DECISION)permissions.require(user,Permission.REVIEW);
        if(form.purpose()==FormModels.FormPurpose.CLOSURE_APPROVAL){permissions.require(user,Permission.APPROVE);closureAccess.requireEligibleReviewer(user.organizationId(),user.externalUserId());if(forms.publishedForArea(user.organizationId(),form.areaId(),form.purpose(),form.workflowStage()).stream().noneMatch(candidate->candidate.id().equals(form.id())&&candidate.versionId().equals(form.versionId())))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"This published form version is not currently eligible for closure review.");}
        var field=form.configuration().fields().stream().filter(candidate->candidate.key().equals(request.fieldKey())).findFirst().orElse(null);
        if(field==null||!OperationalFormService.cacheableOptionSource(field.optionSource()))throw new IllegalArgumentException("This field's option source is not eligible for reference-data refresh.");
        return forms.refreshOptions(user.organizationId(),request.formId(),request.versionId(),request.fieldKey(),request.areaId(),request.componentId());
    }

    private void operationalReader(AuthenticatedUser user){var grants=permissions.permissionsFor(user.organizationId(),user.role());if(!grants.contains(Permission.VIEW)&&!grants.contains(Permission.REPORT))throw new org.springframework.security.access.AccessDeniedException("View or report permission is required.");}
    private void operationalRead(AuthenticatedUser user){var grants=permissions.permissionsFor(user.organizationId(),user.role());if(grants.stream().noneMatch(Set.of(Permission.VIEW,Permission.DEFECT_VIEW_ALL,Permission.REPORT,Permission.REVIEW,Permission.INSPECT,Permission.PERFORM_ACTION,Permission.APPROVE,Permission.VERIFY,Permission.REOPEN)::contains))throw new org.springframework.security.access.AccessDeniedException("An applicable operational permission is required to read published forms.");}
    private void requireWorkflowStatusReader(AuthenticatedUser user){var allowed=Set.of(Permission.VIEW,Permission.DEFECT_VIEW_ALL,Permission.REPORT,Permission.REVIEW,Permission.INSPECT,Permission.ASSIGN_ACTION,Permission.PERFORM_ACTION,Permission.VERIFY,Permission.APPROVE,Permission.CLOSE,Permission.REOPEN);if(permissions.permissionsFor(user.organizationId(),user.role()).stream().noneMatch(allowed::contains))throw new org.springframework.security.access.AccessDeniedException("Workflow status read permission is required.");}

    private AuthenticatedUser operationalReader(JwtAuthenticationToken auth){AuthenticatedUser user=users.require(auth);var grants=permissions.permissionsFor(user.organizationId(),user.role());if(!grants.contains(Permission.VIEW)&&!grants.contains(Permission.REPORT))throw new org.springframework.security.access.AccessDeniedException("View or report permission is required.");return user;}
}
