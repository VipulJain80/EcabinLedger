package com.ecabin.ledger.api;

import static com.ecabin.ledger.api.ApiModels.*;

import com.ecabin.ledger.service.DefectService;
import com.ecabin.ledger.service.OperationalFormService;
import com.ecabin.ledger.service.ClosureReviewAccessService;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DefectController {
    private final DefectService service;
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final OperationalFormService forms;
    private final ClosureReviewAccessService closureAccess;
    @org.springframework.beans.factory.annotation.Autowired
    public DefectController(DefectService service, CurrentUserResolver users, PermissionService permissions, OperationalFormService forms,ClosureReviewAccessService closureAccess) { this.service = service; this.users = users; this.permissions = permissions; this.forms = forms;this.closureAccess=closureAccess; }
    public DefectController(DefectService service,CurrentUserResolver users,PermissionService permissions,OperationalFormService forms){this(service,users,permissions,forms,null);}

    @GetMapping("/defects")
    public DefectPage list(JwtAuthenticationToken auth,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String location,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "DEFECT_ID") String searchBy,
        @RequestParam(required = false) String aircraftType,
        @RequestParam(required = false) Instant fromDate,
        @RequestParam(required = false) Instant toDate,
        @RequestParam(defaultValue = "false") boolean mine,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "25") int limit) {
        AuthenticatedUser user = users.require(auth);
        Set<Permission> grants = permissions.requireDefectRead(user);
        boolean viewAll = PermissionService.hasDefectViewAll(grants);
        boolean teamScoped = !viewAll && permissions.isMaintenanceTeamScoped(user.organizationId(), user.role());
        boolean reporterScoped = !viewAll && permissions.isReporterScoped(user.organizationId(), user.role());
        boolean reviewerScoped = !viewAll && permissions.isReviewerTeamScoped(user.organizationId(),user.role());
        return service.list(user.organizationId(), user.externalUserId(), status, location, q, searchBy, aircraftType, fromDate, toDate, mine || reporterScoped, teamScoped, reviewerScoped, cursor, limit);
    }

    @GetMapping("/maintenance-work-queue")
    public MaintenanceWorkQueuePage maintenanceWorkQueue(JwtAuthenticationToken auth,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) String search, @RequestParam(required = false) String actionStatus,
        @RequestParam(defaultValue = "assignmentDate") String sortBy, @RequestParam(defaultValue = "DESC") String sortDirection) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.VIEW); permissions.require(user, Permission.PERFORM_ACTION);
        return service.maintenanceWorkQueue(user.organizationId(), user.externalUserId(), page, size, search, actionStatus, sortBy, sortDirection);
    }

    @GetMapping("/aircraft/{aircraftId}")
    public FleetStatusRow aircraft(JwtAuthenticationToken auth, @PathVariable UUID aircraftId) {
        AuthenticatedUser user = users.require(auth); Set<Permission> grants = permissions.requireDefectRead(user); boolean viewAll = PermissionService.hasDefectViewAll(grants);
        return service.aircraft(user.organizationId(), user.externalUserId(), aircraftId,
            !viewAll && permissions.isMaintenanceTeamScoped(user.organizationId(), user.role()), !viewAll && permissions.isReporterScoped(user.organizationId(), user.role()), false);
    }

    @GetMapping("/aircraft/{aircraftId}/defects")
    public AircraftDefectPage aircraftDefects(JwtAuthenticationToken auth, @PathVariable UUID aircraftId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "reportedAt") String sortBy, @RequestParam(defaultValue = "DESC") String sortDirection) {
        AuthenticatedUser user = users.require(auth); permissions.requireDefectRead(user);
        // This aircraft-specific list has a deliberately different read policy from
        // /defects (Defect Log Book): auditors are limited to their own cases, while
        // reviewer, maintenance, and organization-admin roles see tenant cases.
        boolean auditorScoped = permissions.isReporterScoped(user.organizationId(), user.role());
        return service.aircraftDefects(user.organizationId(), user.externalUserId(), aircraftId,
            false, auditorScoped, false, page, size, status, search, sortBy, sortDirection);
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummary summary(JwtAuthenticationToken auth) {
        AuthenticatedUser user=users.require(auth);
        Set<Permission> grants=permissions.requireDefectRead(user);
        boolean viewAll=PermissionService.hasDefectViewAll(grants);
        boolean maintenance=!viewAll&&permissions.isMaintenanceTeamScoped(user.organizationId(), user.role());
        boolean reporter=!viewAll&&permissions.isReporterScoped(user.organizationId(), user.role());
        boolean reviewer=!viewAll&&permissions.isReviewerTeamScoped(user.organizationId(), user.role());
        if(!viewAll&&!maintenance&&!reporter&&!reviewer)
            throw new org.springframework.security.access.AccessDeniedException("A defect visibility scope is required for report summary data.");
        return service.summary(user.organizationId(), user.externalUserId(), maintenance, reporter, reviewer);
    }

    @GetMapping("/me")
    public CurrentUserView me(JwtAuthenticationToken auth) { AuthenticatedUser user=users.require(auth); var branding=service.organizationBranding(user.organizationId()); return new CurrentUserView(branding==null?null:branding.organizationName(), branding==null?null:branding.applicationName(), user.displayName(), user.role(), permissions.permissionsFor(user.organizationId(), user.role()).stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet())); }

    @PostMapping("/defects") @ResponseStatus(HttpStatus.CREATED)
    public DefectView create(JwtAuthenticationToken auth, @Valid @RequestBody CreateDefectRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) UUID idempotencyKey) {
        AuthenticatedUser user=users.require(auth); permissions.require(user, Permission.REPORT);
        if (forms.publishedForArea(user.organizationId(), request.areaId()).isEmpty())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "No published operational form is configured for the selected cabin area.");
        if (request.aircraftId() == null || request.formId() == null || request.formVersionId() == null)
            throw new IllegalArgumentException("Select an aircraft and a published form version for the chosen cabin area.");
        var selectedForm = forms.published(user.organizationId(), request.formId(), request.formVersionId(),request.areaId());
        if (!request.areaId().equals(selectedForm.areaId()))
            throw new IllegalArgumentException("The selected published form is not configured for the chosen cabin area.");
        var mapped=forms.resolveDefectReport(user.organizationId(),request.areaId(),request.formId(),request.formVersionId(),request.formAnswers());
        CreateDefectRequest canonical=new CreateDefectRequest(request.tailNumber(),(String)mapped.get("location"),(String)mapped.get("category"),request.areaId(),
            (UUID)mapped.get("componentId"),(UUID)mapped.get("conditionId"),(String)mapped.get("zone"),(Integer)mapped.get("rowNumber"),(String)mapped.get("seatReference"),null,
            (String)mapped.get("title"),(String)mapped.get("description"),(String)mapped.get("severity"),request.aircraftId(),request.formId(),request.formVersionId(),request.formAnswers());
        return service.create(user.organizationId(), user.externalUserId(), canonical,idempotencyKey);
    }

    @GetMapping("/defects/{id}")
    public DefectView get(JwtAuthenticationToken auth, @PathVariable UUID id) {
        AuthenticatedUser user=users.require(auth);
        Set<Permission> grants=permissions.requireDefectRead(user);
        // Match the aircraft Defect List read policy: report-only users see their own
        // reports, while other defect readers may open tenant records. Workflow
        // mutations continue to use the stricter team, assignment, and action checks.
        boolean reporterScoped=!PermissionService.hasDefectViewAll(grants)
            && permissions.isReporterScoped(user.organizationId(),user.role());
        return reporterScoped
            ? service.getForReporter(user.organizationId(),user.externalUserId(),id)
            : service.get(user.organizationId(),id);
    }

    @GetMapping("/aircraft/{aircraftId}/defects/{id}")
    public DefectView aircraftListDefect(JwtAuthenticationToken auth, @PathVariable UUID aircraftId, @PathVariable UUID id) {
        AuthenticatedUser user=users.require(auth);
        permissions.requireDefectRead(user);
        return service.getForAircraftList(user.organizationId(), user.externalUserId(), aircraftId, id,
            permissions.isReporterScoped(user.organizationId(), user.role()));
    }

    @PostMapping("/defects/{id}/transitions")
    public DefectView transition(JwtAuthenticationToken auth, @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        AuthenticatedUser user=users.require(auth);
        visibleDefect(user,id);
        String target=request.status().toUpperCase();
        Permission permission=switch(target) {
            case "UNDER_REVIEW", "INSPECTION_REQUIRED", "REJECTED", "CANCELLED" -> Permission.REVIEW;
            case "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE" -> Permission.INSPECT;
            case "ACTION_ASSIGNED" -> Permission.ASSIGN_ACTION;
            case "IN_PROGRESS", "AWAITING_VERIFICATION" -> Permission.PERFORM_ACTION;
            case "VERIFIED" -> Permission.VERIFY;
            case "APPROVAL_REQUIRED" -> Permission.APPROVE;
            case "CLOSED" -> Permission.CLOSE;
            case "REOPENED" -> Permission.REOPEN;
            default -> throw new IllegalArgumentException("Unsupported status.");
        };
        if (target.equals("INSPECTION_REQUIRED") && "INSPECTION_IN_PROGRESS".equals(visibleDefect(user, id).status())) permission=Permission.INSPECT;
        permissions.require(user, permission);
        return service.transition(user.organizationId(), id, user.externalUserId(), request);
    }

    @GetMapping("/defects/{id}/events")
    public List<DefectEventView> events(JwtAuthenticationToken auth, @PathVariable UUID id) { AuthenticatedUser user=users.require(auth); Set<Permission> grants=permissions.permissionsFor(user.organizationId(),user.role()); if(!grants.contains(Permission.AUDIT)&&!grants.contains(Permission.PERFORM_ACTION)&&!grants.contains(Permission.REVIEW)&&!grants.contains(Permission.REPORT)&&!grants.contains(Permission.DEFECT_VIEW_ALL))throw new org.springframework.security.access.AccessDeniedException("Audit history, the reporting Auditor, assigned Maintenance, Reviewer, or tenant defect-view permission is required."); visibleDefectForRead(user, id, PermissionService.hasDefectViewAll(grants)); return service.events(user.organizationId(), id); }

    private DefectView visibleDefectForRead(AuthenticatedUser user, UUID id, boolean viewAll) {
        return viewAll ? service.get(user.organizationId(), id) : visibleDefect(user, id);
    }

    private DefectView visibleDefect(AuthenticatedUser user, UUID id) {
        if (permissions.permissionsFor(user.organizationId(),user.role()).contains(Permission.REPORT)) {
            try { return service.getForReporter(user.organizationId(),user.externalUserId(),id); }
            catch (org.springframework.web.server.ResponseStatusException notOwned) { if(notOwned.getStatusCode()!=HttpStatus.NOT_FOUND)throw notOwned; }
        }
        if(closureAccess!=null&&permissions.permissionsFor(user.organizationId(),user.role()).contains(Permission.APPROVE)){
            try{DefectView candidate=service.get(user.organizationId(),id);if(closureAccess.mayViewClosureCase(user.organizationId(),user.externalUserId(),id,candidate.status()))return candidate;}
            catch(org.springframework.web.server.ResponseStatusException ignored){}
        }
        if (permissions.isMaintenanceTeamScoped(user.organizationId(), user.role())) return service.getForMaintenanceMember(user.organizationId(), user.externalUserId(), id);
        if (permissions.isReporterScoped(user.organizationId(), user.role())) return service.getForReporter(user.organizationId(), user.externalUserId(), id);
        if (permissions.isReviewerTeamScoped(user.organizationId(),user.role())) return service.getForReviewerMember(user.organizationId(),user.externalUserId(),id);
        return service.get(user.organizationId(), id);
    }
}
