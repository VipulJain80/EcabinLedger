package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefectService {
    private static final Set<String> LOCATIONS = Set.of("CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT");
    private static final Set<String> CATEGORIES = Set.of("CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT", "IFE", "SEAT", "LIGHTING", "OVERHEAD_BIN", "PSU", "EMERGENCY_EQUIPMENT", "OTHER");
    private static final RowMapper<DefectView> DEFECT = (rs, row) -> {
        int rowNumber = rs.getInt("row_number");
        boolean rowNumberNull = rs.wasNull();
        var dueAt = rs.getTimestamp("due_at");
        var closedAt = rs.getTimestamp("closed_at");
        return new DefectView(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("tail_number"),
            rs.getString("aircraft_type"), rs.getString("location"), rs.getString("category"), rs.getString("zone"),
            rowNumberNull ? null : rowNumber, rs.getString("seat_reference"), rs.getString("component"),
            rs.getObject("area_id", UUID.class), rs.getObject("component_id", UUID.class), rs.getObject("condition_id", UUID.class), rs.getString("condition_name_snapshot"),
            rs.getString("title"), rs.getString("description"), rs.getString("severity"), rs.getString("severity_name"),
            rs.getString("status"), rs.getString("reported_by"), rs.getString("assigned_to"),
            rs.getObject("active_action_id", UUID.class), dueAt == null ? null : dueAt.toInstant(), rs.getTimestamp("reported_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getString("assigned_team"), "REPORTED".equals(rs.getString("status")) && rs.getObject("reviewer_team_id") == null && rs.getString("assigned_team") == null ? "No eligible Reviewer or Maintenance route is configured. Contact an organization administrator." : null,
            closedAt == null ? null : closedAt.toInstant());
    };
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AreaMaintenanceRoutingService areaRouting;
    private final DefectReferenceService defectReferences;
    private com.ecabin.ledger.email.EmailOutboxService emailOutbox;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmailOutbox(com.ecabin.ledger.email.EmailOutboxService emailOutbox) { this.emailOutbox = emailOutbox; }

    @org.springframework.beans.factory.annotation.Autowired
    public DefectService(JdbcTemplate jdbc, NotificationService notifications, AreaMaintenanceRoutingService areaRouting, DefectReferenceService defectReferences) { this.jdbc = jdbc; this.notifications = notifications; this.areaRouting = areaRouting; this.defectReferences = defectReferences; }
    /** Compatibility constructor for isolated legacy unit tests. */
    public DefectService(JdbcTemplate jdbc, NotificationService notifications, AreaMaintenanceRoutingService areaRouting) { this(jdbc, notifications, areaRouting, new DefectReferenceService(jdbc)); }
    public DefectService(JdbcTemplate jdbc, NotificationService notifications) { this(jdbc, notifications, null, new DefectReferenceService(jdbc)); }
    public DefectService(JdbcTemplate jdbc) { this(jdbc, null, null, new DefectReferenceService(jdbc)); }

    public DefectPage list(UUID operatorId, String externalUserId, String status, String location, String query, String searchBy, String aircraftType,
            Instant fromDate, Instant toDate, boolean mine, String cursor, int limit) {
        return list(operatorId, externalUserId, status, location, query, searchBy, aircraftType, fromDate, toDate, mine, false, cursor, limit);
    }

    public DefectPage list(UUID operatorId, String externalUserId, String status, String location, String query, String searchBy, String aircraftType,
            Instant fromDate, Instant toDate, boolean mine, boolean teamScoped, String cursor, int limit) {
        return list(operatorId,externalUserId,status,location,query,searchBy,aircraftType,fromDate,toDate,mine,teamScoped,false,cursor,limit);
    }

    public DefectPage list(UUID operatorId, String externalUserId, String status, String location, String query, String searchBy, String aircraftType,
            Instant fromDate, Instant toDate, boolean mine, boolean teamScoped, boolean reviewerScoped, String cursor, int limit) {
        if (status != null && !Set.of("REPORTED", "UNDER_REVIEW", "REWORK", "INSPECTION_REQUIRED", "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE", "ACTION_ASSIGNED", "IN_PROGRESS", "AWAITING_VERIFICATION", "VERIFIED", "APPROVAL_REQUIRED", "CLOSED", "REJECTED", "CANCELLED", "REOPENED").contains(status)) throw new IllegalArgumentException("Unsupported status filter.");
        if (location != null && !LOCATIONS.contains(location)) throw new IllegalArgumentException("Unsupported location filter.");
        String searchColumn = switch (searchBy == null ? "ALL" : searchBy.toUpperCase(java.util.Locale.ROOT)) {
            case "DEFECT_ID" -> "d.reference";
            case "AIRCRAFT_REGISTRATION" -> "a.tail_number";
            case "CATEGORY" -> "d.category";
            case "COMPONENT" -> "d.component";
            case "ASSIGNED_USER" -> "d.assigned_to";
            case "ALL" -> "concat_ws(' ',d.reference,d.title,d.description,a.tail_number,a.aircraft_type,d.category,d.zone,d.component,d.assigned_to,d.severity,(SELECT s.severity_name FROM defect_severities_mst s WHERE s.operator_id=d.operator_id AND s.id=d.severity_id))";
            default -> throw new IllegalArgumentException("Unsupported defect search field.");
        };
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
                before = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid page cursor."); }
        }
        String sql = "SELECT d.*, " + closedAtProjection() + ", " + severityNameProjection() + ", a.tail_number, a.aircraft_type, current_action.id AS active_action_id FROM defects_dtl d JOIN aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id " +
            "LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') ORDER BY ca.created_at DESC LIMIT 1) current_action ON TRUE " +
            "WHERE d.operator_id=? AND d.isactive=1 AND a.isactive=1 AND (CAST(? AS varchar) IS NULL OR d.status=?) AND (CAST(? AS varchar) IS NULL OR d.location=?) " +
            "AND (CAST(? AS varchar) IS NULL OR " + searchColumn + " ILIKE '%'||?||'%') " +
            "AND (CAST(? AS varchar) IS NULL OR a.aircraft_type=?) AND (CAST(? AS timestamptz) IS NULL OR d.reported_at>=CAST(? AS timestamptz)) " +
            "AND (CAST(? AS timestamptz) IS NULL OR d.reported_at<CAST(? AS timestamptz)+interval '1 day') AND (?=false OR d.reported_by=?) " +
            "AND (?=false OR ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR " + teamMembershipExists("d.assigned_team") + ")) " +
            "AND (?=false OR (d.reviewer_team_id IS NOT NULL AND d.status IN ('REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED') AND " + reviewerMembershipExists("d.reviewer_team_id") + ")) " +
            "AND (CAST(? AS timestamptz) IS NULL OR (d.reported_at,d.id) < (CAST(? AS timestamptz),CAST(? AS uuid))) ORDER BY d.reported_at DESC,d.id DESC LIMIT ?";
        List<DefectView> results = jdbc.query(sql, DEFECT, operatorId, status, status, location, location,
            blankToNull(query), blankToNull(query), blankToNull(aircraftType), blankToNull(aircraftType), utcTimestamp(fromDate), utcTimestamp(fromDate), utcTimestamp(toDate), utcTimestamp(toDate),
            mine, externalUserId, teamScoped, externalUserId, operatorId, externalUserId, reviewerScoped, operatorId, externalUserId, utcTimestamp(before), utcTimestamp(before), beforeId, Math.min(Math.max(limit, 1), 100) + 1);
        boolean more = results.size() > Math.min(Math.max(limit, 1), 100);
        List<DefectView> items = more ? results.subList(0, results.size() - 1) : results;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString((items.get(items.size()-1).reportedAt()+"|"+items.get(items.size()-1).id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new DefectPage(items, next);
    }

    public DefectPage list(UUID operatorId, String status, String location, String cursor, int limit) {
        return list(operatorId, "", status, location, null, "ALL", null, null, null, false, cursor, limit);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static OffsetDateTime utcTimestamp(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }

    public DashboardSummary summary(UUID operatorId) {
        return jdbc.queryForObject("SELECT " +
            "count(*) FILTER (WHERE status NOT IN ('CLOSED','REJECTED','CANCELLED')) AS open_defects, " +
            "count(*) FILTER (WHERE severity='CRITICAL' AND status NOT IN ('CLOSED','REJECTED','CANCELLED')) AS critical_defects, " +
            "count(*) FILTER (WHERE reported_at >= date_trunc('day',now())) AS reported_today, " +
            "count(*) FILTER (WHERE status='CLOSED' AND updated_at >= date_trunc('month',now())) AS closed_this_month " +
            "FROM defects_dtl WHERE operator_id=? AND isactive=1", (rs, row) -> new DashboardSummary(rs.getLong("open_defects"), rs.getLong("critical_defects"), rs.getLong("reported_today"), rs.getLong("closed_this_month")), operatorId);
    }

    public DashboardSummary summary(UUID operatorId, String externalUserId, boolean teamScoped) {
        if (!teamScoped) return summary(operatorId);
        return jdbc.queryForObject("SELECT " +
            "count(*) FILTER (WHERE status NOT IN ('CLOSED','REJECTED','CANCELLED')) AS open_defects, " +
            "count(*) FILTER (WHERE severity='CRITICAL' AND status NOT IN ('CLOSED','REJECTED','CANCELLED')) AS critical_defects, " +
            "count(*) FILTER (WHERE reported_at >= date_trunc('day',now())) AS reported_today, " +
            "count(*) FILTER (WHERE status='CLOSED' AND updated_at >= date_trunc('month',now())) AS closed_this_month " +
            "FROM defects_dtl d WHERE operator_id=? AND isactive=1 AND status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND ((assigned_team IS NULL AND assigned_user_id=?) OR " + teamMembershipExists("d.assigned_team") + ")",
            (rs, row) -> new DashboardSummary(rs.getLong("open_defects"), rs.getLong("critical_defects"), rs.getLong("reported_today"), rs.getLong("closed_this_month")),
            operatorId, externalUserId, operatorId, externalUserId);
    }

    public DashboardSummary summary(UUID operatorId, String externalUserId, boolean teamScoped, boolean reporterScoped) {
        if (teamScoped) return summary(operatorId, externalUserId, true);
        if (!reporterScoped) return summary(operatorId);
        return jdbc.queryForObject("SELECT count(*) FILTER (WHERE status NOT IN ('CLOSED','REJECTED','CANCELLED')) AS open_defects,count(*) FILTER (WHERE severity='CRITICAL' AND status NOT IN ('CLOSED','REJECTED','CANCELLED')) AS critical_defects,count(*) FILTER (WHERE reported_at>=date_trunc('day',now())) AS reported_today,count(*) FILTER (WHERE status='CLOSED' AND updated_at>=date_trunc('month',now())) AS closed_this_month FROM defects_dtl WHERE operator_id=? AND reported_by=? AND isactive=1",
            (rs,row)->new DashboardSummary(rs.getLong("open_defects"),rs.getLong("critical_defects"),rs.getLong("reported_today"),rs.getLong("closed_this_month")),operatorId,externalUserId);
    }

    public DashboardSummary summary(UUID operatorId,String externalUserId,boolean teamScoped,boolean reporterScoped,boolean reviewerScoped){
        // When a membership carries multiple scoped capabilities, apply their intersection just
        // like the Defect Register list query. The previous reviewer-only branch could count
        // defects assigned to a different maintenance team for combined Reviewer/Maintenance roles.
        if(reviewerScoped&&(teamScoped||reporterScoped)){
            String reviewerStatuses="'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','CLOSED','REOPENED','REJECTED'";
            String sql="SELECT count(*) FILTER(WHERE d.status NOT IN ('CLOSED','CANCELLED')) AS open_defects,"
                +"count(*) FILTER(WHERE d.severity='CRITICAL' AND d.status NOT IN ('CLOSED','CANCELLED')) AS critical_defects,"
                +"count(*) FILTER(WHERE d.reported_at>=date_trunc('day',now())) AS reported_today,"
                +"count(*) FILTER(WHERE d.status='CLOSED' AND d.updated_at>=date_trunc('month',now())) AS closed_this_month "
                +"FROM defects_dtl d WHERE d.operator_id=? AND d.isactive=1 "
                +"AND (?=false OR d.reported_by=?) "
                +"AND (?=false OR ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR "+teamMembershipExists("d.assigned_team")+")) "
                +"AND (?=false OR (d.reviewer_team_id IS NOT NULL AND d.status IN ("+reviewerStatuses+") AND "+reviewerMembershipExists("d.reviewer_team_id")+"))";
            return jdbc.queryForObject(sql,(rs,row)->new DashboardSummary(rs.getLong("open_defects"),rs.getLong("critical_defects"),rs.getLong("reported_today"),rs.getLong("closed_this_month")),
                operatorId,reporterScoped,externalUserId,teamScoped,externalUserId,operatorId,externalUserId,reviewerScoped,operatorId,externalUserId);
        }
        if(!reviewerScoped)return summary(operatorId,externalUserId,teamScoped,reporterScoped);
        String statuses="'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','CLOSED','REOPENED','REJECTED'";
        String sql="SELECT count(*) FILTER(WHERE status NOT IN ('CLOSED','CANCELLED')) AS open_defects,count(*) FILTER(WHERE severity='CRITICAL' AND status NOT IN ('CLOSED','CANCELLED')) AS critical_defects,count(*) FILTER(WHERE reported_at>=date_trunc('day',now())) AS reported_today,count(*) FILTER(WHERE status='CLOSED' AND updated_at>=date_trunc('month',now())) AS closed_this_month FROM defects_dtl d WHERE d.operator_id=? AND d.isactive=1 AND d.reviewer_team_id IS NOT NULL AND d.status IN ("+statuses+") AND "+reviewerMembershipExists("d.reviewer_team_id");
        return jdbc.queryForObject(sql,(rs,row)->new DashboardSummary(rs.getLong("open_defects"),rs.getLong("critical_defects"),rs.getLong("reported_today"),rs.getLong("closed_this_month")),operatorId,operatorId,externalUserId);
    }

    /** Team work is queried from the current defect assignment and active membership, before paging. */
    public MaintenanceWorkQueuePage maintenanceWorkQueue(UUID operatorId, String externalUserId, int page, int size,
            String search, String actionStatus, String sortBy, String sortDirection) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Choose a page and page size between 1 and 100.");
        String normalizedStatus = actionStatus == null || actionStatus.isBlank() ? "ALL" : actionStatus.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ALL", "ASSIGNED", "IN_PROGRESS").contains(normalizedStatus)) throw new IllegalArgumentException("Choose a valid corrective-action status.");
        String query = search == null ? "" : search.trim();
        if (query.length() > 200) throw new IllegalArgumentException("Search is limited to 200 characters.");
        Map<String,String> sorts = Map.ofEntries(Map.entry("defectId", "d.reference"), Map.entry("aircraftRegistration", "a.tail_number"),
            Map.entry("msnNumber", "a.msn_number"), Map.entry("aircraftType", "a.aircraft_type"), Map.entry("zone", "d.zone"),
            Map.entry("component", "d.component"), Map.entry("assignedTeam", "d.assigned_team"), Map.entry("defectStatus", "d.status"),
            Map.entry("correctiveActionStatus", "current_action.status"), Map.entry("assignmentDate", "current_action.created_at"),
            Map.entry("reportedAt", "d.reported_at"));
        String sortColumn = sorts.get(sortBy == null || sortBy.isBlank() ? "assignmentDate" : sortBy);
        if (sortColumn == null) throw new IllegalArgumentException("Choose a supported work-queue sort column.");
        String direction = sortDirection == null || sortDirection.isBlank() ? "DESC" : sortDirection.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) throw new IllegalArgumentException("Choose ascending or descending sort order.");
        String from = " FROM defects_dtl d JOIN aircraft_mst a ON a.operator_id=d.operator_id AND a.id=d.aircraft_id " +
            "JOIN LATERAL (SELECT ca.assigned_team,ca.assigned_user_id,ca.status,ca.created_at FROM corrective_actions_dtl ca " +
            "WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 ORDER BY ca.created_at DESC,ca.id DESC LIMIT 1) current_action ON TRUE ";
        String membership = " EXISTS (SELECT 1 FROM operator_membership_teams_mst omt " +
            "JOIN operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1 " +
            "JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1 " +
            "WHERE omt.operator_id=d.operator_id AND omt.external_user_id=? AND omt.team_name=d.assigned_team AND omt.isactive=1) ";
        String filter = " WHERE d.operator_id=? AND d.isactive=1 AND a.isactive=1 AND d.assigned_team IS NOT NULL " +
            "AND d.status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND current_action.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') " +
            "AND " + membership + "AND (?='ALL' OR current_action.status=?) " +
            "AND (?='' OR POSITION(LOWER(?) IN LOWER(CONCAT_WS(' ',d.reference,a.tail_number,a.msn_number,a.aircraft_type,d.zone,d.component,d.condition_name_snapshot,d.title,d.description,d.assigned_team)))>0)";
        Long totalValue = jdbc.queryForObject("SELECT count(*)" + from + filter, Long.class, operatorId, externalUserId, normalizedStatus, normalizedStatus, query, query);
        long total = totalValue == null ? 0 : totalValue;
        String sql = "SELECT d.id,d.reference,a.tail_number,a.msn_number,a.aircraft_type,d.zone,d.component,d.condition_name_snapshot,d.description,d.assigned_team,d.status,current_action.status AS corrective_action_status,current_action.created_at AS assignment_date,d.reported_at" +
            from + filter + " ORDER BY " + sortColumn + " " + direction + ",d.id ASC LIMIT ? OFFSET ?";
        List<MaintenanceWorkQueueRow> items = jdbc.query(sql, (rs, row) -> new MaintenanceWorkQueueRow(
            rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("tail_number"), rs.getString("msn_number"),
            rs.getString("aircraft_type"), rs.getString("zone"), rs.getString("component"), rs.getString("condition_name_snapshot"),
            rs.getString("description"), rs.getString("assigned_team"), rs.getString("status"), rs.getString("corrective_action_status"),
            rs.getTimestamp("assignment_date").toInstant(), rs.getTimestamp("reported_at").toInstant()),
            operatorId, externalUserId, normalizedStatus, normalizedStatus, query, query, size, (long) page * size);
        return new MaintenanceWorkQueuePage(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    public FleetStatusPage fleetStatus(UUID operatorId, int page, int size, String search, String sortBy, String sortDirection) {
        return fleetStatus(operatorId, "", false, false, null, null, page, size, search, sortBy, sortDirection);
    }

    public FleetStatusPage fleetStatus(UUID operatorId, String externalUserId, boolean teamScoped, boolean reporterScoped, String fleetId, String aircraftType, int page, int size, String search, String sortBy, String sortDirection) {
        return fleetStatus(operatorId,externalUserId,teamScoped,reporterScoped,false,fleetId,aircraftType,page,size,search,sortBy,sortDirection);
    }

    public FleetStatusPage fleetStatus(UUID operatorId, String externalUserId, boolean teamScoped, boolean reporterScoped, boolean reviewerScoped, String fleetId, String aircraftType, int page, int size, String search, String sortBy, String sortDirection) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Choose a page and page size between 1 and 100.");
        String normalizedFleetId = normalizeFleetId(fleetId);
        String sort = fleetSort(sortBy, sortDirection);
        String query = search == null ? "" : search.trim();
        if (query.length() > 200) throw new IllegalArgumentException("Search is limited to 200 characters.");
        String reviewerStatuses="'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED'";
        String reviewerAircraft="(?=false OR EXISTS (SELECT 1 FROM defects_dtl rd WHERE rd.operator_id=a.operator_id AND rd.aircraft_id=a.id AND rd.isactive=1 AND rd.reviewer_team_id IS NOT NULL AND rd.status IN ("+reviewerStatuses+") AND "+reviewerMembershipExists("rd.reviewer_team_id")+"))";
        String where = " WHERE a.operator_id=? AND a.isactive=1 AND "+reviewerAircraft+" AND (?='' OR POSITION(LOWER(?) IN LOWER(CONCAT_WS(' ',a.msn_number,a.tail_number,a.aircraft_type)))>0) AND (NULLIF(?, '') IS NULL OR a.fleet_id=CAST(NULLIF(?, '') AS uuid)) AND (?='' OR a.aircraft_type=?)";
        Long count = jdbc.queryForObject("SELECT count(*) FROM aircraft_mst a" + where, Long.class, operatorId,reviewerScoped,operatorId,externalUserId, query, query, normalizedFleetId, normalizedFleetId, aircraftType == null ? "" : aircraftType, aircraftType == null ? "" : aircraftType);
        String visibleCount = "(SELECT COUNT(*) FROM defects_dtl d WHERE d.operator_id=a.operator_id AND d.aircraft_id=a.id AND d.isactive=1 AND d.status NOT IN ('CLOSED','CANCELLED') AND (?=false OR d.reported_by=?) AND (?=false OR (d.status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND d.assigned_team IS NOT NULL AND EXISTS (SELECT 1 FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') AND ca.assigned_team=d.assigned_team) AND " + teamMembershipExists("d.assigned_team") + ")) AND (?=false OR (d.reviewer_team_id IS NOT NULL AND d.status IN ("+reviewerStatuses+") AND " + reviewerMembershipExists("d.reviewer_team_id") + ")))";
        String sql = "SELECT a.id,a.msn_number,a.tail_number,a.aircraft_type," + visibleCount + " AS open_defects FROM aircraft_mst a" + where +
            " ORDER BY " + sort + " NULLS LAST,a.id ASC LIMIT ? OFFSET ?";
        List<FleetStatusRow> rows = jdbc.query(sql, (rs, row) -> new FleetStatusRow(rs.getObject("id", UUID.class), rs.getString("msn_number"), rs.getString("tail_number"), rs.getString("aircraft_type"), rs.getLong("open_defects")),
            reporterScoped, externalUserId, teamScoped, operatorId, externalUserId,reviewerScoped,operatorId,externalUserId,operatorId,reviewerScoped,operatorId,externalUserId, query, query, normalizedFleetId, normalizedFleetId, aircraftType == null ? "" : aircraftType, aircraftType == null ? "" : aircraftType, size, (long) page * size);
        long total = count == null ? 0 : count;
        return new FleetStatusPage(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    public FleetDefectSummary fleetDefectSummary(UUID operatorId, String externalUserId, boolean teamScoped, boolean reporterScoped) {
        String scope = " AND (?=false OR d.reported_by=?) AND (?=false OR (d.status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND d.assigned_team IS NOT NULL AND EXISTS (SELECT 1 FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') AND ca.assigned_team=d.assigned_team) AND " + teamMembershipExists("d.assigned_team") + "))";
        String sql = "SELECT count(*) AS total_defects,count(*) FILTER (WHERE d.status IN ('REPORTED','UNDER_REVIEW')) AS awaiting_review,count(*) FILTER (WHERE d.status IN ('ACTION_ASSIGNED','IN_PROGRESS')) AS in_maintenance,count(*) FILTER (WHERE d.status='CLOSED') AS closed FROM defects_dtl d WHERE d.operator_id=? AND d.isactive=1" + scope;
        return jdbc.queryForObject(sql, (rs, row) -> new FleetDefectSummary(rs.getLong("total_defects"), rs.getLong("awaiting_review"), rs.getLong("in_maintenance"), rs.getLong("closed")),
            operatorId, reporterScoped, externalUserId, teamScoped, operatorId, externalUserId);
    }

    public FleetDefectSummary fleetDefectSummary(UUID operatorId,String externalUserId,boolean teamScoped,boolean reporterScoped,boolean reviewerScoped){
        if(!reviewerScoped)return fleetDefectSummary(operatorId,externalUserId,teamScoped,reporterScoped);
        String statuses="'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','CLOSED','REOPENED','REJECTED'";
        String sql="SELECT count(*) AS total_defects,count(*) FILTER(WHERE d.status IN ('REPORTED','UNDER_REVIEW')) AS awaiting_review,count(*) FILTER(WHERE d.status IN ('ACTION_ASSIGNED','IN_PROGRESS')) AS in_maintenance,count(*) FILTER(WHERE d.status='CLOSED') AS closed FROM defects_dtl d WHERE d.operator_id=? AND d.isactive=1 AND d.reviewer_team_id IS NOT NULL AND d.status IN ("+statuses+") AND "+reviewerMembershipExists("d.reviewer_team_id");
        return jdbc.queryForObject(sql,(rs,row)->new FleetDefectSummary(rs.getLong("total_defects"),rs.getLong("awaiting_review"),rs.getLong("in_maintenance"),rs.getLong("closed")),operatorId,operatorId,externalUserId);
    }

    public FleetStatusRow aircraft(UUID operatorId, UUID aircraftId) {
        return jdbc.query("SELECT a.id,a.msn_number,a.tail_number,a.aircraft_type,COUNT(d.id) FILTER (WHERE d.isactive=1 AND d.status NOT IN ('CLOSED','CANCELLED')) AS open_defects FROM aircraft_mst a LEFT JOIN defects_dtl d ON d.operator_id=a.operator_id AND d.aircraft_id=a.id WHERE a.operator_id=? AND a.id=? AND a.isactive=1 GROUP BY a.id,a.msn_number,a.tail_number,a.aircraft_type",
            (rs, row) -> new FleetStatusRow(rs.getObject("id", UUID.class), rs.getString("msn_number"), rs.getString("tail_number"), rs.getString("aircraft_type"), rs.getLong("open_defects")), operatorId, aircraftId)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Aircraft not found."));
    }

    public FleetStatusRow aircraft(UUID operatorId, String externalUserId, UUID aircraftId, boolean teamScoped, boolean reporterScoped) {
        return aircraft(operatorId,externalUserId,aircraftId,teamScoped,reporterScoped,false);
    }

    public FleetStatusRow aircraft(UUID operatorId, String externalUserId, UUID aircraftId, boolean teamScoped, boolean reporterScoped,boolean reviewerScoped) {
        String scope = " AND (?=false OR d.reported_by=?) AND (?=false OR ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR " + teamMembershipExists("d.assigned_team") + ")) AND (?=false OR (d.reviewer_team_id IS NOT NULL AND d.status IN ('REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED') AND "+reviewerMembershipExists("d.reviewer_team_id")+"))";
        String sql = "SELECT a.id,a.msn_number,a.tail_number,a.aircraft_type,(SELECT COUNT(*) FROM defects_dtl d WHERE d.operator_id=a.operator_id AND d.aircraft_id=a.id AND d.isactive=1 AND d.status NOT IN ('CLOSED','CANCELLED')" + scope + ") AS open_defects FROM aircraft_mst a WHERE a.operator_id=? AND a.id=? AND a.isactive=1 AND (?=false OR EXISTS(SELECT 1 FROM defects_dtl rd WHERE rd.operator_id=a.operator_id AND rd.aircraft_id=a.id AND rd.isactive=1 AND rd.reviewer_team_id IS NOT NULL AND rd.status IN ('REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED') AND "+reviewerMembershipExists("rd.reviewer_team_id")+"))";
        return jdbc.query(sql, (rs, row) -> new FleetStatusRow(rs.getObject("id", UUID.class), rs.getString("msn_number"), rs.getString("tail_number"), rs.getString("aircraft_type"), rs.getLong("open_defects")),
            reporterScoped, externalUserId, teamScoped, externalUserId, operatorId, externalUserId,reviewerScoped,operatorId,externalUserId,operatorId,aircraftId,reviewerScoped,operatorId,externalUserId)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Aircraft not found."));
    }

    public AircraftDefectPage aircraftDefects(UUID operatorId, String externalUserId, UUID aircraftId, boolean teamScoped, boolean reporterScoped,
            int page, int size, String status, String search, String sortBy, String sortDirection) {
        return aircraftDefects(operatorId,externalUserId,aircraftId,teamScoped,reporterScoped,false,page,size,status,search,sortBy,sortDirection);
    }

    public AircraftDefectPage aircraftDefects(UUID operatorId, String externalUserId, UUID aircraftId, boolean teamScoped, boolean reporterScoped,boolean reviewerScoped,
            int page, int size, String status, String search, String sortBy, String sortDirection) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Choose a page and page size between 1 and 100.");
        aircraft(operatorId,externalUserId,aircraftId,teamScoped,reporterScoped,reviewerScoped);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null && !Set.of("REPORTED", "UNDER_REVIEW", "REWORK", "INSPECTION_REQUIRED", "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE", "ACTION_ASSIGNED", "IN_PROGRESS", "AWAITING_VERIFICATION", "VERIFIED", "APPROVAL_REQUIRED", "CLOSED", "REJECTED", "CANCELLED", "REOPENED").contains(normalizedStatus)) throw new IllegalArgumentException("Unsupported status filter.");
        String term = search == null ? "" : search.trim();
        if (term.length() > 200) throw new IllegalArgumentException("Search is limited to 200 characters.");
        String scope = " AND (?=false OR d.reported_by=?) AND (?=false OR ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR " + teamMembershipExists("d.assigned_team") + ")) AND (?=false OR (d.reviewer_team_id IS NOT NULL AND d.status IN ('REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED') AND "+reviewerMembershipExists("d.reviewer_team_id")+"))";
        String where = " WHERE d.operator_id=? AND d.aircraft_id=? AND d.isactive=1 AND a.isactive=1 AND (CAST(? AS varchar) IS NULL OR d.status=?) AND (?='' OR concat_ws(' ',d.reference,d.title,d.description,d.location,d.zone,d.component,d.condition_name_snapshot,d.status,d.severity,(SELECT s.severity_name FROM defect_severities_mst s WHERE s.operator_id=d.operator_id AND s.id=d.severity_id)) ILIKE '%'||?||'%')" + scope;
        Object[] args = {operatorId, aircraftId, normalizedStatus, normalizedStatus, term, term, reporterScoped, externalUserId, teamScoped, externalUserId, operatorId, externalUserId,reviewerScoped,operatorId,externalUserId};
        Long total = jdbc.queryForObject("SELECT count(*) FROM defects_dtl d JOIN aircraft_mst a ON a.operator_id=d.operator_id AND a.id=d.aircraft_id" + where, Long.class, args);
        Map<String,String> sortColumns = Map.of("reference", "d.reference", "location", "d.location", "zone", "d.zone", "component", "d.component", "title", "d.title", "severity", "d.severity", "status", "d.status", "reportedAt", "d.reported_at");
        String sortColumn = sortColumns.get(sortBy == null || sortBy.isBlank() ? "reportedAt" : sortBy);
        if (sortColumn == null) throw new IllegalArgumentException("Choose a supported defect-list sort field.");
        String direction = sortDirection == null || sortDirection.isBlank() ? "DESC" : sortDirection.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) throw new IllegalArgumentException("Choose ascending or descending sort order.");
        String sql = "SELECT d.*," + closedAtProjection() + "," + severityNameProjection() + ",a.tail_number,a.aircraft_type,current_action.id AS active_action_id FROM defects_dtl d JOIN aircraft_mst a ON a.operator_id=d.operator_id AND a.id=d.aircraft_id LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') ORDER BY ca.created_at DESC LIMIT 1) current_action ON TRUE" + where + " ORDER BY " + sortColumn + " " + direction + ",d.id DESC LIMIT ? OFFSET ?";
        Object[] rowsArgs = java.util.Arrays.copyOf(args, args.length + 2);
        rowsArgs[args.length] = size;
        rowsArgs[args.length + 1] = (long) page * size;
        List<DefectView> rows = jdbc.query(sql, DEFECT, rowsArgs);
        long count = total == null ? 0 : total;
        return new AircraftDefectPage(rows, page, size, count, (int) Math.ceil((double) count / size));
    }

    @Transactional(readOnly = true)
    public void exportFleetStatus(UUID operatorId, String search, String sortBy, String sortDirection, List<String> requestedColumns, OutputStream output) throws IOException {
        exportFleetStatus(operatorId, "", false, false, null, null, search, sortBy, sortDirection, requestedColumns, output);
    }

    @Transactional(readOnly = true)
    public void exportFleetStatus(UUID operatorId, String externalUserId, boolean teamScoped, boolean reporterScoped, String fleetId, String aircraftType, String search, String sortBy, String sortDirection, List<String> requestedColumns, OutputStream output) throws IOException {
        exportFleetStatus(operatorId,externalUserId,teamScoped,reporterScoped,false,fleetId,aircraftType,search,sortBy,sortDirection,requestedColumns,output);
    }

    @Transactional(readOnly = true)
    public void exportFleetStatus(UUID operatorId, String externalUserId, boolean teamScoped, boolean reporterScoped,boolean reviewerScoped, String fleetId, String aircraftType, String search, String sortBy, String sortDirection, List<String> requestedColumns, OutputStream output) throws IOException {
        String normalizedFleetId = normalizeFleetId(fleetId);
        String sort = fleetSort(sortBy, sortDirection);
        String query = search == null ? "" : search.trim();
        if (query.length() > 200) throw new IllegalArgumentException("Search is limited to 200 characters.");
        String reviewerStatuses="'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED'";
        String reviewerAircraft="(?=false OR EXISTS (SELECT 1 FROM defects_dtl rd WHERE rd.operator_id=a.operator_id AND rd.aircraft_id=a.id AND rd.isactive=1 AND rd.reviewer_team_id IS NOT NULL AND rd.status IN ("+reviewerStatuses+") AND "+reviewerMembershipExists("rd.reviewer_team_id")+"))";
        String where = " WHERE a.operator_id=? AND a.isactive=1 AND "+reviewerAircraft+" AND (?='' OR POSITION(LOWER(?) IN LOWER(CONCAT_WS(' ',a.msn_number,a.tail_number,a.aircraft_type)))>0) AND (NULLIF(?, '') IS NULL OR a.fleet_id=CAST(NULLIF(?, '') AS uuid)) AND (?='' OR a.aircraft_type=?)";
        Long count = jdbc.queryForObject("SELECT count(*) FROM aircraft_mst a" + where, Long.class, operatorId,reviewerScoped,operatorId,externalUserId, query, query, normalizedFleetId, normalizedFleetId, aircraftType == null ? "" : aircraftType, aircraftType == null ? "" : aircraftType);
        Map<String,String> labels=Map.of("msnNumber","MSN Number","tailNumber","Aircraft Registration","aircraftType","Aircraft Type","openDefects","Open Defects");
        List<String> columns=requestedColumns==null?List.of("msnNumber","tailNumber","aircraftType","openDefects"):requestedColumns.stream().distinct().toList();
        if(columns.stream().anyMatch(column->!labels.containsKey(column)))throw new IllegalArgumentException("This Fleet Status column cannot be exported.");
        if(columns.isEmpty())throw new IllegalArgumentException("Select at least one visible data column to export.");
        XlsxStreamWriter writer = new XlsxStreamWriter(output, columns.stream().map(labels::get).toList(), count == null ? 0 : count);
        String visibleCount = "(SELECT COUNT(*) FROM defects_dtl d WHERE d.operator_id=a.operator_id AND d.aircraft_id=a.id AND d.isactive=1 AND d.status NOT IN ('CLOSED','CANCELLED') AND (?=false OR d.reported_by=?) AND (?=false OR (d.status IN ('ACTION_ASSIGNED','IN_PROGRESS') AND d.assigned_team IS NOT NULL AND EXISTS (SELECT 1 FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') AND ca.assigned_team=d.assigned_team) AND " + teamMembershipExists("d.assigned_team") + ")) AND (?=false OR (d.reviewer_team_id IS NOT NULL AND d.status IN ("+reviewerStatuses+") AND " + reviewerMembershipExists("d.reviewer_team_id") + ")))";
        String sql = "SELECT a.msn_number,a.tail_number,a.aircraft_type," + visibleCount + " AS open_defects FROM aircraft_mst a" + where + " ORDER BY " + sort + " NULLS LAST,a.id ASC";
        try {
            jdbc.query(connection -> {
                var prepared = connection.prepareStatement(sql); prepared.setFetchSize(500);
                prepared.setBoolean(1, reporterScoped); prepared.setString(2, externalUserId); prepared.setBoolean(3, teamScoped);
                prepared.setObject(4, operatorId); prepared.setString(5, externalUserId); prepared.setBoolean(6,reviewerScoped);prepared.setObject(7,operatorId);prepared.setString(8,externalUserId);
                prepared.setObject(9,operatorId);prepared.setBoolean(10,reviewerScoped);prepared.setObject(11,operatorId);prepared.setString(12,externalUserId);prepared.setString(13, query); prepared.setString(14, query);
                prepared.setString(15, normalizedFleetId); prepared.setString(16, normalizedFleetId); prepared.setString(17, aircraftType == null ? "" : aircraftType); prepared.setString(18, aircraftType == null ? "" : aircraftType); return prepared;
            }, result -> {
                try { Map<String,String> values=Map.of("msnNumber",value(result.getString(1)),"tailNumber",value(result.getString(2)),"aircraftType",value(result.getString(3)),"openDefects",String.valueOf(result.getLong(4)));writer.writeRow(columns.stream().map(values::get).toList()); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            writer.finish();
        } catch (UncheckedIOException ex) { throw ex.getCause(); }
    }

    private String fleetSort(String sortBy, String sortDirection) {
        Map<String,String> columns = Map.of("msnNumber","a.msn_number","tailNumber","a.tail_number","aircraftType","a.aircraft_type","openDefects","open_defects");
        String column = columns.get(sortBy == null || sortBy.isBlank() ? "tailNumber" : sortBy);
        if (column == null) throw new IllegalArgumentException("Choose a supported fleet status sort column.");
        String direction = sortDirection == null || sortDirection.isBlank() ? "ASC" : sortDirection.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ASC","DESC").contains(direction)) throw new IllegalArgumentException("Choose ascending or descending sort order.");
        return column + " " + direction;
    }

    private String normalizeFleetId(String fleetId) {
        if (fleetId == null || fleetId.isBlank()) return null;
        try { return UUID.fromString(fleetId.trim()).toString(); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Choose a valid fleet filter."); }
    }

    private static String value(String value) { return value == null ? "—" : value; }

    public OrganizationBranding organizationBranding(UUID operatorId) {
        return jdbc.query("SELECT name, application_name FROM operators_mst WHERE id=? AND isactive=1",
                rs -> rs.next() ? new OrganizationBranding(rs.getString("name"), rs.getString("application_name")) : null,
                operatorId);
    }

    public record OrganizationBranding(String organizationName, String applicationName) {}

    public DefectView get(UUID operatorId, UUID id) {
        return jdbc.query("SELECT d.*, " + closedAtProjection() + ", " + severityNameProjection() + ", a.tail_number, a.aircraft_type, current_action.id AS active_action_id FROM defects_dtl d JOIN aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') ORDER BY ca.created_at DESC LIMIT 1) current_action ON TRUE WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND a.isactive=1", DEFECT, operatorId, id)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Defect not found."));
    }

    /** Detail access matching the aircraft-specific Defect List policy, without broadening general detail access. */
    public DefectView getForAircraftList(UUID operatorId, String externalUserId, UUID aircraftId, UUID id, boolean reporterScoped) {
        return jdbc.query("SELECT d.*, " + closedAtProjection() + ", " + severityNameProjection() + ", a.tail_number, a.aircraft_type, current_action.id AS active_action_id FROM defects_dtl d " +
                "JOIN aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id " +
                "LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') ORDER BY ca.created_at DESC,ca.id DESC LIMIT 1) current_action ON TRUE " +
                "WHERE d.operator_id=? AND d.aircraft_id=? AND d.id=? AND d.isactive=1 AND a.isactive=1 AND (?=false OR d.reported_by=?)",
            DEFECT, operatorId, aircraftId, id, reporterScoped, externalUserId).stream().findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Defect not found."));
    }

    public DefectView getForReporter(UUID operatorId, String externalUserId, UUID id) {
        DefectView defect = get(operatorId, id);
        if (!defect.reportedBy().equals(externalUserId)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Defect not found.");
        return defect;
    }

    /** Non-disclosing detail access for maintenance principals is tied to current active team assignment. */
    public DefectView getForMaintenanceMember(UUID operatorId, String externalUserId, UUID id) {
        return jdbc.query("SELECT d.*," + closedAtProjection() + "," + severityNameProjection() + ",a.tail_number,a.aircraft_type,current_action.id AS active_action_id FROM defects_dtl d " +
            "JOIN aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id " +
            "LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') ORDER BY ca.created_at DESC,ca.id DESC LIMIT 1) current_action ON TRUE " +
            "WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND a.isactive=1 AND ((d.assigned_team IS NULL AND d.assigned_user_id=?) OR " + teamMembershipExists("d.assigned_team") + ")",
            DEFECT, operatorId, id, externalUserId, operatorId, externalUserId).stream().findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Defect not found."));
    }

    /** A reviewer can open a routed defect only through an active Reviewer-team membership and REVIEW grant. */
    public DefectView getForReviewerMember(UUID operatorId,String externalUserId,UUID id) {
        return jdbc.query("SELECT d.*," + closedAtProjection() + "," + severityNameProjection() + ",a.tail_number,a.aircraft_type,current_action.id AS active_action_id FROM defects_dtl d JOIN aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') ORDER BY ca.created_at DESC,ca.id DESC LIMIT 1) current_action ON TRUE WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND a.isactive=1 AND EXISTS (SELECT 1 FROM maintenance_teams_mst t JOIN operator_membership_teams_mst omt ON omt.operator_id=t.operator_id AND omt.team_name=t.name AND omt.isactive=1 JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE t.operator_id=d.operator_id AND t.id=d.reviewer_team_id AND t.team_type='REVIEWER' AND t.isactive=1 AND omt.external_user_id=? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%')",DEFECT,operatorId,id,externalUserId).stream().findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Defect not found."));
    }

    private String severityNameProjection() {
        return "COALESCE((SELECT s.severity_name FROM defect_severities_mst s WHERE s.operator_id=d.operator_id AND s.id=d.severity_id),d.severity) AS severity_name";
    }

    private String closedAtProjection() {
        return "CASE WHEN d.status='CLOSED' THEN COALESCE((SELECT MAX(ev.occurred_at) FROM defect_events_dtl ev WHERE ev.operator_id=d.operator_id AND ev.defect_id=d.id AND (ev.to_status='CLOSED' OR ev.event_type='CLOSURE_APPROVED')),d.updated_at) ELSE NULL END AS closed_at";
    }

    private String teamMembershipExists(String defectTeamColumn) {
        return "EXISTS (SELECT 1 FROM operator_membership_teams_mst omt " +
            "JOIN operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1 " +
            "JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1 " +
            "WHERE omt.operator_id=? AND omt.external_user_id=? AND omt.team_name=" + defectTeamColumn + " AND omt.isactive=1)";
    }

    private String reviewerMembershipExists(String defectTeamIdColumn) {
        return "EXISTS (SELECT 1 FROM maintenance_teams_mst rt JOIN operator_membership_teams_mst omt ON omt.operator_id=rt.operator_id AND omt.team_name=rt.name AND omt.isactive=1 JOIN operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1 JOIN operator_roles_mst r ON r.operator_id=om.operator_id AND r.role_key=om.role AND r.isactive=1 WHERE rt.operator_id=? AND rt.id="+defectTeamIdColumn+" AND rt.team_type='REVIEWER' AND rt.isactive=1 AND omt.external_user_id=? AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%')";
    }

    @Transactional
    public DefectView create(UUID operatorId, String actorId, CreateDefectRequest input) {
        return create(operatorId,actorId,input,null);
    }

    @Transactional
    public DefectView create(UUID operatorId, String actorId, CreateDefectRequest input,UUID idempotencyKey) {
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)", String.class, actorId);
        String payloadHash=idempotencyKey==null?null:reportPayloadHash(input);
        if(idempotencyKey!=null){
            var existing=jdbc.query("SELECT id,reported_by,report_payload_hash FROM defects_dtl WHERE operator_id=? AND report_idempotency_key=?",rs->rs.next()?new Object[]{rs.getObject("id",UUID.class),rs.getString("reported_by"),rs.getString("report_payload_hash")}:null,operatorId,idempotencyKey);
            if(existing!=null){
                if(!actorId.equals(existing[1])||!payloadHash.equals(existing[2]))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"This report retry key was already used for a different actor or report. Start a new report to submit changed details.");
                return get(operatorId,(UUID)existing[0]);
            }
        }
        String location = input.location().toUpperCase();
        String severity = input.severity().toUpperCase(java.util.Locale.ROOT);
        if (!LOCATIONS.contains(location)) throw new IllegalArgumentException("Unsupported defect location.");
        String category = input.category().toUpperCase();
        boolean hasTenantCatalog = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defect_categories_mst WHERE operator_id=?)", Boolean.class, operatorId));
        boolean activeCategory = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defect_categories_mst WHERE operator_id=? AND code=? AND isactive=1)", Boolean.class, operatorId, category));
        if (hasTenantCatalog ? !activeCategory : !CATEGORIES.contains(category)) throw new IllegalArgumentException("Unsupported defect category.");
        UUID severityId = jdbc.query("SELECT id FROM defect_severities_mst WHERE operator_id=? AND severity_code=? AND isactive=1",rs->rs.next()?rs.getObject(1,UUID.class):null,operatorId,severity);
        if (severityId == null) throw new IllegalArgumentException("Choose an active severity belonging to this organization.");
        Object[] aircraft = input.aircraftId() == null
            ? jdbc.query("SELECT id,tail_number FROM aircraft_mst WHERE operator_id=? AND tail_number=? AND isactive=1", (rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("tail_number")}, operatorId, input.tailNumber().toUpperCase()).stream().findFirst().orElse(null)
            : jdbc.query("SELECT id,tail_number FROM aircraft_mst WHERE operator_id=? AND id=? AND isactive=1", (rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("tail_number")}, operatorId, input.aircraftId()).stream().findFirst().orElse(null);
        if (aircraft == null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Aircraft not found.");
        if (!((String) aircraft[1]).equalsIgnoreCase(input.tailNumber().trim()))
            throw new IllegalArgumentException("Aircraft registration does not match the selected active aircraft.");
        UUID aircraftId = (UUID) aircraft[0];
        if (!exists("SELECT 1 FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1", operatorId, input.areaId()))
            throw new IllegalArgumentException("Choose an active area belonging to this organization.");
        String componentName = jdbc.query("SELECT name FROM cabin_components_mst WHERE operator_id=? AND id=? AND area_id=? AND isactive=1", rs -> rs.next() ? rs.getString(1) : null, operatorId, input.componentId(), input.areaId());
        if (componentName == null) throw new IllegalArgumentException("Choose an active component that belongs to the selected area and organization.");
        String conditionName = jdbc.query("SELECT c.name FROM component_conditions_mst m JOIN defect_conditions_mst c ON c.operator_id=m.operator_id AND c.id=m.condition_id AND c.isactive=1 WHERE m.operator_id=? AND m.component_id=? AND m.condition_id=? AND m.isactive=1", rs -> rs.next() ? rs.getString(1) : null, operatorId, input.componentId(), input.conditionId());
        if (conditionName == null) throw new IllegalArgumentException("Choose an active condition mapped to the selected component and organization.");
        LocalDate reportDate = jdbc.queryForObject("SELECT (transaction_timestamp() AT TIME ZONE 'Asia/Kolkata')::date", LocalDate.class);
        lockReportIdentity(operatorId, aircraftId, input.areaId(), input.componentId(), input.conditionId(), reportDate);
        if(idempotencyKey!=null){
            var concurrentRetry=jdbc.query("SELECT id,reported_by,report_payload_hash FROM defects_dtl WHERE operator_id=? AND report_idempotency_key=?",rs->rs.next()?new Object[]{rs.getObject("id",UUID.class),rs.getString("reported_by"),rs.getString("report_payload_hash")}:null,operatorId,idempotencyKey);
            if(concurrentRetry!=null){
                if(!actorId.equals(concurrentRetry[1])||!payloadHash.equals(concurrentRetry[2]))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"This report retry key was already used for a different actor or report. Start a new report to submit changed details.");
                return get(operatorId,(UUID)concurrentRetry[0]);
            }
        }
        Instant reportDayStart = reportDate.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        String reportedZone = input.zone().trim();
        var duplicate = jdbc.query("SELECT reference FROM defects_dtl WHERE operator_id=? AND aircraft_id=? AND location=? AND area_id=? AND component_id=? AND condition_id=? AND UPPER(BTRIM(zone))=UPPER(BTRIM(?)) AND reported_at>=? AND reported_at<? ORDER BY reported_at LIMIT 1",
            rs -> rs.next() ? rs.getString("reference") : null, operatorId, aircraftId, location, input.areaId(), input.componentId(), input.conditionId(), reportedZone,
            java.sql.Timestamp.from(reportDayStart), java.sql.Timestamp.from(reportDayStart.plusSeconds(86_400)));
        if (duplicate != null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
            "A case for this aircraft, cabin area, component, condition, and location has already been reported today (IST): " + duplicate + ". Open the existing case instead.");
        UUID id = UUID.randomUUID();
        String reference = defectReferences.next(operatorId, input.areaId());
        AreaMaintenanceRoutingService.Route configuredRoute = areaRouting == null ? null : areaRouting.route(operatorId,input.areaId());
        boolean direct = configuredRoute != null && "DIRECT_MAINTENANCE".equals(configuredRoute.mode());
        UUID workflowVersionId = configuredRoute == null ? null : configuredRoute.workflowVersionId();
        UUID reviewerTeamId=direct?null:jdbc.query("SELECT o.default_reviewer_team_id FROM operators_mst o JOIN maintenance_teams_mst t ON t.operator_id=o.id AND t.id=o.default_reviewer_team_id AND t.isactive=1 AND t.team_type='REVIEWER' WHERE o.id=? AND EXISTS (SELECT 1 FROM operator_membership_teams_mst omt JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE omt.operator_id=o.id AND omt.team_name=t.name AND omt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%')",rs->rs.next()?rs.getObject(1,UUID.class):null,operatorId);
        String initialStatus = direct && configuredRoute.teamId()!=null ? "ACTION_ASSIGNED" : "REPORTED";
        List<UUID> inserted=jdbc.query("INSERT INTO defects_dtl(id,operator_id,aircraft_id,reference,location,category,area_id,zone,row_number,seat_reference,component_id,component,condition_id,condition_name_snapshot,title,description,severity,severity_id,status,reported_by,reviewer_team_id,report_idempotency_key,report_payload_hash,workflow_version_id,assigned_team) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(operator_id,report_idempotency_key) WHERE report_idempotency_key IS NOT NULL DO NOTHING RETURNING id",
            (rs,row)->rs.getObject(1,UUID.class),id, operatorId, aircraftId, reference, location, category, input.areaId(), input.zone().trim(), input.rowNumber(), input.seatReference(), input.componentId(), componentName, input.conditionId(), conditionName, input.title().trim(), input.description().trim(), severity, severityId, initialStatus, actorId, reviewerTeamId,idempotencyKey,payloadHash,workflowVersionId,direct&&configuredRoute.teamId()!=null?configuredRoute.teamName():null);
        if(inserted.isEmpty()){
            var existing=jdbc.query("SELECT id,reported_by,report_payload_hash FROM defects_dtl WHERE operator_id=? AND report_idempotency_key=?",rs->rs.next()?new Object[]{rs.getObject("id",UUID.class),rs.getString("reported_by"),rs.getString("report_payload_hash")}:null,operatorId,idempotencyKey);
            if(existing==null||!actorId.equals(existing[1])||!payloadHash.equals(existing[2]))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"This report retry key was already used for a different actor or report. Start a new report to submit changed details.");
            return get(operatorId,(UUID)existing[0]);
        }
        UUID reportEventId = jdbc.query("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,to_status,note,actor_id,entity_type,entity_id,new_state,request_id) VALUES(?,?,'REPORTED','REPORTED',? ,?,'DEFECT',?,jsonb_build_object('status','REPORTED','reference',?,'conditionId',?,'condition',?,'reviewerTeamId',CAST(? AS text),'workflowVersionId',CAST(? AS text)),?) RETURNING id", (rs, row) -> rs.getObject(1, UUID.class), operatorId, id, "Defect reported: " + input.title().trim(), actorId, id, reference, input.conditionId(), conditionName, reviewerTeamId == null ? null : reviewerTeamId.toString(),workflowVersionId==null?null:workflowVersionId.toString(), safeRequestId()).get(0);
        if(direct){
            if(configuredRoute.teamId()==null){
                jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,new_state,request_id) VALUES(?,?,'AREA_MAINTENANCE_ROUTING_FAILED','No active eligible Maintenance team is mapped to this cabin area.',?,'DEFECT',?,jsonb_build_object('actionable','Configure an active area-to-team mapping and retry.'),?)",operatorId,id,actorId,id,safeRequestId());
            } else areaRouting.assignForDirectMode(operatorId,id,actorId,configuredRoute.mappingId(),configuredRoute.teamName(),input.title().trim(),severity);
        } else {
            if (notifications != null) notifications.notifyReviewers(operatorId, id, reportEventId, actorId, "DEFECT_REPORTED", "A new cabin defect is waiting for Reviewer assessment.");
            if (emailOutbox != null) emailOutbox.enqueue(operatorId, id, reportEventId, actorId, "DEFECT_REPORTED", null, null);
            if(reviewerTeamId==null) jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,new_state,request_id) VALUES(?,?,'REVIEW_ROUTING_FAILED','No active default Reviewer team with an eligible REVIEW member is configured.',?,'DEFECT',?,jsonb_build_object('reviewerTeamId',NULL,'actionable','Configure the organization Reviewer route and route this defect.'),?)",operatorId,id,actorId,id,safeRequestId());
            else jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,new_state,request_id) VALUES(?,?,'REVIEW_ROUTED','Defect routed to the organization default Reviewer team.',?,'DEFECT',?,jsonb_build_object('reviewerTeamId',CAST(? AS text)),?)",operatorId,id,actorId,id,reviewerTeamId.toString(),safeRequestId());
        }
        return get(operatorId, id);
    }

    private void lockReportIdentity(UUID operatorId, UUID aircraftId, UUID areaId, UUID componentId, UUID conditionId, LocalDate reportDate) {
        String lockKey = String.join("|", operatorId.toString(), aircraftId.toString(), areaId.toString(), componentId.toString(), conditionId.toString(), reportDate.toString());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { rs.next(); return null; }, lockKey);
    }

    private String reportPayloadHash(CreateDefectRequest input){
        try{
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 is unavailable.",ex);}
    }

    @Transactional
    public DefectView transition(UUID operatorId, UUID id, String actorId, TransitionRequest input) {
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)", String.class, actorId);
        String target = input.status().toUpperCase();
        if (target.equals("REJECTED") || target.equals("REWORK"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Reviewer rejection and return-for-correction must use the authorized Reviewer decision operation.");
        DefectView current = jdbc.query("SELECT d.*, " + closedAtProjection() + ", " + severityNameProjection() + ", a.tail_number, a.aircraft_type, current_action.id AS active_action_id FROM defects_dtl d JOIN aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id LEFT JOIN LATERAL (SELECT ca.id FROM corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 AND ca.status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') ORDER BY ca.created_at DESC LIMIT 1) current_action ON TRUE WHERE d.operator_id=? AND d.id=? AND d.isactive=1 AND a.isactive=1 FOR UPDATE OF d", DEFECT, operatorId, id)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Defect not found."));
        DefectWorkflow.requireTransition(current.status(), target, input.note());
        if (current.status().equals("UNDER_REVIEW") && target.equals("INSPECTION_REQUIRED"))
            throw new IllegalStateException("Reviewer decisions must be submitted through the authorized Reviewer decision operation.");
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
        jdbc.update("UPDATE defects_dtl SET status=?,updated_at=now(),version=version+1 WHERE operator_id=? AND id=?", target, operatorId, id);
        UUID eventId = jdbc.query("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,from_status,to_status,note,evidence_reference,inspection_outcome,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,?,'STATUS_CHANGED',?,?,?,?,?,?,'DEFECT',?,jsonb_build_object('status',?),jsonb_build_object('status',?)) RETURNING id", (rs, row) -> rs.getObject(1, UUID.class), operatorId, id, current.status(), target, input.note().trim(), evidence, inspectionOutcome, actorId, id, current.status(), target).get(0);
        if (notifications != null && (target.equals("CLOSED") || target.equals("REOPENED")))
            notifications.notifyParticipants(operatorId, id, eventId, actorId, target.equals("CLOSED") ? "DEFECT_CLOSED" : "DEFECT_REOPENED", target.equals("CLOSED") ? "A cabin defect was closed." : "A closed cabin defect was reopened.");
        if (emailOutbox != null && (target.equals("CLOSED") || target.equals("REOPENED")))
            emailOutbox.enqueue(operatorId,id,eventId,actorId,target.equals("CLOSED")?"DEFECT_CLOSED":"DEFECT_REOPENED",null,null);
        return get(operatorId, id);
    }

    public List<DefectEventView> events(UUID operatorId, UUID id) {
        get(operatorId, id);
        String sql = "SELECT e.id,e.event_type,e.from_status,e.to_status,e.note,e.evidence_reference,e.inspection_outcome,e.actor_id,e.occurred_at, " +
            "f.id AS form_id,v.form_name,v.purpose AS form_purpose,v.workflow_stage,v.version_number, " +
            "s.snapshot_defect_category,s.snapshot_zone,s.snapshot_row_number,s.snapshot_seat_reference,s.snapshot_title,s.snapshot_description, " +
            "c.name AS component_name,dc.name AS condition_name,sev.severity_name, " +
            "CASE WHEN e.event_type='INSPECTION_COMPLETED' THEN i.notes END AS inspection_notes, " +
            "CASE WHEN e.event_type IN ('ACTION_CLOSE_SUBMITTED','MAINTENANCE_ACTION_UPDATED') OR e.new_state ?? 'maintenanceStatus' THEN e.new_state->>'maintenanceStatus' END AS maintenance_status, " +
            "CASE WHEN e.event_type IN ('ACTION_CLOSE_SUBMITTED','MAINTENANCE_ACTION_UPDATED') OR e.new_state ?? 'slaTargetDate' THEN e.new_state->>'slaTargetDate' END AS sla_target_date, " +
            "CASE WHEN e.event_type IN ('ACTION_CLOSE_SUBMITTED','MAINTENANCE_ACTION_UPDATED') OR e.previous_state ?? 'slaTargetDate' THEN e.previous_state->>'slaTargetDate' END AS previous_sla_target_date, " +
            "CASE WHEN e.event_type IN ('ACTION_CLOSE_SUBMITTED','MAINTENANCE_ACTION_UPDATED') OR e.new_state ?? 'actionStatus' THEN e.new_state->>'actionStatus' END AS action_status, " +
            "CASE WHEN e.event_type IN ('ACTION_CLOSE_SUBMITTED','MAINTENANCE_ACTION_UPDATED') OR e.previous_state ?? 'actionStatus' THEN e.previous_state->>'actionStatus' END AS previous_action_status, " +
            "CASE WHEN e.event_type IN ('ACTION_CLOSE_SUBMITTED','MAINTENANCE_ACTION_UPDATED') OR e.new_state ?? 'remark' THEN COALESCE(e.new_state->>'remark',e.note) END AS maintenance_remark, " +
            "CASE WHEN e.event_type IN ('REVIEW_APPROVED','REVIEW_REJECTED') OR e.event_type LIKE 'CLOSURE_%' THEN e.new_state->>'decision' END AS recorded_decision, " +
            "CASE WHEN e.event_type IN ('ACTION_ASSIGNED','ACTION_REASSIGNED') THEN e.new_state->>'team' " +
                "WHEN e.event_type='REVIEW_APPROVED' THEN e.new_state->>'maintenanceTeam' END AS maintenance_team, " +
            "CASE WHEN e.event_type='ATTACHMENT_UPLOADED' AND e.entity_type='ATTACHMENT' THEN e.entity_id END AS evidence_attachment_id " +
            "FROM defect_events_dtl e " +
            "LEFT JOIN operational_form_submissions_dtl s ON e.entity_type='FORM_SUBMISSION' AND s.operator_id=e.operator_id AND s.defect_id=e.defect_id AND s.id=e.entity_id " +
            "LEFT JOIN operational_forms_dtl f ON f.operator_id=e.operator_id AND f.id::text=COALESCE(s.form_id::text,e.new_state->>'formId') " +
            "LEFT JOIN operational_form_versions_dtl v ON v.operator_id=e.operator_id AND v.form_id=f.id AND v.id::text=COALESCE(s.form_version_id::text,e.new_state->>'formVersionId',e.new_state->>'versionId') " +
            "LEFT JOIN cabin_components_mst c ON c.operator_id=s.operator_id AND c.id=s.snapshot_component_id " +
            "LEFT JOIN defect_conditions_mst dc ON dc.operator_id=s.operator_id AND dc.id=s.snapshot_condition_id " +
            "LEFT JOIN defect_severities_mst sev ON sev.operator_id=s.operator_id AND sev.id=s.snapshot_severity_id " +
            "LEFT JOIN inspections_dtl i ON e.entity_type='INSPECTION' AND i.operator_id=e.operator_id AND i.defect_id=e.defect_id AND i.id=e.entity_id " +
            "WHERE e.operator_id=? AND e.defect_id=? ORDER BY e.occurred_at DESC,e.id DESC";
        return jdbc.query(sql, (rs, row) -> new DefectEventView(
            rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("from_status"), rs.getString("to_status"),
            rs.getString("note"), rs.getString("evidence_reference"), rs.getString("inspection_outcome"), rs.getString("actor_id"),
            rs.getTimestamp("occurred_at").toInstant(), eventDetails(rs)), operatorId, id);
    }

    /** Projects only known business fields; event JSON is never returned wholesale. */
    private DefectEventDetails eventDetails(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<SubmittedFormValue> submitted = new ArrayList<>();
        if ("FORM_SUBMITTED".equals(rs.getString("event_type")) && "DEFECT_REPORT".equals(rs.getString("form_purpose"))) {
            addSubmitted(submitted, "Category", rs.getString("snapshot_defect_category"));
            addSubmitted(submitted, "Component", rs.getString("component_name"));
            addSubmitted(submitted, "Condition", rs.getString("condition_name"));
            addSubmitted(submitted, "Cabin zone", rs.getString("snapshot_zone"));
            int rowNumber = rs.getInt("snapshot_row_number");
            if (!rs.wasNull()) addSubmitted(submitted, "Row", Integer.toString(rowNumber));
            addSubmitted(submitted, "Seat / reference", rs.getString("snapshot_seat_reference"));
            addSubmitted(submitted, "Title", rs.getString("snapshot_title"));
            addSubmitted(submitted, "Description", rs.getString("snapshot_description"));
            addSubmitted(submitted, "Severity", rs.getString("severity_name"));
        }
        if ("INSPECTION_COMPLETED".equals(rs.getString("event_type"))) addSubmitted(submitted, "Inspection notes", rs.getString("inspection_notes"));
        String formId = rs.getString("form_id");
        String formName = rs.getString("form_name");
        String formPurpose = rs.getString("form_purpose");
        String stage = rs.getString("workflow_stage");
        int version = rs.getInt("version_number");
        Integer versionNumber = rs.wasNull() ? null : version;
        String maintenanceStatus = rs.getString("maintenance_status");
        String targetDate = rs.getString("sla_target_date");
        String previousDate = rs.getString("previous_sla_target_date");
        String actionStatus = rs.getString("action_status");
        String previousActionStatus = rs.getString("previous_action_status");
        String remark = rs.getString("maintenance_remark");
        String decision = rs.getString("recorded_decision");
        String maintenanceTeam = rs.getString("maintenance_team");
        UUID evidenceAttachmentId = rs.getObject("evidence_attachment_id", UUID.class);
        if (formId == null && formName == null && submitted.isEmpty() && maintenanceStatus == null && targetDate == null &&
            previousDate == null && actionStatus == null && previousActionStatus == null && remark == null && decision == null &&
            maintenanceTeam == null && evidenceAttachmentId == null) return null;
        return new DefectEventDetails(formId, formName, formPurpose, stage, versionNumber, submitted,
            maintenanceStatus, targetDate, previousDate, actionStatus, previousActionStatus, remark, decision, maintenanceTeam, evidenceAttachmentId);
    }

    private void addSubmitted(List<SubmittedFormValue> values, String label, String value) {
        if (value != null) values.add(new SubmittedFormValue(label, value));
    }

    private boolean hasPassedInspection(UUID operatorId, UUID id) { return exists("SELECT 1 FROM inspections_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='COMPLETED' AND result IN ('PASS','CONDITIONAL')", operatorId, id); }
    private boolean hasAssignedAction(UUID operatorId, UUID id) { return exists("SELECT 1 FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS','COMPLETED')", operatorId, id); }
    private boolean hasCompletedAction(UUID operatorId, UUID id) { return exists("SELECT 1 FROM corrective_actions_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND status='COMPLETED'", operatorId, id); }
    private boolean hasPassingVerification(UUID operatorId, UUID id) { return exists("SELECT 1 FROM verifications_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND result='PASS'", operatorId, id); }
    private boolean hasApprovedClosure(UUID operatorId, UUID id) { return exists("SELECT 1 FROM closure_approvals_dtl WHERE operator_id=? AND defect_id=? AND isactive=1 AND decision='APPROVED'", operatorId, id); }
    private boolean exists(String sql, UUID operatorId, UUID id) { return Boolean.TRUE.equals(jdbc.query(sql, (ResultSetExtractor<Boolean>) rs -> rs.next(), operatorId, id)); }
    private UUID safeRequestId() { try { return UUID.fromString(MDC.get("requestId")); } catch (RuntimeException ex) { return null; } }
}
