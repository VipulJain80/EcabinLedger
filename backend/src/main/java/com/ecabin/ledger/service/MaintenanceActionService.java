package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.*;
import static com.ecabin.ledger.api.FormModels.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaintenanceActionService {
    private final JdbcTemplate jdbc;
    private final OperationalFormService forms;
    private final MaintenanceSlaPolicyService policies;
    private com.ecabin.ledger.email.EmailOutboxService emailOutbox;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmailOutbox(com.ecabin.ledger.email.EmailOutboxService emailOutbox){this.emailOutbox=emailOutbox;}
    public MaintenanceActionService(JdbcTemplate jdbc,OperationalFormService forms,MaintenanceSlaPolicyService policies){this.jdbc=jdbc;this.forms=forms;this.policies=policies;}

    public MaintenanceSlaEffective effectivePolicy(UUID tenant,UUID defectId,String actor,String statusKey){
        requireAssignedAction(tenant,defectId,null,actor,false);
        return policies.effective(tenant,statusKey);
    }

    @Transactional
    public WorkflowRecord update(UUID tenant,UUID defectId,UUID actionId,String actor,MaintenanceActionUpdateRequest request){
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);
        Map<String,Object> current=requireAssignedAction(tenant,defectId,actionId,actor,true);
        String defectStatus=String.valueOf(current.get("defectStatus"));
        if(!Set.of("ACTION_ASSIGNED","IN_PROGRESS","ON_HOLD").contains(defectStatus))throw new ResponseStatusException(HttpStatus.CONFLICT,"This case is no longer open for maintenance updates.");
        String actionStatus=String.valueOf(current.get("actionStatus"));
        if(!Set.of("ASSIGNED","IN_PROGRESS","ON_HOLD").contains(actionStatus))throw new ResponseStatusException(HttpStatus.CONFLICT,"The corrective action is no longer active.");
        String statusKey=request.status();
        if(!Set.of("WORK_IN_PROGRESS","ON_HOLD","CLOSE_SUBMITTED").contains(statusKey))throw new IllegalArgumentException("Choose Work in Progress, On Hold, or Close Submitted.");
        UUID currentActionId=(UUID)current.get("id");
        if(statusKey.equals("ON_HOLD")&&"ON_HOLD".equals(actionStatus)&&Set.of("IN_PROGRESS","ON_HOLD").contains(defectStatus))return new WorkflowRecord(currentActionId,"ON_HOLD");
        if(statusKey.equals("WORK_IN_PROGRESS")&&"IN_PROGRESS".equals(actionStatus)&&"IN_PROGRESS".equals(defectStatus))return new WorkflowRecord(currentActionId,"IN_PROGRESS");
        FormForArea form=forms.published(tenant,request.formId(),request.formVersionId(),(UUID)current.get("areaId"));
        if(form.purpose()!=FormPurpose.CORRECTIVE_ACTION||!form.areaId().equals(current.get("areaId"))||form.workflowStage()!=WorkflowStage.IN_PROGRESS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected form version is not the published maintenance-action form for this case.");
        boolean eligible=forms.publishedForDefect(tenant,defectId,FormPurpose.CORRECTIVE_ACTION).stream().anyMatch(candidate->candidate.id().equals(form.id())&&candidate.versionId().equals(form.versionId()));
        if(!eligible)throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected maintenance form is not eligible for the case's current workflow state.");
        MaintenanceActionFormRules.requireValid(form.configuration());
        forms.validateMaintenanceAnswers(form,statusKey,request.slaTargetDate(),request.remark());
        forms.validateMaintenanceEvidence(tenant,defectId,form,statusKey,request.attachments());
        LocalDate date=policies.validateDate(tenant,statusKey,request.slaTargetDate());
        if(request.remark()!=null&&request.remark().codePointCount(0,request.remark().length())>2000)throw new IllegalArgumentException("Remarks must be 2,000 characters or fewer.");
        MaintenanceSlaPolicy policy=policies.requireConfigured(tenant,statusKey);
        ZoneId zone=ZoneId.of(policy.businessTimezone());
        Timestamp target=date==null?null:Timestamp.from(date.atStartOfDay(zone).toInstant());
        String nextAction=switch(statusKey){case "WORK_IN_PROGRESS"->"IN_PROGRESS";case "ON_HOLD"->"ON_HOLD";default->"COMPLETED";};
        // A Maintenance Close submission is the handoff to Closure Team review.
        // Verification remains available for legacy workflows, but is not part of this path.
        String nextDefect=statusKey.equals("CLOSE_SUBMITTED")?"APPROVAL_REQUIRED":statusKey.equals("ON_HOLD")?"ON_HOLD":"IN_PROGRESS";
        if(statusKey.equals("ON_HOLD")&&"ASSIGNED".equals(actionStatus))throw new ResponseStatusException(HttpStatus.CONFLICT,"Start the assigned action before placing it on hold.");
        if(statusKey.equals("CLOSE_SUBMITTED")&&!"IN_PROGRESS".equals(actionStatus))throw new ResponseStatusException(HttpStatus.CONFLICT,"Move the action to Work in Progress before submitting it for closure review.");
        if(statusKey.equals("CLOSE_SUBMITTED"))DefectWorkflow.requireTransition(defectStatus,"APPROVAL_REQUIRED",request.remark()==null?"Maintenance submitted for closure review.":request.remark());
        LocalDate previousDate=null;
        Object previousDue=current.get("dueAt");
        if(previousDue instanceof Timestamp timestamp)previousDate=timestamp.toInstant().atZone(zone).toLocalDate();
        int updated=jdbc.update("UPDATE corrective_actions_dtl SET status=?,due_at=?,completed_at=?,completed_by_user_id=?,completion_notes=?,updated_at=now() WHERE operator_id=? AND defect_id=? AND id=? AND isactive=1 AND status=?",
            nextAction,target,statusKey.equals("CLOSE_SUBMITTED")?Timestamp.from(java.time.Instant.now()):null,statusKey.equals("CLOSE_SUBMITTED")?actor:null,statusKey.equals("CLOSE_SUBMITTED")?request.remark():null,tenant,defectId,actionId,actionStatus);
        if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The maintenance action changed while saving. Refresh and try again.");
        if(!defectStatus.equals(nextDefect))DefectWorkflow.requireTransition(defectStatus,nextDefect,request.remark()==null?"Maintenance action updated.":request.remark());
        int changed=jdbc.update("UPDATE defects_dtl SET status=?,due_at=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND status=? AND isactive=1",nextDefect,target,tenant,defectId,defectStatus);
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The case changed while saving. Refresh and try again.");
        Map<String,Object> newState=new java.util.LinkedHashMap<>();newState.put("actionStatus",nextAction);newState.put("slaTargetDate",date==null?"":date.toString());newState.put("previousSlaTargetDate",previousDate==null?"":previousDate.toString());newState.put("remark",request.remark()==null?"":request.remark());newState.put("formId",form.id().toString());newState.put("formVersionId",form.versionId().toString());newState.put("maintenanceStatus",statusKey);newState.put("evidenceAttachments",request.attachments()==null?Map.of():request.attachments());
        UUID eventId=jdbc.query("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,?,?,?,?,?,?,'CORRECTIVE_ACTION',?,CAST(? AS jsonb),CAST(? AS jsonb),?) RETURNING id",(rs,n)->rs.getObject(1,UUID.class),
            tenant,defectId,statusKey.equals("CLOSE_SUBMITTED")?"ACTION_CLOSE_SUBMITTED":"MAINTENANCE_ACTION_UPDATED",defectStatus,nextDefect,request.remark()==null?"":request.remark().trim(),actor,actionId,
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("actionStatus",actionStatus,"slaTargetDate",previousDate==null?"":previousDate.toString())).toString(),
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(newState).toString(),MDC.get("request_id")).get(0);
        if(statusKey.equals("CLOSE_SUBMITTED")&&emailOutbox!=null)emailOutbox.enqueue(tenant,defectId,eventId,actor,"ACTION_CLOSE_SUBMITTED",null,null);
        return new WorkflowRecord(actionId,nextDefect);
    }

    private Map<String,Object> requireAssignedAction(UUID tenant,UUID defectId,UUID actionId,String actor,boolean lock){
        String sql="SELECT ca.id,ca.status AS \"actionStatus\",ca.assigned_team AS \"assignedTeam\",ca.assigned_user_id AS \"assignedUserId\",ca.due_at AS \"dueAt\",d.status AS \"defectStatus\",d.area_id AS \"areaId\" FROM corrective_actions_dtl ca JOIN defects_dtl d ON d.operator_id=ca.operator_id AND d.id=ca.defect_id WHERE ca.operator_id=? AND ca.defect_id=? AND ca.isactive=1 AND d.isactive=1 AND ca.id=(SELECT latest.id FROM corrective_actions_dtl latest WHERE latest.operator_id=ca.operator_id AND latest.defect_id=ca.defect_id AND latest.isactive=1 ORDER BY latest.created_at DESC,latest.id DESC LIMIT 1) AND ( (?::uuid IS NULL AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD')) OR ca.id=? ) AND ((ca.assigned_team IS NULL AND ca.assigned_user_id=?) OR (ca.assigned_team IS NOT NULL AND EXISTS (SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=mt.operator_id AND t.name=mt.team_name AND t.isactive=1 WHERE mt.operator_id=ca.operator_id AND mt.external_user_id=? AND mt.team_name=ca.assigned_team AND mt.isactive=1 AND (ca.assigned_user_id IS NULL OR ca.assigned_user_id=?)))) ORDER BY ca.created_at DESC,ca.id DESC LIMIT 1"+(lock?" FOR UPDATE OF ca,d":"");
        return jdbc.query(sql,new ColumnMapRowMapper(),tenant,defectId,actionId,actionId,actor,actor,actor).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"An active corrective action assigned to your team was not found."));
    }
}
