package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Shared, tenant-scoped Reviewer queue and explicit review-routing configuration. */
@Service
public class ReviewerQueueService {
    private static final Set<String> STATUSES=Set.of("REPORTED","UNDER_REVIEW","INSPECTION_REQUIRED","IN_PROGRESS","AWAITING_VERIFICATION","VERIFIED","APPROVAL_REQUIRED","REOPENED");
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final OperationalFormService operationalForms;
    private com.ecabin.ledger.email.EmailOutboxService emailOutbox;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmailOutbox(com.ecabin.ledger.email.EmailOutboxService emailOutbox){this.emailOutbox=emailOutbox;}
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewerQueueService(JdbcTemplate jdbc,NotificationService notifications,OperationalFormService operationalForms){this.jdbc=jdbc;this.notifications=notifications;this.operationalForms=operationalForms;}
    /** Compatibility constructor for isolated legacy unit tests. */
    public ReviewerQueueService(JdbcTemplate jdbc,NotificationService notifications){this(jdbc,notifications,null);}
    public ReviewerQueueService(JdbcTemplate jdbc){this(jdbc,null,null);}

    @Transactional(readOnly=true)
    public ReviewerQueuePage queue(UUID org,String actor,boolean organizationAdmin,int page,int size,String status,String search,String sortBy,String sortDirection){
        if(page<0||size<1||size>100)throw new IllegalArgumentException("Choose a page and page size between 1 and 100.");
        String normalized=status==null||status.isBlank()?"ALL":status.toUpperCase(java.util.Locale.ROOT);
        if(!normalized.equals("ALL")&&!STATUSES.contains(normalized))throw new IllegalArgumentException("Choose a valid review status.");
        String query=search==null?"":search.trim(); if(query.length()>200)throw new IllegalArgumentException("Search is limited to 200 characters.");
        Map<String,String> sorts=Map.of("defectId","d.reference","aircraftRegistration","a.tail_number","msnNumber","a.msn_number","aircraftType","a.aircraft_type","severity","d.severity","status","d.status","reportedAt","d.reported_at");
        String order=sorts.get(sortBy==null||sortBy.isBlank()?"reportedAt":sortBy); if(order==null)throw new IllegalArgumentException("Choose a supported Reviewer queue sort column.");
        String direction=sortDirection==null||sortDirection.isBlank()?"DESC":sortDirection.toUpperCase(java.util.Locale.ROOT);if(!Set.of("ASC","DESC").contains(direction))throw new IllegalArgumentException("Choose ascending or descending sort order.");
        String access=organizationAdmin?"TRUE":"(EXISTS (SELECT 1 FROM operator_membership_teams_mst omt JOIN operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1 JOIN maintenance_teams_mst rt ON rt.operator_id=omt.operator_id AND rt.name=omt.team_name AND rt.team_type='REVIEWER' AND rt.isactive=1 WHERE omt.operator_id=d.operator_id AND omt.external_user_id=? AND omt.team_name=review_team.name AND omt.isactive=1) OR (d.status='APPROVAL_REQUIRED' AND EXISTS (SELECT 1 FROM operators_mst o JOIN maintenance_teams_mst ct ON ct.operator_id=o.id AND ct.id=o.default_closure_team_id AND ct.team_type IN ('CLOSURE','REVIEWER') AND ct.isactive=1 JOIN operator_membership_teams_mst cmt ON cmt.operator_id=ct.operator_id AND cmt.team_name=ct.name AND cmt.isactive=1 JOIN operator_memberships_mst cm ON cm.operator_id=cmt.operator_id AND cm.external_user_id=cmt.external_user_id AND cm.isactive=1 JOIN operator_roles_mst cr ON cr.operator_id=cm.operator_id AND cr.role_key=cm.role AND cr.isactive=1 WHERE o.id=d.operator_id AND cmt.external_user_id=? AND ('|'||cr.permissions||'|') LIKE '%|APPROVE|%')))";
        String from=" FROM defects_dtl d JOIN aircraft_mst a ON a.operator_id=d.operator_id AND a.id=d.aircraft_id LEFT JOIN maintenance_teams_mst review_team ON review_team.operator_id=d.operator_id AND review_team.id=d.reviewer_team_id LEFT JOIN LATERAL (SELECT ca.assigned_team FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 ORDER BY ca.created_at DESC,ca.id DESC LIMIT 1) action ON TRUE ";
        String where=" WHERE d.operator_id=? AND d.isactive=1 AND a.isactive=1 AND d.status IN ("+STATUSES.stream().map(s->"'"+s+"'").collect(java.util.stream.Collectors.joining(","))+") AND NOT (d.status='REPORTED' AND EXISTS(SELECT 1 FROM defect_events_dtl rejected WHERE rejected.operator_id=d.operator_id AND rejected.defect_id=d.id AND rejected.event_type='REVIEW_REJECTED' AND rejected.occurred_at >= COALESCE((SELECT max(s.submitted_at) FROM operational_form_submissions_dtl s WHERE s.operator_id=rejected.operator_id AND s.defect_id=rejected.defect_id AND s.purpose='DEFECT_REPORT'),'-infinity'::timestamptz))) AND (d.status<>'IN_PROGRESS' OR NOT EXISTS(SELECT 1 FROM corrective_actions_dtl open_action WHERE open_action.operator_id=d.operator_id AND open_action.defect_id=d.id AND open_action.isactive=1 AND open_action.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD'))) AND ((d.reviewer_team_id IS NULL AND ?=true) OR (d.reviewer_team_id IS NOT NULL AND "+access+")) AND (?='ALL' OR d.status=?) AND (?='' OR POSITION(LOWER(?) IN LOWER(CONCAT_WS(' ',d.reference,a.tail_number,a.msn_number,a.aircraft_type,d.zone,d.component,d.title,d.description,d.severity,d.status,(SELECT s.severity_name FROM defect_severities_mst s WHERE s.operator_id=d.operator_id AND s.id=d.severity_id))))>0)";
        Object[] args=organizationAdmin?new Object[]{org,true,normalized,normalized,query,query}:new Object[]{org,false,actor,actor,normalized,normalized,query,query};
        Long count=jdbc.queryForObject("SELECT count(*)"+from+where,Long.class,args);long total=count==null?0:count;
        String sql="SELECT d.id,d.reference,a.tail_number,a.msn_number,a.aircraft_type,d.zone,d.component,d.severity,COALESCE((SELECT s.severity_name FROM defect_severities_mst s WHERE s.operator_id=d.operator_id AND s.id=d.severity_id),d.severity) AS severity_name,d.status,review_team.name AS reviewer_team,action.assigned_team AS maintenance_team,(d.reviewer_team_id IS NULL OR NOT EXISTS(SELECT 1 FROM operator_membership_teams_mst et JOIN operator_memberships_mst em ON em.operator_id=et.operator_id AND em.external_user_id=et.external_user_id AND em.isactive=1 JOIN operator_roles_mst er ON er.operator_id=em.operator_id AND er.role_key=em.role AND er.isactive=1 WHERE et.operator_id=d.operator_id AND et.team_name=review_team.name AND et.isactive=1 AND ('|'||er.permissions||'|') LIKE '%|REVIEW|%')) AS routing_needs_attention,d.reported_at"+from+where+" ORDER BY "+order+" "+direction+",d.id ASC LIMIT ? OFFSET ?";
        Object[] pageArgs=java.util.Arrays.copyOf(args,args.length+2);pageArgs[args.length]=size;pageArgs[args.length+1]=(long)page*size;
        List<ReviewerQueueRow> rows=jdbc.query(sql,(rs,n)->new ReviewerQueueRow(rs.getObject("id",UUID.class),rs.getString("reference"),rs.getString("tail_number"),rs.getString("msn_number"),rs.getString("aircraft_type"),rs.getString("zone"),rs.getString("component"),rs.getString("severity"),rs.getString("severity_name"),rs.getString("status"),rs.getString("reviewer_team"),rs.getString("maintenance_team"),rs.getBoolean("routing_needs_attention"),rs.getTimestamp("reported_at").toInstant()),pageArgs);
        return new ReviewerQueuePage(rows,page,size,total,(int)Math.ceil((double)total/size));
    }

