package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.RowMapper;

class NotificationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationService service = new NotificationService(jdbc);
    private final UUID organization = UUID.randomUUID();
    private final UUID defect = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    @Test void reviewerRecipientsAreResolvedByTenantTeamAndLiveReviewPermissionAndEventIsDeduplicated() {
        service.notifyReviewers(organization, defect, event, "reporter", "DEFECT_REPORTED", "New defect");
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("d.operator_id=? AND d.id=? AND d.isactive=1"));
        assertTrue(sql.getValue().contains("t.team_type='REVIEWER' AND t.isactive=1"));
        assertTrue(sql.getValue().contains("'%|REVIEW|%'"));
        assertTrue(sql.getValue().contains("source_event_id"));
        assertTrue(sql.getValue().contains("ON CONFLICT DO NOTHING"));
    }

    @Test void assignmentRecipientsRequireActiveMaintenancePermissionAndTenantTeamMembership() {
        service.notifyMaintenance(organization, defect, event, "reviewer", "Cabin Maintenance", "tech-1", "ACTION_ASSIGNED", "Assigned");
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("m.operator_id=?"));
        assertTrue(sql.getValue().contains("t.team_type='MAINTENANCE' AND t.isactive=1"));
        assertTrue(sql.getValue().contains("'%|PERFORM_ACTION|%'"));
        assertTrue(sql.getValue().contains("UNION"));
    }

    @Test void lifecycleParticipantNotificationsAreDeduplicatedAndTenantScoped() {
        service.notifyParticipants(organization, defect, event, "reviewer", "DEFECT_CLOSED", "Defect closed");
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("d.operator_id=? AND d.id=?"));
        assertTrue(sql.getValue().contains("t.team_type='REVIEWER' AND t.isactive=1"));
        assertTrue(sql.getValue().contains("active_member.isactive=1"));
        assertTrue(sql.getValue().contains("source_event_id"));
        assertTrue(sql.getValue().contains("ON CONFLICT DO NOTHING"));
        assertFalse(sql.getValue().contains("PERFORM_ACTION"), "completed work must not notify users who no longer have access to it");
    }

    @Test void inboxAndReadOperationsAreBoundToAuthenticatedRecipientAndOrganization() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        var result = service.inbox(organization, "reviewer", false, false, true);
        assertEquals(3, result.unreadCount());
        var selectSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(selectSql.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(selectSql.getAllValues().stream().allMatch(sql -> sql.contains("n.operator_id=? AND n.recipient_user_id=?")));
        assertTrue(selectSql.getAllValues().get(0).contains("d.reviewer_team_id"));

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        service.markRead(organization, "reviewer", UUID.randomUUID(), false, false, true);
        var updateSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(updateSql.capture(), any(Object[].class));
        assertTrue(updateSql.getValue().contains("n.operator_id=? AND n.recipient_user_id=? AND n.id=?"));
        assertTrue(updateSql.getValue().contains("d.reviewer_team_id"));
    }

    @Test void inaccessibleOrOtherUsersNotificationCannotBeMarkedRead() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThrows(ResponseStatusException.class,
            () -> service.markRead(organization, "reviewer", UUID.randomUUID(), false, false, true));
    }

    @Test void markAllReadOnlyUpdatesTheCurrentRecipientInbox() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);
        service.markAllRead(organization, "reporter", false, true, false);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("n.operator_id=? AND n.recipient_user_id=?"));
        assertTrue(sql.getValue().contains("d.reported_by=?"));
        assertTrue(sql.getValue().contains("n.read_at IS NULL"));
    }
}
