package com.ecabin.ledger.api;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.AttachmentService;
import com.ecabin.ledger.service.DefectService;
import com.ecabin.ledger.service.ClosureReviewAccessService;
import com.ecabin.ledger.api.ApiModels.DefectView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class AttachmentController {
    public record RemoveRequest(@NotBlank @Size(max=1000) String reason){}
    private final AttachmentService attachments; private final CurrentUserResolver users; private final PermissionService permissions; private final DefectService defects; private final ClosureReviewAccessService closureAccess;
    @org.springframework.beans.factory.annotation.Autowired
    public AttachmentController(AttachmentService attachments,CurrentUserResolver users,PermissionService permissions,DefectService defects,ClosureReviewAccessService closureAccess){this.attachments=attachments;this.users=users;this.permissions=permissions;this.defects=defects;this.closureAccess=closureAccess;}
    public AttachmentController(AttachmentService attachments,CurrentUserResolver users,PermissionService permissions,DefectService defects){this(attachments,users,permissions,defects,null);}

    @PostMapping(value="/defects/{defectId}/forms/{formId}/versions/{versionId}/fields/{fieldKey}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public com.ecabin.ledger.api.FormModels.AttachmentView upload(JwtAuthenticationToken auth,@PathVariable UUID defectId,@PathVariable UUID formId,@PathVariable UUID versionId,@PathVariable String fieldKey,@RequestPart("file") MultipartFile file){AuthenticatedUser u=users.require(auth);permissions.require(u,attachments.permissionForUpload(u.organizationId(),defectId,formId,versionId));visibleDefect(u,defectId);return attachments.upload(u,permissions.isMaintenanceTeamScoped(u.organizationId(),u.role()),permissions.isReporterScoped(u.organizationId(),u.role()),defectId,formId,versionId,fieldKey,file);}

    @GetMapping("/defects/{defectId}/attachments")
    public java.util.List<com.ecabin.ledger.api.FormModels.AttachmentView> listForDefect(JwtAuthenticationToken auth,@PathVariable UUID defectId){AuthenticatedUser u=users.require(auth);var grants=permissions.permissionsFor(u.organizationId(),u.role());if(!grants.contains(Permission.REPORT)&&!grants.contains(Permission.PERFORM_ACTION)&&!grants.contains(Permission.REVIEW)&&!grants.contains(Permission.APPROVE)&&!grants.contains(Permission.DEFECT_VIEW_ALL))throw new org.springframework.security.access.AccessDeniedException("The case reporter, assigned Maintenance member, Reviewer, closure-approval, or tenant defect-view permission is required to view case evidence.");visibleDefectForRead(u,defectId,PermissionService.hasDefectViewAll(grants));return attachments.listForDefect(u.organizationId(),defectId);}

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> download(JwtAuthenticationToken auth,@PathVariable UUID attachmentId){AuthenticatedUser u=users.require(auth);var grants=permissions.requireEvidenceRead(u);boolean viewAll=PermissionService.hasDefectViewAll(grants);UUID defectId=attachments.defectForAttachment(u.organizationId(),attachmentId);if(defectId==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"Evidence was not found for this organization.");visibleDefectForRead(u,defectId,viewAll);var file=attachments.download(u,!viewAll&&permissions.isMaintenanceTeamScoped(u.organizationId(),u.role()),!viewAll&&permissions.isReporterScoped(u.organizationId(),u.role()),attachmentId);HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(file.mimeType()));headers.setContentLength(file.content().length);headers.setContentDisposition(ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build());headers.setCacheControl("no-store");headers.set("X-Content-Type-Options","nosniff");return ResponseEntity.ok().headers(headers).body(file.content());}

    @PostMapping("/attachments/{attachmentId}/remove") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(JwtAuthenticationToken auth,@PathVariable UUID attachmentId,@Valid @RequestBody RemoveRequest request){AuthenticatedUser u=users.require(auth);permissions.require(u,attachments.permissionForRemoval(u,attachmentId));UUID defectId=attachments.defectForAttachment(u.organizationId(),attachmentId);if(defectId==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"Evidence was not found for this organization.");visibleDefect(u,defectId);attachments.deactivate(u,permissions.isMaintenanceTeamScoped(u.organizationId(),u.role()),permissions.isReporterScoped(u.organizationId(),u.role()),attachmentId,request.reason());}

    private AuthenticatedUser require(JwtAuthenticationToken auth,Permission permission){AuthenticatedUser u=users.require(auth);permissions.require(u,permission);return u;}
    private DefectView visibleDefectForRead(AuthenticatedUser user,UUID defectId,boolean viewAll){return viewAll?defects.get(user.organizationId(),defectId):visibleDefect(user,defectId);}
    private DefectView visibleDefect(AuthenticatedUser user,UUID defectId){if(closureAccess!=null&&permissions.permissionsFor(user.organizationId(),user.role()).contains(Permission.APPROVE)){try{DefectView candidate=defects.get(user.organizationId(),defectId);if(closureAccess.mayViewClosureCase(user.organizationId(),user.externalUserId(),defectId,candidate.status()))return candidate;}catch(org.springframework.web.server.ResponseStatusException ignored){}}if(permissions.isMaintenanceTeamScoped(user.organizationId(),user.role()))return defects.getForMaintenanceMember(user.organizationId(),user.externalUserId(),defectId);if(permissions.isReporterScoped(user.organizationId(),user.role()))return defects.getForReporter(user.organizationId(),user.externalUserId(),defectId);if(permissions.isReviewerTeamScoped(user.organizationId(),user.role()))return defects.getForReviewerMember(user.organizationId(),user.externalUserId(),defectId);return defects.get(user.organizationId(),defectId);}
}
