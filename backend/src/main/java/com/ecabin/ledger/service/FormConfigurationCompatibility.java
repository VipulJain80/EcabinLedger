package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.OptionSource;
import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import java.util.List;

/** Compatibility normalization for legacy form configuration values. */
final class FormConfigurationCompatibility {
    private FormConfigurationCompatibility() {}

    static FormConfiguration canonicalizeSeveritySource(FormConfiguration configuration) {
        if (configuration == null || configuration.fields() == null) return configuration;
        List<FieldDefinition> fields = configuration.fields().stream().map(field -> {
            if (field.binding() != DefectFieldBinding.SEVERITY || field.optionSource() != OptionSource.GALLEY_SEVERITIES) return field;
            return new FieldDefinition(field.key(), field.label(), field.type(), field.helpText(), field.required(), field.order(),
                field.textRules(), field.numericRules(), field.dateRules(), OptionSource.DEFECT_SEVERITIES, field.optionFilter(),
                field.fileRules(), field.binding(), field.visible(), field.readOnly(), field.visibleWhen(), field.requiredWhen(),field.defaultValue());
        }).toList();
        return new FormConfiguration(fields);
    }
}
