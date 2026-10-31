package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecabin.ledger.api.ApiModels.MaintenanceActionUpdateRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MaintenanceActionHoldIdempotencyTest {
    @Mock JdbcTemplate jdbc;
    @Mock OperationalFormService forms;
    @Mock MaintenanceSlaPolicyService policies;

    @Test
    void legacyHeldCaseReturnsSuccessWithoutWritesWhenHoldIsRepeated() {
        assertIdempotentState("ON_HOLD", "IN_PROGRESS", "ON_HOLD", "ON_HOLD");
    }

    @Test
    void newHeldCaseReturnsSuccessWithoutWritesWhenHoldIsRepeated() {
        assertIdempotentState("ON_HOLD", "ON_HOLD", "ON_HOLD", "ON_HOLD");
    }

    @Test
    void alreadyResumedCaseReturnsSuccessWithoutDuplicateAuditOrTimestampUpdates() {
        assertIdempotentState("WORK_IN_PROGRESS", "IN_PROGRESS", "IN_PROGRESS", "IN_PROGRESS");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void unauthorizedOrNonMemberCannotUseIdempotentSuccess() {
        UUID tenant=UUID.randomUUID(), defect=UUID.randomUUID(), action=UUID.randomUUID();
        String actor="not-an-assigned-maintenance-user";
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(actor))).thenReturn(actor);
        doReturn(List.of()).when(jdbc).query(anyString(),any(ColumnMapRowMapper.class),any(Object[].class));

        MaintenanceActionService service=new MaintenanceActionService(jdbc,forms,policies);
        assertThrows(ResponseStatusException.class,()->service.update(tenant,defect,action,actor,
            new MaintenanceActionUpdateRequest(UUID.randomUUID(),UUID.randomUUID(),"ON_HOLD",null,null)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(forms,never()).published(any(),any(),any(),any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertIdempotentState(String requested, String defectStatus, String actionStatus, String expectedStatus) {
        UUID tenant=UUID.randomUUID(), defect=UUID.randomUUID(), action=UUID.randomUUID();
        String actor="assigned-maintenance-user";
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(actor))).thenReturn(actor);
        Map<String,Object> current=new java.util.HashMap<>();
        current.put("id",action);current.put("actionStatus",actionStatus);current.put("assignedTeam","Cabin Maintenance");current.put("assignedUserId",actor);current.put("dueAt",null);current.put("defectStatus",defectStatus);current.put("areaId",UUID.randomUUID());
        doReturn(List.of(current)).when(jdbc).query(anyString(),any(ColumnMapRowMapper.class),any(Object[].class));

        MaintenanceActionService service=new MaintenanceActionService(jdbc,forms,policies);
        var result=service.update(tenant,defect,action,actor,new MaintenanceActionUpdateRequest(UUID.randomUUID(),UUID.randomUUID(),requested,null,null));

        assertEquals(action,result.id());
        assertEquals(expectedStatus,result.status());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(forms,never()).published(any(),any(),any(),any());
        verify(policies,never()).validateDate(any(),anyString(),any());
    }
}
