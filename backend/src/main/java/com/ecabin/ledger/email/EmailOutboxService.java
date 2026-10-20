package com.ecabin.ledger.email;

import java.sql.Timestamp;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Transactional email enqueueing. It never performs SMTP delivery. */
@Service
public class EmailOutboxService {
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final ObjectProvider<VerifiedEmailDirectory> directoryProvider;

    public EmailOutboxService(JdbcTemplate jdbc, ObjectProvider<VerifiedEmailDirectory> directoryProvider) {
        this.jdbc = jdbc;
        this.directoryProvider = directoryProvider;
    }

    /** Call after inserting the source workflow event, inside the same Spring transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int enqueue(UUID tenantId, UUID defectId, UUID eventId, String actorId, String eventKey,
                      String maintenanceTeam, String assignedUserId) {
        if (tenantId == null || defectId == null || eventId == null || actorId == null || actorId.isBlank())
            throw new IllegalArgumentException("Email outbox requires persisted tenant, actor, defect, and event context.");
        Event event = Event.from(eventKey);
        if (event == null) throw new IllegalArgumentException("Unsupported email workflow event.");
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)", String.class, actorId);
        UUID requestId = requestId();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT COALESCE((SELECT is_enabled FROM email_notification_settings_mst WHERE operator_id=?),false) AND COALESCE((SELECT is_enabled FROM email_notification_event_settings_mst WHERE operator_id=? AND event_key=?),false)", Boolean.class, tenantId, tenantId, eventKey))) return 0;

        Source source = jdbc.query("SELECT d.reference,d.status,e.event_type,e.to_status,e.occurred_at FROM defect_events_dtl e JOIN defects_dtl d ON d.operator_id=e.operator_id AND d.id=e.defect_id WHERE e.operator_id=? AND d.id=? AND e.id=? AND d.isactive=1",
            rs -> rs.next() ? new Source(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5)) : null,
            tenantId, defectId, eventId);
        if (source == null || !event.accepts(source.type(), source.toStatus())) return 0;

        List<String> recipients = resolveRecipients(tenantId, defectId, actorId, event, maintenanceTeam, assignedUserId);
        if (recipients.isEmpty()) return 0;
        VerifiedEmailDirectory directory = directoryProvider.getIfAvailable();
        if (directory == null) {
            log.warn("email_enqueue skipped eventKey={} reason=VERIFIED_EMAIL_DIRECTORY_UNAVAILABLE", eventKey);
            return 0;
        }
        Map<String, VerifiedEmailDirectory.VerifiedAddress> addresses = directory.findVerified(tenantId, recipients);
        int queued = 0;
        final String payload;
        try {
            payload = JSON.writeValueAsString(Map.of("defectId", defectId.toString(), "reference", source.reference(),
                "eventKey", eventKey, "status", source.status(), "occurredAt", source.occurredAt().toInstant().toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not create the minimal email payload.", ex);
        }
        for (String recipient : recipients) {
            VerifiedEmailDirectory.VerifiedAddress address = addresses.get(recipient);
            if (address == null || !address.verified() || !validEmail(address.address())) {
                log.warn("email_enqueue skipped eventKey={} reason=NO_VERIFIED_RECIPIENT_ADDRESS", eventKey);
                continue;
            }
            queued += jdbc.update("INSERT INTO email_outbox_dtl(operator_id,source_event_id,recipient_user_id,recipient_email,notification_type,template_key,template_version,payload,request_id,created_by,updated_by) VALUES(?,?,?,?,?,'defect-workflow',1,CAST(? AS jsonb),?,?,?) ON CONFLICT (operator_id,source_event_id,recipient_user_id,notification_type) DO NOTHING",
                tenantId, eventId, recipient, address.address(), eventKey, payload, requestId, actorId, actorId);
        }
        return queued;
    }

    private List<String> resolveRecipients(UUID tenant, UUID defect, String actor, Event event, String team, String assignedUser) {
        String sql = switch (event.audience()) {
            case REVIEWERS -> "SELECT DISTINCT m.external_user_id FROM defects_dtl d JOIN maintenance_teams_mst t ON t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.team_type='REVIEWER' AND t.isactive=1 JOIN operator_membership_teams_mst mt ON mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE d.operator_id=? AND d.id=? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%' AND m.external_user_id<>? ORDER BY m.external_user_id";
            case REPORTER -> "SELECT m.external_user_id FROM defects_dtl d JOIN operator_memberships_mst m ON m.operator_id=d.operator_id AND m.external_user_id=d.reported_by AND m.isactive=1 WHERE d.operator_id=? AND d.id=? AND m.external_user_id<>?";
            case PARTICIPANTS -> "SELECT DISTINCT m.external_user_id FROM defects_dtl d JOIN operator_memberships_mst m ON m.operator_id=d.operator_id AND m.isactive=1 WHERE d.operator_id=? AND d.id=? AND m.external_user_id<>? AND (m.external_user_id=d.reported_by OR EXISTS(SELECT 1 FROM maintenance_teams_mst t JOIN operator_membership_teams_mst mt ON mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.team_type='REVIEWER' AND t.isactive=1 AND mt.external_user_id=m.external_user_id AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%')) ORDER BY m.external_user_id";
            case MAINTENANCE -> maintenanceRecipientsSql();
            case CLOSURE -> "SELECT DISTINCT m.external_user_id FROM operators_mst o JOIN maintenance_teams_mst t ON t.operator_id=o.id AND t.id=o.default_closure_team_id AND t.team_type IN ('CLOSURE','REVIEWER') AND t.isactive=1 JOIN operator_membership_teams_mst mt ON mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE o.id=? AND EXISTS(SELECT 1 FROM defects_dtl d WHERE d.operator_id=o.id AND d.id=?) AND ('|'||r.permissions||'|') LIKE '%|APPROVE|%' AND m.external_user_id<>? ORDER BY m.external_user_id";
        };
        if (event.audience() == Audience.MAINTENANCE) {
            return jdbc.query(sql, (rs, n) -> rs.getString(1), tenant, assignedUser, tenant, team, actor);
        }
        return jdbc.query(sql, (rs, n) -> rs.getString(1), tenant, defect, actor);
    }

    private String maintenanceRecipientsSql() {
        return "SELECT DISTINCT eligible.external_user_id FROM ("+
            "SELECT m.external_user_id FROM operator_memberships_mst m JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE m.operator_id=? AND m.external_user_id=? AND m.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%' " +
            "UNION SELECT m.external_user_id FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=mt.operator_id AND t.name=mt.team_name AND t.team_type='MAINTENANCE' AND t.isactive=1 WHERE mt.operator_id=? AND mt.team_name=? AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%') eligible WHERE eligible.external_user_id<>?";
    }

    private static boolean validEmail(String value) { return value != null && value.length() <= 320 && value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"); }
    private static UUID requestId() { try { return UUID.fromString(MDC.get("request_id") == null ? MDC.get("requestId") : MDC.get("request_id")); } catch (RuntimeException ex) { return null; } }
    private record Source(String reference, String status, String type, String toStatus, Timestamp occurredAt) {}
    private enum Audience { REVIEWERS, REPORTER, PARTICIPANTS, MAINTENANCE, CLOSURE }
    private enum Event {
        DEFECT_REPORTED(Audience.REVIEWERS), REVIEW_APPROVED(Audience.MAINTENANCE), REVIEW_REJECTED(Audience.REPORTER),
        ACTION_ASSIGNED(Audience.MAINTENANCE), ACTION_STARTED(Audience.REVIEWERS), ACTION_COMPLETED(Audience.REVIEWERS),
        INSPECTION_COMPLETED(Audience.REVIEWERS), WORK_VERIFIED(Audience.PARTICIPANTS), VERIFICATION_REJECTED(Audience.PARTICIPANTS),
        ACTION_CLOSE_SUBMITTED(Audience.CLOSURE), CLOSURE_APPROVED(Audience.PARTICIPANTS), CLOSURE_REJECTED(Audience.PARTICIPANTS),
        DEFECT_CLOSED(Audience.PARTICIPANTS), DEFECT_REOPENED(Audience.PARTICIPANTS);
        private final Audience audience;
        Event(Audience audience) { this.audience = audience; }
        Audience audience() { return audience; }
        static Event from(String value) { try { return value == null ? null : valueOf(value); } catch (IllegalArgumentException ex) { return null; } }
        boolean accepts(String type, String status) {
            return switch (this) {
                case DEFECT_REPORTED -> "REPORTED".equals(type);
                case ACTION_ASSIGNED -> Set.of("ACTION_ASSIGNED", "ACTION_REASSIGNED", "AREA_MAINTENANCE_ROUTED", "CLOSURE_REJECTED").contains(type);
                case WORK_VERIFIED, VERIFICATION_REJECTED -> "VERIFICATION_COMPLETED".equals(type);
                case DEFECT_CLOSED -> "STATUS_CHANGED".equals(type) && "CLOSED".equals(status);
                case DEFECT_REOPENED -> "STATUS_CHANGED".equals(type) && "REOPENED".equals(status);
                default -> this.name().equals(type);
            };
        }
    }
}
