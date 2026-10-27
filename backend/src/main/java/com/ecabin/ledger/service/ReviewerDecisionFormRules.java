package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.OptionSource;
import com.ecabin.ledger.api.FormModels.VisibilityRule;
import java.util.EnumMap;
import java.util.Map;

/** Area-neutral allow-listed shape for Reviewer decisions; conditions are typed equality checks only. */
public final class ReviewerDecisionFormRules {
    private ReviewerDecisionFormRules() {}

    public static void requireValid(FormConfiguration configuration) {
        if (configuration == null || configuration.fields() == null || configuration.fields().size() != 4)
            throw new IllegalArgumentException("A Reviewer Decision form must contain Decision, Assign Team, Rejection Reason, and optional Remark fields.");
        Map<DefectFieldBinding,FieldDefinition> fields = new EnumMap<>(DefectFieldBinding.class);
        for (FieldDefinition field : configuration.fields()) {
            if (field.type() == FieldType.SECTION || field.binding() == null || fields.putIfAbsent(field.binding(),field) != null)
                throw new IllegalArgumentException("Configure exactly one field for each approved Reviewer Decision binding.");
        }
        if (fields.size()!=4) throw new IllegalArgumentException("Configure exactly Decision, Assign Team, Rejection Reason, and Remark fields.");
        FieldDefinition decision=fields.get(DefectFieldBinding.REVIEW_DECISION);
        FieldDefinition team=fields.get(DefectFieldBinding.REVIEW_MAINTENANCE_TEAM);
        FieldDefinition reason=fields.get(DefectFieldBinding.REVIEW_REJECTION_REASON);
        FieldDefinition remark=fields.get(DefectFieldBinding.REVIEW_REMARK);
        if (decision==null||team==null||reason==null||remark==null)
            throw new IllegalArgumentException("Configure the approved Decision, Assign Team, Rejection Reason, and Remark bindings.");
        if (decision.type()!=FieldType.DROPDOWN||!decision.required()||decision.optionSource()!=OptionSource.REVIEW_DECISIONS||!alwaysVisible(decision))
            throw new IllegalArgumentException("Decision must be a visible required dropdown using the approved Reviewer decisions source.");
        if (team.type()!=FieldType.DROPDOWN||!team.required()||team.optionSource()!=OptionSource.MAINTENANCE_TEAMS||!when(team,decision,"APPROVE"))
            throw new IllegalArgumentException("Assign Team must be a required maintenance-team dropdown visible only when Decision is APPROVE.");
        if (reason.type()!=FieldType.TEXTAREA||!reason.required()||!withinLimit(reason,2000)||!when(reason,decision,"REJECT"))
            throw new IllegalArgumentException("Rejection Reason must be required multiline text up to 2000 characters, visible only when Decision is REJECT.");
        if (remark.type()!=FieldType.TEXTAREA||remark.required()||!withinLimit(remark,2000)||!alwaysVisible(remark))
            throw new IllegalArgumentException("Remark must be optional multiline text up to 2000 characters.");
    }

    private static boolean withinLimit(FieldDefinition field,int max) {
        return field.textRules()!=null&&field.textRules().maxLength()!=null&&field.textRules().maxLength()>0&&field.textRules().maxLength()<=max;
    }
    private static boolean alwaysVisible(FieldDefinition field) { return !Boolean.FALSE.equals(field.visible())&&field.visibleWhen()==null&&!Boolean.TRUE.equals(field.readOnly()); }
    private static boolean when(FieldDefinition field,FieldDefinition decision,String value) {
        VisibilityRule rule=field.visibleWhen();
        return !Boolean.FALSE.equals(field.visible())&&!Boolean.TRUE.equals(field.readOnly())&&rule!=null&&decision.key().equals(rule.fieldKey())&&value.equals(rule.expectedValue());
    }
}
