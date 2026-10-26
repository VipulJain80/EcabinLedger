package com.ecabin.ledger.service;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Tenant-scoped login history and last-login projection. Enterprise login recording is an internal integration boundary;
 * it must only be called after a gateway contract has produced a validated identity and active membership. */
@Service
public class UserLoginMonitoringService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserLoginMonitoringService.class);
    public enum LoginSource { LOCAL_DEMO }
    public record Summary(long totalActiveUsers, long loggedInLast30Days, long inactive30DaysOrMore, long neverLoggedIn) {}
    public record UserActivity(String id, String externalUserId, String displayName, String role, int membershipStatus,
            Instant lastLoginAt, Instant lastLogoutAt, Instant lastActivityAt, Long daysSinceLogin, String inactivityStatus) {}
    public record UserPage(List<UserActivity> items, int page, int size, long totalElements, int totalPages) {}
    public record LoginEvent(String id, String eventType, Instant occurredAt, String requestId, String authenticationSource) {}
    public record EventPage(List<LoginEvent> items, int page, int size, long totalElements, int totalPages) {}

    private static final Set<String> ACTIVITY_FILTERS = Set.of("ALL", "RECENT", "INACTIVE_30", "NEVER");
    private static final Set<String> STATUS_FILTERS = Set.of("ACTIVE", "INACTIVE", "ALL");
    private static final int EXPORT_PAGE_SIZE = 100;
    private static final long MAX_EXPORT_ROWS = 10_000;
    private static final List<ExportColumn> USER_EXPORT_COLUMNS = List.of(
        new ExportColumn("displayName", "User name"), new ExportColumn("externalUserId", "Enterprise identity"),
        new ExportColumn("role", "Application role"), new ExportColumn("membershipStatus", "Membership"),
        new ExportColumn("lastLoginAt", "Last successful login"), new ExportColumn("lastLogoutAt", "Last recorded logout"),
        new ExportColumn("daysSinceLogin", "Days since login"), new ExportColumn("inactivityStatus", "Classification"));
    private static final List<ExportColumn> EVENT_EXPORT_COLUMNS = List.of(
        new ExportColumn("eventType", "Event"), new ExportColumn("occurredAt", "Time"),
        new ExportColumn("authenticationSource", "Source"), new ExportColumn("requestId", "Request"));
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final Clock clock;

    @Autowired
    public UserLoginMonitoringService(JdbcTemplate jdbc, PermissionService permissions) {
        this(jdbc, permissions, Clock.systemUTC());
    }

    UserLoginMonitoringService(JdbcTemplate jdbc, PermissionService permissions, Clock clock) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.clock = clock;
    }

    /** Trusted auth-completion hook. Local demo invokes this only after resolving its allow-listed active membership.
     * A future gateway adapter must call it only after its confirmed callback contract has validated the identity. */
    @Transactional
    public void recordSuccessfulLogin(UUID operatorId, String externalUserId, LoginSource source) {
        if (operatorId == null || externalUserId == null || externalUserId.isBlank() || externalUserId.length() > 200 || source == null)
            throw new IllegalArgumentException("A validated user, organization, and known authentication source are required.");
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM operator_memberships_mst m JOIN operators_mst o ON o.id=m.operator_id WHERE m.operator_id=? AND m.external_user_id=? AND m.isactive=1 AND o.isactive=1)", Boolean.class, operatorId, externalUserId));
        if (!active) throw new AccessDeniedException("No active organization membership exists for this user.");
        record(operatorId, externalUserId, "LOGIN", source.name());
    }

    @Transactional
    public void recordExplicitLogout(AuthenticatedUser user, String authenticationSource) {
        if (user == null) throw new AccessDeniedException("An active authenticated user is required.");
        // Only a fixed, known local issuer label is retained. Enterprise issuer/source details are not currently contracted.
        String source = "LOCAL_DEMO".equals(authenticationSource) ? "LOCAL_DEMO" : null;
        record(user.organizationId(), user.externalUserId(), "LOGOUT", source);
    }

    private void record(UUID operatorId, String externalUserId, String eventType, String source) {
        Instant occurredAt = clock.instant();
        String requestId = boundedRequestId(MDC.get("requestId"));
        java.sql.Timestamp eventTimestamp = java.sql.Timestamp.from(occurredAt);
        try {
            jdbc.update("INSERT INTO user_login_events_dtl(id,operator_id,external_user_id,event_type,occurred_at,request_id,authentication_source) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID(), operatorId, externalUserId, eventType, eventTimestamp, requestId, source);
            if ("LOGIN".equals(eventType)) {
                jdbc.update("UPDATE operator_memberships_mst SET last_login_at=GREATEST(COALESCE(last_login_at,?),?) WHERE operator_id=? AND external_user_id=? AND isactive=1",
                    eventTimestamp, eventTimestamp, operatorId, externalUserId);
            }
        } catch (DataAccessException failure) {
            log.warn("Could not persist authentication event type {}; transaction will not be reported as successful.", eventType);
            throw failure;
        }
    }

    private String boundedRequestId(String candidate) {
        if (candidate == null || candidate.length() > 80) return null;
        return candidate.matches("[0-9a-fA-F-]{36}") ? candidate : null;
    }

    @Transactional(readOnly = true)
    public Summary summary(AuthenticatedUser actor) {
        requireAdmin(actor);
        Instant cutoff = clock.instant().minus(java.time.Duration.ofDays(30));
        return jdbc.queryForObject("""
            SELECT count(*) FILTER (WHERE m.isactive=1) AS active_count,
                   count(*) FILTER (WHERE m.isactive=1 AND m.last_login_at > ?) AS recent_count,
                   count(*) FILTER (WHERE m.isactive=1 AND m.last_login_at <= ?) AS inactive_count,
                   count(*) FILTER (WHERE m.isactive=1 AND m.last_login_at IS NULL) AS never_count
              FROM operator_memberships_mst m WHERE m.operator_id=?
            """, (rs, row) -> new Summary(rs.getLong("active_count"), rs.getLong("recent_count"), rs.getLong("inactive_count"), rs.getLong("never_count")),
            java.sql.Timestamp.from(cutoff), java.sql.Timestamp.from(cutoff), actor.organizationId());
    }

    @Transactional(readOnly = true)
    public UserPage users(AuthenticatedUser actor, int page, int size, String search, String status, String activity, String sortBy, String sortDirection) {
        requireAdmin(actor);
        validatePage(page, size);
        String normalizedStatus = normalizeFilter(status, STATUS_FILTERS, "status");
        String normalizedActivity = normalizeFilter(activity, ACTIVITY_FILTERS, "activity");
        Instant now = clock.instant();
        Instant cutoff = now.minus(java.time.Duration.ofDays(30));
        String term = search == null ? "" : search.trim();
        if (term.length() > 200) throw new IllegalArgumentException("Search is limited to 200 characters.");
        String sort = sortColumn(sortBy);
        String direction = sortDirection == null || sortDirection.isBlank() ? "DESC" : sortDirection.toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) throw new IllegalArgumentException("Choose ascending or descending sort order.");

        StringBuilder where = new StringBuilder(" WHERE m.operator_id=?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(actor.organizationId());
        if (normalizedStatus.equals("ACTIVE")) where.append(" AND m.isactive=1");
        if (normalizedStatus.equals("INACTIVE")) where.append(" AND m.isactive=2");
        if (!term.isEmpty()) {
            where.append(" AND (POSITION(LOWER(?) IN LOWER(m.display_name))>0 OR POSITION(LOWER(?) IN LOWER(m.external_user_id))>0)");
            args.add(term); args.add(term);
        }
        switch (normalizedActivity) {
            case "RECENT" -> { where.append(" AND m.last_login_at > ?"); args.add(java.sql.Timestamp.from(cutoff)); }
            case "INACTIVE_30" -> { where.append(" AND m.last_login_at <= ?"); args.add(java.sql.Timestamp.from(cutoff)); }
            case "NEVER" -> where.append(" AND m.last_login_at IS NULL");
            default -> { }
        }
        String from = "operator_memberships_mst m LEFT JOIN LATERAL (SELECT max(e.occurred_at) AS last_activity_at,max(e.occurred_at) FILTER (WHERE e.event_type='LOGOUT') AS last_logout_at FROM user_login_events_dtl e WHERE e.operator_id=m.operator_id AND e.external_user_id=m.external_user_id) ev ON true";
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + from + where, Long.class, args.toArray());
        int offset = Math.multiplyExact(page, size);
        java.util.ArrayList<Object> rowArgs = new java.util.ArrayList<>();
        rowArgs.add(java.sql.Timestamp.from(now));
        rowArgs.addAll(args);
        rowArgs.add(size); rowArgs.add(offset);
        List<UserActivity> rows = jdbc.query("""
            SELECT m.external_user_id,m.display_name,m.role,m.isactive,m.last_login_at,ev.last_logout_at,ev.last_activity_at,
                   CASE WHEN m.last_login_at IS NULL THEN NULL ELSE floor(extract(epoch FROM (?::timestamptz-m.last_login_at))/86400)::bigint END AS days_since_login
              FROM """ + " " + from + where + " ORDER BY " + sort + " " + direction + " NULLS LAST,m.external_user_id ASC LIMIT ? OFFSET ?",
            userRowMapper(now), rowArgs.toArray());
        long count = total == null ? 0 : total;
        return new UserPage(rows, page, size, count, Math.max(1, (int) Math.ceil((double) count / size)));
    }

    @Transactional(readOnly = true)
    public EventPage events(AuthenticatedUser actor, String externalUserId, int page, int size) {
        requireAdmin(actor);
        validatePage(page, size);
        if (externalUserId == null || externalUserId.isBlank() || externalUserId.length() > 200)
            throw new IllegalArgumentException("A valid organization membership identity is required.");
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=?)", Boolean.class, actor.organizationId(), externalUserId));
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The organization membership was not found.");
        Long total = jdbc.queryForObject("SELECT count(*) FROM user_login_events_dtl WHERE operator_id=? AND external_user_id=?", Long.class, actor.organizationId(), externalUserId);
        int offset = Math.multiplyExact(page, size);
        List<LoginEvent> rows = jdbc.query("SELECT id,event_type,occurred_at,request_id,authentication_source FROM user_login_events_dtl WHERE operator_id=? AND external_user_id=? ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new LoginEvent(rs.getString("id"), rs.getString("event_type"), instant(rs, "occurred_at"), rs.getString("request_id"), rs.getString("authentication_source")),
            actor.organizationId(), externalUserId, size, offset);
        long count = total == null ? 0 : total;
        return new EventPage(rows, page, size, count, Math.max(1, (int) Math.ceil((double) count / size)));
    }

    /** Exports all matching tenant memberships, not only the currently visible page. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void exportUsers(AuthenticatedUser actor, String search, String status, String activity,
            String sortBy, String sortDirection, List<String> requestedColumns, OutputStream output) throws IOException {
        List<ExportColumn> columns = resolveExportColumns(USER_EXPORT_COLUMNS, requestedColumns);
        UserPage first = users(actor, 0, EXPORT_PAGE_SIZE, search, status, activity, sortBy, sortDirection);
        enforceExportLimit(first.totalElements());
        XlsxStreamWriter writer = new XlsxStreamWriter(output, columns.stream().map(ExportColumn::label).toList(), first.totalElements());
        for (int page = 0; page < first.totalPages(); page++) {
            UserPage current = page == 0 ? first : users(actor, page, EXPORT_PAGE_SIZE, search, status, activity, sortBy, sortDirection);
            for (UserActivity item : current.items()) writer.writeRow(columns.stream().map(column -> userValue(item, column.key())).toList());
        }
        writer.finish();
    }

    /** Exports the complete event history for one membership, restricted to the authenticated tenant. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void exportEvents(AuthenticatedUser actor, String externalUserId, OutputStream output) throws IOException {
        List<ExportColumn> columns = EVENT_EXPORT_COLUMNS;
        EventPage first = events(actor, externalUserId, 0, EXPORT_PAGE_SIZE);
        enforceExportLimit(first.totalElements());
        XlsxStreamWriter writer = new XlsxStreamWriter(output, columns.stream().map(ExportColumn::label).toList(), first.totalElements());
        for (int page = 0; page < first.totalPages(); page++) {
            EventPage current = page == 0 ? first : events(actor, externalUserId, page, EXPORT_PAGE_SIZE);
            for (LoginEvent item : current.items()) writer.writeRow(List.of(
                item.eventType().equals("LOGIN") ? "Successful login" : "Explicit application logout",
                value(item.occurredAt()), value(item.authenticationSource()), value(item.requestId())));
        }
        writer.finish();
    }

    private List<ExportColumn> resolveExportColumns(List<ExportColumn> available, List<String> requested) {
        if (requested == null) return available;
        LinkedHashSet<String> unique = new LinkedHashSet<>(requested);
        if (unique.isEmpty()) throw new IllegalArgumentException("Select at least one visible data column to export.");
        List<ExportColumn> columns = new ArrayList<>(unique.size());
        for (String key : unique) columns.add(available.stream().filter(column -> column.key().equals(key)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("This login monitoring column cannot be exported.")));
        return List.copyOf(columns);
    }

    private void enforceExportLimit(long rows) {
        if (rows > MAX_EXPORT_ROWS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
            "This export exceeds the 10,000 row limit. Narrow the filters and try again.");
    }

    private String userValue(UserActivity item, String key) {
        return switch (key) {
            case "displayName" -> item.displayName();
            case "externalUserId" -> item.externalUserId();
            case "role" -> item.role();
            case "membershipStatus" -> item.membershipStatus() == 1 ? "Active" : "Inactive";
            case "lastLoginAt" -> value(item.lastLoginAt());
            case "lastLogoutAt" -> value(item.lastLogoutAt());
            case "daysSinceLogin" -> item.daysSinceLogin() == null ? "" : item.daysSinceLogin().toString();
            case "inactivityStatus" -> inactivityLabel(item.inactivityStatus());
            default -> throw new IllegalArgumentException("This login monitoring column cannot be exported.");
        };
    }

    private String inactivityLabel(String value) {
        return switch (value) {
            case "NEVER_LOGGED_IN" -> "NA";
            case "ACTIVE_RECENTLY" -> "Active";
            default -> "Inactive 30+ days";
        };
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }

    private record ExportColumn(String key, String label) {}

    private void requireAdmin(AuthenticatedUser actor) {
        if (actor == null) throw new AccessDeniedException("An active authenticated user is required.");
        permissions.require(actor, Permission.ADMINISTER_ORGANIZATION);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100.");
    }

    private String normalizeFilter(String value, Set<String> allowed, String field) {
        String normalized = value == null || value.isBlank() ? "ALL" : value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("Select a valid " + field + " filter.");
        return normalized;
    }

    private String sortColumn(String requested) {
        return switch (requested == null || requested.isBlank() ? "lastLoginAt" : requested) {
            case "displayName" -> "m.display_name";
            case "externalUserId" -> "m.external_user_id";
            case "role" -> "m.role";
            case "status" -> "m.isactive";
            case "lastLoginAt" -> "m.last_login_at";
            case "daysSinceLogin" -> "m.last_login_at";
            case "lastActivityAt" -> "ev.last_activity_at";
            default -> throw new IllegalArgumentException("Sorting is not available for this column.");
        };
    }

    static String classifyInactivity(Instant lastLoginAt, Instant now) {
        if (lastLoginAt == null) return "NEVER_LOGGED_IN";
        return lastLoginAt.isAfter(now.minus(java.time.Duration.ofDays(30))) ? "ACTIVE_RECENTLY" : "INACTIVE_30_DAYS";
    }

    private RowMapper<UserActivity> userRowMapper(Instant now) {
        return (rs, row) -> {
            Instant lastLogin = instant(rs, "last_login_at");
            return new UserActivity(rs.getString("external_user_id"), rs.getString("external_user_id"), rs.getString("display_name"), rs.getString("role"),
                rs.getInt("isactive"), lastLogin, instant(rs, "last_logout_at"), instant(rs, "last_activity_at"),
                rs.getObject("days_since_login") == null ? null : rs.getLong("days_since_login"), classifyInactivity(lastLogin, now));
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
