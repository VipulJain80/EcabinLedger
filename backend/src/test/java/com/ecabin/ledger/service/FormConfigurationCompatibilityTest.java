package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.FormConfiguration;
import com.ecabin.ledger.api.FormModels.OptionSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormConfigurationCompatibilityTest {
    @Test
    void canonicalizesOnlyLegacySeveritySourceAndPreservesFieldMetadata() {
        FieldDefinition legacySeverity = new FieldDefinition("severity", "Priority", FieldType.DROPDOWN, "Help", true, 7,
            null, null, null, OptionSource.GALLEY_SEVERITIES, null, null, DefectFieldBinding.SEVERITY, true, false, null, null);
        FieldDefinition component = new FieldDefinition("component", "Component", FieldType.DROPDOWN, null, true, 2,
            null, null, null, OptionSource.COMPONENTS, null, null, DefectFieldBinding.COMPONENT_ID);
        FieldDefinition notes = new FieldDefinition("notes", "Notes", FieldType.TEXT, null, false, 3,
            new com.ecabin.ledger.api.FormModels.TextRules(null, 80, false, false, null), null, null, null, null, null,
            null, true, false, null, null, "Versioned default");
        FormConfiguration legacy = new FormConfiguration(List.of(legacySeverity, component, notes));

        FormConfiguration normalized = FormConfigurationCompatibility.canonicalizeSeveritySource(legacy);

        assertEquals(OptionSource.DEFECT_SEVERITIES, normalized.fields().get(0).optionSource());
        assertEquals("Priority", normalized.fields().get(0).label());
        assertEquals("Help", normalized.fields().get(0).helpText());
        assertEquals(7, normalized.fields().get(0).order());
        assertEquals(OptionSource.COMPONENTS, normalized.fields().get(1).optionSource());
        assertSame(component, normalized.fields().get(1));
        assertSame(notes, normalized.fields().get(2));
        assertEquals("Versioned default", normalized.fields().get(2).defaultValue());
        assertEquals(OptionSource.GALLEY_SEVERITIES, legacy.fields().get(0).optionSource(), "Normalization must not mutate stored historical configuration.");
    }
}
