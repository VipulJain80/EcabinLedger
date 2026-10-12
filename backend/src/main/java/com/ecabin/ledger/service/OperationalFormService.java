package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Stream;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Tenant-scoped form definitions, immutable versions and validated operational submissions. */
@Service
public class OperationalFormService {
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "pdf");
    private static final Set<String> MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "application/pdf");
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z][a-zA-Z0-9_]{0,79}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FormFieldBindingCatalog bindingCatalog;

    public OperationalFormService(JdbcTemplate jdbc, ObjectMapper mapper) { this(jdbc,mapper,new FormFieldBindingCatalog()); }
    @org.springframework.beans.factory.annotation.Autowired
    public OperationalFormService(JdbcTemplate jdbc, ObjectMapper mapper, FormFieldBindingCatalog bindingCatalog) { this.jdbc = jdbc; this.mapper = mapper; this.bindingCatalog=bindingCatalog; }

    public List<FormSummary> list(UUID operatorId, boolean includeInactive) {
        String sql = "SELECT f.id,f.form_key,f.name,f.area_id,a.name AS area_name,f.purpose,f.workflow_stage,f.isactive,f.updated_at,p.version_number AS published_version,p.id AS published_version_id,d.version_number AS draft_version,d.id AS draft_version_id " +
            "FROM operational_forms_dtl f JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id " +
            "LEFT JOIN operational_form_versions_dtl p ON p.operator_id=f.operator_id AND p.form_id=f.id AND p.id=f.published_version_id " +
            "LEFT JOIN operational_form_versions_dtl d ON d.operator_id=f.operator_id AND d.form_id=f.id AND d.status='DRAFT' " +
            "WHERE f.operator_id=?" + (includeInactive ? "" : " AND f.isactive=1") + " ORDER BY f.updated_at DESC,f.name LIMIT 500";
        return jdbc.query(sql, (rs,n) -> summary(rs),operatorId);
    }

    @Transactional
    public void reassignArea(UUID operatorId,String actor,UUID formId,UUID areaId,String reason) {
        auditActor(actor);
        Map<String,Object> form=requireForm(operatorId,formId);
        if(number(form.get("isactive"))!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reactivate the form before changing its cabin area.");
        requireActiveArea(operatorId,areaId);
        UUID previousArea=(UUID)form.get("area_id");
        if(previousArea.equals(areaId)) return;
        int changed=jdbc.update("UPDATE operational_forms_dtl SET area_id=?,updated_at=now(),updated_by=? WHERE operator_id=? AND id=? AND area_id=? AND isactive=1",areaId,actor,operatorId,formId,previousArea);
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"The form mapping changed while saving. Reload and try again.");
        audit(operatorId,actor,null,"FORM_AREA_REASSIGNED","FORM",formId,reason.trim(),Map.of("areaId",previousArea),Map.of("areaId",areaId));
    }

    @Transactional
    public void updateMapping(UUID operatorId,String actor,UUID formId,UpdateFormMappingRequest request) {
        auditActor(actor);
        Map<String,Object> form=requireForm(operatorId,formId);
        if(number(form.get("isactive"))!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reactivate the form before changing its mapping.");
        requireActiveArea(operatorId,request.areaId());
        FormResolutionRules.requireMapping(request.purpose(),request.workflowStage());
        UUID previousArea=(UUID)form.get("area_id"); String previousPurpose=String.valueOf(form.get("purpose")); String previousStage=String.valueOf(form.get("workflow_stage"));
        if(previousArea.equals(request.areaId())&&previousPurpose.equals(request.purpose().name())&&previousStage.equals(request.workflowStage().name()))return;
        int changed=jdbc.update("UPDATE operational_forms_dtl SET area_id=?,purpose=?,workflow_stage=?,updated_at=now(),updated_by=? WHERE operator_id=? AND id=? AND isactive=1",request.areaId(),request.purpose().name(),request.workflowStage().name(),actor,operatorId,formId);
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The form mapping changed while saving. Reload and try again.");
        audit(operatorId,actor,null,"FORM_MAPPING_UPDATED","FORM",formId,request.reason().trim(),Map.of("areaId",previousArea,"purpose",previousPurpose,"workflowStage",previousStage),Map.of("areaId",request.areaId(),"purpose",request.purpose().name(),"workflowStage",request.workflowStage().name()));
    }

    public FormTablePage table(UUID operatorId,int page,int size,String search,String status,String sortBy,String direction){
        if(page<0||size<1||size>100)throw new IllegalArgumentException("Choose a valid page and page size from 1 to 100.");
        String normalizedStatus=status==null?"ACTIVE":status.toUpperCase(java.util.Locale.ROOT);if(!Set.of("ACTIVE","INACTIVE","ALL").contains(normalizedStatus))throw new IllegalArgumentException("Choose a valid form status.");
        String term=search==null?"":search.trim();if(term.length()>200)throw new IllegalArgumentException("Search is limited to 200 characters.");
        Map<String,String> sortColumns=Map.of("name","f.name","areaName","a.name","purpose","f.purpose","workflowStage","f.workflow_stage","published","v.version_number","isactive","f.isactive","updatedAt","f.updated_at");String sort=sortColumns.get(sortBy==null||sortBy.isBlank()?"updatedAt":sortBy);if(sort==null)throw new IllegalArgumentException("Choose a supported form sort column.");String sortDirection=direction==null?"ASC":direction.toUpperCase(java.util.Locale.ROOT);if(!Set.of("ASC","DESC").contains(sortDirection))throw new IllegalArgumentException("Choose a valid sort direction.");
        String where=" WHERE f.operator_id=?"+(normalizedStatus.equals("ACTIVE")?" AND f.isactive=1":normalizedStatus.equals("INACTIVE")?" AND f.isactive=2":"")+" AND (?='' OR POSITION(LOWER(?) IN LOWER(CONCAT_WS(' ',f.name,f.form_key,a.name,f.purpose,f.workflow_stage)))>0)";
        Long count=jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl f JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id LEFT JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id"+where,Long.class,operatorId,term,term);
        List<FormSummary> rows=jdbc.query("SELECT f.id,f.form_key,f.name,f.area_id,a.name AS area_name,f.purpose,f.workflow_stage,f.isactive,f.updated_at,p.version_number AS published_version,p.id AS published_version_id,d.version_number AS draft_version,d.id AS draft_version_id FROM operational_forms_dtl f JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id LEFT JOIN operational_form_versions_dtl p ON p.operator_id=f.operator_id AND p.form_id=f.id AND p.id=f.published_version_id LEFT JOIN operational_form_versions_dtl d ON d.operator_id=f.operator_id AND d.form_id=f.id AND d.status='DRAFT'"+where+" ORDER BY "+sort+" "+sortDirection+" NULLS LAST,f.id LIMIT ? OFFSET ?",(rs,n)->summary(rs),operatorId,term,term,size,(long)page*size);
        long total=count==null?0:count;return new FormTablePage(rows,page,size,total,Math.max(1,(int)Math.ceil((double)total/size)));
    }

    @Transactional(readOnly=true)
    public void exportExcel(UUID operatorId,List<String> requestedColumns,String search,String status,String sortBy,String direction,OutputStream output)throws IOException{
        Map<String,String> headers=Map.of("name","Form name","areaName","Area","purpose","Purpose","workflowStage","Workflow stage","published","Published version","isactive","Status","updatedAt","Last updated");
        List<String> columns=requestedColumns==null?List.of("name","areaName","purpose","workflowStage","published","isactive","updatedAt"):requestedColumns.stream().distinct().toList();
        if(columns.isEmpty()||columns.stream().anyMatch(column->!headers.containsKey(column)))throw new IllegalArgumentException("Select visible Form Master columns to export.");
        String normalizedStatus=status==null?"ACTIVE":status.toUpperCase(java.util.Locale.ROOT);if(!Set.of("ACTIVE","INACTIVE","ALL").contains(normalizedStatus))throw new IllegalArgumentException("Choose a valid form status.");String term=search==null?"":search.trim();if(term.length()>200)throw new IllegalArgumentException("Search is limited to 200 characters.");Map<String,String> sortColumns=Map.of("name","f.name","areaName","a.name","purpose","f.purpose","workflowStage","f.workflow_stage","published","v.version_number","isactive","f.isactive","updatedAt","f.updated_at");String sort=sortColumns.get(sortBy==null||sortBy.isBlank()?"updatedAt":sortBy);if(sort==null)throw new IllegalArgumentException("Choose a supported form sort column.");String sortDirection=direction==null?"ASC":direction.toUpperCase(java.util.Locale.ROOT);if(!Set.of("ASC","DESC").contains(sortDirection))throw new IllegalArgumentException("Choose a valid sort direction.");String where=" WHERE f.operator_id=?"+(normalizedStatus.equals("ACTIVE")?" AND f.isactive=1":normalizedStatus.equals("INACTIVE")?" AND f.isactive=2":"")+" AND (?='' OR POSITION(LOWER(?) IN LOWER(CONCAT_WS(' ',f.name,f.form_key,a.name,f.purpose,f.workflow_stage)))>0)";
        Long total=jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl f JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id LEFT JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id"+where,Long.class,operatorId,term,term);
        XlsxStreamWriter writer=new XlsxStreamWriter(output,columns.stream().map(headers::get).toList(),total==null?0:total);
        try{
            PreparedStatementCreator statement=connection->{var p=connection.prepareStatement("SELECT f.name,a.name AS area_name,f.purpose,f.workflow_stage,v.version_number,f.isactive,f.updated_at FROM operational_forms_dtl f JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id LEFT JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id"+where+" ORDER BY "+sort+" "+sortDirection+" NULLS LAST,f.id");p.setFetchSize(500);p.setObject(1,operatorId);p.setString(2,term);p.setString(3,term);return p;};
            RowCallbackHandler handler=rs->{Map<String,String> values=Map.of("name",rs.getString("name"),"areaName",rs.getString("area_name"),"purpose",rs.getString("purpose"),"workflowStage",rs.getString("workflow_stage"),"published",rs.getObject("version_number")==null?"—":"v"+rs.getInt("version_number"),"isactive",rs.getInt("isactive")==1?"Active":"Inactive","updatedAt",rs.getTimestamp("updated_at").toInstant().toString());try{writer.writeRow(columns.stream().map(values::get).toList());}catch(IOException e){throw new UncheckedIOException(e);}};
            jdbc.query(statement,handler);writer.finish();
        }catch(UncheckedIOException ex){throw ex.getCause();}
    }

    @Transactional
    public FormSummary create(UUID operatorId, String actor, CreateFormRequest request) {
        auditActor(actor);
        String key = request.formKey().trim().toUpperCase(java.util.Locale.ROOT);
        if (!key.matches("[A-Z][A-Z0-9_-]{1,79}")) throw new IllegalArgumentException("Form key must use 2–80 uppercase letters, numbers, underscores or hyphens.");
        requireActiveArea(operatorId, request.areaId());
        FormPurpose purpose=request.purpose()==null?FormPurpose.DEFECT_REPORT:request.purpose();
        WorkflowStage workflowStage=request.workflowStage()==null?(purpose==FormPurpose.DEFECT_REPORT?WorkflowStage.INITIAL_REPORT:null):request.workflowStage();
        FormResolutionRules.requireMapping(purpose,workflowStage);
        UUID id = UUID.randomUUID(), versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO operational_forms_dtl(id,operator_id,area_id,form_key,name,purpose,workflow_stage) VALUES(?,?,?,?,?,?,?)",id,operatorId,request.areaId(),key,request.name().trim(),purpose.name(),workflowStage.name());
        jdbc.update("INSERT INTO operational_form_versions_dtl(id,operator_id,form_id,version_number,status,fields_json,created_by) VALUES(?,?,?,1,'DRAFT','{\"fields\":[]}'::jsonb,?)",versionId,operatorId,id,actor);
        audit(operatorId,actor,null,"FORM_CREATED","FORM",id,"Form created: "+request.name(),null,Map.of("formKey",key,"areaId",request.areaId(),"purpose",purpose.name(),"workflowStage",workflowStage.name()));
        return list(operatorId,true).stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public FormVersion saveDraft(UUID operatorId, String actor, UUID formId, UUID versionId, FormConfiguration configuration) {
        auditActor(actor);
        FormVersion current = requireVersion(operatorId,formId,versionId);
        if (!"DRAFT".equals(current.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Published form versions cannot be edited; create a new draft.");
        validateConfiguration(operatorId, formArea(operatorId,formId), configuration, formPurpose(operatorId,formId), false);
        jdbc.update("UPDATE operational_form_versions_dtl SET fields_json=?::jsonb WHERE operator_id=? AND form_id=? AND id=? AND status='DRAFT'",json(configuration),operatorId,formId,versionId);
        jdbc.update("UPDATE operational_forms_dtl SET updated_at=now() WHERE operator_id=? AND id=?",operatorId,formId);
        audit(operatorId,actor,null,"FORM_DRAFT_UPDATED","FORM_VERSION",versionId,"Draft form fields updated.",null,Map.of("formId",formId,"fieldCount",configuration.fields().size()));
        return requireVersion(operatorId,formId,versionId);
    }

    @Transactional
    public FormVersion createDraft(UUID operatorId, String actor, UUID formId) {
        auditActor(actor);
        Map<String,Object> form = requireForm(operatorId,formId);
        if (number(form.get("isactive")) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reactivate the form before creating a draft.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? AND status='DRAFT')",Boolean.class,operatorId,formId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This form already has an editable draft.");
        Integer max = jdbc.queryForObject("SELECT COALESCE(max(version_number),0) FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=?",Integer.class,operatorId,formId);
        UUID id=UUID.randomUUID();
        Object published=form.get("published_version_id");
        String fields = published == null ? "{\"fields\":[]}" : jdbc.queryForObject("SELECT fields_json::text FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? AND id=? AND status='PUBLISHED'",String.class,operatorId,formId,published);
        jdbc.update("INSERT INTO operational_form_versions_dtl(id,operator_id,form_id,version_number,status,fields_json,created_by) VALUES(?,?,?,?,'DRAFT',?::jsonb,?)",id,operatorId,formId,(max==null?0:max)+1,fields,actor);
        jdbc.update("UPDATE operational_forms_dtl SET updated_at=now() WHERE operator_id=? AND id=?",operatorId,formId);
        audit(operatorId,actor,null,"FORM_DRAFT_CREATED","FORM_VERSION",id,"Draft form version created.",null,Map.of("formId",formId,"versionNumber",(max==null?0:max)+1));
        return requireVersion(operatorId,formId,id);
    }

    @Transactional
    public FormVersion publish(UUID operatorId,String actor,UUID formId,UUID versionId) {
        auditActor(actor);
        if(number(requireForm(operatorId,formId).get("isactive"))!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reactivate the form before publishing a version.");
        FormVersion version=requireVersion(operatorId,formId,versionId);
        if (!"DRAFT".equals(version.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"This form version is already published.");
        validateConfiguration(operatorId,formArea(operatorId,formId),version.configuration(),formPurpose(operatorId,formId),true);
        if(formPurpose(operatorId,formId)==FormPurpose.DEFECT_REPORT) {
            String areaCode=jdbc.queryForObject("SELECT code FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",String.class,operatorId,formArea(operatorId,formId));
            if(areaCode.equals("GALLEY")&&DefectReportBindingRules.isGalleyCompact(version.configuration().fields()))DefectReportBindingRules.requireGalleyReport(version.configuration().fields());
            else DefectReportBindingRules.requireComplete(version.configuration().fields());
        }
        if (version.configuration().fields().isEmpty()) throw new IllegalArgumentException("Add at least one field before publishing.");
        int published=jdbc.update("UPDATE operational_form_versions_dtl SET status='PUBLISHED',published_by=?,published_at=now() WHERE operator_id=? AND form_id=? AND id=? AND status='DRAFT'",actor,operatorId,formId,versionId);
        int linked=jdbc.update("UPDATE operational_forms_dtl SET published_version_id=?,updated_at=now() WHERE operator_id=? AND id=? AND isactive=1",versionId,operatorId,formId);
        if(published!=1||linked!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"The form changed while publishing. Reload and try again.");
        audit(operatorId,actor,null,"FORM_VERSION_PUBLISHED","FORM",formId,"Form version published.",null,Map.of("versionId",versionId,"versionNumber",version.versionNumber()));
        return requireVersion(operatorId,formId,versionId);
    }

    @Transactional
    public void setActive(UUID operatorId,String actor,UUID formId,boolean active,String reason) {
        auditActor(actor);
        Map<String,Object> before=requireForm(operatorId,formId);
        int target=active?1:2;
        if(number(before.get("isactive"))==target) return;
        jdbc.update("UPDATE operational_forms_dtl SET isactive=?,updated_at=now() WHERE operator_id=? AND id=?",target,operatorId,formId);
        audit(operatorId,actor,null,active?"FORM_REACTIVATED":"FORM_DEACTIVATED","FORM",formId,reason,Map.of("isactive",before.get("isactive")),Map.of("isactive",target));
    }

    public List<FormVersion> versions(UUID operatorId,UUID formId) {
        requireForm(operatorId,formId);
        return jdbc.query("SELECT * FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? ORDER BY version_number DESC",(rs,n)->mapVersion(rs.getObject("id",UUID.class),formId,rs.getInt("version_number"),rs.getString("status"),rs.getString("fields_json"),rs.getString("created_by"),rs.getTimestamp("created_at").toInstant(),rs.getString("published_by"),rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant()),operatorId,formId);
    }

    public FormVersion version(UUID operatorId,UUID formId,UUID versionId) { return requireVersion(operatorId,formId,versionId); }

    public List<FormForArea> publishedForArea(UUID operatorId,UUID areaId) {
        return publishedForArea(operatorId,areaId,FormPurpose.DEFECT_REPORT,WorkflowStage.INITIAL_REPORT);
    }

    public List<FormForArea> publishedForArea(UUID operatorId,UUID areaId,FormPurpose purpose,WorkflowStage stage) {
        requireActiveArea(operatorId,areaId);
        FormResolutionRules.requireMapping(purpose,stage);
        List<FormForArea> published=jdbc.query("SELECT f.id,f.form_key,f.name,f.area_id,f.purpose,f.workflow_stage,v.id AS version_id,v.version_number,v.fields_json::text FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id AND v.status='PUBLISHED' WHERE f.operator_id=? AND f.area_id=? AND f.purpose=? AND f.workflow_stage=? AND f.isactive=1 ORDER BY f.name",(rs,n)->formForArea(rs),operatorId,areaId,purpose.name(),stage.name());
        if(purpose!=FormPurpose.DEFECT_REPORT)return published;
        String areaCode=jdbc.queryForObject("SELECT code FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",String.class,operatorId,areaId);
        List<FormForArea> eligible=new ArrayList<>();List<String> configurationIssues=new ArrayList<>();
        for(FormForArea form:published){
            try{if("GALLEY".equals(areaCode)&&DefectReportBindingRules.isGalleyCompact(form.configuration().fields()))DefectReportBindingRules.requireGalleyReport(form.configuration().fields());else DefectReportBindingRules.requireComplete(form.configuration().fields());eligible.add(form);}
            catch(IllegalArgumentException invalid){configurationIssues.add(form.name()+": "+invalid.getMessage());}
        }
        if(!eligible.isEmpty())return List.copyOf(eligible);
        if(!configurationIssues.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"No published defect-report form for this area has a valid configuration. Create or edit a draft version in Form Master, fix the configuration issue below, then publish a new version. "+configurationIssues.get(0));
        return List.of();
    }

    /** Search active aircraft for an authorized reporting user; identity is supplied by the controller's validated principal. */
    public AircraftOptionPage reportingAircraft(UUID operatorId,String search,int page,int size) {
        int safePage=Math.max(0,Math.min(page,100_000));
        int safeSize=Math.max(1,Math.min(size,100));
        String term=search==null?"":search.trim().toLowerCase(java.util.Locale.ROOT);
        if(term.length()>80)throw new IllegalArgumentException("Aircraft search must be 80 characters or fewer.");
        String like="%"+term+"%";
        Long total=jdbc.queryForObject("SELECT count(*) FROM aircraft_mst WHERE operator_id=? AND isactive=1 AND (?='' OR lower(tail_number) LIKE ? OR lower(msn_number) LIKE ?)",Long.class,operatorId,term,like,like);
        List<AircraftOption> items=jdbc.query("SELECT id,tail_number,msn_number,aircraft_type FROM aircraft_mst WHERE operator_id=? AND isactive=1 AND (?='' OR lower(tail_number) LIKE ? OR lower(msn_number) LIKE ?) ORDER BY tail_number,id LIMIT ? OFFSET ?",(rs,n)->new AircraftOption(rs.getObject("id",UUID.class),rs.getString("tail_number"),rs.getString("msn_number"),rs.getString("aircraft_type")),operatorId,term,like,like,safeSize,(long)safePage*safeSize);
        long count=total==null?0:total;
        return new AircraftOptionPage(items,safePage,safeSize,count,(int)Math.ceil((double)count/safeSize));
    }

    /** Resolve mapped report answers from the exact published definition; client-supplied defect properties are ignored. */
    public Map<String,Object> resolveDefectReport(UUID operatorId,UUID areaId,UUID formId,UUID versionId,Map<String,Object> answers) {
        FormForArea form=published(operatorId,formId,versionId);
        if(!form.areaId().equals(areaId)||form.purpose()!=FormPurpose.DEFECT_REPORT||form.workflowStage()!=WorkflowStage.INITIAL_REPORT)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected version is not an initial defect-report form for this area.");
        Map<String,Object> submitted=answers==null?Map.of():answers;
        for(FieldDefinition field:form.configuration().fields())if(field.binding()==DefectFieldBinding.AIRCRAFT_ID&&submitted.containsKey(field.key()))throw new IllegalArgumentException("Aircraft registration is selected separately and cannot be submitted as a form answer.");
        for(FieldDefinition field:form.configuration().fields())if(Boolean.TRUE.equals(field.readOnly())&&!blank(submitted.get(field.key())))throw new IllegalArgumentException("Read-only field "+field.label()+" cannot receive a submitted value.");
        Map<String,Object> result=new HashMap<>();
        String areaCode=jdbc.queryForObject("SELECT code FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",String.class,operatorId,areaId);
        Map<DefectFieldBinding,FieldDefinition> bindings=areaCode.equals("GALLEY")&&DefectReportBindingRules.isGalleyCompact(form.configuration().fields())?DefectReportBindingRules.requireGalleyReport(form.configuration().fields()):DefectReportBindingRules.requireComplete(form.configuration().fields());
        result.put("location",switch(areaCode){case "CABIN","ENTRY_AREA"->"CABIN";case "GALLEY"->"GALLEY";case "LAVATORY"->"LAVATORY";case "ATTENDANT_SEAT"->"ATTENDANT_SEAT";default->throw new IllegalArgumentException("This cabin area has no supported defect-location mapping. Ask an administrator to review the area configuration.");});
        UUID componentId=UUID.fromString(answer(bindings.get(DefectFieldBinding.COMPONENT_ID),submitted));
        result.put("componentId",componentId);
        UUID conditionId=UUID.fromString(answer(bindings.get(DefectFieldBinding.CONDITION_ID),submitted));
        result.put("conditionId",conditionId);
        result.put("zone",textAnswer(bindings.get(DefectFieldBinding.ZONE),submitted));
        if(areaCode.equals("GALLEY")) {
            String category=jdbc.query("SELECT code FROM defect_categories_mst WHERE operator_id=? AND code='GALLEY' AND isactive=1",rs->{if(!rs.next())throw new IllegalArgumentException("Activate the Galley defect category before publishing this report form.");return rs.getString(1);},operatorId);
            String componentName=jdbc.query("SELECT name FROM cabin_components_mst WHERE operator_id=? AND id=? AND area_id=? AND isactive=1",rs->{if(!rs.next())throw new IllegalArgumentException("Choose an active Galley component.");return rs.getString(1);},operatorId,componentId,areaId);
            String conditionName=jdbc.query("SELECT c.name FROM component_conditions_mst m JOIN defect_conditions_mst c ON c.operator_id=m.operator_id AND c.id=m.condition_id WHERE m.operator_id=? AND m.component_id=? AND m.condition_id=? AND m.isactive=1 AND c.isactive=1",rs->{if(!rs.next())throw new IllegalArgumentException("Choose an active condition mapped to this Galley component.");return rs.getString(1);},operatorId,componentId,conditionId);
            String zone=(String)result.get("zone");String title=("Galley — "+componentName+" — "+zone).trim();
            if(title.length()>160)title=title.substring(0,160);
            FieldDefinition remarksField=bindings.get(DefectFieldBinding.REMARKS);
            String remarks=remarksField!=null&&!blank(submitted.get(remarksField.key()))?textAnswer(remarksField,submitted):"";
            String description="Condition: "+conditionName+(remarks.isBlank()?"":"\nRemarks: "+remarks);
            if(description.length()>4000)throw new IllegalArgumentException("Condition and remarks must fit within 4000 characters.");
            result.put("category",category);result.put("title",title);result.put("description",description);
        } else {
            result.put("category",referenceCode(operatorId,areaId,bindings.get(DefectFieldBinding.DEFECT_CATEGORY),submitted,null));
            result.put("title",textAnswer(bindings.get(DefectFieldBinding.TITLE),submitted));
            result.put("description",textAnswer(bindings.get(DefectFieldBinding.DESCRIPTION),submitted));
        }
        result.put("severity",referenceCode(operatorId,areaId,bindings.get(DefectFieldBinding.SEVERITY),submitted,null));
        FieldDefinition row=bindings.get(DefectFieldBinding.ROW_NUMBER);
        if(row!=null&&!blank(submitted.get(row.key()))){validateNumber(row,submitted.get(row.key()));try{result.put("rowNumber",new BigDecimal(submitted.get(row.key()).toString()).intValueExact());}catch(ArithmeticException|NumberFormatException ex){throw new IllegalArgumentException(row.label()+" must be a whole number.");}}
        FieldDefinition seat=bindings.get(DefectFieldBinding.SEAT_REFERENCE);
        if(seat!=null&&!blank(submitted.get(seat.key())))result.put("seatReference",textAnswer(seat,submitted));
        // Validate reference IDs against the same tenant-scoped option sources used by the renderer.
        referenceCode(operatorId,areaId,bindings.get(DefectFieldBinding.COMPONENT_ID),submitted,null);
        referenceCode(operatorId,areaId,bindings.get(DefectFieldBinding.CONDITION_ID),submitted,componentId);
        return result;
    }

    private String referenceCode(UUID operatorId,UUID areaId,FieldDefinition field,Map<String,Object> answers,UUID componentId) {
        String value=answer(field,answers); UUID id;
        try{id=UUID.fromString(value);}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Choose an authorized option for "+field.label()+".");}
        return optionsFor(operatorId,areaId,field,areaId,componentId).stream().filter(option->option.id().equals(id)).map(FormOption::code).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Choose an active option belonging to this organization for "+field.label()+"."));
    }
    private String textAnswer(FieldDefinition field,Map<String,Object> answers) {
        String value=answer(field,answers); validateText(field,value); return value.trim();
    }
    private String answer(FieldDefinition field,Map<String,Object> answers) {
        Object value=answers.get(field.key()); if(blank(value))throw new IllegalArgumentException("A value is required for "+field.label()+"."); return value.toString();
    }
    private boolean blank(Object value){return value==null||(value instanceof String text&&text.isBlank());}

    public FormForArea published(UUID operatorId,UUID formId,UUID versionId) {
        return jdbc.query("SELECT f.id,f.form_key,f.name,f.area_id,f.purpose,f.workflow_stage,v.id AS version_id,v.version_number,v.fields_json::text FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id WHERE f.operator_id=? AND f.id=? AND f.isactive=1 AND v.id=? AND v.status='PUBLISHED'",(rs,n)->formForArea(rs),operatorId,formId,versionId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Published form was not found for this organization."));
    }

    public List<FormForArea> publishedForDefect(UUID operatorId,UUID defectId,FormPurpose purpose) {
        Map<String,Object> defect=jdbc.query("SELECT area_id,status FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1",new ColumnMapRowMapper(),operatorId,defectId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found for this organization."));
        String status=String.valueOf(defect.get("status"));
        WorkflowStage stage=FormResolutionRules.stageForDefect(purpose,status);
        return publishedForArea(operatorId,(UUID)defect.get("area_id"),purpose,stage);
    }

    public FormForArea requireOperationalForm(UUID operatorId,UUID defectId,UUID formId,UUID versionId) {
        FormForArea form=published(operatorId,formId,versionId);
        return publishedForDefect(operatorId,defectId,form.purpose()).stream()
            .filter(candidate->candidate.id().equals(formId)&&candidate.versionId().equals(versionId))
            .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"This form is not eligible for the defect's current workflow stage."));
    }

    public List<FormOption> options(UUID operatorId,UUID formId,UUID versionId,String fieldKey,UUID areaId,UUID componentId) {
        FormForArea form=published(operatorId,formId,versionId);
        if(areaId!=null&&!areaId.equals(form.areaId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Area was not found for this form.");
        FieldDefinition field=findField(form.configuration(),fieldKey);
        if(field==null || field.optionSource()==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Dropdown field was not found.");
        return optionsFor(operatorId,form.areaId(),field,areaId,componentId);
    }

    @Transactional
    public FormSubmission submit(UUID operatorId,String actor,UUID defectId,SubmitFormRequest request) {
        auditActor(actor);
        FormForArea form=published(operatorId,request.formId(),request.versionId());
        Map<String,Object> defect=jdbc.query("SELECT area_id,status FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1",new ColumnMapRowMapper(),operatorId,defectId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect was not found for this organization."));
        if(!form.areaId().equals(defect.get("area_id"))) throw new IllegalArgumentException("The selected form is not assigned to the defect's area.");
        FormPurpose purpose=request.purpose()==null?form.purpose():request.purpose();
        if(purpose!=form.purpose())throw new IllegalArgumentException("The selected form is not configured for the requested purpose.");
        String status=String.valueOf(defect.get("status"));
        WorkflowStage current=FormResolutionRules.stageForDefect(purpose,status);
        if(form.workflowStage()!=current)throw new ResponseStatusException(HttpStatus.CONFLICT,"The published form is not configured for the defect's current workflow stage.");
        Map<String,Object> answers=request.answers()==null?Map.of():request.answers();
        validateAnswers(operatorId,defectId,form,answers);
        FormDataSnapshot snapshot=purpose==FormPurpose.DEFECT_REPORT?reportSnapshot(operatorId,defectId,form,answers):null;
        UUID submissionId=UUID.randomUUID();
        jdbc.update("INSERT INTO operational_form_submissions_dtl(id,operator_id,defect_id,form_id,form_version_id,purpose,workflow_stage,submitted_by,snapshot_defect_category,snapshot_component_id,snapshot_condition_id,snapshot_zone,snapshot_row_number,snapshot_seat_reference,snapshot_title,snapshot_description,snapshot_severity_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            submissionId,operatorId,defectId,form.id(),form.versionId(),purpose.name(),form.workflowStage().name(),actor,
            snapshot==null?null:snapshot.defectCategory(),snapshot==null?null:snapshot.componentId(),snapshot==null?null:snapshot.conditionId(),snapshot==null?null:snapshot.zone(),snapshot==null?null:snapshot.rowNumber(),snapshot==null?null:snapshot.seatReference(),snapshot==null?null:snapshot.title(),snapshot==null?null:snapshot.description(),snapshot==null?null:snapshot.severityId());
        for(FieldDefinition field:form.configuration().fields()) if(field.type()==FieldType.FILE_UPLOAD||field.type()==FieldType.IMAGE_UPLOAD) {
            Object value=answers.get(field.key()); if(value instanceof List<?> ids) for(Object raw:ids) {
                UUID attachmentId=parseUuid(raw,field.key());
                int updated=jdbc.update("UPDATE attachments_dtl SET form_submission_id=? WHERE operator_id=? AND id=? AND defect_id=? AND form_id=? AND form_version_id=? AND field_key=? AND form_submission_id IS NULL AND scan_status='CLEAN' AND isactive=1",submissionId,operatorId,attachmentId,defectId,form.id(),form.versionId(),field.key());
                if(updated!=1) throw new IllegalArgumentException("Evidence must be uploaded, validated and clean before form submission.");
            }
        }
        audit(operatorId,actor,defectId,"FORM_SUBMITTED","FORM_SUBMISSION",submissionId,"Operational form submitted.",null,Map.of("formId",form.id(),"versionId",form.versionId()));
        return submission(operatorId,submissionId);
    }

    public List<FormSubmission> submissions(UUID operatorId,UUID defectId) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1)",Boolean.class,operatorId,defectId))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");
        return jdbc.query("SELECT s.id,s.defect_id,s.form_id,s.form_version_id,s.purpose,s.workflow_stage,s.submitted_by,s.snapshot_defect_category,s.snapshot_component_id,s.snapshot_condition_id,s.snapshot_zone,s.snapshot_row_number,s.snapshot_seat_reference,s.snapshot_title,s.snapshot_description,s.snapshot_severity_id,s.submitted_at,v.version_number FROM operational_form_submissions_dtl s JOIN operational_form_versions_dtl v ON v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id WHERE s.operator_id=? AND s.defect_id=? ORDER BY s.submitted_at DESC",this::mapSubmission,operatorId,defectId);
    }

    public List<FormSubmission> submissions(UUID operatorId,UUID defectId,String externalUserId,boolean teamScoped,boolean reporterScoped) {
        String teamVisibility = "EXISTS (SELECT 1 FROM corrective_actions_dtl ca JOIN operator_membership_teams_mst omt ON omt.operator_id=ca.operator_id AND omt.team_name=ca.assigned_team AND omt.external_user_id=? AND omt.isactive=1 JOIN operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1 JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1 WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.assigned_team=d.assigned_team AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS') AND d.status IN ('ACTION_ASSIGNED','IN_PROGRESS'))";
        Boolean visible=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defects_dtl d WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND (?=false OR d.reported_by=?) AND (?=false OR "+teamVisibility+"))",Boolean.class,operatorId,defectId,reporterScoped,externalUserId,teamScoped,externalUserId);
        if(!Boolean.TRUE.equals(visible)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");
        return submissions(operatorId,defectId);
    }

    public FormSubmission submission(UUID operatorId,UUID id) {
        return jdbc.query("SELECT s.id,s.defect_id,s.form_id,s.form_version_id,s.purpose,s.workflow_stage,s.submitted_by,s.snapshot_defect_category,s.snapshot_component_id,s.snapshot_condition_id,s.snapshot_zone,s.snapshot_row_number,s.snapshot_seat_reference,s.snapshot_title,s.snapshot_description,s.snapshot_severity_id,s.submitted_at,v.version_number FROM operational_form_submissions_dtl s JOIN operational_form_versions_dtl v ON v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id WHERE s.operator_id=? AND s.id=?",this::mapSubmission,operatorId,id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Form submission not found."));
    }

    private FormSubmission mapSubmission(java.sql.ResultSet rs,int row)throws java.sql.SQLException{
        int rowNumber=rs.getInt("snapshot_row_number");Integer nullableRow=rs.wasNull()?null:rowNumber;
        FormDataSnapshot snapshot=rs.getString("purpose").equals("DEFECT_REPORT")?new FormDataSnapshot(rs.getString("snapshot_defect_category"),rs.getObject("snapshot_component_id",UUID.class),rs.getObject("snapshot_condition_id",UUID.class),rs.getString("snapshot_zone"),nullableRow,rs.getString("snapshot_seat_reference"),rs.getString("snapshot_title"),rs.getString("snapshot_description"),rs.getObject("snapshot_severity_id",UUID.class)):null;
        return new FormSubmission(rs.getObject("id",UUID.class),rs.getObject("defect_id",UUID.class),rs.getObject("form_id",UUID.class),rs.getObject("form_version_id",UUID.class),rs.getInt("version_number"),FormPurpose.valueOf(rs.getString("purpose")),WorkflowStage.valueOf(rs.getString("workflow_stage")),rs.getString("submitted_by"),snapshot,rs.getTimestamp("submitted_at").toInstant());
    }

    private FormDataSnapshot reportSnapshot(UUID operatorId,UUID defectId,FormForArea form,Map<String,Object> answers){
        Map<String,Object> mapped=resolveDefectReport(operatorId,form.areaId(),form.id(),form.versionId(),answers);
        return jdbc.query("SELECT d.category,d.component_id,d.condition_id,d.zone,d.row_number,d.seat_reference,d.title,d.description,d.severity_id,s.severity_code FROM defects_dtl d JOIN defect_severities_mst s ON s.operator_id=d.operator_id AND s.id=d.severity_id AND s.isactive=1 WHERE d.operator_id=? AND d.id=? AND d.area_id=? AND d.isactive=1",
            rs->{if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect was not found for this organization and cabin area.");
                String category=rs.getString("category");UUID component=rs.getObject("component_id",UUID.class);UUID condition=rs.getObject("condition_id",UUID.class);String zone=rs.getString("zone");int rowNumber=rs.getInt("row_number");Integer row=rs.wasNull()?null:rowNumber;String seat=rs.getString("seat_reference");String title=rs.getString("title");String description=rs.getString("description");UUID severity=rs.getObject("severity_id",UUID.class);
                boolean matches=java.util.Objects.equals(category,mapped.get("category"))&&java.util.Objects.equals(component,mapped.get("componentId"))&&java.util.Objects.equals(condition,mapped.get("conditionId"))&&java.util.Objects.equals(zone,mapped.get("zone"))&&java.util.Objects.equals(row,mapped.get("rowNumber"))&&java.util.Objects.equals(seat,mapped.get("seatReference"))&&java.util.Objects.equals(title,mapped.get("title"))&&java.util.Objects.equals(description,mapped.get("description"));
                String severityCode=rs.getString("severity_code");
                if(!matches||!java.util.Objects.equals(severityCode,mapped.get("severity")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Submitted fields do not match the defect created from this published form. Reload the defect and try again.");
                return new FormDataSnapshot(category,component,condition,zone,row,seat,title,description,severity);
            },operatorId,defectId,form.areaId());
    }

    public FieldDefinition requireUploadField(UUID operatorId,UUID formId,UUID versionId,String fieldKey,UUID defectId) {
        FormForArea form=published(operatorId,formId,versionId);
        UUID area=jdbc.query("SELECT area_id FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1",rs->rs.next()?rs.getObject(1,UUID.class):null,operatorId,defectId);
        if(area==null || !area.equals(form.areaId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect or form was not found for this organization.");
        FieldDefinition field=findField(form.configuration(),fieldKey);
        if(field==null || (field.type()!=FieldType.FILE_UPLOAD && field.type()!=FieldType.IMAGE_UPLOAD)) throw new IllegalArgumentException("The selected form field does not accept evidence uploads.");
        return field;
    }

    public FormConfiguration validateConfiguration(UUID operatorId,UUID formArea,FormConfiguration config) {
        return validateConfiguration(operatorId,formArea,config,FormPurpose.DEFECT_REPORT,false);
    }

    private FormConfiguration validateConfiguration(UUID operatorId,UUID formArea,FormConfiguration config,FormPurpose purpose,boolean publishing) {
        if(config==null || config.fields()==null || config.fields().size()>100) throw new IllegalArgumentException("A form must contain no more than 100 fields.");
        Set<String> keys=new HashSet<>();
        Map<DefectFieldBinding,FieldDefinition> bindings=new java.util.EnumMap<>(DefectFieldBinding.class);
        for(FieldDefinition f:config.fields()) {
            if(f==null || f.key()==null || !SAFE_KEY.matcher(f.key()).matches()) throw new IllegalArgumentException("Field keys must start with a lowercase letter and contain only letters, numbers or underscores.");
            if(!keys.add(f.key())) throw new IllegalArgumentException("Field keys must be unique within a form.");
            if(f.label()==null || f.label().isBlank() || f.order()<0 || f.order()>1000) throw new IllegalArgumentException("Each field requires a label and a valid order.");
            validateFieldShape(f);
            if(Boolean.FALSE.equals(f.visible())&&f.required())throw new IllegalArgumentException("A hidden field cannot be required.");
            if(f.binding()!=null) {
                FieldBindingDefinition approved=bindingCatalog.require(f.binding());
                if(f.binding()==DefectFieldBinding.AIRCRAFT_ID&&(!"aircraftId".equals(f.key())||f.type()!=FieldType.AIRCRAFT_SELECT))throw new IllegalArgumentException("The aircraft reference must use the stable aircraftId key and Aircraft Registration selector type.");
                if(!approved.formPurposes().contains(purpose))throw new IllegalArgumentException("The selected backend binding is not supported for this form purpose.");
                if(!approved.supportedFieldTypes().contains(f.type()))throw new IllegalArgumentException("Field “"+f.label()+"” uses an unsupported type for this backend binding.");
                String areaCode=jdbc.queryForObject("SELECT code FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",String.class,operatorId,formArea);
                boolean galleySeverity=f.binding()==DefectFieldBinding.SEVERITY&&areaCode.equals("GALLEY")&&f.optionSource()==OptionSource.GALLEY_SEVERITIES;
                if(approved.referenceDataSource()!=f.optionSource()&&!galleySeverity)throw new IllegalArgumentException("Field “"+f.label()+"” must use its approved reference-data source.");
                if(!approved.multipleAllowed()&&bindings.putIfAbsent(f.binding(),f)!=null)throw new IllegalArgumentException("Only one field may use the "+approved.name()+" binding in a form.");
                if(approved.requiredPolicy().equals("REQUIRED")&&!f.required())throw new IllegalArgumentException(approved.name()+" is required by the defect model.");
                if(!approved.cabinAreaCodes().contains(areaCode))throw new IllegalArgumentException(approved.name()+" is not applicable to the selected cabin area.");
            }
        }
        for(FieldDefinition f:config.fields()) {
            if(f.optionFilter()!=null) {
                if(f.optionFilter().areaFieldKey()!=null && (!keys.contains(f.optionFilter().areaFieldKey()) || f.key().equals(f.optionFilter().areaFieldKey()))) throw new IllegalArgumentException("The area filter must reference another configured field.");
                if(f.optionFilter().componentFieldKey()!=null && (!keys.contains(f.optionFilter().componentFieldKey()) || f.key().equals(f.optionFilter().componentFieldKey()))) throw new IllegalArgumentException("The component filter must reference another configured field.");
                if(f.optionFilter().areaFieldKey()!=null && config.fields().stream().noneMatch(parent->parent.key().equals(f.optionFilter().areaFieldKey())&&parent.optionSource()==OptionSource.CABIN_AREAS)) throw new IllegalArgumentException("Area filters must reference a cabin-area dropdown.");
                if(f.optionFilter().componentFieldKey()!=null && config.fields().stream().noneMatch(parent->parent.key().equals(f.optionFilter().componentFieldKey())&&parent.optionSource()==OptionSource.COMPONENTS)) throw new IllegalArgumentException("Component filters must reference a component dropdown.");
            }
            if(f.optionSource()==OptionSource.DEFECT_CONDITIONS && (f.optionFilter()==null || f.optionFilter().componentFieldKey()==null)) throw new IllegalArgumentException("Defect condition dropdowns must be filtered by a component field.");
        }
        if(publishing) {
            for(FieldDefinition field:config.fields()) {
                if(field.type()==FieldType.SECTION)continue;
                if(field.binding()==null)throw new IllegalArgumentException(FormFieldBindingCatalog.UNSUPPORTED_FIELD_MESSAGE+" (“"+field.label()+"”)");
                if((field.type()==FieldType.FILE_UPLOAD||field.type()==FieldType.IMAGE_UPLOAD)&&field.binding()!=DefectFieldBinding.EVIDENCE_ATTACHMENTS)
                    throw new IllegalArgumentException("Upload fields must use the approved Evidence attachments binding.");
            }
            if(purpose==FormPurpose.DEFECT_REPORT){String code=jdbc.queryForObject("SELECT code FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",String.class,operatorId,formArea);if(code.equals("GALLEY")&&DefectReportBindingRules.isGalleyCompact(config.fields()))DefectReportBindingRules.requireGalleyReport(config.fields());else DefectReportBindingRules.requireComplete(config.fields());}
        }
        return config;
    }

    public List<FormOption> optionsFor(UUID operatorId,UUID formArea,FieldDefinition field,UUID areaId,UUID componentId) {
        if(field.optionSource()==null) throw new IllegalArgumentException("This field has no authorized dropdown source.");
        UUID selectedArea=areaId==null?formArea:areaId;
        requireActiveArea(operatorId,selectedArea);
        return switch(field.optionSource()) {
            case DEFECT_CATEGORIES -> jdbc.query("SELECT id,code,name FROM defect_categories_mst WHERE operator_id=? AND isactive=1 ORDER BY name LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId);
            case DEFECT_SEVERITIES -> jdbc.query("SELECT id,severity_code AS code,severity_name AS name FROM defect_severities_mst WHERE operator_id=? AND isactive=1 ORDER BY sort_order,severity_code LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId);
            case GALLEY_SEVERITIES -> {String code=jdbc.queryForObject("SELECT code FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",String.class,operatorId,formArea);if(!code.equals("GALLEY"))throw new IllegalArgumentException("Galley severity options are only available for Galley forms.");yield jdbc.query("SELECT id,severity_code AS code,severity_name AS name FROM defect_severities_mst WHERE operator_id=? AND isactive=1 AND severity_code IN ('CRITICAL','MAJOR','MINOR','OBSERVATION') ORDER BY CASE severity_code WHEN 'CRITICAL' THEN 1 WHEN 'MAJOR' THEN 2 WHEN 'MINOR' THEN 3 ELSE 4 END",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId);}
            case CABIN_AREAS -> jdbc.query("SELECT id,code,name FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId,formArea);
            // Compatibility for already-published immutable definitions: old CABIN_ZONES
            // option sources resolve against cabin areas and never query the retired master.
            case CABIN_ZONES -> jdbc.query("SELECT id,code,name FROM cabin_areas_mst WHERE operator_id=? AND isactive=1 ORDER BY name LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId);
            case COMPONENTS -> jdbc.query("SELECT id,code,name FROM cabin_components_mst WHERE operator_id=? AND area_id=? AND isactive=1 ORDER BY name LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId,selectedArea);
            case DEFECT_CONDITIONS -> {
                if(componentId==null) yield List.of();
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cabin_components_mst WHERE operator_id=? AND id=? AND area_id=? AND isactive=1)",Boolean.class,operatorId,componentId,selectedArea))) throw new IllegalArgumentException("Choose an active component in the selected area.");
                yield jdbc.query("SELECT c.id,c.code,c.name FROM component_conditions_mst m JOIN defect_conditions_mst c ON c.operator_id=m.operator_id AND c.id=m.condition_id WHERE m.operator_id=? AND m.component_id=? AND m.isactive=1 AND c.isactive=1 ORDER BY c.name LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId,componentId);
            }
            case AIRCRAFT -> jdbc.query("SELECT id,tail_number AS code,tail_number AS name FROM aircraft_mst WHERE operator_id=? AND isactive=1 ORDER BY tail_number LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId);
            case FLEETS -> jdbc.query("SELECT id,name AS code,name FROM fleets_mst WHERE operator_id=? AND isactive=1 ORDER BY name LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId);
            case MAINTENANCE_TEAMS -> jdbc.query("SELECT id,name AS code,name FROM maintenance_teams_mst WHERE operator_id=? AND isactive=1 ORDER BY name LIMIT 500",(rs,n)->new FormOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name")),operatorId);
        };
    }

    public UUID formArea(UUID operatorId,UUID formId) { return (UUID)requireForm(operatorId,formId).get("area_id"); }
    private FormPurpose formPurpose(UUID operatorId,UUID formId) { return FormPurpose.valueOf(String.valueOf(requireForm(operatorId,formId).get("purpose"))); }
    public Map<String,Object> requireForm(UUID operatorId,UUID id) { return jdbc.query("SELECT * FROM operational_forms_dtl WHERE operator_id=? AND id=?",new ColumnMapRowMapper(),operatorId,id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Form was not found for this organization.")); }

    private void validateAnswers(UUID operatorId,UUID defectId,FormForArea form,Map<String,Object> answers) {
        Map<String,FieldDefinition> fields=new LinkedHashMap<>(); for(FieldDefinition f:form.configuration().fields()) if(f.type()!=FieldType.SECTION) fields.put(f.key(),f);
        for(String key:answers.keySet()) if(!fields.containsKey(key)) throw new IllegalArgumentException("Unknown form field: "+key);
        for(FieldDefinition f:fields.values()) {
            if(Boolean.FALSE.equals(f.visible())) {if(answers.containsKey(f.key()))throw new IllegalArgumentException("A hidden field cannot receive a submitted value.");continue;}
            if(Boolean.TRUE.equals(f.readOnly())&&answers.containsKey(f.key()))throw new IllegalArgumentException("A read-only field cannot receive a submitted value.");
            if(f.binding()==null&&f.type()!=FieldType.FILE_UPLOAD&&f.type()!=FieldType.IMAGE_UPLOAD)throw new IllegalArgumentException(FormFieldBindingCatalog.UNSUPPORTED_FIELD_MESSAGE+" (“"+f.label()+"”)");
            if(f.binding()!=null){FieldBindingDefinition approved=bindingCatalog.require(f.binding());String areaCode=jdbc.queryForObject("SELECT code FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",String.class,operatorId,form.areaId());boolean galleySeverity=f.binding()==DefectFieldBinding.SEVERITY&&areaCode.equals("GALLEY")&&f.optionSource()==OptionSource.GALLEY_SEVERITIES;if(!approved.formPurposes().contains(form.purpose())||!approved.supportedFieldTypes().contains(f.type())||approved.referenceDataSource()!=f.optionSource()&&!galleySeverity)throw new IllegalArgumentException("The published field has an unsupported or mismatched backend binding.");}
            if(f.type()==FieldType.AIRCRAFT_SELECT){if(answers.containsKey(f.key()))throw new IllegalArgumentException("Aircraft registration is selected separately and cannot be submitted as a form answer.");if(jdbc.queryForObject("SELECT count(*) FROM defects_dtl WHERE operator_id=? AND id=? AND aircraft_id IS NOT NULL AND isactive=1",Integer.class,operatorId,defectId)!=1)throw new IllegalArgumentException("Select an active aircraft before submitting this report.");continue;}
            Object value=answers.get(f.key());
            if(value==null || (value instanceof String s && s.isBlank())) {
                if(f.required()) throw new IllegalArgumentException("A value is required for "+f.label()+".");
                if(f.type()==FieldType.FILE_UPLOAD || f.type()==FieldType.IMAGE_UPLOAD) validateAttachmentCount(f,List.of());
                continue;
            }
            switch(f.type()) {
                case TEXT, TEXTAREA -> validateText(f,value);
                case NUMBER -> validateNumber(f,value);
                case DATE -> validateDate(f,value);
                case CHECKBOX -> { if(!(value instanceof Boolean)) throw new IllegalArgumentException(f.label()+" must be true or false."); if(f.required()&&!Boolean.TRUE.equals(value))throw new IllegalArgumentException(f.label()+" must be checked."); }
                case DROPDOWN -> validateOption(operatorId,form.areaId(),f,value,answers);
                case MULTI_SELECT -> { if(!(value instanceof List<?> list)) throw new IllegalArgumentException(f.label()+" must be a list of selections."); Set<Object> unique=new HashSet<>(); for(Object option:list){if(!unique.add(option))throw new IllegalArgumentException("Duplicate options are not allowed for "+f.label()+".");validateOption(operatorId,form.areaId(),f,option,answers);} if(f.required()&&list.isEmpty())throw new IllegalArgumentException("Choose at least one option for "+f.label()+"."); }
                case FILE_UPLOAD, IMAGE_UPLOAD -> validateAttachmentCount(f,value);
                case AIRCRAFT_SELECT -> {
                    if(answers.containsKey(f.key()))throw new IllegalArgumentException("Aircraft registration is selected separately and cannot be submitted as a form answer.");
                    if(jdbc.queryForObject("SELECT count(*) FROM defects_dtl WHERE operator_id=? AND id=? AND aircraft_id IS NOT NULL AND isactive=1",Integer.class,operatorId,defectId)!=1)throw new IllegalArgumentException("Select an active aircraft before submitting this report.");
                }
                case SECTION -> {}
            }
            if(f.type()==FieldType.FILE_UPLOAD||f.type()==FieldType.IMAGE_UPLOAD) validateAttachments(operatorId,defectId,form,f,value);
        }
    }

    private void validateText(FieldDefinition f,Object value) {
        if(!(value instanceof String s)) throw new IllegalArgumentException(f.label()+" must be text.");
        TextRules r=f.textRules(); int length=s.codePointCount(0,s.length());
        if(r!=null){if(r.minLength()!=null&&length<r.minLength())throw new IllegalArgumentException(f.label()+" is too short.");if(r.maxLength()!=null&&length>r.maxLength())throw new IllegalArgumentException(f.label()+" is too long.");if(Boolean.TRUE.equals(r.alphaOnly())&&!s.matches("[\\p{L} ]+"))throw new IllegalArgumentException(f.label()+" must contain letters and spaces only.");if(Boolean.TRUE.equals(r.alphanumeric())&&!s.matches("[\\p{L}\\p{N}]+"))throw new IllegalArgumentException(f.label()+" must contain letters and numbers only.");if(r.pattern()!=null&&!Pattern.compile(r.pattern()).matcher(s).matches())throw new IllegalArgumentException(f.label()+" does not match the configured format.");}
    }
    private void validateNumber(FieldDefinition f,Object value) {
        if(!(value instanceof Number) && !(value instanceof String)) throw new IllegalArgumentException(f.label()+" must be numeric.");
        try { BigDecimal v=new BigDecimal(value.toString()); NumericRules r=f.numericRules(); if(r!=null){if(r.min()!=null&&v.compareTo(r.min())<0)throw new IllegalArgumentException(f.label()+" is below the minimum.");if(r.max()!=null&&v.compareTo(r.max())>0)throw new IllegalArgumentException(f.label()+" exceeds the maximum.");if(r.precision()!=null&&Math.max(0,v.stripTrailingZeros().scale())>r.precision())throw new IllegalArgumentException(f.label()+" has too many decimal places.");} } catch(NumberFormatException ex){throw new IllegalArgumentException(f.label()+" must be numeric.");}
    }
    private void validateDate(FieldDefinition f,Object value) {
        if(!(value instanceof String s)) throw new IllegalArgumentException(f.label()+" must be an ISO date.");
        try { LocalDate date=LocalDate.parse(s); DateRules r=f.dateRules(); if(r!=null&&r.minDate()!=null&&date.isBefore(LocalDate.parse(r.minDate())))throw new IllegalArgumentException(f.label()+" is before the minimum date.");if(r!=null&&r.maxDate()!=null&&date.isAfter(LocalDate.parse(r.maxDate())))throw new IllegalArgumentException(f.label()+" is after the maximum date."); } catch(java.time.format.DateTimeParseException ex){throw new IllegalArgumentException(f.label()+" must be a valid ISO date (YYYY-MM-DD).");}
    }
    private void validateOption(UUID operatorId,UUID formArea,FieldDefinition f,Object value,Map<String,Object> answers) {
        UUID id=parseUuid(value,f.key()); OptionFilter filter=f.optionFilter(); UUID area=formArea,component=null;
        if(filter!=null&&filter.areaFieldKey()!=null&&answers.get(filter.areaFieldKey())!=null) {area=parseUuid(answers.get(filter.areaFieldKey()),filter.areaFieldKey());if(!formArea.equals(area))throw new IllegalArgumentException("The selected cabin area does not match this form.");}
        if(filter!=null&&filter.componentFieldKey()!=null&&answers.get(filter.componentFieldKey())!=null) component=parseUuid(answers.get(filter.componentFieldKey()),filter.componentFieldKey());
        if(optionsFor(operatorId,formArea,f,area,component).stream().noneMatch(option->option.id().equals(id))) throw new IllegalArgumentException("Choose an active tenant-owned option for "+f.label()+".");
    }
    private void validateAttachmentCount(FieldDefinition f,Object value) {
        if(value!=null&&!(value instanceof List<?>)) throw new IllegalArgumentException(f.label()+" must contain attachment IDs.");
        int count=value instanceof List<?> list?list.size():0; FileRules r=f.fileRules(); int min=r==null?0:r.minCount(), max=r==null?0:r.maxCount();
        if(count<min)throw new IllegalArgumentException("Attach at least "+min+" file(s) for "+f.label()+"."); if(count>max)throw new IllegalArgumentException("Attach no more than "+max+" file(s) for "+f.label()+".");
    }
    private void validateAttachments(UUID operatorId,UUID defectId,FormForArea form,FieldDefinition f,Object value) {
        if(!(value instanceof List<?> ids))return;
        for(Object raw:ids){UUID id=parseUuid(raw,f.key()); Integer count=jdbc.queryForObject("SELECT count(*) FROM attachments_dtl WHERE operator_id=? AND id=? AND defect_id=? AND form_id=? AND form_version_id=? AND field_key=? AND size_bytes<=? AND lower(split_part(original_filename,'.',array_length(string_to_array(original_filename,'.'),1)))=ANY(?) AND lower(detected_mime_type)=ANY(?) AND scan_status='CLEAN' AND isactive=1",Integer.class,operatorId,id,defectId,form.id(),form.versionId(),f.key(),f.fileRules().maxFileSizeBytes(),f.fileRules().extensions().stream().map(x->x.toLowerCase(java.util.Locale.ROOT)).toArray(String[]::new),f.fileRules().mimeTypes().stream().map(x->x.toLowerCase(java.util.Locale.ROOT)).toArray(String[]::new)); if(count==null||count!=1)throw new IllegalArgumentException("Each file must satisfy this field's restrictions, belong to this form version, and pass the configured malware scan.");}
    }

    private void validateFieldShape(FieldDefinition f) {
        TextRules t=f.textRules(); NumericRules n=f.numericRules(); DateRules d=f.dateRules(); FileRules files=f.fileRules();
        switch(f.type()) {
            case TEXT,TEXTAREA -> { if(n!=null||d!=null||files!=null||f.optionSource()!=null)throw new IllegalArgumentException("Text fields cannot use numeric, date, option or file settings.");if(t!=null){if(t.minLength()!=null&&(t.minLength()<0||t.minLength()>10000))throw new IllegalArgumentException("Invalid minimum text length.");if(t.maxLength()!=null&&(t.maxLength()<1||t.maxLength()>10000))throw new IllegalArgumentException("Invalid maximum text length.");if(t.minLength()!=null&&t.maxLength()!=null&&t.minLength()>t.maxLength())throw new IllegalArgumentException("Minimum text length cannot exceed maximum.");if(Boolean.TRUE.equals(t.alphaOnly())&&Boolean.TRUE.equals(t.alphanumeric()))throw new IllegalArgumentException("Choose one text validation mode.");validateSafePattern(t.pattern());} }
            case NUMBER -> { if(t!=null||d!=null||files!=null||f.optionSource()!=null)throw new IllegalArgumentException("Number fields cannot use text, date, option or file settings.");if(n!=null){if(n.min()!=null&&n.max()!=null&&n.min().compareTo(n.max())>0)throw new IllegalArgumentException("Numeric minimum cannot exceed maximum.");if(n.precision()!=null&&(n.precision()<0||n.precision()>8))throw new IllegalArgumentException("Decimal precision must be between 0 and 8.");} }
            case DATE -> { if(t!=null||n!=null||files!=null||f.optionSource()!=null)throw new IllegalArgumentException("Date fields cannot use text, numeric, option or file settings.");if(d!=null){LocalDate min=parseDateRule(d.minDate()),max=parseDateRule(d.maxDate());if(min!=null&&max!=null&&min.isAfter(max))throw new IllegalArgumentException("Minimum date cannot be after maximum date.");} }
            case DROPDOWN,MULTI_SELECT -> { if(f.optionSource()==null||t!=null||n!=null||d!=null||files!=null)throw new IllegalArgumentException("Selection fields require one authorized master-data source only."); }
            case AIRCRAFT_SELECT -> {if(f.optionSource()!=OptionSource.AIRCRAFT||t!=null||n!=null||d!=null||files!=null)throw new IllegalArgumentException("Aircraft selectors require the approved Aircraft Master source only.");}
            case FILE_UPLOAD,IMAGE_UPLOAD -> {if(files==null||t!=null||n!=null||d!=null||f.optionSource()!=null)throw new IllegalArgumentException("Evidence fields require file restrictions only.");if(files.maxFileSizeBytes()==null||files.maxFileSizeBytes()<1||files.maxFileSizeBytes()>15728640||files.minCount()==null||files.maxCount()==null||files.minCount()<0||files.maxCount()<1||files.maxCount()>10||files.minCount()>files.maxCount())throw new IllegalArgumentException("Configure valid file size and count limits (maximum 15 MB and 10 files per field).");if(files.extensions()==null||files.extensions().isEmpty()||files.mimeTypes()==null||files.mimeTypes().isEmpty())throw new IllegalArgumentException("Evidence fields must list allowed extensions and MIME types.");for(String ext:files.extensions())if(!EXTENSIONS.contains(ext.toLowerCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("Unsupported file extension: "+ext);for(String mime:files.mimeTypes())if(!MIME_TYPES.contains(mime.toLowerCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("Unsupported MIME type: "+mime);if(f.type()==FieldType.IMAGE_UPLOAD&&files.extensions().stream().anyMatch(ext->!Set.of("png","jpg","jpeg","webp").contains(ext.toLowerCase(java.util.Locale.ROOT))))throw new IllegalArgumentException("Image uploads may use PNG, JPEG or WEBP only."); }
            case CHECKBOX -> {if(t!=null||n!=null||d!=null||files!=null||f.optionSource()!=null)throw new IllegalArgumentException("Checkbox fields do not accept extra validation settings.");}
            case SECTION -> {if(f.required()||t!=null||n!=null||d!=null||files!=null||f.optionSource()!=null)throw new IllegalArgumentException("Section fields are headings and cannot be required or validated.");}
        }
    }
    private void validateSafePattern(String pattern) {if(pattern==null)return;if(pattern.length()>128||pattern.contains("(?")||pattern.matches(".*\\\\[1-9].*")||pattern.matches(".*\\([^)]*[+*][^)]*\\)[+*{].*"))throw new IllegalArgumentException("Validation patterns must be simple, bounded expressions without backreferences or nested quantifiers.");try{Pattern.compile(pattern);}catch(Exception ex){throw new IllegalArgumentException("The configured validation pattern is invalid.");}}
    private LocalDate parseDateRule(String value){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value);}catch(Exception ex){throw new IllegalArgumentException("Date limits must use YYYY-MM-DD.");}}
    private FieldDefinition findField(FormConfiguration configuration,String key){return configuration.fields().stream().filter(field->field.key().equals(key)).findFirst().orElse(null);}
    private FormVersion requireVersion(UUID operatorId,UUID formId,UUID versionId){return jdbc.query("SELECT * FROM operational_form_versions_dtl WHERE operator_id=? AND form_id=? AND id=?",(rs,n)->mapVersion(versionId,formId,rs.getInt("version_number"),rs.getString("status"),rs.getString("fields_json"),rs.getString("created_by"),rs.getTimestamp("created_at").toInstant(),rs.getString("published_by"),rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant()),operatorId,formId,versionId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Form version not found for this organization."));}
    private FormSummary summary(java.sql.ResultSet rs)throws java.sql.SQLException{return new FormSummary(rs.getObject("id",UUID.class),rs.getString("form_key"),rs.getString("name"),rs.getObject("area_id",UUID.class),rs.getString("area_name"),FormPurpose.valueOf(rs.getString("purpose")),WorkflowStage.valueOf(rs.getString("workflow_stage")),rs.getInt("isactive"),nullableInt(rs,"published_version"),rs.getObject("published_version_id",UUID.class),nullableInt(rs,"draft_version"),rs.getObject("draft_version_id",UUID.class),rs.getTimestamp("updated_at").toInstant());}

    private FormForArea formForArea(java.sql.ResultSet rs)throws java.sql.SQLException{return new FormForArea(rs.getObject("id",UUID.class),rs.getString("form_key"),rs.getString("name"),rs.getObject("area_id",UUID.class),FormPurpose.valueOf(rs.getString("purpose")),WorkflowStage.valueOf(rs.getString("workflow_stage")),rs.getObject("version_id",UUID.class),rs.getInt("version_number"),parseConfiguration(rs.getString("fields_json")));}

    private FormVersion mapVersion(UUID id,UUID formId,int number,String status,String fields,String createdBy,Instant createdAt,String publishedBy,Instant publishedAt){return new FormVersion(id,formId,number,status,parseConfiguration(fields),createdBy,createdAt,publishedBy,publishedAt);}
    private FormConfiguration parseConfiguration(String json){try{return mapper.readValue(json,FormConfiguration.class);}catch(Exception ex){throw new IllegalStateException("Stored form configuration is invalid.",ex);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException("Form configuration could not be serialized.",ex);}}
    private Map<String,Object> readMap(String json){try{return mapper.readValue(json,mapper.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));}catch(Exception ex){throw new IllegalStateException("Stored form answers could not be read.",ex);}}
    private void requireActiveArea(UUID operatorId,UUID areaId){if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1)",Boolean.class,operatorId,areaId)))throw new IllegalArgumentException("Choose an active area from this organization.");}
    private UUID parseUuid(Object raw,String label){try{return UUID.fromString(String.valueOf(raw));}catch(Exception ex){throw new IllegalArgumentException("Choose a valid authorized option for "+label+".");}}
    private int number(Object value){return value instanceof Number n?n.intValue():Integer.parseInt(String.valueOf(value));}
    private Integer nullableInt(java.sql.ResultSet rs,String column)throws java.sql.SQLException{int value=rs.getInt(column);return rs.wasNull()?null:value;}
    private void audit(UUID operatorId,String actor,UUID defectId,String type,String entityType,UUID entityId,String note,Map<String,Object> previous,Map<String,Object> next){jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb)",operatorId,defectId,type,note,actor,entityType,entityId,previous==null?null:json(previous),next==null?null:json(next));}
    private void auditActor(String actor){jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);}
}
