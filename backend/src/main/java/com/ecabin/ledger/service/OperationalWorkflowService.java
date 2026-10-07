package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.*;

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

    public OperationalWorkflowService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public WorkflowRecord completeInspection(UUID organizationId, UUID defectId, String actorId, InspectionRequest request) {
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("INSPECTION_IN_PROGRESS")) throw new IllegalStateException("The defect is not awaiting an inspection.");
        String result = request.result().toUpperCase();
        if (!RESULTS.contains(result)) throw new IllegalArgumentException("Unsupported inspection result.");
        UUID inspectionId = UUID.randomUUID();
        Timestamp inspectedAt = Timestamp.from(java.time.Instant.now());
        jdbc.update("INSERT INTO inspections(id,operator_id,defect_id,inspector_user_id,status,result,inspected_at,findings,notes) VALUES(?,?,?,?,'COMPLETED',?,?,?,?)",
            inspectionId, organizationId, defectId, actorId, result, inspectedAt, request.findings().trim(), request.notes() == null ? null : request.notes().trim());
        String target = Set.of("FAIL", "REQUIRES_FOLLOW_UP").contains(result) ? "INSPECTION_REQUIRED" : "INSPECTION_COMPLETE";
        updateStatus(organizationId, defectId, from, target);
        audit(organizationId, defectId, "INSPECTION_COMPLETED", from, target, request.findings().trim(), "INSPECTION", inspectionId, actorId, result, null);
        return new WorkflowRecord(inspectionId, target);
    }

    @Transactional
    public WorkflowRecord assignAction(UUID organizationId, UUID defectId, String actorId, CorrectiveActionRequest request) {
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("INSPECTION_COMPLETE")) throw new IllegalStateException("A completed inspection is required before corrective action assignment.");
        String priority = request.priority().toUpperCase();
        if (!PRIORITIES.contains(priority)) throw new IllegalArgumentException("Unsupported corrective-action priority.");
        String assignedUser = request.assignedUserId() == null || request.assignedUserId().isBlank() ? null : request.assignedUserId().trim();
        String team = request.assignedTeam() == null || request.assignedTeam().isBlank() ? null : request.assignedTeam().trim();
        if (assignedUser == null && team == null) throw new IllegalArgumentException("Assign the action to a user or team.");
        if (assignedUser != null && !activeMember(organizationId, assignedUser)) throw new IllegalArgumentException("The assigned user is not an active member of this organization.");
        UUID actionId = UUID.randomUUID();
        jdbc.update("INSERT INTO corrective_actions(id,operator_id,defect_id,created_by_user_id,assigned_user_id,assigned_team,description,priority,due_at,status) VALUES(?,?,?,?,?,?,?,?,?,'ASSIGNED')",
            actionId, organizationId, defectId, actorId, assignedUser, team, request.description().trim(), priority, timestamp(request.dueAt()));
        jdbc.update("UPDATE defects SET assigned_to=?,assigned_user_id=?,assigned_team=?,due_at=?,updated_at=now() WHERE operator_id=? AND id=? AND isactive=1",
            assignedUser == null ? team : assignedUser, assignedUser, team, timestamp(request.dueAt()), organizationId, defectId);
        updateStatus(organizationId, defectId, from, "ACTION_ASSIGNED");
        audit(organizationId, defectId, "ACTION_ASSIGNED", from, "ACTION_ASSIGNED", request.description().trim(), "CORRECTIVE_ACTION", actionId, actorId, null, null);
        if (assignedUser != null) jdbc.update("INSERT INTO notifications(operator_id,recipient_user_id,defect_id,notification_type,message) VALUES(?,?,?,'ACTION_ASSIGNED',?)", organizationId, assignedUser, defectId, "A corrective action was assigned to you.");
        return new WorkflowRecord(actionId, "ASSIGNED");
    }

    @Transactional
    public WorkflowRecord startAction(UUID organizationId, UUID defectId, UUID actionId, String actorId) {
        String from = lockDefect(organizationId, defectId);
        int updated = jdbc.update("UPDATE corrective_actions ca SET status='IN_PROGRESS',updated_at=now() WHERE ca.operator_id=? AND ca.defect_id=? AND ca.id=? AND ca.isactive=1 AND ca.status='ASSIGNED' AND (ca.assigned_user_id=? OR (ca.assigned_user_id IS NULL AND EXISTS (SELECT 1 FROM operator_membership_teams mt JOIN operator_memberships m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id WHERE mt.operator_id=ca.operator_id AND mt.external_user_id=? AND mt.team_name=ca.assigned_team AND mt.isactive=1 AND m.isactive=1)))", organizationId, defectId, actionId, actorId, actorId);
        if (updated != 1) throw new IllegalStateException("The action is not assigned to you or is no longer open.");
        if (from.equals("ACTION_ASSIGNED")) updateStatus(organizationId, defectId, from, "IN_PROGRESS");
        audit(organizationId, defectId, "ACTION_STARTED", from, from.equals("ACTION_ASSIGNED") ? "IN_PROGRESS" : from, "Corrective action started.", "CORRECTIVE_ACTION", actionId, actorId, null, null);
        return new WorkflowRecord(actionId, "IN_PROGRESS");
    }

    @Transactional
    public WorkflowRecord completeAction(UUID organizationId, UUID defectId, UUID actionId, String actorId, ActionCompletionRequest request) {
        String from = lockDefect(organizationId, defectId);
        int updated = jdbc.update("UPDATE corrective_actions ca SET status='COMPLETED',completed_at=now(),completed_by_user_id=?,completion_notes=?,updated_at=now() WHERE ca.operator_id=? AND ca.defect_id=? AND ca.id=? AND ca.isactive=1 AND ca.status='IN_PROGRESS' AND (ca.assigned_user_id=? OR (ca.assigned_user_id IS NULL AND EXISTS (SELECT 1 FROM operator_membership_teams mt JOIN operator_memberships m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id WHERE mt.operator_id=ca.operator_id AND mt.external_user_id=? AND mt.team_name=ca.assigned_team AND mt.isactive=1 AND m.isactive=1)))", actorId, request.completionNotes().trim(), organizationId, defectId, actionId, actorId, actorId);
        if (updated != 1) throw new IllegalStateException("The action is not in progress for you.");
        updateStatus(organizationId, defectId, from, "AWAITING_VERIFICATION");
        audit(organizationId, defectId, "ACTION_COMPLETED", from, "AWAITING_VERIFICATION", request.completionNotes().trim(), "CORRECTIVE_ACTION", actionId, actorId, null, null);
        return new WorkflowRecord(actionId, "COMPLETED");
    }

    @Transactional
    public WorkflowRecord verify(UUID organizationId, UUID defectId, String actorId, VerificationRequest request) {
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("AWAITING_VERIFICATION")) throw new IllegalStateException("The defect is not awaiting verification.");
        String result = request.result().toUpperCase();
        if (!Set.of("PASS", "FAIL").contains(result)) throw new IllegalArgumentException("Verification result must be PASS or FAIL.");
        boolean samePerson = jdbc.query("SELECT 1 FROM corrective_actions WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='COMPLETED' AND (completed_by_user_id=? OR created_by_user_id=?) LIMIT 1", (ResultSetExtractor<Boolean>) rs -> rs.next(), organizationId, defectId, actorId, actorId);
        if (samePerson) throw new AccessDeniedException("The corrective-action author or technician cannot verify their own work.");
        UUID verificationId = UUID.randomUUID();
        jdbc.update("INSERT INTO verifications(id,operator_id,defect_id,verifier_user_id,result,notes) VALUES(?,?,?,?,?,?)", verificationId, organizationId, defectId, actorId, result, request.notes().trim());
        String target = result.equals("PASS") ? "VERIFIED" : "IN_PROGRESS";
        updateStatus(organizationId, defectId, from, target);
        audit(organizationId, defectId, "VERIFICATION_COMPLETED", from, target, request.notes().trim(), "VERIFICATION", verificationId, actorId, result, request.evidenceReference().trim());
        return new WorkflowRecord(verificationId, result);
    }

    @Transactional
    public WorkflowRecord approve(UUID organizationId, UUID defectId, String actorId, ApprovalRequest request) {
        String from = lockDefect(organizationId, defectId);
        if (!from.equals("APPROVAL_REQUIRED")) throw new IllegalStateException("The defect is not awaiting closure approval.");
        String decision = request.decision().toUpperCase();
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) throw new IllegalArgumentException("Decision must be APPROVED or REJECTED.");
        String verifier = jdbc.query("SELECT verifier_user_id FROM verifications WHERE operator_id=? AND defect_id=? AND isactive=1 ORDER BY verified_at DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null, organizationId, defectId);
        if (actorId.equals(verifier)) throw new AccessDeniedException("The verifier cannot approve the same closure.");
        String evidence = request.evidenceReference() == null ? null : request.evidenceReference().trim();
        if (decision.equals("APPROVED") && (evidence == null || evidence.isBlank())) throw new IllegalArgumentException("A closure evidence reference is required for approval.");
        UUID approvalId = UUID.randomUUID();
        jdbc.update("INSERT INTO closure_approvals(id,operator_id,defect_id,approver_user_id,decision,reason) VALUES(?,?,?,?,?,?)", approvalId, organizationId, defectId, actorId, decision, request.reason().trim());
        String target = decision.equals("APPROVED") ? "CLOSED" : "IN_PROGRESS";
        updateStatus(organizationId, defectId, from, target);
        audit(organizationId, defectId, "CLOSURE_" + decision, from, target, request.reason().trim(), "CLOSURE_APPROVAL", approvalId, actorId, null, evidence);
        return new WorkflowRecord(approvalId, decision);
    }

    private String lockDefect(UUID organizationId, UUID defectId) {
        return jdbc.query("SELECT status FROM defects WHERE operator_id=? AND id=? AND isactive=1 FOR UPDATE", rs -> {
            if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Defect not found.");
            return rs.getString(1);
        }, organizationId, defectId);
    }

    private void updateStatus(UUID org, UUID defectId, String from, String to) {
        DefectWorkflow.requireTransition(from, to, to.equals("REOPENED") ? "reopened" : "workflow");
        jdbc.update("UPDATE defects SET status=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=? AND isactive=1", to, org, defectId);
    }

    private boolean activeMember(UUID org, String externalId) {
        return jdbc.query("SELECT 1 FROM operator_memberships WHERE operator_id=? AND external_user_id=? AND isactive=1", (ResultSetExtractor<Boolean>) rs -> rs.next(), org, externalId);
    }

    private Timestamp timestamp(java.time.Instant value) { return value == null ? null : Timestamp.from(value); }

    private void audit(UUID org, UUID defectId, String type, String from, String to, String note, String entityType, UUID entityId, String actor, String result, String evidence) {
        jdbc.update("INSERT INTO defect_events(operator_id,defect_id,event_type,from_status,to_status,note,evidence_reference,inspection_outcome,actor_id,entity_type,entity_id,previous_state,new_state,request_id) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,jsonb_build_object('status',?),jsonb_build_object('status',?),?)",
            org, defectId, type, from, to, note, evidence, result, actor, entityType, entityId, from, to, safeRequestId());
    }

    private UUID safeRequestId() {
        try { return UUID.fromString(MDC.get("requestId")); } catch (RuntimeException ex) { return null; }
    }
}
