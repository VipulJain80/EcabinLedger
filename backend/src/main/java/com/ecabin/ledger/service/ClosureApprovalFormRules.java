package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.OptionSource;
import java.util.List;

/** Validates the exact, workflow-controlled fields for a cabin-area closure decision form. */
public final class ClosureApprovalFormRules {
    private ClosureApprovalFormRules() {}

    public static void requireValid(FormConfiguration configuration) {
        List<FieldDefinition> fields=configuration.fields().stream().filter(f->f.type()!=FieldType.SECTION).toList();
        if(fields.size()!=2)throw new IllegalArgumentException("A Closure Review form must contain exactly Closure Decision and Remarks.");
        FieldDefinition decision=fields.stream().filter(f->f.binding()==DefectFieldBinding.CLOSURE_DECISION).findFirst().orElse(null);
        FieldDefinition remark=fields.stream().filter(f->f.binding()==DefectFieldBinding.CLOSURE_REMARK).findFirst().orElse(null);
        if(decision==null||remark==null)throw new IllegalArgumentException("Configure the approved Closure Decision and Closure Remarks bindings exactly once.");
        if(decision.type()!=FieldType.DROPDOWN||!decision.required()||decision.optionSource()!=OptionSource.CLOSURE_DECISIONS||Boolean.FALSE.equals(decision.visible())||Boolean.TRUE.equals(decision.readOnly())||decision.visibleWhen()!=null)
            throw new IllegalArgumentException("Closure Decision must be an editable, visible, required decision control using ACCEPT and DECLINE.");
        if(remark.type()!=FieldType.TEXTAREA||remark.required()||Boolean.FALSE.equals(remark.visible())||Boolean.TRUE.equals(remark.readOnly())||remark.textRules()==null||remark.textRules().maxLength()==null||remark.textRules().maxLength()>2000||remark.requiredWhen()==null||!decision.key().equals(remark.requiredWhen().fieldKey())||!"DECLINE".equals(remark.requiredWhen().expectedValue()))
            throw new IllegalArgumentException("Remarks must be optional multiline text up to 2,000 characters and required only when the decision is DECLINE.");
    }
}
