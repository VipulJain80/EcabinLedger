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

    @Test void acceptsAndSerializesIndependentUploadRulesForBothFieldTypes(){
        for(FieldType type:List.of(FieldType.IMAGE_UPLOAD,FieldType.FILE_UPLOAD)){
            List<String> extensions=type==FieldType.IMAGE_UPLOAD?List.of("png"):List.of("png","pdf");
            List<String> mimes=type==FieldType.IMAGE_UPLOAD?List.of("image/png"):List.of("image/png","application/pdf");
            var field=new FieldDefinition("evidence","Evidence",type,null,false,0,null,null,null,null,null,
                new FileRules(extensions,mimes,20L*1024*1024,1,3,30L*1024*1024));
            var configuration=service.validateConfiguration(operator,area,new FormConfiguration(List.of(field)));
            assertEquals(30L*1024*1024,configuration.fields().get(0).fileRules().maxTotalSizeBytes());
            String json;
            try{json=new ObjectMapper().writeValueAsString(configuration);}catch(Exception ex){throw new AssertionError(ex);}
            assertTrue(json.contains("\"maxTotalSizeBytes\":31457280"));
            try{
                var reloaded=new ObjectMapper().readValue(json,FormConfiguration.class);
                assertEquals(30L*1024*1024,reloaded.fields().get(0).fileRules().maxTotalSizeBytes());
            }catch(Exception ex){throw new AssertionError(ex);}
        }
    }

    @Test void existingUploadConfigurationsWithoutTotalLimitRemainValidAndKeepAbsentProperty(){
        var legacy=new FieldDefinition("evidence","Evidence",FieldType.IMAGE_UPLOAD,null,true,0,null,null,null,null,null,
            new FileRules(List.of("png"),List.of("image/png"),5L*1024*1024,1,3));
        var validated=service.validateConfiguration(operator,area,new FormConfiguration(List.of(legacy)));
        assertNull(validated.fields().get(0).fileRules().maxTotalSizeBytes());
        assertEquals(15L*1024*1024,com.ecabin.ledger.storage.EvidenceFileValidator.maxTotalBytes(validated.fields().get(0)));
    }

    @Test void rejectsUnsupportedCombinedUploadLimits(){
        for(Long limit:List.of(0L,-1L,19L*1024*1024,201L*1024*1024,30L*1024*1024+1)){
            var invalid=new FieldDefinition("evidence","Evidence",FieldType.FILE_UPLOAD,null,false,0,null,null,null,null,null,
                new FileRules(List.of("pdf"),List.of("application/pdf"),20L*1024*1024,0,3,limit));
            assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(invalid))),String.valueOf(limit));
        }
    }

    @Test void textAndTextareaDefaultsRoundTripWithVersionedConfiguration(){
        for(FieldType type:List.of(FieldType.TEXT,FieldType.TEXTAREA)){
            var field=new FieldDefinition("notes","Notes",type,null,false,0,new TextRules(1,40,false,false,"[A-Za-z ]+"),null,null,null,null,null,null,true,false,null,null,"Starting notes");
            var configuration=service.validateConfiguration(operator,area,new FormConfiguration(List.of(field)));
            try{
                String stored=new ObjectMapper().writeValueAsString(configuration);
                FormConfiguration reloaded=new ObjectMapper().readValue(stored,FormConfiguration.class);
                assertEquals("Starting notes",reloaded.fields().get(0).defaultValue());
                FormConfiguration copied=new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(reloaded),FormConfiguration.class);
                assertEquals("Starting notes",copied.fields().get(0).defaultValue());
            }catch(Exception ex){throw new AssertionError(ex);}
        }
    }

    @Test void validatesDefaultsAgainstTextRulesAndDisallowsUnsupportedFieldTypes(){
        var tooLong=new FieldDefinition("notes","Notes",FieldType.TEXT,null,false,0,new TextRules(null,5,false,false,null),null,null,null,null,null,null,true,false,null,null,"default too long");
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(tooLong))));
        var wrongPattern=new FieldDefinition("notes","Notes",FieldType.TEXTAREA,null,false,0,new TextRules(null,40,true,false,null),null,null,null,null,null,null,true,false,null,null,"123");
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(wrongPattern))));
        var unsupported=new FieldDefinition("choice","Choice",FieldType.DROPDOWN,null,false,0,null,null,null,OptionSource.CABIN_AREAS,null,null,null,true,false,null,null,"CABIN");
        assertThrows(IllegalArgumentException.class,()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(unsupported))));
        var empty=new FieldDefinition("notes","Notes",FieldType.TEXT,null,false,0,new TextRules(1,40,false,false,null),null,null,null,null,null,null,true,false,null,null,"");
        assertDoesNotThrow(()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(empty))));
        var nullDefault=new FieldDefinition("notes","Notes",FieldType.TEXT,null,false,0,new TextRules(1,40,false,false,null),null,null,null,null,null,null,true,false,null,null,null);
        assertDoesNotThrow(()->service.validateConfiguration(operator,area,new FormConfiguration(List.of(nullDefault))));
    }

    @Test void legacyFormJsonWithoutDefaultValueLoadsWithoutIntroducingOne(){
        String json="{\"fields\":[{\"key\":\"notes\",\"label\":\"Notes\",\"type\":\"TEXT\",\"required\":false,\"order\":0}]}";
        try{assertNull(new ObjectMapper().readValue(json,FormConfiguration.class).fields().get(0).defaultValue());}
        catch(Exception ex){throw new AssertionError(ex);}
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
        assertTrue(query.getValue().contains("f.operator_id=? AND va.area_id=? AND v.purpose=? AND v.workflow_stage=? AND f.isactive=1"));
        assertTrue(query.getValue().contains("v.id=f.published_version_id AND v.status='PUBLISHED'"));
        assertTrue(query.getValue().contains("v.form_name"));
        assertTrue(query.getValue().contains("va.area_id AS version_area_id"));
        assertTrue(query.getValue().contains("v.operator_id=f.operator_id AND v.form_id=f.id"));
        assertTrue(query.getValue().contains("JOIN operational_form_version_areas_dtl va"));
        assertFalse(query.getValue().contains("cabin_zones"));
    }

    @Test void inactiveOrForeignTenantAreaReturnsNoFormsAndDoesNotQueryMappings(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        assertThrows(IllegalArgumentException.class,()->mappedService.publishedForArea(operator,area));
        verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void mappingOptionsComeFromActiveTenantAreasAndBackendPurposeStageRules(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);UUID galley=UUID.randomUUID(),cabin=UUID.randomUUID(),futureArea=UUID.randomUUID();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(
            Map.of("id",galley,"code","GALLEY"),Map.of("id",cabin,"code","CABIN"),Map.of("id",futureArea,"code","NEW_SECTION")));
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        var options=mappedService.mappingOptions(operator);

        assertTrue(options.stream().anyMatch(option->option.areaId().equals(galley)&&option.purpose()==FormPurpose.CLOSURE_APPROVAL&&option.workflowStage()==WorkflowStage.APPROVAL_REQUIRED));
        assertTrue(options.stream().anyMatch(option->option.areaId().equals(cabin)&&option.purpose()==FormPurpose.CLOSURE_APPROVAL&&option.workflowStage()==WorkflowStage.APPROVAL_REQUIRED));
        assertTrue(options.stream().anyMatch(option->option.areaId().equals(futureArea)&&option.purpose()==FormPurpose.CLOSURE_APPROVAL&&option.workflowStage()==WorkflowStage.APPROVAL_REQUIRED));
        assertFalse(options.stream().anyMatch(option->option.purpose()==FormPurpose.DEFECT_REPORT&&option.workflowStage()!=WorkflowStage.INITIAL_REPORT));
    }

    @Test void sharedWorkflowPurposeMappingsAreOfferedForEveryAuditedAndFutureArea(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        List<String> codes=List.of("ATTENDANT_SEAT","CABIN","ENTRY_AREA","GALLEY","LAVATORY","AIRPORT_TAXI");
        List<Map<String,Object>> areas=new java.util.ArrayList<>();
        for(String code:codes)areas.add(Map.of("id",UUID.randomUUID(),"code",code));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(areas);
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        var options=mappedService.mappingOptions(operator);

        for(String code:codes)for(FormPurpose purpose:List.of(FormPurpose.REVIEW_DECISION,FormPurpose.CORRECTIVE_ACTION,FormPurpose.CLOSURE_APPROVAL)){
            var matching=options.stream().filter(option->option.areaCode().equals(code)&&option.purpose()==purpose).toList();
            assertFalse(matching.isEmpty(),code+" / "+purpose);
        }
        assertTrue(options.stream().filter(option->option.purpose()==FormPurpose.REVIEW_DECISION)
            .allMatch(option->option.workflowStage()==WorkflowStage.REPORTED||option.workflowStage()==WorkflowStage.UNDER_REVIEW));
    }

    @Test void closureFormResolutionUsesAnyActiveTenantAreaWithoutGalleySpecialCasing(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        assertTrue(mappedService.publishedForArea(operator,area,FormPurpose.CLOSURE_APPROVAL,WorkflowStage.APPROVAL_REQUIRED).isEmpty());

        var query=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(query.capture(),any(RowMapper.class),any(Object[].class));
        assertTrue(query.getValue().contains("va.area_id=? AND v.purpose=? AND v.workflow_stage=?"));
        verify(jdbc,never()).queryForObject(contains("SELECT code FROM cabin_areas_mst"),eq(String.class),any(Object[].class));
    }

    @Test void mappingOptionsExposeExistingSelectionsAndCanLimitChoicesToRequestedAreas(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);UUID galley=UUID.randomUUID(),cabin=UUID.randomUUID(),formId=UUID.randomUUID();
        when(jdbc.query(contains("SELECT id,code FROM cabin_areas_mst"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(
            Map.of("id",galley,"code","GALLEY"),Map.of("id",cabin,"code","CABIN")));
        when(jdbc.query(contains("SELECT area_id FROM operational_form_areas_mst"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(galley));
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        var options=mappedService.mappingOptions(operator,formId,List.of(galley));

        assertFalse(options.isEmpty());
        assertTrue(options.stream().allMatch(option->option.areaId().equals(galley)));
        assertTrue(options.stream().anyMatch(option->option.mapped()&&option.purpose()==FormPurpose.DEFECT_REPORT&&option.workflowStage()==WorkflowStage.INITIAL_REPORT));
    }

    @Test void mappingOptionsRejectInactiveOrCrossTenantAreaSelections(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);UUID galley=UUID.randomUUID(),formId=UUID.randomUUID(),unavailable=UUID.randomUUID();
        when(jdbc.query(contains("SELECT id,code FROM cabin_areas_mst"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(Map.of("id",galley,"code","GALLEY")));
        when(jdbc.query(contains("SELECT area_id FROM operational_form_areas_mst"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->mappedService.mappingOptions(operator,formId,List.of(unavailable)));
    }

    @Test void areaReassignmentIsTenantScopedAuditedAndCreatesMappingSnapshotDraft(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        UUID formId=UUID.randomUUID(),previousArea=UUID.randomUUID(),nextArea=UUID.randomUUID();
        Map<String,Object> form=new java.util.HashMap<>();form.put("isactive",1);form.put("area_id",previousArea);form.put("purpose","DEFECT_REPORT");form.put("workflow_stage","INITIAL_REPORT");form.put("name","Galley Area");form.put("published_version_id",null);
        when(jdbc.query(contains("FOR UPDATE OF f"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(form));
        when(jdbc.query(contains("SELECT id,fields_json::text AS fields_json"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.query(contains("SELECT f.*,a.name AS area_name"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(form));
        when(jdbc.query(contains("SELECT area_id FROM operational_form_areas_mst"),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(previousArea));
        when(jdbc.queryForObject(contains("cabin_areas_mst"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        when(jdbc.queryForObject(contains("set_config"),eq(String.class),any(Object[].class))).thenReturn("admin-user");
        when(jdbc.queryForObject(contains("max(version_number)"),eq(Integer.class),any(Object[].class))).thenReturn(2);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        var mappedService=new OperationalFormService(jdbc,new ObjectMapper());

        mappedService.reassignArea(operator,"admin-user",formId,nextArea,"Correct area assignment");

        var updates=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc,times(6)).update(updates.capture(),any(Object[].class));
        assertTrue(updates.getAllValues().stream().anyMatch(sql->sql.contains("UPDATE operational_forms_dtl SET area_id=?")));
        assertTrue(updates.getAllValues().stream().anyMatch(sql->sql.contains("INSERT INTO operational_form_versions_dtl")&&sql.contains("form_name,area_id,purpose,workflow_stage")));
        assertTrue(updates.getAllValues().stream().anyMatch(sql->sql.contains("DELETE FROM operational_form_areas_mst")));
        assertTrue(updates.getAllValues().stream().anyMatch(sql->sql.contains("INSERT INTO operational_form_areas_mst")));
        assertTrue(updates.getAllValues().stream().anyMatch(sql->sql.contains("INSERT INTO defect_events_dtl")),"The mapping change must be audited.");
    }

    private FieldDefinition field(String key,FieldType type){return new FieldDefinition(key,key,type,null,false,0,null,null,null,null,null,null);}
}
