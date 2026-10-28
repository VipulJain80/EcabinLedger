package com.ecabin.ledger.api;

import com.ecabin.ledger.api.FormModels.*;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.OperationalFormService;
import com.ecabin.ledger.service.FormFieldBindingCatalog;
import com.ecabin.ledger.service.DefectService;
import com.ecabin.ledger.service.ClosureReviewAccessService;
import com.ecabin.ledger.api.ApiModels.DefectView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OperationalFormsController {
    private final OperationalFormService forms;
    private final CurrentUserResolver users;
    private final PermissionService permissions;
    private final DefectService defects;
    private final FormFieldBindingCatalog bindings;
    private final ClosureReviewAccessService closureAccess;

    @org.springframework.beans.factory.annotation.Autowired
    public OperationalFormsController(OperationalFormService forms, CurrentUserResolver users, PermissionService permissions,DefectService defects,FormFieldBindingCatalog bindings,ClosureReviewAccessService closureAccess) {
        this.forms=forms; this.users=users; this.permissions=permissions; this.defects=defects;this.bindings=bindings;this.closureAccess=closureAccess;
    }
    public OperationalFormsController(OperationalFormService forms, CurrentUserResolver users, PermissionService permissions,DefectService defects,FormFieldBindingCatalog bindings){this(forms,users,permissions,defects,bindings,null);}

    @GetMapping("/configuration/forms") @Operation(summary="List tenant form definitions")
    public List<FormSummary> list(JwtAuthenticationToken auth,@RequestParam(defaultValue="false") boolean includeInactive){
        AuthenticatedUser user=admin(auth); return forms.list(user.organizationId(),includeInactive);
    }
    @GetMapping("/configuration/form-field-bindings") @Operation(summary="List backend-approved form field bindings")
    public List<FieldBindingDefinition> fieldBindings(JwtAuthenticationToken auth){admin(auth);return forms.fieldBindingDefinitions();}
    @GetMapping("/configuration/form-mappings") @Operation(summary="List supported tenant form mapping choices")
    public List<FormMappingOption> formMappings(JwtAuthenticationToken auth,@RequestParam(required=false) UUID formId,@RequestParam(required=false) List<UUID> areaIds){AuthenticatedUser u=admin(auth);return forms.mappingOptions(u.organizationId(),formId,areaIds);}
    public List<FormMappingOption> formMappings(JwtAuthenticationToken auth){return formMappings(auth,null,null);}
    @GetMapping("/configuration/forms/table")
    public FormTablePage table(JwtAuthenticationToken auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(required=false) String search,@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(required=false) String sortBy,@RequestParam(defaultValue="DESC") String sortDirection){AuthenticatedUser u=admin(auth);return forms.table(u.organizationId(),page,size,search,status,sortBy,sortDirection);}
    @GetMapping(value="/configuration/forms/export",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<StreamingResponseBody> export(JwtAuthenticationToken auth,@RequestParam(required=false) List<String> columns,@RequestParam(required=false) String search,@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(required=false) String sortBy,@RequestParam(defaultValue="DESC") String sortDirection){AuthenticatedUser u=admin(auth);StreamingResponseBody body=out->forms.exportExcel(u.organizationId(),columns,search,status,sortBy,sortDirection,out);HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));headers.setContentDisposition(ContentDisposition.attachment().filename("ecabin-forms.xlsx",StandardCharsets.UTF_8).build());headers.setCacheControl("no-store");return ResponseEntity.ok().headers(headers).body(body);}
    @PostMapping("/configuration/forms") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Create a draft form definition")
    public FormSummary create(JwtAuthenticationToken auth,@Valid @RequestBody CreateFormRequest request){AuthenticatedUser u=admin(auth);return forms.create(u.organizationId(),u.externalUserId(),request);}
    @PostMapping("/configuration/forms/{formId}/duplicate") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Duplicate a form definition into a new draft",description="Resolves the source within the active tenant. Uses the source form's current published version unless sourceVersionId explicitly selects one of its versions; never copies submissions or publication state.")
    public FormSummary duplicate(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody DuplicateFormRequest request){AuthenticatedUser u=admin(auth);return forms.duplicate(u.organizationId(),u.externalUserId(),formId,request);}
    @PostMapping("/configuration/forms/{formId}/rename") @Operation(summary="Rename a tenant form without changing its versions")
    public FormSummary rename(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody RenameFormRequest request){AuthenticatedUser u=admin(auth);return forms.rename(u.organizationId(),u.externalUserId(),formId,request);}
    @PostMapping("/configuration/forms/{formId}/delete") @Operation(summary="Permanently delete an unused unpublished form")
    public DeletedForm delete(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody DeleteFormRequest request){AuthenticatedUser u=admin(auth);return forms.delete(u.organizationId(),u.externalUserId(),formId,request);}
    @PostMapping("/configuration/forms/{formId}/area") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reassignArea(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody ReassignFormAreaRequest request){AuthenticatedUser u=admin(auth);forms.reassignArea(u.organizationId(),u.externalUserId(),formId,request.areaId(),request.reason());}
    @PostMapping("/configuration/forms/{formId}/mapping") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMapping(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody UpdateFormMappingRequest request){AuthenticatedUser u=admin(auth);forms.updateMapping(u.organizationId(),u.externalUserId(),formId,request);}
    @GetMapping("/configuration/forms/{formId}/versions")
    public List<FormVersion> versions(JwtAuthenticationToken auth,@PathVariable UUID formId){AuthenticatedUser u=admin(auth);return forms.versions(u.organizationId(),formId);}
    @GetMapping("/configuration/forms/{formId}/versions/{versionId}")
    public FormVersion version(JwtAuthenticationToken auth,@PathVariable UUID formId,@PathVariable UUID versionId){AuthenticatedUser u=admin(auth);return forms.version(u.organizationId(),formId,versionId);}
    @PostMapping("/configuration/forms/{formId}/versions/{versionId}")
    public FormVersion save(JwtAuthenticationToken auth,@PathVariable UUID formId,@PathVariable UUID versionId,@Valid @RequestBody SaveDraftRequest request){AuthenticatedUser u=admin(auth);return forms.saveDraft(u.organizationId(),u.externalUserId(),formId,versionId,request.configuration());}
    @PostMapping("/configuration/forms/{formId}/drafts") @ResponseStatus(HttpStatus.CREATED)
    public FormVersion draft(JwtAuthenticationToken auth,@PathVariable UUID formId){AuthenticatedUser u=admin(auth);return forms.createDraft(u.organizationId(),u.externalUserId(),formId);}
    @PostMapping("/configuration/forms/{formId}/versions/{versionId}/restore") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Restore a published version into a new editable draft")
    public FormVersion restore(JwtAuthenticationToken auth,@PathVariable UUID formId,@PathVariable UUID versionId,@Valid @RequestBody RestoreFormVersionRequest request){AuthenticatedUser u=admin(auth);return forms.restorePublishedVersion(u.organizationId(),u.externalUserId(),formId,versionId,request);}
    @PostMapping("/configuration/forms/{formId}/versions/{versionId}/delete") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="Discard an unreferenced unpublished form draft")
    public void deleteDraftVersion(JwtAuthenticationToken auth,@PathVariable UUID formId,@PathVariable UUID versionId,@Valid @RequestBody DeleteFormRequest request){AuthenticatedUser u=admin(auth);forms.deleteDraftVersion(u.organizationId(),u.externalUserId(),formId,versionId,request);}
    @PostMapping("/configuration/forms/{formId}/publish")
    public FormVersion publish(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody PublishRequest request){AuthenticatedUser u=admin(auth);return forms.publish(u.organizationId(),u.externalUserId(),formId,request.versionId());}
    @PostMapping("/configuration/forms/{formId}/deactivate") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody DeactivateRequest request){AuthenticatedUser u=admin(auth);forms.setActive(u.organizationId(),u.externalUserId(),formId,false,request.reason());}
    @PostMapping("/configuration/forms/{formId}/activate") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(JwtAuthenticationToken auth,@PathVariable UUID formId,@Valid @RequestBody DeactivateRequest request){AuthenticatedUser u=admin(auth);forms.setActive(u.organizationId(),u.externalUserId(),formId,true,request.reason());}

    @GetMapping("/areas/{areaId}/forms") @Operation(summary="List published forms for a cabin area, purpose and workflow stage")
    public List<FormForArea> forArea(JwtAuthenticationToken auth,@PathVariable UUID areaId,@RequestParam(defaultValue="DEFECT_REPORT") FormPurpose purpose,@RequestParam(defaultValue="INITIAL_REPORT") WorkflowStage stage){if(purpose==FormPurpose.REVIEW_DECISION)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Reviewer Decision forms resolve only against an existing eligible case.");if(purpose==FormPurpose.CLOSURE_APPROVAL)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Closure Review forms resolve only against an existing eligible case and configured closure route.");AuthenticatedUser u=operationalRead(auth);return forms.publishedForArea(u.organizationId(),areaId,purpose,stage);}
    @GetMapping("/defects/{defectId}/operational-forms") @Operation(summary="Resolve eligible forms from server-side defect status")
    public List<FormForArea> forDefect(JwtAuthenticationToken auth,@PathVariable UUID defectId,@RequestParam FormPurpose purpose){AuthenticatedUser u=operational(auth,permissionFor(purpose));if(purpose==FormPurpose.CLOSURE_APPROVAL){defects.get(u.organizationId(),defectId);if(closureAccess==null)throw new org.springframework.security.access.AccessDeniedException("Closure review routing is unavailable.");closureAccess.requireEligibleReviewer(u.organizationId(),u.externalUserId());}else visibleDefect(u,defectId);return forms.publishedForDefect(u.organizationId(),defectId,purpose);}
    @GetMapping("/forms/{formId}/versions/{versionId}/definition")
    public FormForArea published(JwtAuthenticationToken auth,@PathVariable UUID formId,@PathVariable UUID versionId,@RequestParam(required=false) UUID areaId){AuthenticatedUser u=operationalRead(auth);FormForArea form=forms.published(u.organizationId(),formId,versionId,areaId);if(form.purpose()==FormPurpose.REVIEW_DECISION)permissions.require(u,Permission.REVIEW);if(form.purpose()==FormPurpose.CLOSURE_APPROVAL){permissions.require(u,Permission.APPROVE);requireClosureFormAccess(u,form);}return form;}
    @GetMapping("/forms/{formId}/versions/{versionId}/fields/{fieldKey}/options")
    public List<FormOption> options(JwtAuthenticationToken auth,@PathVariable UUID formId,@PathVariable UUID versionId,@PathVariable String fieldKey,@RequestParam(required=false) UUID areaId,@RequestParam(required=false) UUID componentId){AuthenticatedUser u=operationalRead(auth);FormForArea form=forms.published(u.organizationId(),formId,versionId,areaId);if(form.purpose()==FormPurpose.REVIEW_DECISION)permissions.require(u,Permission.REVIEW);if(form.purpose()==FormPurpose.CLOSURE_APPROVAL){permissions.require(u,Permission.APPROVE);requireClosureFormAccess(u,form);}return forms.options(u.organizationId(),formId,versionId,fieldKey,areaId,componentId);}
    @GetMapping("/aircraft/options") @Operation(summary="Search active aircraft available to defect reporters in their organization")
    public AircraftOptionPage reportingAircraft(JwtAuthenticationToken auth,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){AuthenticatedUser u=operational(auth,Permission.REPORT);return forms.reportingAircraft(u.organizationId(),search,page,size);}
    @PostMapping("/defects/{defectId}/form-submissions") @ResponseStatus(HttpStatus.CREATED)
    public FormSubmission submit(JwtAuthenticationToken auth,@PathVariable UUID defectId,@Valid @RequestBody SubmitFormRequest request){FormPurpose purpose=request.purpose()==null?FormPurpose.DEFECT_REPORT:request.purpose();if(purpose==FormPurpose.REVIEW_DECISION)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"Reviewer decisions must use the authorized case decision endpoint.");AuthenticatedUser u=operational(auth,permissionFor(purpose));visibleDefect(u,defectId);return forms.submit(u.organizationId(),u.externalUserId(),defectId,request);}
    @GetMapping("/defects/{defectId}/form-submissions")
    public List<FormSubmission> submissions(JwtAuthenticationToken auth,@PathVariable UUID defectId){AuthenticatedUser u=operationalRead(auth);var grants=permissions.permissionsFor(u.organizationId(),u.role());boolean viewAll=PermissionService.hasDefectViewAll(grants);visibleDefectForRead(u,defectId,viewAll);return forms.submissions(u.organizationId(),defectId,u.externalUserId(),!viewAll&&permissions.isMaintenanceTeamScoped(u.organizationId(),u.role()),!viewAll&&permissions.isReporterScoped(u.organizationId(),u.role()));}

    private AuthenticatedUser admin(JwtAuthenticationToken auth){return operational(auth,Permission.ADMINISTER_ORGANIZATION);}
    private AuthenticatedUser operational(JwtAuthenticationToken auth,Permission permission){AuthenticatedUser u=users.require(auth);permissions.require(u,permission);return u;}
    private AuthenticatedUser operationalRead(JwtAuthenticationToken auth){AuthenticatedUser u=users.require(auth);var grants=permissions.permissionsFor(u.organizationId(),u.role());if(grants.stream().noneMatch(java.util.Set.of(Permission.VIEW,Permission.DEFECT_VIEW_ALL,Permission.REPORT,Permission.REVIEW,Permission.INSPECT,Permission.PERFORM_ACTION,Permission.VERIFY,Permission.APPROVE,Permission.REOPEN)::contains))throw new org.springframework.security.access.AccessDeniedException("An applicable operational permission is required to read published forms.");return u;}
    private void requireClosureFormAccess(AuthenticatedUser user,FormForArea form){if(closureAccess==null)throw new org.springframework.security.access.AccessDeniedException("Closure review routing is unavailable.");closureAccess.requireEligibleReviewer(user.organizationId(),user.externalUserId());if(form.purpose()!=FormPurpose.CLOSURE_APPROVAL||form.workflowStage()!=WorkflowStage.APPROVAL_REQUIRED||forms.publishedForArea(user.organizationId(),form.areaId(),FormPurpose.CLOSURE_APPROVAL,WorkflowStage.APPROVAL_REQUIRED).stream().noneMatch(candidate->candidate.id().equals(form.id())&&candidate.versionId().equals(form.versionId())))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"This published form version is not currently eligible for closure review.");}
    private Permission permissionFor(FormPurpose purpose){return switch(purpose){case DEFECT_REPORT->Permission.REPORT;case REVIEW_DECISION->Permission.REVIEW;case INSPECTION->Permission.INSPECT;case CORRECTIVE_ACTION->Permission.PERFORM_ACTION;case VERIFICATION->Permission.VERIFY;case CLOSURE_APPROVAL->Permission.APPROVE;case REOPEN->Permission.REOPEN;};}
    private DefectView visibleDefectForRead(AuthenticatedUser user,UUID id,boolean viewAll){return viewAll?defects.get(user.organizationId(),id):visibleDefect(user,id);}
    private DefectView visibleDefect(AuthenticatedUser user,UUID id){if(permissions.permissionsFor(user.organizationId(),user.role()).contains(Permission.REPORT)){try{return defects.getForReporter(user.organizationId(),user.externalUserId(),id);}catch(org.springframework.web.server.ResponseStatusException notOwned){if(notOwned.getStatusCode()!=HttpStatus.NOT_FOUND)throw notOwned;}}if(permissions.isMaintenanceTeamScoped(user.organizationId(),user.role()))return defects.getForMaintenanceMember(user.organizationId(),user.externalUserId(),id);if(permissions.isReporterScoped(user.organizationId(),user.role()))return defects.getForReporter(user.organizationId(),user.externalUserId(),id);if(permissions.isReviewerTeamScoped(user.organizationId(),user.role()))return defects.getForReviewerMember(user.organizationId(),user.externalUserId(),id);return defects.get(user.organizationId(),id);}
}
