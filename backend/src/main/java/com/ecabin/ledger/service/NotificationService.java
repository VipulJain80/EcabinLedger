package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.NotificationModels.*;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persistent tenant-scoped inbox. Recipient selection and delivery stay inside business transactions. */
@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    public NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String CURRENT_MAINTENANCE_ACCESS = "EXISTS (SELECT 1 FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') AND ca.id=(SELECT latest.id FROM corrective_actions_dtl latest WHERE latest.operator_id=ca.operator_id AND latest.defect_id=ca.defect_id AND latest.isactive=1 ORDER BY latest.created_at DESC,latest.id DESC LIMIT 1) AND ((ca.assigned_team IS NULL AND ca.assigned_user_id=?) OR (ca.assigned_team IS NOT NULL AND (ca.assigned_user_id IS NULL OR ca.assigned_user_id=?) AND EXISTS (SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=mt.operator_id AND t.name=mt.team_name AND t.isactive=1 WHERE mt.operator_id=d.operator_id AND mt.external_user_id=? AND mt.team_name=ca.assigned_team AND mt.isactive=1))))";
    private static final String CURRENT_REVIEW_ACCESS = "EXISTS (SELECT 1 FROM maintenance_teams_mst t JOIN operator_membership_teams_mst mt ON mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.team_type='REVIEWER' AND t.isactive=1 AND mt.external_user_id=? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%')";

    public Inbox inbox(UUID organizationId, String recipient, boolean maintenanceScoped,
                       boolean reporterScoped, boolean reviewerScoped) {
        String visibility = visibility(maintenanceScoped, reporterScoped, reviewerScoped);
        Object[] args = visibilityArgs(organizationId, recipient, maintenanceScoped, reporterScoped, reviewerScoped);
        Long unread = jdbc.queryForObject("SELECT count(*) FROM notifications_dtl n JOIN defects_dtl d ON d.operator_id=n.operator_id AND d.id=n.defect_id WHERE n.operator_id=? AND n.recipient_user_id=? AND n.isactive=1 AND n.read_at IS NULL AND d.isactive=1" + visibility,
            Long.class, args);
        Object[] pageArgs = java.util.Arrays.copyOf(args, args.length + 1);
        pageArgs[args.length] = 50;
        List<InboxItem> rows = jdbc.query("SELECT n.id,n.notification_type,n.message,n.defect_id,d.reference,n.created_at,n.read_at FROM notifications_dtl n JOIN defects_dtl d ON d.operator_id=n.operator_id AND d.id=n.defect_id WHERE n.operator_id=? AND n.recipient_user_id=? AND n.isactive=1 AND d.isactive=1" + visibility + " ORDER BY n.created_at DESC,n.id DESC LIMIT ?",
            (rs, row) -> new InboxItem(rs.getObject("id", UUID.class), rs.getString("notification_type"), rs.getString("message"), rs.getObject("defect_id", UUID.class), rs.getString("reference"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()), pageArgs);
        return new Inbox(unread == null ? 0 : unread, rows);
    }

    @Transactional
    public void markRead(UUID organizationId, String recipient, UUID notificationId,
                         boolean maintenanceScoped, boolean reporterScoped, boolean reviewerScoped) {
        String visibility = visibility(maintenanceScoped, reporterScoped, reviewerScoped);
        Object[] args = new Object[3 + visibilityArgs(organizationId, recipient, maintenanceScoped, reporterScoped, reviewerScoped).length - 2];
        args[0] = organizationId; args[1] = recipient; args[2] = notificationId;
        Object[] scoped = visibilityArgs(organizationId, recipient, maintenanceScoped, reporterScoped, reviewerScoped);
        System.arraycopy(scoped, 2, args, 3, scoped.length - 2);
        int changed = jdbc.update("UPDATE notifications_dtl n SET read_at=COALESCE(n.read_at,now()) WHERE n.operator_id=? AND n.recipient_user_id=? AND n.id=? AND n.isactive=1 AND EXISTS (SELECT 1 FROM defects_dtl d WHERE d.operator_id=n.operator_id AND d.id=n.defect_id AND d.isactive=1" + visibility + ")", args);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
    }

    @Transactional
    public void markAllRead(UUID organizationId, String recipient, boolean maintenanceScoped,
                            boolean reporterScoped, boolean reviewerScoped) {
        String visibility = visibility(maintenanceScoped, reporterScoped, reviewerScoped);
        Object[] scoped = visibilityArgs(organizationId, recipient, maintenanceScoped, reporterScoped, reviewerScoped);
        Object[] args = new Object[scoped.length];
        args[0] = organizationId; args[1] = recipient;
        System.arraycopy(scoped, 2, args, 2, scoped.length - 2);
        jdbc.update("UPDATE notifications_dtl n SET read_at=COALESCE(n.read_at,now()) WHERE n.operator_id=? AND n.recipient_user_id=? AND n.isactive=1 AND n.read_at IS NULL AND EXISTS (SELECT 1 FROM defects_dtl d WHERE d.operator_id=n.operator_id AND d.id=n.defect_id AND d.isactive=1" + visibility + ")", args);
    }

    public void notifyReviewers(UUID org, UUID defectId, UUID eventId, String actor,
                                String type, String message) {
        jdbc.update("INSERT INTO notifications_dtl(operator_id,recipient_user_id,defect_id,notification_type,message,source_event_id) " +
            "SELECT d.operator_id,m.external_user_id,d.id,?,?,? FROM defects_dtl d JOIN maintenance_teams_mst t ON t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.team_type='REVIEWER' AND t.isactive=1 JOIN operator_membership_teams_mst mt ON mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND m.external_user_id<>? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%' ON CONFLICT DO NOTHING",
            type, message, eventId, org, defectId, actor);
    }

    public void notifyMaintenance(UUID org, UUID defectId, UUID eventId, String actor,
                                  String team, String assignedUser, String type, String message) {
        jdbc.update("INSERT INTO notifications_dtl(operator_id,recipient_user_id,defect_id,notification_type,message,source_event_id) " +
            "SELECT ?,eligible.external_user_id,?, ?, ?, ? FROM (" +
            "SELECT m.external_user_id FROM operator_memberships_mst m JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE m.operator_id=? AND m.external_user_id=? AND m.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%' " +
            "UNION SELECT m.external_user_id FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 JOIN maintenance_teams_mst t ON t.operator_id=mt.operator_id AND t.name=mt.team_name AND t.team_type='MAINTENANCE' AND t.isactive=1 WHERE mt.operator_id=? AND mt.team_name=? AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%') eligible WHERE eligible.external_user_id<>? ON CONFLICT DO NOTHING",
            org, defectId, type, message, eventId, org, assignedUser, org, team, actor);
    }

    public void notifyParticipants(UUID org, UUID defectId, UUID eventId, String actor,
                                   String type, String message) {
        jdbc.update("INSERT INTO notifications_dtl(operator_id,recipient_user_id,defect_id,notification_type,message,source_event_id) " +
            "SELECT ?,participants.external_user_id,?,?,?,? FROM (" +
            "SELECT d.reported_by AS external_user_id FROM defects_dtl d WHERE d.operator_id=? AND d.id=? AND d.reported_by IS NOT NULL " +
            "UNION SELECT m.external_user_id FROM defects_dtl d JOIN maintenance_teams_mst t ON t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.team_type='REVIEWER' AND t.isactive=1 JOIN operator_membership_teams_mst mt ON mt.operator_id=t.operator_id AND mt.team_name=t.name AND mt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE d.operator_id=? AND d.id=? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%' " +
            ") participants JOIN operator_memberships_mst active_member ON active_member.operator_id=? AND active_member.external_user_id=participants.external_user_id AND active_member.isactive=1 WHERE participants.external_user_id<>? ON CONFLICT DO NOTHING",
            org, defectId, type, message, eventId, org, defectId, org, defectId, org, actor);
    }

    private String visibility(boolean maintenance, boolean reporter, boolean reviewer) {
        return " AND (?=false OR d.reported_by=?) AND (?=false OR " + CURRENT_MAINTENANCE_ACCESS + ") AND (?=false OR " + CURRENT_REVIEW_ACCESS + ")";
    }

    private Object[] visibilityArgs(UUID org, String user, boolean maintenance, boolean reporter, boolean reviewer) {
        return new Object[] { org, user, reporter, user, maintenance, user, user, user, reviewer, user };
    }
}
