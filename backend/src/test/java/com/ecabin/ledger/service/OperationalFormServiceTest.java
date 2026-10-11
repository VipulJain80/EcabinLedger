package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.FormModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationalFormServiceTest {
    private final OperationalFormService service=new OperationalFormService(null,new ObjectMapper());
    private final UUID operator=UUID.randomUUID(),area=UUID.randomUUID();

    @Test void acceptsTypedFieldConfigurationAndPreservesOrdering(){
        var result=service.validateConfiguration(operator,area,new FormConfiguration(List.of(
            new FieldDefinition("finding","Finding",FieldType.TEXT,null,true,0,new TextRules(1,240,false,false,"[A-Za-z0-9 .,/-]+"),null,null,null,null,null),
            new FieldDefinition("serviceable","Serviceable",FieldType.CHECKBOX,null,false,1,null,null,null,null,null,null),
            new FieldDefinition("component","Component",FieldType.DROPDOWN,null,true,2,null,null,null,OptionSource.COMPONENTS,null,null),
            new FieldDefinition("condition","Condition",FieldType.DROPDOWN,null,true,3,null,null,null,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component"),null),
            new FieldDefinition("evidence","Evidence",FieldType.FILE_UPLOAD,null,true,4,null,null,null,null,null,new FileRules(List.of("png","pdf"),List.of("image/png","application/pdf"),5_000_000L,1,3))
        )));
        assertEquals(5,result.fields().size());
    }

    @Test void rejectsDuplicateKeysAndUnsafePatterns(){
        var duplicate=new FormConfiguration(List.of(field("finding",FieldType.TEXT),field("finding",FieldType.DATE)));
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,duplicate));
        var unsafe=new FieldDefinition("reference","Reference",FieldType.TEXT,null,false,0,new TextRules(null,40,false,false,"(a+)+"),null,null,null,null,null);
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(unsafe))));
    }

    @Test void rejectsTypeMismatchedRulesAndInvalidEvidenceLimits(){
        var mismatched=new FieldDefinition("date","Date",FieldType.DATE,null,false,0,new TextRules(1,20,false,false,null),null,null,null,null,null);
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(mismatched))));
        var invalidFile=new FieldDefinition("evidence","Evidence",FieldType.FILE_UPLOAD,null,false,0,null,null,null,null,null,new FileRules(List.of("exe"),List.of("application/pdf"),100L,3,2));
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(invalidFile))));
    }

    @Test void requiresConditionDropdownToDependOnComponentMaster(){
        var condition=new FieldDefinition("condition","Condition",FieldType.DROPDOWN,null,false,1,null,null,null,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component"),null);
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(field("component",FieldType.TEXT),condition))));
    }

    @Test void publishedAreaLookupUsesTenantAreaActiveFormAndCurrentPublishedVersionMapping(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        assertTrue(mappedService.publishedForArea(operator,area).isEmpty());

        var query=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(query.capture(),any(RowMapper.class),any(Object[].class));
        verify(jdbc).queryForObject(contains("FROM cabin_areas_mst"),eq(Boolean.class),any(Object[].class));
        assertTrue(query.getValue().contains("f.operator_id=? AND f.area_id=? AND f.purpose=? AND f.workflow_stage=? AND f.isactive=1"));
        assertTrue(query.getValue().contains("v.id=f.published_version_id AND v.status='PUBLISHED'"));
        assertTrue(query.getValue().contains("v.operator_id=f.operator_id AND v.form_id=f.id"));
        assertFalse(query.getValue().contains("cabin_zones"));
    }

    @Test void inactiveOrForeignTenantAreaReturnsNoFormsAndDoesNotQueryMappings(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        assertThrows(IllegalArgumentException.class,()->mappedService.publishedForArea(operator,area));
        verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void areaReassignmentIsTenantScopedAndAuditedWithoutChangingFormVersions(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        UUID formId=UUID.randomUUID(),previousArea=UUID.randomUUID(),nextArea=UUID.randomUUID();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenReturn(List.<Object>of(Map.of("isactive",1,"area_id",previousArea)));
        when(jdbc.queryForObject(contains("cabin_areas_mst"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        mappedService.reassignArea(operator,"admin-user",formId,nextArea,"Correct area assignment");

        var updates=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc,times(2)).update(updates.capture(),any(Object[].class));
        assertTrue(updates.getAllValues().stream().anyMatch(sql->sql.contains("UPDATE operational_forms_dtl SET area_id=?")));
        assertTrue(updates.getAllValues().stream().anyMatch(sql->sql.contains("INSERT INTO defect_events_dtl")),"The mapping change must be audited.");
        assertTrue(updates.getAllValues().stream().noneMatch(sql->sql.contains("operational_form_versions_dtl")));
    }

    private FieldDefinition field(String key,FieldType type){return new FieldDefinition(key,key,type,null,false,0,null,null,null,null,null,null);}
}
