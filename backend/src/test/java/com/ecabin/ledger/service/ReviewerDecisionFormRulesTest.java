package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.OptionSource;
import com.ecabin.ledger.api.FormModels.TextRules;
import com.ecabin.ledger.api.FormModels.VisibilityRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewerDecisionFormRulesTest {
    @Test
    void allowsOnlyTheApprovedFourFieldsWithTypedDecisionConditions() {
        assertDoesNotThrow(() -> ReviewerDecisionFormRules.requireValid(configuration()));
    }

    @Test
    void rejectsTeamWithoutApproveCondition() {
        var fields = new java.util.ArrayList<>(configuration().fields());
        fields.set(1, field("maintenanceTeam", "Assign Team", FieldType.DROPDOWN, true, OptionSource.MAINTENANCE_TEAMS,
            DefectFieldBinding.REVIEW_MAINTENANCE_TEAM, new VisibilityRule("decision", "REJECT"), null));
        assertThrows(IllegalArgumentException.class, () -> ReviewerDecisionFormRules.requireValid(new FormConfiguration(fields)));
    }

    @Test
    void rejectsMissingRequiredRejectionReasonAndExtraBusinessField() {
        var fields = configuration().fields().stream().filter(field -> field.binding() != DefectFieldBinding.REVIEW_REJECTION_REASON).toList();
        assertThrows(IllegalArgumentException.class, () -> ReviewerDecisionFormRules.requireValid(new FormConfiguration(fields)));
        var extra = new java.util.ArrayList<>(configuration().fields());
        extra.add(field("extra", "Extra", FieldType.TEXT, false, null, null, null, new TextRules(null, 20, false, false, null)));
        assertThrows(IllegalArgumentException.class, () -> ReviewerDecisionFormRules.requireValid(new FormConfiguration(extra)));
    }

    @Test
    void visibilityConditionUsesThePublishedFormEqualsProperty() throws Exception {
        var mapper=new ObjectMapper();
        String json=mapper.writeValueAsString(new VisibilityRule("decision","REJECT"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"equals\":\"REJECT\""));
        assertDoesNotThrow(()->mapper.readValue(json,VisibilityRule.class));
        org.junit.jupiter.api.Assertions.assertEquals("REJECT",mapper.readValue(json,VisibilityRule.class).expectedValue());
    }

    private static FormConfiguration configuration() {
        return new FormConfiguration(List.of(
            field("decision", "Decision", FieldType.DROPDOWN, true, OptionSource.REVIEW_DECISIONS, DefectFieldBinding.REVIEW_DECISION, null, null),
            field("maintenanceTeam", "Assign Team", FieldType.DROPDOWN, true, OptionSource.MAINTENANCE_TEAMS, DefectFieldBinding.REVIEW_MAINTENANCE_TEAM, new VisibilityRule("decision", "APPROVE"), null),
            field("rejectionReason", "Rejection Reason", FieldType.TEXTAREA, true, null, DefectFieldBinding.REVIEW_REJECTION_REASON, new VisibilityRule("decision", "REJECT"), new TextRules(null, 2000, false, false, null)),
            field("remark", "Remark", FieldType.TEXTAREA, false, null, DefectFieldBinding.REVIEW_REMARK, null, new TextRules(null, 2000, false, false, null))));
    }

    private static FieldDefinition field(String key, String label, FieldType type, boolean required, OptionSource source,
            DefectFieldBinding binding, VisibilityRule condition, TextRules textRules) {
        return new FieldDefinition(key,label,type,null,required,0,textRules,null,null,source,null,null,binding,true,false,condition);
    }
}
