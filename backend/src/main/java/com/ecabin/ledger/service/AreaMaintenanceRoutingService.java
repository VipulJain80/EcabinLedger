package com.ecabin.ledger.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.MDC;

/** Tenant-scoped cabin-area routing and immutable workflow-mode versions. */
@Service
public class AreaMaintenanceRoutingService {
    public record Route(UUID workflowVersionId, String mode, UUID mappingId, UUID teamId, String teamName) {}
    public record MappingRequest(UUID id, UUID areaId, UUID maintenanceTeamId, String reason) {}
    public record WorkflowRequest(String assignmentMode, String reason) {}
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    public AreaMaintenanceRoutingService(JdbcTemplate jdbc, NotificationService notifications) {
        this.jdbc = jdbc; this.notifications = notifications;
    }

    public List<Map<String,Object>> mappings(UUID tenant) {
        return jdbc.query("SELECT m.id,m.area_id AS \"areaId\",a.code AS \"areaCode\",a.name AS \"areaName\",m.maintenance_team_id AS \"maintenanceTeamId\",t.name AS \"teamName\",m.isactive,m.change_reason AS \"changeReason\",m.created_by AS \"createdBy\",m.created_at AS \"createdAt\",m.updated_by AS \"updatedBy\",m.updated_at AS \"updatedAt\" FROM area_maintenance_team_mappings_mst m JOIN cabin_areas_mst a ON a.operator_id=m.operator_id AND a.id=m.area_id JOIN maintenance_teams_mst t ON t.operator_id=m.operator_id AND t.id=m.maintenance_team_id WHERE m.operator_id=? ORDER BY a.name", new ColumnMapRowMapper(), tenant);
    }

    public List<Map<String,Object>> eligibleTeams(UUID tenant) {
        return jdbc.query("SELECT t.id,t.name FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.isactive=1 AND t.team_type='MAINTENANCE' AND EXISTS (SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%') ORDER BY t.name", new ColumnMapRowMapper(), tenant);
    }

    public Map<String,Object> closureRouting(UUID tenant) {
        UUID teamId=jdbc.query("SELECT default_closure_team_id FROM operators_mst WHERE id=?",rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
        String teamName=teamId==null?null:jdbc.query("SELECT name FROM maintenance_teams_mst WHERE operator_id=? AND id=? AND team_type IN ('CLOSURE','REVIEWER') AND isactive=1",rs->rs.next()?rs.getString(1):null,tenant,teamId);
        List<Map<String,Object>> teams=jdbc.query("SELECT t.id,t.name FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.isactive=1 AND t.team_type IN ('CLOSURE','REVIEWER') AND EXISTS (SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|APPROVE|%') ORDER BY t.name",new ColumnMapRowMapper(),tenant);
        return Map.of("teamId",teamId==null?"":teamId,"teamName",teamName==null?"":teamName,"eligibleTeams",teams);
    }

    @Transactional
    public Map<String,Object> setClosureTeam(UUID tenant,String actor,UUID teamId,String reason) {
        String change=requiredReason(reason);
        if(!exists("SELECT 1 FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.id=? AND t.team_type IN ('CLOSURE','REVIEWER') AND t.isactive=1 AND EXISTS(SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|APPROVE|%')",tenant,teamId))throw new IllegalArgumentException("Choose an active Reviewer or Closure team in this organization with at least one member who has the APPROVE permission.");
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);
        UUID previous=jdbc.query("SELECT default_closure_team_id FROM operators_mst WHERE id=? FOR UPDATE",rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
        if(teamId.equals(previous))throw new IllegalArgumentException("That team is already configured for closure review.");
        jdbc.update("UPDATE operators_mst SET default_closure_team_id=?,updated_at=now() WHERE id=? AND isactive=1",teamId,tenant);
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,'CLOSURE_REVIEW_ROUTING_CONFIGURED',?,?, 'ORGANIZATION',?,jsonb_build_object('teamId',CAST(? AS text)),jsonb_build_object('teamId',CAST(? AS text)),?)",tenant,change,actor,tenant,previous==null?null:previous.toString(),teamId.toString(),MDC.get("request_id"));
        return closureRouting(tenant);
    }

