package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ecabin.ledger.api.FormModels.*;
import java.util.List;
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
}
