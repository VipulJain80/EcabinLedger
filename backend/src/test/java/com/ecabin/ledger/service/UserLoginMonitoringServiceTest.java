package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class UserLoginMonitoringServiceTest {
    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PermissionService permissions = org.mockito.Mockito.mock(PermissionService.class);
    private final UUID tenantId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private final UserLoginMonitoringService service = new UserLoginMonitoringService(jdbc, permissions, clock);
    private final AuthenticatedUser admin = new AuthenticatedUser(tenantId, "admin@example.test", "Admin", "ADMIN");

    @BeforeEach void allowAdmin() { doNothing().when(permissions).require(admin, Permission.ADMINISTER_ORGANIZATION); }

    @Test void recordsLoginOnlyForAnActiveMembershipAndAdvancesLastLoginMonotonically() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantId), eq("member@example.test"))).thenReturn(true);

        service.recordSuccessfulLogin(tenantId, "member@example.test", UserLoginMonitoringService.LoginSource.LOCAL_DEMO);

        verify(jdbc).update(eq("INSERT INTO user_login_events_dtl(id,operator_id,external_user_id,event_type,occurred_at,request_id,authentication_source) VALUES(?,?,?,?,?,?,?)"),
            any(UUID.class), eq(tenantId), eq("member@example.test"), eq("LOGIN"), eq(java.sql.Timestamp.from(Instant.parse("2026-10-15T12:00:00Z"))), any(), eq("LOCAL_DEMO"));
        verify(jdbc).update(eq("UPDATE operator_memberships_mst SET last_login_at=GREATEST(COALESCE(last_login_at,?),?) WHERE operator_id=? AND external_user_id=? AND isactive=1"),
            eq(java.sql.Timestamp.from(Instant.parse("2026-10-15T12:00:00Z"))), eq(java.sql.Timestamp.from(Instant.parse("2026-10-15T12:00:00Z"))), eq(tenantId), eq("member@example.test"));
    }

    @Test void doesNotWriteAnEventForAnInactiveMembership() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantId), eq("disabled@example.test"))).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.recordSuccessfulLogin(tenantId, "disabled@example.test", UserLoginMonitoringService.LoginSource.LOCAL_DEMO));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void requiresOrganizationAdministrationBeforeAnyMonitoringQuery() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("forbidden"))
            .when(permissions).require(admin, Permission.ADMINISTER_ORGANIZATION);

        assertThrows(AccessDeniedException.class, () -> service.summary(admin));

        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test void logoutIsAttributedToTheValidatedMembershipAndKnownSourceOnly() {
        service.recordExplicitLogout(admin, "UNTRUSTED_SOURCE_FROM_CLIENT");

        verify(jdbc).update(eq("INSERT INTO user_login_events_dtl(id,operator_id,external_user_id,event_type,occurred_at,request_id,authentication_source) VALUES(?,?,?,?,?,?,?)"),
            any(UUID.class), eq(tenantId), eq("admin@example.test"), eq("LOGOUT"), eq(java.sql.Timestamp.from(Instant.parse("2026-10-15T12:00:00Z"))), any(), eq(null));
    }

    @Test void classifiesTheExactThirtyDayBoundaryAsInactiveAndNeverLoggedInSeparately() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        assertEquals("ACTIVE_RECENTLY", UserLoginMonitoringService.classifyInactivity(now.minus(java.time.Duration.ofDays(30)).plusMillis(1), now));
        assertEquals("INACTIVE_30_DAYS", UserLoginMonitoringService.classifyInactivity(now.minus(java.time.Duration.ofDays(30)), now));
        assertEquals("NEVER_LOGGED_IN", UserLoginMonitoringService.classifyInactivity(null, now));
    }

    @Test void buildsTenantScopedPagedSearchAndUsesOnlyAllowListedSorting() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UserLoginMonitoringService.UserActivity>>any(), any(Object[].class))).thenReturn(List.of());

        var page = service.users(admin, 2, 20, "pilot@example.test", "ACTIVE", "INACTIVE_30", "lastLoginAt", "ASC");

        assertEquals(0, page.totalElements());
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<UserLoginMonitoringService.UserActivity>>any(), any(Object[].class));
        assertTrue(sql.getValue().contains("WHERE m.operator_id=? AND m.isactive=1"));
        assertTrue(sql.getValue().contains("POSITION(LOWER(?) IN LOWER(m.display_name))"));
        assertTrue(sql.getValue().contains("m.last_login_at <= ?"));
        assertTrue(sql.getValue().contains("ORDER BY m.last_login_at ASC"));
    }

    @Test void guessedMembershipFromAnotherTenantCannotReturnEventHistory() {
        when(jdbc.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq(tenantId), eq("other@example.test"))).thenReturn(false);

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.events(admin, "other@example.test", 0, 25));

        verify(jdbc, never()).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UserLoginMonitoringService.LoginEvent>>any(), any(Object[].class));
    }
}
