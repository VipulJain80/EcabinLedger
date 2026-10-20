package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.*;
import com.ecabin.ledger.api.FormModels.*;

import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class OperationalWorkflowService {
    private static final Set<String> RESULTS = Set.of("PASS", "FAIL", "CONDITIONAL", "REQUIRES_FOLLOW_UP");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final ClosureReviewAccessService closureAccess;
    private final OperationalFormService forms;
    private com.ecabin.ledger.email.EmailOutboxService emailOutbox;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmailOutbox(com.ecabin.ledger.email.EmailOutboxService emailOutbox){this.emailOutbox=emailOutbox;}

    @org.springframework.beans.factory.annotation.Autowired
    public OperationalWorkflowService(JdbcTemplate jdbc, NotificationService notifications, ClosureReviewAccessService closureAccess, OperationalFormService forms) { this.jdbc = jdbc; this.notifications = notifications; this.closureAccess=closureAccess; this.forms=forms; }
    /** Compatibility constructor for isolated legacy unit tests. */
    public OperationalWorkflowService(JdbcTemplate jdbc, NotificationService notifications) { this(jdbc,notifications,null,null); }
    public OperationalWorkflowService(JdbcTemplate jdbc) { this(jdbc, null,null,null); }

    @Transactional
    public WorkflowRecord completeInspection(UUID organizationId, UUID defectId, String actorId, InspectionRequest request) {
        auditActor(actorId);
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("INSPECTION_IN_PROGRESS")) throw new IllegalStateException("The defect is not awaiting an inspection.");
        String result = request.result().toUpperCase();
        if (!RESULTS.contains(result)) throw new IllegalArgumentException("Unsupported inspection result.");
        UUID inspectionId = UUID.randomUUID();
        Timestamp inspectedAt = Timestamp.from(java.time.Instant.now());
        jdbc.update("INSERT INTO inspections_dtl(id,operator_id,defect_id,inspector_user_id,status,result,inspected_at,findings,notes) VALUES(?,?,?,?,'COMPLETED',?,?,?,?)",
            inspectionId, organizationId, defectId, actorId, result, inspectedAt, request.findings().trim(), request.notes() == null ? null : request.notes().trim());
        String target = result.equals("PASS") ? "INSPECTION_COMPLETE" : "INSPECTION_REQUIRED";
        updateStatus(organizationId, defectId, from, target);
        UUID inspectionEventId = audit(organizationId, defectId, "INSPECTION_COMPLETED", from, target, request.findings().trim(), "INSPECTION", inspectionId, actorId, result, null);
        if (notifications != null) notifications.notifyReviewers(organizationId, defectId, inspectionEventId, actorId, "INSPECTION_COMPLETED", "An inspection result was recorded for a routed cabin defect.");
        if (emailOutbox != null) emailOutbox.enqueue(organizationId,defectId,inspectionEventId,actorId,"INSPECTION_COMPLETED",null,null);
        if (target.equals("INSPECTION_COMPLETE")) {
            String approvedMaintenanceTeam = jdbc.query("SELECT new_state->>'maintenanceTeam' FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND event_type='REVIEW_APPROVED' ORDER BY occurred_at DESC,id DESC LIMIT 1",rs->rs.next()?rs.getString(1):null,organizationId,defectId);
            if (approvedMaintenanceTeam != null && activeMaintenanceTeamWithMember(organizationId, approvedMaintenanceTeam)) {
                String[] defect = jdbc.query("SELECT title,severity FROM defects_dtl WHERE operator_id=? AND id=?",rs->rs.next()?new String[]{rs.getString(1),rs.getString(2)}:new String[]{"Cabin corrective action","MEDIUM"},organizationId,defectId);
                UUID actionId=UUID.randomUUID();
                String priority=Set.of("HIGH","CRITICAL").contains(defect[1])?defect[1]:"MEDIUM";
                String description="Review-approved cabin defect: "+defect[0];
                jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_team,description,priority,status) VALUES(?,?,?,?,?,?,?,'ASSIGNED')",actionId,organizationId,defectId,actorId,approvedMaintenanceTeam,description,priority);
                jdbc.update("UPDATE defects_dtl SET assigned_to=?,assigned_user_id=NULL,assigned_team=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND isactive=1",approvedMaintenanceTeam,approvedMaintenanceTeam,organizationId,defectId);
                updateStatus(organizationId,defectId,"INSPECTION_COMPLETE","ACTION_ASSIGNED");
                UUID actionEventId = insertEvent("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,?,'ACTION_ASSIGNED','INSPECTION_COMPLETE','ACTION_ASSIGNED',?,?,'CORRECTIVE_ACTION',?,jsonb_build_object('status','INSPECTION_COMPLETE'),jsonb_build_object('status','ACTION_ASSIGNED','team',?::varchar,'correctiveActionId',?),?)",organizationId,defectId,description,actorId,actionId,approvedMaintenanceTeam,actionId,safeRequestId());
                notifyAssignedTeam(organizationId,defectId,approvedMaintenanceTeam,null,actionEventId,actorId);
                if(emailOutbox!=null)emailOutbox.enqueue(organizationId,defectId,actionEventId,actorId,"ACTION_ASSIGNED",approvedMaintenanceTeam,null);
                target="ACTION_ASSIGNED";
            } else if (approvedMaintenanceTeam != null) {
                jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,new_state,request_id) VALUES(?,?,'MAINTENANCE_ROUTING_FAILED','INSPECTION_COMPLETE','INSPECTION_COMPLETE','Approved Cabin Maintenance team is no longer active or has no active maintenance members.',?,'DEFECT',?,jsonb_build_object('maintenanceTeam',?::varchar,'actionable','Organization administrator must configure a valid maintenance route and assign corrective action.'),?)",organizationId,defectId,actorId,defectId,approvedMaintenanceTeam,safeRequestId());
            }
        }
        return new WorkflowRecord(inspectionId, target);
    }

    @Transactional
    public WorkflowRecord assignAction(UUID organizationId, UUID defectId, String actorId, CorrectiveActionRequest request) {
        auditActor(actorId);
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("INSPECTION_COMPLETE")) throw new IllegalStateException("A completed inspection is required before corrective action assignment.");
        String priority = request.priority().toUpperCase();
        if (!PRIORITIES.contains(priority)) throw new IllegalArgumentException("Unsupported corrective-action priority.");
        String assignedUser = request.assignedUserId() == null || request.assignedUserId().isBlank() ? null : request.assignedUserId().trim();
        String team = request.assignedTeam() == null || request.assignedTeam().isBlank() ? null : request.assignedTeam().trim();
        if (assignedUser == null && team == null) throw new IllegalArgumentException("Assign the action to a user or team.");
        if (assignedUser != null && !activeMember(organizationId, assignedUser)) throw new IllegalArgumentException("The assigned user is not an active member of this organization.");
        if (team != null && !activeTeam(organizationId, team)) throw new IllegalArgumentException("Choose an active maintenance team in this organization.");
        if (team != null && assignedUser != null && !activeTeamMember(organizationId, team, assignedUser)) throw new IllegalArgumentException("The individual assignee must be an active member of the selected maintenance team.");
        UUID actionId = UUID.randomUUID();
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_user_id,assigned_team,description,priority,due_at,status) VALUES(?,?,?,?,?,?,?,?,?,'ASSIGNED')",
            actionId, organizationId, defectId, actorId, assignedUser, team, request.description().trim(), priority, timestamp(request.dueAt()));
        jdbc.update("UPDATE defects_dtl SET assigned_to=?,assigned_user_id=?,assigned_team=?,due_at=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND isactive=1",
            assignedUser == null ? team : assignedUser, assignedUser, team, timestamp(request.dueAt()), organizationId, defectId);
        updateStatus(organizationId, defectId, from, "ACTION_ASSIGNED");
        UUID eventId = insertEvent("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) " +
            "VALUES(?,?,'ACTION_ASSIGNED',?,'ACTION_ASSIGNED',?,?,'CORRECTIVE_ACTION',?,jsonb_build_object('status',?),jsonb_build_object('status','ACTION_ASSIGNED','team',?::varchar,'assignedUserId',?::varchar,'correctiveActionId',?),?)",
            organizationId, defectId, from, request.description().trim(), actorId, actionId, from, team, assignedUser, actionId, safeRequestId());
        notifyAssignedTeam(organizationId, defectId, team, assignedUser,eventId,actorId);
        if(emailOutbox!=null)emailOutbox.enqueue(organizationId,defectId,eventId,actorId,"ACTION_ASSIGNED",team,assignedUser);
        return new WorkflowRecord(actionId, "ASSIGNED");
    }

    @Transactional
    public WorkflowRecord reassignAction(UUID organizationId, UUID defectId, UUID actionId, String actorId, CorrectiveActionReassignmentRequest request) {
        auditActor(actorId);
        String from = lockDefect(organizationId, defectId);
        if (!Set.of("ACTION_ASSIGNED", "IN_PROGRESS").contains(from)) throw new IllegalStateException("Only an active corrective action can be reassigned.");
        ActionAssignment previous = jdbc.query("SELECT id,assigned_team,assigned_user_id,description,priority,due_at FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') FOR UPDATE",
            (rs, row) -> new ActionAssignment(rs.getObject("id", UUID.class), rs.getString("assigned_team"), rs.getString("assigned_user_id"), rs.getString("description"), rs.getString("priority"), rs.getTimestamp("due_at")), organizationId, defectId, actionId)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active corrective action was not found."));
        String newTeam = request.assignedTeam().trim();
        String newUser = request.assignedUserId() == null || request.assignedUserId().isBlank() ? null : request.assignedUserId().trim();
        if (!activeTeam(organizationId, newTeam)) throw new IllegalArgumentException("Choose an active maintenance team in this organization.");
        if (newUser != null && !activeTeamMember(organizationId, newTeam, newUser)) throw new IllegalArgumentException("The individual assignee must be an active member of the selected maintenance team.");
        if (newTeam.equals(previous.team()) && java.util.Objects.equals(newUser, previous.assignedUser())) throw new IllegalArgumentException("Choose a different team or individual assignee.");

        int cancelled = jdbc.update("UPDATE corrective_actions_dtl SET status='CANCELLED',updated_at=now() WHERE operator_id=? AND defect_id=? AND id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD')",
            organizationId, defectId, actionId);
        if (cancelled != 1) throw new IllegalStateException("The corrective action changed while it was being reassigned. Refresh and try again.");
        UUID replacementId = UUID.randomUUID();
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_user_id,assigned_team,description,priority,due_at,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,'ASSIGNED',clock_timestamp())",
            replacementId, organizationId, defectId, actorId, newUser, newTeam, previous.description(), previous.priority(), previous.dueAt());
        jdbc.update("UPDATE defects_dtl SET assigned_to=?,assigned_user_id=?,assigned_team=?,due_at=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND isactive=1",
            newUser == null ? newTeam : newUser, newUser, newTeam, previous.dueAt(), organizationId, defectId);
        if (from.equals("IN_PROGRESS")) updateStatus(organizationId, defectId, from, "ACTION_ASSIGNED");
        UUID eventId = insertEvent("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) " +
            "VALUES(?,?,'ACTION_REASSIGNED',?,'ACTION_ASSIGNED',?,?,'CORRECTIVE_ACTION',?,jsonb_build_object('team',?::varchar,'assignedUserId',?::varchar,'correctiveActionId',?),jsonb_build_object('team',?::varchar,'assignedUserId',?::varchar,'correctiveActionId',?,'status','ACTION_ASSIGNED'),?)",
            organizationId, defectId, from, request.reason().trim(), actorId, replacementId, previous.team(), previous.assignedUser(), previous.id(), newTeam, newUser, replacementId, safeRequestId());
        notifyAssignedTeam(organizationId, defectId, newTeam, newUser,eventId,actorId);
        if(emailOutbox!=null)emailOutbox.enqueue(organizationId,defectId,eventId,actorId,"ACTION_ASSIGNED",newTeam,newUser);
        return new WorkflowRecord(replacementId, "ASSIGNED");
    }

    @Transactional
    public WorkflowRecord startAction(UUID organizationId, UUID defectId, UUID actionId, String actorId) {
        auditActor(actorId);
        String from = lockDefect(organizationId, defectId);
        int updated = jdbc.update("UPDATE corrective_actions_dtl ca SET status='IN_PROGRESS',updated_at=now() WHERE ca.operator_id=? AND ca.defect_id=? AND ca.id=? AND ca.isactive=1 AND ca.status='ASSIGNED' AND ((ca.assigned_team IS NULL AND ca.assigned_user_id=?) OR (ca.assigned_team IS NOT NULL AND EXISTS (SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=mt.operator_id AND t.name=mt.team_name AND t.isactive=1 WHERE mt.operator_id=ca.operator_id AND mt.external_user_id=? AND mt.team_name=ca.assigned_team AND mt.isactive=1 AND (ca.assigned_user_id IS NULL OR ca.assigned_user_id=?))))", organizationId, defectId, actionId, actorId, actorId, actorId);
        if (updated != 1) throw new IllegalStateException("The action is not assigned to you or is no longer open.");
        if (from.equals("ACTION_ASSIGNED")) updateStatus(organizationId, defectId, from, "IN_PROGRESS");
        UUID eventId = audit(organizationId, defectId, "ACTION_STARTED", from, from.equals("ACTION_ASSIGNED") ? "IN_PROGRESS" : from, "Corrective action started.", "CORRECTIVE_ACTION", actionId, actorId, null, null);
        if (notifications != null) notifications.notifyReviewers(organizationId, defectId, eventId, actorId, "ACTION_STARTED", "Maintenance started the assigned corrective work.");
        if(emailOutbox!=null)emailOutbox.enqueue(organizationId,defectId,eventId,actorId,"ACTION_STARTED",null,null);
        return new WorkflowRecord(actionId, "IN_PROGRESS");
    }

    @Transactional
    public WorkflowRecord completeAction(UUID organizationId, UUID defectId, UUID actionId, String actorId, ActionCompletionRequest request) {
        auditActor(actorId);
        String from = lockDefect(organizationId, defectId);
        int updated = jdbc.update("UPDATE corrective_actions_dtl ca SET status='COMPLETED',completed_at=now(),completed_by_user_id=?,completion_notes=?,updated_at=now() WHERE ca.operator_id=? AND ca.defect_id=? AND ca.id=? AND ca.isactive=1 AND ca.status='IN_PROGRESS' AND ((ca.assigned_team IS NULL AND ca.assigned_user_id=?) OR (ca.assigned_team IS NOT NULL AND EXISTS (SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=mt.operator_id AND t.name=mt.team_name AND t.isactive=1 WHERE mt.operator_id=ca.operator_id AND mt.external_user_id=? AND mt.team_name=ca.assigned_team AND mt.isactive=1 AND (ca.assigned_user_id IS NULL OR ca.assigned_user_id=?))))", actorId, request.completionNotes().trim(), organizationId, defectId, actionId, actorId, actorId, actorId);
        if (updated != 1) throw new IllegalStateException("The action is not in progress for you.");
        updateStatus(organizationId, defectId, from, "AWAITING_VERIFICATION");
        UUID eventId = audit(organizationId, defectId, "ACTION_COMPLETED", from, "AWAITING_VERIFICATION", request.completionNotes().trim(), "CORRECTIVE_ACTION", actionId, actorId, null, null);
        if (notifications != null) notifications.notifyReviewers(organizationId, defectId, eventId, actorId, "ACTION_COMPLETED", "Maintenance completed corrective work; independent verification is required.");
        if(emailOutbox!=null)emailOutbox.enqueue(organizationId,defectId,eventId,actorId,"ACTION_COMPLETED",null,null);
        return new WorkflowRecord(actionId, "COMPLETED");
    }

    @Transactional
    public WorkflowRecord verify(UUID organizationId, UUID defectId, String actorId, VerificationRequest request) {
        auditActor(actorId);
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("AWAITING_VERIFICATION")) throw new IllegalStateException("The defect is not awaiting verification.");
        String result = request.result().toUpperCase();
        if (!Set.of("PASS", "FAIL").contains(result)) throw new IllegalArgumentException("Verification result must be PASS or FAIL.");
        boolean samePerson = jdbc.query("SELECT 1 FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='COMPLETED' AND (completed_by_user_id=? OR created_by_user_id=?) LIMIT 1", (ResultSetExtractor<Boolean>) rs -> rs.next(), organizationId, defectId, actorId, actorId);
        if (samePerson) throw new AccessDeniedException("The corrective-action author or technician cannot verify their own work.");
        UUID verificationId = UUID.randomUUID();
        jdbc.update("INSERT INTO verifications_dtl(id,operator_id,defect_id,verifier_user_id,result,notes) VALUES(?,?,?,?,?,?)", verificationId, organizationId, defectId, actorId, result, request.notes().trim());
        String target = result.equals("PASS") ? "VERIFIED" : "IN_PROGRESS";
        if(result.equals("FAIL")) returnCompletedCorrectiveAction(organizationId,defectId,actorId);
        updateStatus(organizationId, defectId, from, target);
        UUID eventId = audit(organizationId, defectId, "VERIFICATION_COMPLETED", from, target, request.notes().trim(), "VERIFICATION", verificationId, actorId, result, request.evidenceReference().trim());
        if (notifications != null) notifications.notifyParticipants(organizationId, defectId, eventId, actorId, result.equals("PASS") ? "WORK_VERIFIED" : "VERIFICATION_REJECTED", result.equals("PASS") ? "Corrective work passed independent verification." : "Independent verification failed; further corrective work is required.");
        if(emailOutbox!=null)emailOutbox.enqueue(organizationId,defectId,eventId,actorId,result.equals("PASS")?"WORK_VERIFIED":"VERIFICATION_REJECTED",null,null);
        return new WorkflowRecord(verificationId, result);
    }

    @Transactional
    public WorkflowRecord approve(UUID organizationId, UUID defectId, String actorId, ApprovalRequest request) {
        auditActor(actorId);
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("APPROVAL_REQUIRED")) throw new IllegalStateException("The defect is not awaiting closure approval.");
        String areaCode=jdbc.query("SELECT a.code FROM defects_dtl d JOIN cabin_areas_mst a ON a.operator_id=d.operator_id AND a.id=d.area_id WHERE d.operator_id=? AND d.id=?",rs->rs.next()?rs.getString(1):null,organizationId,defectId);
        String submittedDecision=request.decision().trim().toUpperCase(java.util.Locale.ROOT);
        if(!Set.of("ACCEPT","DECLINE").contains(submittedDecision))throw new IllegalArgumentException("Decision must be ACCEPT or DECLINE.");
        String decision=ClosureDecisionRules.normalize(submittedDecision);
        String reason=ClosureDecisionRules.validateReason(decision,request.reason());
        boolean hasForm=request.formId()!=null||request.versionId()!=null;
        if((request.formId()==null)!=(request.versionId()==null))throw new IllegalArgumentException("Select both the published form and its version.");
        UUID closureTeamId=null;
        if("GALLEY".equals(areaCode)||hasForm){
            if(closureAccess==null||forms==null)throw new AccessDeniedException("Closure review is not configured for this application instance.");
            closureTeamId=closureAccess.requireEligibleReviewer(organizationId,actorId);
            if(!hasForm)throw new IllegalArgumentException("Select the published Closure Review form before submitting a decision.");
            UUID defectAreaId=jdbc.query("SELECT area_id FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1",rs->rs.next()?rs.getObject(1,UUID.class):null,organizationId,defectId);
            if(defectAreaId==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Defect not found.");
            FormForArea selected=forms.published(organizationId,request.formId(),request.versionId(),defectAreaId);
            if(selected.purpose()!=FormPurpose.CLOSURE_APPROVAL||selected.workflowStage()!=WorkflowStage.APPROVAL_REQUIRED)throw new IllegalArgumentException("The selected published form is not eligible for closure review.");
            ClosureApprovalFormRules.requireValid(selected.configuration());
            boolean eligible=forms.publishedForDefect(organizationId,defectId,FormPurpose.CLOSURE_APPROVAL).stream().anyMatch(form->form.id().equals(selected.id())&&form.versionId().equals(selected.versionId()));
            if(!eligible)throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected closure form version is stale or not eligible for this defect.");
        }
        String verifier = jdbc.query("SELECT verifier_user_id FROM verifications_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 ORDER BY verified_at DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null, organizationId, defectId);
        if (actorId.equals(verifier)) throw new AccessDeniedException("The verifier cannot approve the same closure.");
        String evidence = request.evidenceReference() == null ? null : request.evidenceReference().trim();
        UUID approvalId = UUID.randomUUID();
        jdbc.update("INSERT INTO closure_approvals_dtl(id,operator_id,defect_id,approver_user_id,decision,reason) VALUES(?,?,?,?,?,?)", approvalId, organizationId, defectId, actorId, decision, reason);
        String target = decision.equals("APPROVED") ? "CLOSED" : "IN_PROGRESS";
        UUID returnedActionId=decision.equals("REJECTED")?returnCompletedCorrectiveAction(organizationId,defectId,actorId):null;
        updateStatus(organizationId, defectId, from, target);
        UUID eventId=insertEvent("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,evidence_reference,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?,?,?,?,?,?,?,?,?, ?,jsonb_build_object('status',?),jsonb_build_object('status',?,'decision',?,'formId',?::text,'versionId',?::text,'closureTeamId',?::text,'returnedActionId',?::text),?)",organizationId,defectId,"CLOSURE_"+decision,from,target,reason,evidence,actorId,"CLOSURE_APPROVAL",approvalId,from,target,submittedDecision,request.formId()==null?null:request.formId().toString(),request.versionId()==null?null:request.versionId().toString(),closureTeamId==null?null:closureTeamId.toString(),returnedActionId==null?null:returnedActionId.toString(),safeRequestId());
        if (notifications != null) notifications.notifyParticipants(organizationId, defectId, eventId, actorId, decision.equals("APPROVED") ? "CLOSURE_APPROVED" : "CLOSURE_REJECTED", decision.equals("APPROVED") ? "Defect closure was approved." : "Defect closure was rejected; corrective work must continue.");
        if(emailOutbox!=null){emailOutbox.enqueue(organizationId,defectId,eventId,actorId,decision.equals("APPROVED")?"CLOSURE_APPROVED":"CLOSURE_REJECTED",null,null);if(decision.equals("REJECTED")&&returnedActionId!=null){ActionAssignment assignment=jdbc.query("SELECT assigned_team,assigned_user_id FROM corrective_actions_dtl WHERE operator_id=? AND id=?",(rs,n)->new ActionAssignment(null,rs.getString("assigned_team"),rs.getString("assigned_user_id"),null,null,null),organizationId,returnedActionId).get(0);emailOutbox.enqueue(organizationId,defectId,eventId,actorId,"ACTION_ASSIGNED",assignment.team(),assignment.assignedUser());}}
        if(decision.equals("REJECTED")&&returnedActionId!=null){ActionAssignment assignment=jdbc.query("SELECT id,assigned_team,assigned_user_id,description,priority,due_at FROM corrective_actions_dtl WHERE operator_id=? AND id=?",(rs,n)->new ActionAssignment(rs.getObject("id",UUID.class),rs.getString("assigned_team"),rs.getString("assigned_user_id"),rs.getString("description"),rs.getString("priority"),rs.getTimestamp("due_at")),organizationId,returnedActionId).get(0);notifyAssignedTeam(organizationId,defectId,assignment.team(),assignment.assignedUser(),eventId,actorId);}
        return new WorkflowRecord(approvalId, target);
    }

    private String lockDefect(UUID organizationId, UUID defectId) {
        return jdbc.query("SELECT status FROM defects_dtl WHERE operator_id=? AND id=? AND isactive=1 FOR UPDATE", rs -> {
            if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Defect not found.");
            return rs.getString(1);
        }, organizationId, defectId);
    }

    private void updateStatus(UUID org, UUID defectId, String from, String to) {
        DefectWorkflow.requireTransition(from, to, to.equals("REOPENED") ? "reopened" : "workflow");
        jdbc.update("UPDATE defects_dtl SET status=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND isactive=1", to, org, defectId);
    }

    private boolean activeMember(UUID org, String externalId) {
        return jdbc.query("SELECT 1 FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=? AND isactive=1", (ResultSetExtractor<Boolean>) rs -> rs.next(), org, externalId);
    }

    private boolean activeTeam(UUID org, String teamName) {
        return jdbc.query("SELECT 1 FROM maintenance_teams_mst WHERE operator_id=? AND name=? AND team_type='MAINTENANCE' AND isactive=1", (ResultSetExtractor<Boolean>) rs -> rs.next(), org, teamName);
    }

    private boolean activeTeamMember(UUID org, String teamName, String externalId) {
        return jdbc.query("SELECT 1 FROM operator_membership_teams_mst omt JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=omt.operator_id AND t.name=omt.team_name AND t.team_type='MAINTENANCE' AND t.isactive=1 WHERE omt.operator_id=? AND omt.team_name=? AND omt.external_user_id=? AND omt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%'", (ResultSetExtractor<Boolean>) rs -> rs.next(), org, teamName, externalId);
    }

    private boolean activeMaintenanceTeamWithMember(UUID org,String teamName){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM maintenance_teams_mst t WHERE t.operator_id=? AND t.name=? AND t.team_type='MAINTENANCE' AND t.isactive=1 AND EXISTS(SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%'))",Boolean.class,org,teamName));}

    /** Return rejected completed work to its existing assignment; the decision remains in immutable audit history. */
    private UUID returnCompletedCorrectiveAction(UUID org,UUID defectId,String actor){
        ActionAssignment prior=jdbc.query("SELECT id,assigned_team,assigned_user_id,description,priority,due_at FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='COMPLETED' ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE",(rs,n)->new ActionAssignment(rs.getObject("id",UUID.class),rs.getString("assigned_team"),rs.getString("assigned_user_id"),rs.getString("description"),rs.getString("priority"),rs.getTimestamp("due_at")),org,defectId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"No completed maintenance action is available to return to its assigned team."));
        UUID actionId=UUID.randomUUID();
        jdbc.update("INSERT INTO corrective_actions_dtl(id,operator_id,defect_id,created_by_user_id,assigned_user_id,assigned_team,description,priority,due_at,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,'IN_PROGRESS',clock_timestamp())",actionId,org,defectId,actor,prior.assignedUser(),prior.team(),prior.description(),prior.priority(),prior.dueAt());
        jdbc.update("UPDATE defects_dtl SET assigned_to=?,assigned_user_id=?,assigned_team=?,due_at=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND isactive=1",prior.assignedUser()==null?prior.team():prior.assignedUser(),prior.assignedUser(),prior.team(),prior.dueAt(),org,defectId);
        return actionId;
    }

    private record ActionAssignment(UUID id,String team,String assignedUser,String description,String priority,Timestamp dueAt){}

    /** One in-app assignment notification per eligible recipient; UNION removes duplicate individual/team recipients. */
    private void notifyAssignedTeam(UUID org, UUID defectId, String teamName, String assignedUser, UUID eventId, String actor) {
        if (teamName == null && assignedUser == null) return;
        if (notifications != null) notifications.notifyMaintenance(org,defectId,eventId,actor,teamName,assignedUser,"ACTION_ASSIGNED","A corrective action was assigned to your maintenance team.");
    }

    private Timestamp timestamp(java.time.Instant value) { return value == null ? null : Timestamp.from(value); }

    private void auditActor(String actorId) { jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)", String.class, actorId); }

    private UUID audit(UUID org, UUID defectId, String type, String from, String to, String note, String entityType, UUID entityId, String actor, String result, String evidence) {
        return insertEvent("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,evidence_reference,inspection_outcome,actor_id,entity_type,entity_id,previous_state,new_state,request_id) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,jsonb_build_object('status',?),jsonb_build_object('status',?),?)",
            org, defectId, type, from, to, note, evidence, result, actor, entityType, entityId, from, to, safeRequestId());
    }

    private UUID insertEvent(String sql, Object... args) {
        return jdbc.query(sql + " RETURNING id", (rs, row) -> rs.getObject(1, UUID.class), args).get(0);
    }

    private UUID safeRequestId() {
        try { return UUID.fromString(MDC.get("requestId")); } catch (RuntimeException ex) { return null; }
    }
}
