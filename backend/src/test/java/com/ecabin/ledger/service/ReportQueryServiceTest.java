package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecabin.ledger.api.ReportApiException;
import com.ecabin.ledger.api.ReportQueryModels.QueryRequest;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

class ReportQueryServiceTest {
    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PermissionService permissions = org.mockito.Mockito.mock(PermissionService.class);
    private final ReportQueryService service = new ReportQueryService(jdbc, permissions);
    private final UUID tenant = UUID.randomUUID();
    private final UUID report = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(tenant, "subject-1", "Reporter", "REPORT_USER");

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activeMembership() {
        doReturn(true).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    void activeMembershipIsRequiredBeforeApplicationPermissions() {
        doReturn(false).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        assertThrows(AccessDeniedException.class,
            () -> service.query(report, new QueryRequest(Map.of(), 0, 25, List.of()), user, requestId));
    }

    @Test
    void requiresBothReportApplicationPermissions() {
        doThrow(new AccessDeniedException("forbidden")).when(permissions).require(user, Permission.REPORT_EXECUTE);
        assertThrows(AccessDeniedException.class,
            () -> service.query(report, new QueryRequest(Map.of(), 0, 25, List.of()), user, requestId));
        verify(permissions).require(user, Permission.REPORT_VIEW);
        verify(permissions).require(user, Permission.REPORT_EXECUTE);
    }

    @Test
    void requiresTheExistingDefectReadPermissionForDefectScopedRoutines() {
        doThrow(new AccessDeniedException("forbidden")).when(permissions).requireDefectRead(user);
        assertThrows(AccessDeniedException.class,
            () -> service.query(report, new QueryRequest(Map.of(), 0, 25, List.of()), user, requestId));
        verify(permissions).requireDefectRead(user);
    }

    @Test
    void hidesUnavailableOrCrossTenantReportMetadata() {
        ReportApiException error = assertThrows(ReportApiException.class,
            () -> service.query(report, new QueryRequest(Map.of(), 0, 25, List.of()), user, requestId));
        assertEquals(404, error.status().value());
        assertEquals("not_found", error.code());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void loadsTheFirstAndEveryRegisteredRoutineParameter() throws Exception {
        doReturn(false).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doAnswer(invocation -> {
            RowMapper<ReportQueryService.RoutineParameter> mapper = invocation.getArgument(1);
            String[][] fixture = {
                {"from_date", "DATE", "false", "true"},
                {"to_date", "DATE", "false", "true"},
                {"area", "TEXT", "false", "true"},
                {"status", "TEXT", "false", "true"}
            };
            List<ReportQueryService.RoutineParameter> rows = new ArrayList<>();
            for (int index = 0; index < fixture.length; index++) {
                ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
                when(row.getString(1)).thenReturn(fixture[index][0]);
                when(row.getString(2)).thenReturn(fixture[index][1]);
                when(row.getBoolean(3)).thenReturn(Boolean.parseBoolean(fixture[index][2]));
                when(row.getBoolean(4)).thenReturn(Boolean.parseBoolean(fixture[index][3]));
                rows.add(mapper.mapRow(row, index));
            }
            return rows;
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        Map<String, ReportQueryService.RoutineParameter> parameters = service.routineParameters(UUID.randomUUID(), 2);

        assertEquals(Set.of("from_date", "to_date", "area", "status"), parameters.keySet());
    }

}
