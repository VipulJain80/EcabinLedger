package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.FormModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConditionalFieldRulesTest {
    private FieldDefinition field(String key, FieldType type, VisibilityRule rule) {
        return new FieldDefinition(key,key,type,null,false,0,null,null,null,null,null,null,null,true,false,rule,null);
    }

    @Test void acceptsGenericRulesForDifferentFieldKindsAndWorkflowPurposes() {
        FieldDefinition source=field("choice",FieldType.DROPDOWN,null);
        FieldDefinition target=field("details",FieldType.TEXT,new VisibilityRule("choice","APPROVE"));
        assertDoesNotThrow(()->ConditionalFieldRules.validate(List.of(source,target)));
        assertTrue(ConditionalFieldRules.isVisible(target,Map.of("choice",source,"details",target),Map.of("choice","APPROVE"),Set.of()));
        assertFalse(ConditionalFieldRules.isVisible(target,Map.of("choice",source,"details",target),Map.of(),Set.of()));
    }

    @Test void matchesBooleanAndMultiSelectAnswers() {
        FieldDefinition checkbox=field("flag",FieldType.CHECKBOX,null);
        FieldDefinition checkboxTarget=field("checkboxTarget",FieldType.TEXT,new VisibilityRule("flag","true"));
        FieldDefinition multi=field("choices",FieldType.MULTI_SELECT,null);
        FieldDefinition multiTarget=field("multiTarget",FieldType.TEXT,new VisibilityRule("choices","B"));
        Map<String,FieldDefinition> fields=Map.of("flag",checkbox,"checkboxTarget",checkboxTarget,"choices",multi,"multiTarget",multiTarget);
        assertTrue(ConditionalFieldRules.isVisible(checkboxTarget,fields,Map.of("flag",true),Set.of()));
        assertTrue(ConditionalFieldRules.isVisible(multiTarget,fields,Map.of("choices",List.of("A","B")),Set.of()));
    }

    @Test void rejectsMissingSelfAndCyclicDependencies() {
        assertThrows(IllegalArgumentException.class,()->ConditionalFieldRules.validate(List.of(field("x",FieldType.TEXT,new VisibilityRule("missing","yes")))));
        assertThrows(IllegalArgumentException.class,()->ConditionalFieldRules.validate(List.of(field("x",FieldType.TEXT,new VisibilityRule("x","yes")))));
        assertThrows(IllegalArgumentException.class,()->ConditionalFieldRules.validate(List.of(
                field("a",FieldType.TEXT,new VisibilityRule("b","yes")),field("b",FieldType.TEXT,new VisibilityRule("a","yes")))));
    }

    @Test void hiddenSourceAndStaticVisibilityFailClosed() {
        FieldDefinition hidden= new FieldDefinition("source","Source",FieldType.TEXT,null,false,0,null,null,null,null,null,null,null,false,false,null,null);
        FieldDefinition target=field("target",FieldType.TEXT,new VisibilityRule("source","yes"));
        assertFalse(ConditionalFieldRules.isVisible(target,Map.of("source",hidden,"target",target),Map.of("source","yes"),Set.of()));
    }
}
