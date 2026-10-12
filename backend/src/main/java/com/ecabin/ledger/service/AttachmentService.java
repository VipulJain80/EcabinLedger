package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.AttachmentView;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.storage.BlobStorage;
import com.ecabin.ledger.storage.EvidenceScanner;
import com.ecabin.ledger.storage.EvidenceFileValidator;
import com.ecabin.ledger.storage.EvidenceScanStatus;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AttachmentService {
    private final JdbcTemplate jdbc; private final BlobStorage storage; private final EvidenceScanner scanner; private final OperationalFormService forms;
    public AttachmentService(JdbcTemplate jdbc,BlobStorage storage,EvidenceScanner scanner,OperationalFormService forms){this.jdbc=jdbc;this.storage=storage;this.scanner=scanner;this.forms=forms;}

    public Permission permissionForUpload(UUID operatorId,UUID defectId,UUID formId,UUID versionId){return permissionFor(forms.requireOperationalForm(operatorId,defectId,formId,versionId).purpose());}

    public Permission permissionForRemoval(AuthenticatedUser user,UUID attachmentId){
        var rows=jdbc.query("SELECT defect_id,form_id,form_version_id FROM attachments_dtl WHERE operator_id=? AND id=? AND isactive=1",(rs,n)->new Object[]{rs.getObject("defect_id",UUID.class),rs.getObject("form_id",UUID.class),rs.getObject("form_version_id",UUID.class)},user.organizationId(),attachmentId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evidence was not found for this organization.");
        Object[] row=rows.get(0);if(row[1]==null||row[2]==null)return Permission.REPORT;
        return permissionForUpload(user.organizationId(),(UUID)row[0],(UUID)row[1],(UUID)row[2]);
    }

    public UUID defectForAttachment(UUID operatorId,UUID attachmentId){return jdbc.query("SELECT defect_id FROM attachments_dtl WHERE operator_id=? AND id=? AND isactive=1",rs->rs.next()?rs.getObject(1,UUID.class):null,operatorId,attachmentId);}

    private Permission permissionFor(FormPurpose purpose){return switch(purpose){case DEFECT_REPORT->Permission.REPORT;case INSPECTION->Permission.INSPECT;case CORRECTIVE_ACTION->Permission.PERFORM_ACTION;case VERIFICATION->Permission.VERIFY;case CLOSURE_APPROVAL->Permission.APPROVE;case REOPEN->Permission.REOPEN;};}

    @Transactional
    public AttachmentView upload(AuthenticatedUser user,boolean teamScoped,boolean reporterScoped,UUID defectId,UUID formId,UUID versionId,String fieldKey,MultipartFile file){
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,user.externalUserId());
        if(file==null||file.isEmpty()) throw new IllegalArgumentException("Choose an evidence file.");
        // Serialize uploads for the same defect before checking per-field count limits.
        var locked=jdbc.query("SELECT d.id FROM defects_dtl d WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND (?=false OR (d.status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR "+teamMembershipPredicate("d.assigned_team")+"))) AND (?=false OR d.reported_by=?) FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),user.organizationId(),defectId,teamScoped,user.externalUserId(),user.organizationId(),user.externalUserId(),reporterScoped,user.externalUserId());
        if(locked.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect was not found for this organization.");
        FieldDefinition field=forms.requireUploadField(user.organizationId(),formId,versionId,fieldKey,defectId);
        var rules=field.fileRules();
        if(rules==null||rules.maxFileSizeBytes()==null||rules.extensions()==null||rules.mimeTypes()==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This published evidence field has incomplete file rules. Ask an administrator to publish a corrected form version.");
        String safeName=safeFilename(file.getOriginalFilename());String extension=extension(safeName);
        EvidenceFileValidator.validateSize(field,extension,file.getSize());
        byte[] bytes;
        try{bytes=file.getBytes();}catch(Exception ex){throw new IllegalArgumentException("The evidence file could not be read.");}
        EvidenceFileValidator.validateSize(field,extension,bytes.length);
        String detected=EvidenceFileValidator.validateContent(field,extension,bytes);
        Integer count=jdbc.queryForObject("SELECT count(*) FROM attachments_dtl WHERE operator_id=? AND defect_id=? AND form_id=? AND form_version_id=? AND field_key=? AND isactive=1 AND scan_status<>'REJECTED'",Integer.class,user.organizationId(),defectId,formId,versionId,fieldKey);
        if(count!=null&&count>=rules.maxCount())throw new IllegalArgumentException("The maximum number of files for this field has been reached.");
        UUID id=UUID.randomUUID();String key="evidence/"+user.organizationId()+"/"+defectId+"/"+id;String status="QUARANTINED";boolean stored=false;
        try {
            storage.put(key,bytes,detected);stored=true;
            status=EvidenceScanStatus.from(scanner.scan(bytes));
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            jdbc.update("INSERT INTO attachments_dtl(id,operator_id,defect_id,object_key,original_filename,detected_mime_type,size_bytes,sha256,uploaded_by,form_id,form_version_id,field_key,scan_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,user.organizationId(),defectId,key,safeName,detected,bytes.length,digest,user.externalUserId(),formId,versionId,fieldKey,status);
            audit(user,id,defectId,"ATTACHMENT_UPLOADED",safeName,status);
            if("REJECTED".equals(status)){storage.delete(key);}
            return new AttachmentView(id,defectId,null,fieldKey,safeName,detected,bytes.length,status,Instant.now());
        } catch(RuntimeException ex){if(stored)try{storage.delete(key);}catch(RuntimeException ignored){}throw ex;}
        catch(Exception ex){if(stored)try{storage.delete(key);}catch(RuntimeException ignored){}throw new IllegalStateException("Evidence upload could not be completed.");}
    }

    public Download download(AuthenticatedUser user,boolean teamScoped,boolean reporterScoped,UUID attachmentId){
        var rows=jdbc.query("SELECT a.defect_id,a.object_key,a.original_filename,a.detected_mime_type,a.scan_status,a.size_bytes FROM attachments_dtl a JOIN defects_dtl d ON d.operator_id=a.operator_id AND d.id=a.defect_id WHERE a.operator_id=? AND a.id=? AND a.isactive=1 AND d.isactive=1 AND (?=false OR (d.status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR "+teamMembershipPredicate("d.assigned_team")+"))) AND (?=false OR d.reported_by=?)",(rs,n)->new DownloadMeta(rs.getObject("defect_id",UUID.class),rs.getString("object_key"),rs.getString("original_filename"),rs.getString("detected_mime_type"),rs.getString("scan_status"),rs.getLong("size_bytes")),user.organizationId(),attachmentId,teamScoped,user.externalUserId(),user.organizationId(),user.externalUserId(),reporterScoped,user.externalUserId());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evidence was not found for this organization.");var meta=rows.get(0);
        if(!"CLEAN".equals(meta.scanStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Evidence is quarantined or rejected and cannot be downloaded.");
        return new Download(attachmentId,meta.filename(),meta.mimeType(),storage.get(meta.objectKey()));
    }
    @Transactional public void deactivate(AuthenticatedUser user,boolean teamScoped,boolean reporterScoped,UUID attachmentId,String reason){
        UUID operatorId=user.organizationId();String actor=user.externalUserId();
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);
        var rows=jdbc.query("SELECT a.defect_id,a.object_key,a.form_submission_id FROM attachments_dtl a JOIN defects_dtl d ON d.operator_id=a.operator_id AND d.id=a.defect_id WHERE a.operator_id=? AND a.id=? AND a.isactive=1 AND d.isactive=1 AND (?=false OR (d.status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR "+teamMembershipPredicate("d.assigned_team")+"))) AND (?=false OR d.reported_by=?) FOR UPDATE OF d",(rs,n)->new Object[]{rs.getObject("defect_id",UUID.class),rs.getString("object_key"),rs.getObject("form_submission_id",UUID.class)},operatorId,attachmentId,teamScoped,actor,operatorId,actor,reporterScoped,actor);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evidence was not found for this organization.");Object[] row=rows.get(0);
        if(row[2]!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Evidence attached to a submitted compliance record cannot be removed.");
        jdbc.update("UPDATE attachments_dtl SET isactive=2 WHERE operator_id=? AND id=? AND isactive=1",operatorId,attachmentId);
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,?,? ,?,?,?, ?,?::jsonb,?::jsonb)",operatorId,row[0],"ATTACHMENT_REMOVED",reason,actor,"ATTACHMENT",attachmentId,"{\"isactive\":1}","{\"isactive\":2}");
        try{storage.delete((String)row[1]);}catch(RuntimeException ignored){/* Soft-deleted metadata remains for a retryable cleanup job. */}
    }

    private void audit(AuthenticatedUser u,UUID id,UUID defectId,String action,String filename,String state){jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,new_state) VALUES(?,?,?,?,?,?,?,?::jsonb)",u.organizationId(),defectId,action,"Evidence "+filename+" stored with status "+state+".",u.externalUserId(),"ATTACHMENT",id,"{\"scanStatus\":\""+state+"\"}");}
    private String safeFilename(String raw){if(raw==null||raw.isBlank())throw new IllegalArgumentException("A filename is required.");String leaf=raw.replace('\\','/');leaf=leaf.substring(leaf.lastIndexOf('/')+1).replaceAll("[^A-Za-z0-9._-]","_");if(leaf.length()>120)leaf=leaf.substring(leaf.length()-120);if(leaf.isBlank()||leaf.startsWith("."))throw new IllegalArgumentException("The filename is invalid.");return leaf;}
    private String teamMembershipPredicate(String teamColumn){return "EXISTS (SELECT 1 FROM operator_membership_teams_mst omt JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=omt.operator_id AND t.name=omt.team_name AND t.isactive=1 WHERE omt.operator_id=? AND omt.external_user_id=? AND omt.team_name="+teamColumn+" AND omt.isactive=1)";}
    private String extension(String name){int dot=name.lastIndexOf('.');return dot<1?null:name.substring(dot+1).toLowerCase(Locale.ROOT);}
    private record DownloadMeta(UUID defectId,String objectKey,String filename,String mimeType,String scanStatus,long sizeBytes){}
    public record Download(UUID id,String filename,String mimeType,byte[] content){}
}