    @Transactional
    public Map<String,Object> saveMapping(UUID tenant, String actor, MappingRequest request) {
        String reason = requiredReason(request.reason());
        if (!exists("SELECT 1 FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1", tenant, request.areaId()))
            throw new IllegalArgumentException("Choose an active cabin area belonging to this organization.");
        if (!eligibleTeam(tenant, request.maintenanceTeamId()))
            throw new IllegalArgumentException("Choose an active Maintenance team with at least one active member authorized to perform work.");
        UUID id = request.id() == null ? UUID.randomUUID() : request.id();
        Map<String,Object> old = request.id() == null ? null : findMapping(tenant,id);
        if (request.id() != null && old == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cabin area mapping not found.");
        try {
            if (old == null) jdbc.update("INSERT INTO area_maintenance_team_mappings_mst(id,operator_id,area_id,maintenance_team_id,created_by,updated_by,change_reason) VALUES(?,?,?,?,?,?,?)",id,tenant,request.areaId(),request.maintenanceTeamId(),actor,actor,reason);
            else jdbc.update("UPDATE area_maintenance_team_mappings_mst SET area_id=?,maintenance_team_id=?,updated_by=?,updated_at=now(),change_reason=? WHERE operator_id=? AND id=?",request.areaId(),request.maintenanceTeamId(),actor,reason,tenant,id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"An active Maintenance team mapping already exists for this cabin area.");
        }
        event(tenant,id,actor,old == null ? "AREA_TEAM_MAPPING_CREATED" : "AREA_TEAM_MAPPING_UPDATED",reason,
            old, Map.of("areaId",request.areaId().toString(),"maintenanceTeamId",request.maintenanceTeamId().toString(),"isactive",1));
        return findMapping(tenant,id);
    }

    @Transactional
    public void setMappingActive(UUID tenant,String actor,UUID id,boolean active,String reason) {
        String normalized=requiredReason(reason);
        Map<String,Object> old=findMapping(tenant,id);
        if(old==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cabin area mapping not found.");
        if(active&&(!exists("SELECT 1 FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1",tenant,old.get("areaId"))||!eligibleTeam(tenant,(UUID)old.get("maintenanceTeamId"))))
            throw new IllegalArgumentException("Reactivate the area and ensure the mapped Maintenance team has an active eligible work member before activating this mapping.");
        int target=active?1:2;
        try { jdbc.update("UPDATE area_maintenance_team_mappings_mst SET isactive=?,updated_by=?,updated_at=now(),change_reason=? WHERE operator_id=? AND id=?",target,actor,normalized,tenant,id); }
        catch(org.springframework.dao.DuplicateKeyException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"An active mapping already exists for this cabin area.");}
        event(tenant,id,actor,active?"AREA_TEAM_MAPPING_ACTIVATED":"AREA_TEAM_MAPPING_DEACTIVATED",normalized,old,Map.of("isactive",target));
    }

    public Map<String,Object> workflow(UUID tenant) {
        ensureWorkflow(tenant);
        return jdbc.queryForMap("SELECT v.id AS \"versionId\",v.version_number AS \"versionNumber\",v.assignment_mode AS \"assignmentMode\",v.created_by AS \"updatedBy\",v.created_at AS \"updatedAt\",v.change_reason AS reason FROM defect_workflow_config_mst c JOIN defect_workflow_versions_dtl v ON v.operator_id=c.operator_id AND v.id=c.active_version_id WHERE c.operator_id=?",tenant);
    }

    @Transactional
    public Map<String,Object> setWorkflow(UUID tenant,String actor,WorkflowRequest request) {
        String reason=requiredReason(request.reason());
        String mode=request.assignmentMode()==null?"":request.assignmentMode().trim().toUpperCase(java.util.Locale.ROOT);
        if(!List.of("REVIEWER_APPROVAL","DIRECT_MAINTENANCE").contains(mode))throw new IllegalArgumentException("Choose Reviewer approval or direct maintenance assignment.");
        ensureWorkflow(tenant);
        Map<String,Object> current=workflow(tenant);
        if(mode.equals(current.get("assignmentMode")))return current;
        Integer next=jdbc.queryForObject("SELECT COALESCE(max(version_number),0)+1 FROM defect_workflow_versions_dtl WHERE operator_id=?",Integer.class,tenant);
        UUID version=UUID.randomUUID();
        jdbc.update("INSERT INTO defect_workflow_versions_dtl(id,operator_id,version_number,assignment_mode,created_by,change_reason) VALUES(?,?,?,?,?,?)",version,tenant,next,mode,actor,reason);
        jdbc.update("UPDATE defect_workflow_config_mst SET active_version_id=?,updated_by=?,updated_at=now() WHERE operator_id=?",version,actor,tenant);
        event(tenant,version,actor,"DEFECT_WORKFLOW_MODE_CHANGED",reason,current,Map.of("assignmentMode",mode,"versionNumber",next));
        return workflow(tenant);
    }

    public Route route(UUID tenant,UUID areaId) {
        ensureWorkflow(tenant);
        return jdbc.query("SELECT v.id,v.assignment_mode,m.id AS mapping_id,t.id AS team_id,t.name AS team_name FROM defect_workflow_config_mst c JOIN defect_workflow_versions_dtl v ON v.operator_id=c.operator_id AND v.id=c.active_version_id LEFT JOIN area_maintenance_team_mappings_mst m ON m.operator_id=c.operator_id AND m.area_id=? AND m.isactive=1 LEFT JOIN maintenance_teams_mst t ON t.operator_id=m.operator_id AND t.id=m.maintenance_team_id AND t.isactive=1 AND t.team_type='MAINTENANCE' AND EXISTS(SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst om ON om.operator_id=mt.operator_id AND om.external_user_id=mt.external_user_id AND om.isactive=1 JOIN operator_roles_mst r ON r.operator_id=om.operator_id AND r.role_key=om.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%') WHERE c.operator_id=? FOR SHARE OF c,v",rs->rs.next()?new Route(rs.getObject("id",UUID.class),rs.getString("assignment_mode"),rs.getObject("mapping_id",UUID.class),rs.getObject("team_id",UUID.class),rs.getString("team_name")):null,areaId,tenant);
    }

    @Transactional
    public boolean assignForDirectMode(UUID tenant,UUID defectId,String actor,UUID mappingId,String teamName,String title,String severity) {
        if(mappingId==null||teamName==null)return false;
        Integer actions=jdbc.queryForObject("SELECT count(*) FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1",Integer.class,tenant,defectId);
        if(actions!=null&&actions>0)return true;
        UUID action=UUID.randomUUID();
        String priority=switch(severity){case "CRITICAL"->"CRITICAL";case "MAJOR"->"HIGH";case "MINOR"->"MEDIUM";default->"LOW";};
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_team,description,priority,status) VALUES(?,?,?,?,?,?,?,'ASSIGNED')",action,tenant,defectId,actor,teamName,"Investigate reported defect: "+title,priority);
        jdbc.update("UPDATE defects_dtl SET status='ACTION_ASSIGNED',assigned_team=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=?",teamName,tenant,defectId);
        UUID event=jdbc.query("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,new_state,request_id) VALUES(?,?,'AREA_MAINTENANCE_ROUTED','REPORTED','ACTION_ASSIGNED','Defect directly assigned using the active cabin-area mapping.',?,'DEFECT',?,jsonb_build_object('mappingId',CAST(? AS text),'teamName',?,'correctiveActionId',CAST(? AS text)),?) RETURNING id",(rs,row)->rs.getObject(1,UUID.class),tenant,defectId,actor,defectId,mappingId.toString(),teamName,action,MDC.get("request_id")).get(0);
        if(notifications!=null)notifications.notifyMaintenance(tenant,defectId,event,actor,teamName,null,"ACTION_ASSIGNED","A new defect was assigned to your maintenance team.");
        return true;
    }

