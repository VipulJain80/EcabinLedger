package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.FormModels.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClosureApprovalFormRulesTest {
    private FieldDefinition decision(){return new FieldDefinition("decision","Decision",FieldType.DROPDOWN,null,true,0,null,null,null,OptionSource.CLOSURE_DECISIONS,null,null,DefectFieldBinding.CLOSURE_DECISION,true,false,null,null);}
    private FieldDefinition remarks(){return new FieldDefinition("remarks","Remarks",FieldType.TEXTAREA,null,false,1,new TextRules(null,2000,null,null,null),null,null,null,null,null,DefectFieldBinding.CLOSURE_REMARK,true,false,null,new RequiredRule("decision","DECLINE"));}

    @Test void acceptsOnlyPublishedDecisionAndConditionalRemarkDefinition(){
        assertDoesNotThrow(()->ClosureApprovalFormRules.requireValid(new FormConfiguration(List.of(decision(),remarks()))));
    }

    @Test void rejectsExtraFieldsAndInvalidRemarkConfiguration(){
        assertThrows(IllegalArgumentException.class,()->ClosureApprovalFormRules.requireValid(new FormConfiguration(List.of(decision(),remarks(),new FieldDefinition("extra","Extra",FieldType.TEXT,null,false,2,null,null,null,null,null,null)))));
        FieldDefinition unbounded=new FieldDefinition("remarks","Remarks",FieldType.TEXTAREA,null,false,1,new TextRules(null,4000,null,null,null),null,null,null,null,null,DefectFieldBinding.CLOSURE_REMARK,true,false,null,new RequiredRule("decision","DECLINE"));
        assertThrows(IllegalArgumentException.class,()->ClosureApprovalFormRules.requireValid(new FormConfiguration(List.of(decision(),unbounded))));
    }
}
