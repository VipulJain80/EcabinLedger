package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefectService {
    private static final Set<String> LOCATIONS = Set.of("CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> CATEGORIES = Set.of("CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT", "IFE", "SEAT", "LIGHTING", "OVERHEAD_BIN", "PSU", "EMERGENCY_EQUIPMENT", "OTHER");
    private static final RowMapper<DefectView> DEFECT = (rs, row) -> {
        int rowNumber = rs.getInt("row_number");
        boolean rowNumberNull = rs.wasNull();
        var dueAt = rs.getTimestamp("due_at");
        return new DefectView(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("tail_number"),
            rs.getString("aircraft_type"), rs.getString("location"), rs.getString("category"), rs.getString("zone"),
            rowNumberNull ? null : rowNumber, rs.getString("seat_reference"), rs.getString("component"),
            rs.getString("title"), rs.getString("description"), rs.getString("severity"),
            rs.getString("status"), rs.getString("reported_by"), rs.getString("assigned_to"),
            rs.getObject("active_action_id", UUID.class), dueAt == null ? null : dueAt.toInstant(), rs.getTimestamp("reported_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    };
    private final JdbcTemplate jdbc;

    public DefectService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public DefectPage list(UUID operatorId, String status, String location, String cursor, int limit) {
        if (status != null && !Set.of("REPORTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE", "ACTION_ASSIGNED", "IN_PROGRESS", "AWAITING_VERIFICATION", "VERIFIED", "APPROVAL_REQUIRED", "CLOSED", "REJECTED", "CANCELLED", "REOPENED").contains(status)) throw new IllegalArgumentException("Unsupported status filter.");
        if (location != null && !LOCATIONS.contains(location)) throw new IllegalArgumentException("Unsupported location filter.");
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
                before = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid page cursor."); }
        }
        String sql = "SELECT d.*, a.tail_number, a.aircraft_type, current_action.id AS active_action_id FROM defects d JOIN aircraft a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id " +
            "LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS') ORDER BY ca.created_at DESC LIMIT 1) current_action ON TRUE " +
            "WHERE d.operator_id=? AND d.isactive=1 AND a.isactive=1 AND (CAST(? AS varchar) IS NULL OR d.status=?) AND (CAST(? AS varchar) IS NULL OR d.location=?) " +
            "AND (CAST(? AS timestamptz) IS NULL OR (d.reported_at,d.id) < (CAST(? AS timestamptz),CAST(? AS uuid))) ORDER BY d.reported_at DESC,d.id DESC LIMIT ?";
        List<DefectView> results = jdbc.query(sql, DEFECT, operatorId, status, status, location, location, before, before, beforeId, Math.min(Math.max(limit, 1), 100) + 1);
        boolean more = results.size() > Math.min(Math.max(limit, 1), 100);
        List<DefectView> items = more ? results.subList(0, results.size() - 1) : results;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString((items.get(items.size()-1).reportedAt()+"|"+items.get(items.size()-1).id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new DefectPage(items, next);
    }

    public DashboardSummary summary(UUID operatorId) {
        return jdbc.queryForObject("SELECT " +
            "count(*) FILTER (WHERE status NOT IN ('RESOLVED','CLOSED')) AS open_defects, " +
            "count(*) FILTER (WHERE severity='CRITICAL' AND status NOT IN ('RESOLVED','CLOSED')) AS critical_defects, " +
            "count(*) FILTER (WHERE reported_at >= date_trunc('day',now())) AS reported_today, " +
            "count(*) FILTER (WHERE status='CLOSED' AND updated_at >= date_trunc('month',now())) AS closed_this_month " +
            "FROM defects WHERE operator_id=? AND isactive=1", (rs, row) -> new DashboardSummary(rs.getLong("open_defects"), rs.getLong("critical_defects"), rs.getLong("reported_today"), rs.getLong("closed_this_month")), operatorId);
    }

    public String organizationName(UUID operatorId) {
        return jdbc.query("SELECT name FROM operators WHERE id=? AND isactive=1", rs -> rs.next() ? rs.getString(1) : null, operatorId);
    }

    public DefectView get(UUID operatorId, UUID id) {
        return jdbc.query("SELECT d.*, a.tail_number, a.aircraft_type, current_action.id AS active_action_id FROM defects d JOIN aircraft a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS') ORDER BY ca.created_at DESC LIMIT 1) current_action ON TRUE WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND a.isactive=1", DEFECT, operatorId, id)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Defect not found."));
    }

    @Transactional
    public DefectView create(UUID operatorId, String actorId, CreateDefectRequest input) {
        String location = input.location().toUpperCase();
        String severity = input.severity().toUpperCase();
        if (!LOCATIONS.contains(location)) throw new IllegalArgumentException("Unsupported defect location.");
        String category = input.category().toUpperCase();
        boolean hasTenantCatalog = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defect_categories WHERE operator_id=?)", Boolean.class, operatorId));
        boolean activeCategory = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defect_categories WHERE operator_id=? AND code=? AND isactive=1)", Boolean.class, operatorId, category));
        if (hasTenantCatalog ? !activeCategory : !CATEGORIES.contains(category)) throw new IllegalArgumentException("Unsupported defect category.");
        if (!SEVERITIES.contains(severity)) throw new IllegalArgumentException("Unsupported severity.");
        UUID aircraftId = jdbc.query("SELECT id FROM aircraft WHERE operator_id=? AND tail_number=? AND isactive=1", (rs, row) -> rs.getObject(1, UUID.class), operatorId, input.tailNumber().toUpperCase()).stream().findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Aircraft not found."));
        UUID id = UUID.randomUUID();
        String reference = "EL-" + id.toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO defects(id,operator_id,aircraft_id,reference,location,category,zone,row_number,seat_reference,component,title,description,severity,status,reported_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'REPORTED',?)",
            id, operatorId, aircraftId, reference, location, category, input.zone().trim(), input.rowNumber(), input.seatReference(), input.component(), input.title().trim(), input.description().trim(), severity, actorId);
        jdbc.update("INSERT INTO defect_events(operator_id,defect_id,event_type,to_status,note,actor_id,entity_type,entity_id,new_state) VALUES(?,?,'REPORTED','REPORTED',?,?,'DEFECT',?,jsonb_build_object('status','REPORTED','reference',?))", operatorId, id, "Defect reported: " + input.title().trim(), actorId, id, reference);
        return get(operatorId, id);
    }

    @Transactional
    public DefectView transition(UUID operatorId, UUID id, String actorId, TransitionRequest input) {
        String target = input.status().toUpperCase();
        DefectView current = jdbc.query("SELECT d.*, a.tail_number, a.aircraft_type, current_action.id AS active_action_id FROM defects d JOIN aircraft a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS') ORDER BY ca.created_at DESC LIMIT 1) current_action ON TRUE WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND a.isactive=1 FOR UPDATE OF d", DEFECT, operatorId, id)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Defect not found."));
        DefectWorkflow.requireTransition(current.status(), target, input.note());
        if (current.status().equals("INSPECTION_IN_PROGRESS") || Set.of("INSPECTION_COMPLETE", "ACTION_ASSIGNED", "IN_PROGRESS", "AWAITING_VERIFICATION", "VERIFIED", "CLOSED").contains(target))
            throw new IllegalStateException("Use the dedicated inspection, action, verification or approval operation for this transition.");
        String inspectionOutcome = input.inspectionOutcome() == null ? null : input.inspectionOutcome().toUpperCase();
        if (inspectionOutcome != null) throw new IllegalArgumentException("Inspection results must be recorded through the inspection endpoint.");
        String evidence = input.evidenceReference() == null ? null : input.evidenceReference().trim();
        DefectWorkflow.requireEvidence(target, evidence);
        if (target.equals("INSPECTION_COMPLETE") && !hasPassedInspection(operatorId, id)) throw new IllegalStateException("A completed inspection record is required before advancing.");
        if (target.equals("ACTION_ASSIGNED") && !hasAssignedAction(operatorId, id)) throw new IllegalStateException("Assign a corrective action before advancing.");
        if (target.equals("AWAITING_VERIFICATION") && !hasCompletedAction(operatorId, id)) throw new IllegalStateException("Complete a corrective action before verification.");
        if (target.equals("VERIFIED") && !hasPassingVerification(operatorId, id)) throw new IllegalStateException("A passing independent verification is required.");
        if (target.equals("CLOSED") && !hasApprovedClosure(operatorId, id)) throw new IllegalStateException("An approved closure decision is required.");
        jdbc.update("UPDATE defects SET status=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=?", target, operatorId, id);
        jdbc.update("INSERT INTO defect_events(operator_id,defect_id,event_type,from_status,to_status,note,evidence_reference,inspection_outcome,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,?,'STATUS_CHANGED',?,?,?,?,?,?,'DEFECT',?,jsonb_build_object('status',?),jsonb_build_object('status',?))", operatorId, id, current.status(), target, input.note().trim(), evidence, inspectionOutcome, actorId, id, current.status(), target);
        return get(operatorId, id);
    }

    public List<DefectEventView> events(UUID operatorId, UUID id) {
        get(operatorId, id);
        return jdbc.query("SELECT id,event_type,from_status,to_status,note,evidence_reference,inspection_outcome,actor_id,occurred_at FROM defect_events WHERE operator_id=? AND defect_id=? ORDER BY occurred_at DESC,id DESC",
            (rs, row) -> new DefectEventView(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("from_status"), rs.getString("to_status"), rs.getString("note"), rs.getString("evidence_reference"), rs.getString("inspection_outcome"), rs.getString("actor_id"), rs.getTimestamp("occurred_at").toInstant()), operatorId, id);
    }

    private boolean hasPassedInspection(UUID operatorId, UUID id) { return exists("SELECT 1 FROM inspections WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='COMPLETED' AND result IN ('PASS','CONDITIONAL')", operatorId, id); }
    private boolean hasAssignedAction(UUID operatorId, UUID id) { return exists("SELECT 1 FROM corrective_actions WHERE operator_id=? AND defect_id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS','COMPLETED')", operatorId, id); }
    private boolean hasCompletedAction(UUID operatorId, UUID id) { return exists("SELECT 1 FROM corrective_actions WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='COMPLETED'", operatorId, id); }
    private boolean hasPassingVerification(UUID operatorId, UUID id) { return exists("SELECT 1 FROM verifications WHERE operator_id=? AND defect_id=? AND isactive=1 AND result='PASS'", operatorId, id); }
    private boolean hasApprovedClosure(UUID operatorId, UUID id) { return exists("SELECT 1 FROM closure_approvals WHERE operator_id=? AND defect_id=? AND isactive=1 AND decision='APPROVED'", operatorId, id); }
    private boolean exists(String sql, UUID operatorId, UUID defectId) { return Boolean.TRUE.equals(jdbc.query(sql, (ResultSetExtractor<Boolean>) rs -> rs.next(), operatorId, defectId)); }
}