    public Map<String,Object> routing(UUID org){
        return jdbc.query("SELECT o.id AS \"organizationId\",o.default_reviewer_team_id AS \"teamId\",t.name AS \"teamName\",COALESCE((SELECT count(*) FROM operator_membership_teams_mst omt JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN maintenance_teams_mst rt ON rt.operator_id=omt.operator_id AND rt.name=omt.team_name AND rt.team_type='REVIEWER' AND rt.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE omt.operator_id=o.id AND omt.team_name=t.name AND omt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%'),0) AS \"eligibleMembers\" FROM operators_mst o LEFT JOIN maintenance_teams_mst t ON t.operator_id=o.id AND t.id=o.default_reviewer_team_id WHERE o.id=? AND o.isactive=1",rs->{if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization was not found.");return Map.of("organizationId",rs.getObject("organizationId"),"teamId",rs.getObject("teamId")==null?"":rs.getObject("teamId"),"teamName",rs.getString("teamName")==null?"":rs.getString("teamName"),"eligibleMembers",rs.getInt("eligibleMembers"));},org);
    }

    @Transactional(readOnly=true)
    public List<ReviewerTeamOption> reviewerTeams(UUID org){return jdbc.query("SELECT t.id,t.name,count(DISTINCT m.external_user_id) AS eligible_members FROM maintenance_teams_mst t JOIN operator_membership_teams_mst omt ON omt.operator_id=t.operator_id AND omt.team_name=t.name AND omt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE t.operator_id=? AND t.team_type='REVIEWER' AND t.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%' GROUP BY t.id,t.name ORDER BY t.name",(rs,n)->new ReviewerTeamOption(rs.getObject("id",UUID.class),rs.getString("name"),rs.getInt("eligible_members")),org);}

    @Transactional(readOnly=true)
    public ReviewerDecisionOptions decisionOptions(UUID org,UUID defectId,String actor){
        ReviewTarget target=reviewTarget(org,defectId);
        requireDecisionScope(org,defectId,actor,target);
        if(!"GALLEY".equals(target.areaCode()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"The Galley Reviewer decision form is not available for this cabin area.");
        if(!Set.of("REPORTED","UNDER_REVIEW").contains(target.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"This case is not awaiting an initial Reviewer decision.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defect_events_dtl rejected WHERE rejected.operator_id=? AND rejected.defect_id=? AND rejected.event_type='REVIEW_REJECTED' AND rejected.occurred_at >= COALESCE((SELECT max(s.submitted_at) FROM operational_form_submissions_dtl s WHERE s.operator_id=rejected.operator_id AND s.defect_id=rejected.defect_id AND s.purpose='DEFECT_REPORT'),'-infinity'::timestamptz))",Boolean.class,org,defectId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"The Auditor must submit the corrected Galley report before another Reviewer decision.");
        String areaName=jdbc.queryForObject("SELECT name FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY' AND isactive=1 AND id=(SELECT area_id FROM defects_dtl WHERE operator_id=? AND id=?)",String.class,org,org,defectId);
        List<ReviewerDecisionTeam> teams=jdbc.query("SELECT t.id,t.name FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.team_type='MAINTENANCE' AND t.isactive=1 AND EXISTS(SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%') ORDER BY t.name",(rs,n)->new ReviewerDecisionTeam(rs.getObject("id",UUID.class),rs.getString("name")),org);
        return new ReviewerDecisionOptions(areaName,target.status(),teams);
    }

    @Transactional
    public Map<String,Object> setRouting(UUID org,String actor,UUID teamId,String reason){
        if(reason==null||reason.isBlank()||reason.length()>1000)throw new IllegalArgumentException("A routing-change reason of up to 1000 characters is required.");
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);
        if(!eligibleTeam(org,teamId))throw new IllegalArgumentException("Choose an active Reviewer team in this organization with at least one active member granted REVIEW permission.");
        UUID previous=jdbc.query("SELECT default_reviewer_team_id FROM operators_mst WHERE id=? FOR UPDATE",rs->rs.next()?rs.getObject(1,UUID.class):null,org);
        if(teamId.equals(previous))throw new IllegalArgumentException("That team is already the organization's default Reviewer route.");
        jdbc.update("UPDATE operators_mst SET default_reviewer_team_id=?,updated_at=now() WHERE id=? AND isactive=1",teamId,org);
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,'REVIEW_ROUTING_CONFIGURED',? ,?,'ORGANIZATION',?,jsonb_build_object('reviewerTeamId',CAST(? AS text)),jsonb_build_object('reviewerTeamId',CAST(? AS text)),?)",org,reason.trim(),actor,org,previous==null?null:previous.toString(),teamId.toString(),safeRequestId());
        return routing(org);
    }

    @Transactional
    public void reroute(UUID org,UUID defectId,String actor,boolean organizationAdmin,UUID teamId,String reason){
        if(reason==null||reason.isBlank()||reason.length()>1000)throw new IllegalArgumentException("A routing-change reason of up to 1000 characters is required.");
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);
        UUID previous=jdbc.query("SELECT reviewer_team_id FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1 FOR UPDATE",rs->{if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");return rs.getObject(1,UUID.class);},org,defectId);
        if(!organizationAdmin&&(previous==null||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM maintenance_teams_mst t JOIN operator_membership_teams_mst omt ON omt.operator_id=t.operator_id AND omt.team_name=t.name AND omt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE t.operator_id=? AND t.id=? AND t.team_type='REVIEWER' AND t.isactive=1 AND omt.external_user_id=? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%')",Boolean.class,org,previous,actor))))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");
        if(!eligibleTeam(org,teamId))throw new IllegalArgumentException("Choose an active Reviewer team in this organization with an eligible Reviewer.");
        if(teamId.equals(previous))throw new IllegalArgumentException("That defect is already routed to this Reviewer team.");
        jdbc.update("UPDATE defects_dtl SET reviewer_team_id=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=?",teamId,org,defectId);
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,?,'REVIEW_ROUTING_CHANGED',? ,?,'DEFECT',?,jsonb_build_object('reviewerTeamId',CAST(? AS text)),jsonb_build_object('reviewerTeamId',CAST(? AS text)),?)",org,defectId,reason.trim(),actor,defectId,previous==null?null:previous.toString(),teamId.toString(),safeRequestId());
    }

    /** Persist a Galley review decision and route approved work directly to Maintenance. */
    @Transactional
    public WorkflowRecord decide(UUID org,UUID defectId,String actor,ReviewerDecisionRequest request){
        String decision=request.decision()==null?"":request.decision().trim().toUpperCase(java.util.Locale.ROOT);
        if(!Set.of("APPROVE","REJECT").contains(decision))throw new IllegalArgumentException("Decision must be APPROVE or REJECT.");
        String remark=request.remark()==null?null:request.remark().trim();
        if(remark!=null&&remark.length()>2000)throw new IllegalArgumentException("Remark must be 2000 characters or fewer.");
        if(request.rejectionReason()!=null&&request.rejectionReason().length()>2000)throw new IllegalArgumentException("Rejection Reason must be 2000 characters or fewer.");
        String rejectionReason=request.rejectionReason()==null?null:request.rejectionReason().trim();
        if(decision.equals("REJECT")&&(rejectionReason==null||rejectionReason.isBlank()))throw new IllegalArgumentException("Provide a rejection reason before rejecting this report.");
        if(decision.equals("APPROVE")&&rejectionReason!=null&&!rejectionReason.isBlank())throw new IllegalArgumentException("A rejection reason can only be provided when the decision is REJECT.");
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);
        ReviewTarget target=jdbc.query("SELECT d.status,d.reviewer_team_id,t.name AS reviewer_team,a.code AS area_code,d.reported_by,d.assigned_to,d.assigned_user_id,d.assigned_team FROM defects_dtl d JOIN cabin_areas_mst a ON a.operator_id=d.operator_id AND a.id=d.area_id AND a.isactive=1 LEFT JOIN maintenance_teams_mst t ON t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.isactive=1 AND t.team_type='REVIEWER' WHERE d.operator_id=? AND d.id=? AND d.isactive=1 FOR UPDATE OF d",rs->{
            if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");
            return new ReviewTarget(rs.getString("status"),rs.getObject("reviewer_team_id",UUID.class),rs.getString("reviewer_team"),rs.getString("area_code"),rs.getString("reported_by"),rs.getString("assigned_to"),rs.getString("assigned_user_id"),rs.getString("assigned_team"));
        },org,defectId);
        requireDecisionScope(org,defectId,actor,target);
        if(!"GALLEY".equals(target.areaCode()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"The Galley Reviewer decision form is not available for this cabin area.");
        if(!Set.of("REPORTED","UNDER_REVIEW").contains(target.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"This defect is no longer awaiting its initial review decision.");
        requirePublishedDecisionForm(org,defectId,target.status(),request.formId(),request.versionId());
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defect_events_dtl rejected WHERE rejected.operator_id=? AND rejected.defect_id=? AND rejected.event_type='REVIEW_REJECTED' AND rejected.occurred_at >= COALESCE((SELECT max(s.submitted_at) FROM operational_form_submissions_dtl s WHERE s.operator_id=rejected.operator_id AND s.defect_id=rejected.defect_id AND s.purpose='DEFECT_REPORT'),'-infinity'::timestamptz))",Boolean.class,org,defectId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"The Auditor must submit the corrected Galley report before another Reviewer decision.");
        if(decision.equals("REJECT")){
            if(request.maintenanceTeamId()!=null)throw new IllegalArgumentException("Do not assign a maintenance team when rejecting a report.");
            String reporter=target.reportedBy();
            boolean eligibleReporter=reporter!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operator_memberships_mst m JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE m.operator_id=? AND m.external_user_id=? AND m.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|REPORT|%')",Boolean.class,org,reporter));
            if(!eligibleReporter)throw new ResponseStatusException(HttpStatus.CONFLICT,"The original reporter is no longer an active defect reporter. The case cannot be returned safely.");
            DefectWorkflow.requireTransition(target.status(),"REWORK","review decision");
            int changed=jdbc.update("UPDATE defects_dtl SET status='REWORK',assigned_to=?,assigned_user_id=?,assigned_team=NULL,due_at=NULL,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND status=? AND isactive=1",reporter,reporter,org,defectId,target.status());
            if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The defect changed while the review decision was being recorded.");
            UUID eventId=UUID.randomUUID();
            String note="Rejected: "+rejectionReason;
            jdbc.update("INSERT INTO defect_events_dtl(id,operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,?,?,'REVIEW_REJECTED',?,'REWORK',?,?,'REVIEW_DECISION',?,jsonb_build_object('status',?,'assignedTo',?,'assignedUserId',?,'assignedTeam',?,'reviewerTeamId',CAST(? AS text)),jsonb_build_object('status','REWORK','decision','REJECT','rejectionReason',?::varchar,'assignedTo',?,'assignedUserId',?,'assignedTeam',NULL,'originalReporter',?,'reviewerTeamId',CAST(? AS text),'remark',?::varchar,'formId',CAST(? AS text),'formVersionId',CAST(? AS text)),?)",eventId,org,defectId,target.status(),note,actor,eventId,target.status(),target.assignedTo(),target.assignedUserId(),target.assignedTeam(),target.reviewerTeamId().toString(),rejectionReason,reporter,reporter,reporter,target.reviewerTeamId().toString(),remark,request.formId(),request.versionId(),safeRequestId());
            if(notifications!=null)notifications.notifyParticipants(org,defectId,eventId,actor,"REVIEW_REJECTED","The Reviewer returned the defect to you for correction.");
            if(emailOutbox!=null)emailOutbox.enqueue(org,defectId,eventId,actor,"REVIEW_REJECTED",null,null);
            return new WorkflowRecord(eventId,"REWORK");
        }
        if(request.maintenanceTeamId()==null)throw new IllegalArgumentException("Choose an active maintenance team before approving.");
        String maintenanceTeam=jdbc.query("SELECT t.name FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.id=? AND t.team_type='MAINTENANCE' AND t.isactive=1 AND EXISTS(SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%')",rs->{if(!rs.next())throw new IllegalArgumentException("Choose an active maintenance team in this organization with an eligible maintenance member.");return rs.getString(1);},org,request.maintenanceTeamId());
        String prior=target.status();
        UUID actionId=UUID.randomUUID();
        String[] defectData=jdbc.query("SELECT title,severity FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1",rs->rs.next()?new String[]{rs.getString("title"),rs.getString("severity")}:null,org,defectId);
        if(defectData==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");
        String priority=Set.of("HIGH","CRITICAL").contains(defectData[1])?defectData[1]:"MEDIUM";
        String actionDescription="Reviewer-approved cabin defect: "+defectData[0];
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_team,description,priority,status) VALUES(?,?,?,?,?,?,?,'ASSIGNED')",actionId,org,defectId,actor,maintenanceTeam,actionDescription,priority);
        updateReviewStatus(org,defectId,prior,"ACTION_ASSIGNED");
        int assigned=jdbc.update("UPDATE defects_dtl SET assigned_to=?,assigned_user_id=NULL,assigned_team=?,due_at=NULL,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND status='ACTION_ASSIGNED' AND isactive=1",maintenanceTeam,maintenanceTeam,org,defectId);
        if(assigned!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The defect changed while its Maintenance assignment was being recorded.");
        UUID eventId=UUID.randomUUID();
        jdbc.update("INSERT INTO defect_events_dtl(id,operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,?,?,'REVIEW_APPROVED',?,'ACTION_ASSIGNED',?,?,'REVIEW_DECISION',?,jsonb_build_object('status',?,'reviewerTeamId',CAST(? AS text)),jsonb_build_object('status','ACTION_ASSIGNED','decision','APPROVE','reviewerTeamId',CAST(? AS text),'maintenanceTeam',?::varchar,'maintenanceTeamId',?::varchar,'correctiveActionId',CAST(? AS text),'remark',?::varchar,'formId',CAST(? AS text),'formVersionId',CAST(? AS text)),?)",eventId,org,defectId,prior,remark==null||remark.isBlank()?"Reviewer approved the report and assigned it to Maintenance.":remark,actor,eventId,prior,target.reviewerTeamId().toString(),target.reviewerTeamId().toString(),maintenanceTeam,request.maintenanceTeamId().toString(),actionId.toString(),remark,request.formId(),request.versionId(),safeRequestId());
        if(notifications!=null)notifications.notifyMaintenance(org,defectId,eventId,actor,maintenanceTeam,null,"ACTION_ASSIGNED","A Reviewer approved the defect and assigned corrective work to your Maintenance team.");
        if(emailOutbox!=null)emailOutbox.enqueue(org,defectId,eventId,actor,"REVIEW_APPROVED",maintenanceTeam,null);
        return new WorkflowRecord(eventId,"ACTION_ASSIGNED");
    }

    private boolean eligibleReviewer(UUID org,UUID teamId,String actor){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM maintenance_teams_mst t JOIN operator_membership_teams_mst mt ON mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE t.operator_id=? AND t.id=? AND t.team_type='REVIEWER' AND t.isactive=1 AND mt.external_user_id=? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%')",Boolean.class,org,teamId,actor));}
    private void requirePublishedDecisionForm(UUID org,UUID defectId,String status,UUID formId,UUID versionId){
        if(formId==null||versionId==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select the published Galley Reviewer Decision form before submitting.");
        boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id AND v.status='PUBLISHED' JOIN defects_dtl d ON d.operator_id=f.operator_id AND d.area_id=f.area_id AND d.id=? AND d.isactive=1 JOIN cabin_areas_mst a ON a.operator_id=d.operator_id AND a.id=d.area_id AND a.code='GALLEY' AND a.isactive=1 WHERE f.operator_id=? AND f.id=? AND f.published_version_id=? AND f.purpose='REVIEW_DECISION' AND f.workflow_stage=? AND f.isactive=1)",Boolean.class,defectId,org,formId,versionId,status));
        if(!valid)throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected Reviewer Decision version is no longer published or does not match this case's current stage.");
        if(operationalForms!=null&&!operationalForms.publishedForDefect(org,defectId,com.ecabin.ledger.api.FormModels.FormPurpose.REVIEW_DECISION).stream().anyMatch(form->form.id().equals(formId)&&form.versionId().equals(versionId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected Reviewer Decision version has an invalid or ineligible published configuration. Ask an administrator to correct it in Form Master.");
    }
    private void updateReviewStatus(UUID org,UUID defectId,String from,String to){DefectWorkflow.requireTransition(from,to,"review decision");int updated=jdbc.update("UPDATE defects_dtl SET status=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND status=? AND isactive=1",to,org,defectId,from);if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The defect changed while the review decision was being recorded.");}
    private record ReviewTarget(String status,UUID reviewerTeamId,String reviewerTeam,String areaCode,String reportedBy,String assignedTo,String assignedUserId,String assignedTeam){}

    private ReviewTarget reviewTarget(UUID org,UUID defectId){return jdbc.query("SELECT d.status,d.reviewer_team_id,t.name AS reviewer_team,a.code AS area_code,d.reported_by,d.assigned_to,d.assigned_user_id,d.assigned_team FROM defects_dtl d JOIN cabin_areas_mst a ON a.operator_id=d.operator_id AND a.id=d.area_id AND a.isactive=1 LEFT JOIN maintenance_teams_mst t ON t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.isactive=1 AND t.team_type='REVIEWER' WHERE d.operator_id=? AND d.id=? AND d.isactive=1",rs->{if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");return new ReviewTarget(rs.getString("status"),rs.getObject("reviewer_team_id",UUID.class),rs.getString("reviewer_team"),rs.getString("area_code"),rs.getString("reported_by"),rs.getString("assigned_to"),rs.getString("assigned_user_id"),rs.getString("assigned_team"));},org,defectId);}
    private void requireDecisionScope(UUID org,UUID defectId,String actor,ReviewTarget target){if(target.reviewerTeamId()==null||!eligibleReviewer(org,target.reviewerTeamId(),actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"An active member of the defect's currently routed Reviewer team with REVIEW permission is required.");}

    private boolean eligibleTeam(UUID org,UUID team){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.id=? AND t.team_type='REVIEWER' AND t.isactive=1 AND EXISTS (SELECT 1 FROM operator_membership_teams_mst omt JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE omt.operator_id=t.operator_id AND omt.team_name=t.name AND omt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%'))",Boolean.class,org,team));}
    private UUID safeRequestId(){try{return UUID.fromString(MDC.get("requestId"));}catch(RuntimeException ex){return null;}}
}