    public List<Map<String,Object>> exceptions(UUID tenant) {
        return jdbc.query("SELECT d.id,d.reference,d.status,d.location,d.zone,d.title,d.reported_at FROM defects_dtl d JOIN defect_workflow_versions_dtl v ON v.operator_id=d.operator_id AND v.id=d.workflow_version_id WHERE d.operator_id=? AND d.isactive=1 AND d.status='REPORTED' AND v.assignment_mode='DIRECT_MAINTENANCE' AND NOT EXISTS(SELECT 1 FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1) ORDER BY d.reported_at",new ColumnMapRowMapper(),tenant);
    }

    @Transactional
    public boolean retry(UUID tenant,UUID defectId,String actor) {
        Map<String,Object> defect=jdbc.query("SELECT d.area_id,d.title,d.severity,d.status,v.assignment_mode FROM defects_dtl d JOIN defect_workflow_versions_dtl v ON v.operator_id=d.operator_id AND v.id=d.workflow_version_id WHERE d.operator_id=? AND d.id=? AND d.isactive=1 FOR UPDATE OF d",rs->rs.next()?Map.of("areaId",rs.getObject("area_id",UUID.class),"title",rs.getString("title"),"severity",rs.getString("severity"),"status",rs.getString("status"),"mode",rs.getString("assignment_mode")):null,tenant,defectId);
        if(defect==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");
        if(!"DIRECT_MAINTENANCE".equals(defect.get("mode"))||!"REPORTED".equals(defect.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"This defect is not awaiting direct maintenance routing.");
        Route route=route(tenant,(UUID)defect.get("areaId"));
        if(route==null||route.teamId()==null){jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,new_state,request_id) VALUES(?,? ,'AREA_MAINTENANCE_ROUTING_FAILED','No active eligible Maintenance team is mapped to this area.',?,'DEFECT',?,jsonb_build_object('actionable','Configure an eligible area mapping and retry.'),?)",tenant,defectId,actor,defectId,MDC.get("request_id"));return false;}
        return assignForDirectMode(tenant,defectId,actor,route.mappingId(),route.teamName(),String.valueOf(defect.get("title")),String.valueOf(defect.get("severity")));
    }

    private void ensureWorkflow(UUID tenant) {
        if(!exists("SELECT 1 FROM defect_workflow_config_mst WHERE operator_id=?",tenant)) {
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO defect_workflow_versions_dtl(id,operator_id,version_number,assignment_mode,created_by,change_reason) VALUES(?, ?,1,'REVIEWER_APPROVAL','system','Preserve existing Reviewer approval workflow.') ON CONFLICT(operator_id,version_number) DO NOTHING",id,tenant);
            jdbc.update("INSERT INTO defect_workflow_config_mst(operator_id,active_version_id,updated_by) SELECT operator_id,id,'system' FROM defect_workflow_versions_dtl WHERE operator_id=? AND version_number=1 ON CONFLICT(operator_id) DO NOTHING",tenant);
        }
    }
    private boolean eligibleTeam(UUID tenant,UUID team) { return team!=null&&exists("SELECT 1 FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.id=? AND t.isactive=1 AND t.team_type='MAINTENANCE' AND EXISTS(SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%')",tenant,team); }
    private boolean exists(String sql,Object...args){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS("+sql+")",Boolean.class,args));}
    private String requiredReason(String reason){if(reason==null||reason.isBlank()||reason.trim().length()>1000)throw new IllegalArgumentException("Provide a change reason of 1–1000 characters.");return reason.trim();}
    private Map<String,Object> findMapping(UUID tenant,UUID id){return jdbc.query("SELECT m.id,m.area_id AS \"areaId\",a.code AS \"areaCode\",a.name AS \"areaName\",m.maintenance_team_id AS \"maintenanceTeamId\",t.name AS \"teamName\",m.isactive,m.change_reason AS \"changeReason\",m.created_by AS \"createdBy\",m.created_at AS \"createdAt\",m.updated_by AS \"updatedBy\",m.updated_at AS \"updatedAt\" FROM area_maintenance_team_mappings_mst m JOIN cabin_areas_mst a ON a.operator_id=m.operator_id AND a.id=m.area_id JOIN maintenance_teams_mst t ON t.operator_id=m.operator_id AND t.id=m.maintenance_team_id WHERE m.operator_id=? AND m.id=?",new ColumnMapRowMapper(),tenant,id).stream().findFirst().orElse(null);}
    private void event(UUID tenant,UUID entity,String actor,String type,String reason,Object before,Object after){String entityType=type.contains("WORKFLOW")?"DEFECT_WORKFLOW":"AREA_TEAM_MAPPING";var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String previous=before==null?null:mapper.valueToTree(before).toString();String current=mapper.valueToTree(after).toString();jdbc.update("INSERT INTO defect_events_dtl(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)",tenant,type,reason,actor,entityType,entity,previous,current,MDC.get("request_id"));}
}
