package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ecabin.ledger.api.FormModels.*;
import java.util.List;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MaintenanceActionFormRulesTest {
    private FormConfiguration valid(){
        return new FormConfiguration(List.of(
            field("maintenanceStatus",FieldType.DROPDOWN,true,OptionSource.MAINTENANCE_ACTION_STATUSES,DefectFieldBinding.MAINTENANCE_STATUS,null),
            field("slaTargetDate",FieldType.DATE,true,null,DefectFieldBinding.SLA_TARGET_DATE,null),
            field("remark",FieldType.TEXTAREA,false,null,DefectFieldBinding.MAINTENANCE_REMARK,new TextRules(null,2000,null,null,null))));
    }
    private FieldDefinition field(String key,FieldType type,boolean required,OptionSource source,DefectFieldBinding binding,TextRules rules){
        return new FieldDefinition(key,key,type,null,required,0,rules,null,null,source,null,null,binding,true,false);
    }
    @Test void acceptsOnlyTheThreeRequiredWorkflowBindings(){assertDoesNotThrow(()->MaintenanceActionFormRules.requireValid(valid()));}
    @Test void rejectsMissingDuplicateOrExtraBusinessFields(){
        FormConfiguration valid=valid();
        assertThrows(IllegalArgumentException.class,()->MaintenanceActionFormRules.requireValid(new FormConfiguration(valid.fields().subList(0,2))));
        assertThrows(IllegalArgumentException.class,()->MaintenanceActionFormRules.requireValid(new FormConfiguration(List.of(valid.fields().get(0),valid.fields().get(0),valid.fields().get(2)))));
        assertThrows(IllegalArgumentException.class,()->MaintenanceActionFormRules.requireValid(new FormConfiguration(List.of(valid.fields().get(0),valid.fields().get(1),valid.fields().get(2),field("extra",FieldType.TEXT,false,null,DefectFieldBinding.MAINTENANCE_REMARK,null)))));
    }
    @Test void rejectsWrongStatusSourceAndRequiredOrOversizedRemarks(){
        FormConfiguration valid=valid();
        FieldDefinition badStatus=field("status",FieldType.DROPDOWN,true,OptionSource.REVIEW_DECISIONS,DefectFieldBinding.MAINTENANCE_STATUS,null);
        assertThrows(IllegalArgumentException.class,()->MaintenanceActionFormRules.requireValid(new FormConfiguration(List.of(badStatus,valid.fields().get(1),valid.fields().get(2)))));
        FieldDefinition badRemark=field("remark",FieldType.TEXTAREA,true,null,DefectFieldBinding.MAINTENANCE_REMARK,new TextRules(null,2500,null,null,null));
        assertThrows(IllegalArgumentException.class,()->MaintenanceActionFormRules.requireValid(new FormConfiguration(List.of(valid.fields().get(0),valid.fields().get(1),badRemark))));
    }

    @Test void optionalRemarkVisibilityUsesTheSubmittedMaintenanceStatusAndRejectsStaleHiddenAnswers(){
        FieldDefinition status=valid().fields().get(0);
        FieldDefinition date=valid().fields().get(1);
        FieldDefinition remark=new FieldDefinition("remark","Remarks",FieldType.TEXTAREA,null,false,2,new TextRules(null,2000,null,null,null),null,null,null,null,null,DefectFieldBinding.MAINTENANCE_REMARK,true,false,new VisibilityRule("maintenanceStatus","CLOSE_SUBMITTED"),null);
        FormForArea form=new FormForArea(java.util.UUID.randomUUID(),"MAINTENANCE","Maintenance",java.util.UUID.randomUUID(),FormPurpose.CORRECTIVE_ACTION,WorkflowStage.IN_PROGRESS,java.util.UUID.randomUUID(),1,new FormConfiguration(List.of(status,date,remark)));
        OperationalFormService service=new OperationalFormService(null,new ObjectMapper());
        assertDoesNotThrow(()->service.validateMaintenanceAnswers(form,"WORK_IN_PROGRESS",LocalDate.parse("2026-10-29"),null));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->service.validateMaintenanceAnswers(form,"WORK_IN_PROGRESS",LocalDate.parse("2026-10-29"),"stale remark"));
        assertEquals("A hidden field cannot receive a submitted value.",error.getMessage());
        assertDoesNotThrow(()->service.validateMaintenanceAnswers(form,"CLOSE_SUBMITTED",LocalDate.parse("2026-10-29"),"closure note"));
    }
}
