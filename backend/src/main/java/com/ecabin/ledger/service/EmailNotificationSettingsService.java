package com.ecabin.ledger.service;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Tenant-scoped organization controls for the separately operated email worker. */
@Service
public class EmailNotificationSettingsService {
    public record EventSetting(String eventKey, String label, boolean enabled) {}
    public record Settings(boolean enabled, List<EventSetting> events) {}

    private enum Event {
        DEFECT_REPORTED("Defect reported"),
        REVIEW_APPROVED("Review approved"),
        REVIEW_REJECTED("Review rejected"),
        ACTION_ASSIGNED("Maintenance action assigned"),
        ACTION_STARTED("Maintenance action started"),
        ACTION_COMPLETED("Maintenance action completed"),
        INSPECTION_COMPLETED("Inspection completed"),
        WORK_VERIFIED("Work verified"),
        VERIFICATION_REJECTED("Verification rejected"),
        ACTION_CLOSE_SUBMITTED("Closure submitted"),
        CLOSURE_APPROVED("Closure approved"),
        CLOSURE_REJECTED("Closure rejected"),
        DEFECT_CLOSED("Defect closed"),
        DEFECT_REOPENED("Defect reopened");

        private final String label;
        Event(String label) { this.label = label; }
    }

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public EmailNotificationSettingsService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Settings get(AuthenticatedUser user) {
        requireAdministrator(user);
        return load(user);
    }

    @Transactional
    public Settings updateGlobal(AuthenticatedUser user, boolean enabled) {
        requireAdministrator(user);
        setAuditContext(user.externalUserId());
        int changed = jdbc.update("UPDATE email_notification_settings_mst SET is_enabled=?,updated_by=? WHERE operator_id=?",
                enabled, user.externalUserId(), user.organizationId());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email notification settings were not found.");
        return load(user);
    }

    @Transactional
    public Settings updateEvent(AuthenticatedUser user, String eventKey, boolean enabled) {
        requireAdministrator(user);
        Event event = parseEvent(eventKey);
        setAuditContext(user.externalUserId());
        int changed = jdbc.update("UPDATE email_notification_event_settings_mst SET is_enabled=?,updated_by=? WHERE operator_id=? AND event_key=?",
                enabled, user.externalUserId(), user.organizationId(), event.name());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email notification settings were not found.");
        return load(user);
    }

    private Settings load(AuthenticatedUser user) {
        Boolean enabled = jdbc.query("SELECT is_enabled FROM email_notification_settings_mst WHERE operator_id=?",
                rs -> rs.next() ? rs.getBoolean(1) : null, user.organizationId());
        if (enabled == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email notification settings were not found.");
        List<EventSetting> events = jdbc.query("SELECT event_key,is_enabled FROM email_notification_event_settings_mst WHERE operator_id=? ORDER BY event_key",
                (rs, row) -> {
                    Event event = parseEvent(rs.getString("event_key"));
                    return new EventSetting(event.name(), event.label, rs.getBoolean("is_enabled"));
                }, user.organizationId());
        events.sort(Comparator.comparingInt(item -> Event.valueOf(item.eventKey()).ordinal()));
        return new Settings(enabled, List.copyOf(events));
    }

    private void setAuditContext(String actor) {
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)", String.class, actor);
        String requestId = MDC.get("requestId");
        if (requestId == null) requestId = MDC.get("request_id");
        jdbc.queryForObject("SELECT set_config('ecabin.request_id', ?, true)", String.class, requestId == null ? "" : requestId);
    }

    private void requireAdministrator(AuthenticatedUser user) {
        if (user == null) throw new AccessDeniedException("An active organization membership is required.");
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
    }

    private Event parseEvent(String value) {
        try { return Event.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Choose a supported email notification event."); }
    }
}
