package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AreaMaintenanceRoutingServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AreaMaintenanceRoutingService service=new AreaMaintenanceRoutingService(jdbc,null);

    @Test void mappingRequiresAReasonBeforeAnyDatabaseMutation(){
        assertThrows(IllegalArgumentException.class,()->service.saveMapping(UUID.randomUUID(),"admin",new AreaMaintenanceRoutingService.MappingRequest(null,UUID.randomUUID(),UUID.randomUUID()," ")));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void workflowModeIsAllowListedAndCannotBeChangedToAnUnknownMode(){
        assertThrows(IllegalArgumentException.class,()->service.setWorkflow(UUID.randomUUID(),"admin",new AreaMaintenanceRoutingService.WorkflowRequest("SKIP_REVIEW","review bypass")));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void workflowModeRequiresAnAuditableReason(){
        assertThrows(IllegalArgumentException.class,()->service.setWorkflow(UUID.randomUUID(),"admin",new AreaMaintenanceRoutingService.WorkflowRequest("DIRECT_MAINTENANCE"," ")));
        verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class),any(Object[].class));
    }

    @Test void directMaintenanceAssignmentHandlesMissingOptionalDefectTitle(){
        UUID tenant=UUID.randomUUID(),defect=UUID.randomUUID(),mapping=UUID.randomUUID(),event=UUID.randomUUID();
        when(jdbc.queryForObject(contains("SELECT count(*) FROM corrective_actions_dtl"),eq(Integer.class),any(Object[].class))).thenReturn(0);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(java.util.List.of(event));

        assertTrue(service.assignForDirectMode(tenant,defect,"reporter",mapping,"Maintenance",null,"MINOR"));

        ArgumentCaptor<Object[]> args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO corrective_actions_dtl"),args.capture());
        assertTrue(java.util.Arrays.asList(args.getValue()).contains("Investigate reported defect."));
    }
}
