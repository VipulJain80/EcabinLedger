package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ecabin.ledger.api.FormModels.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefectPersistenceRequirementsTest {
    private final DefectPersistenceRequirements requirements =
        new DefectPersistenceRequirements(null, new FormFieldBindingCatalog());

    @Test void derivesRequiredBindingsFromSchemaNullabilityAndDefaults() {
        Set<DefectFieldBinding> required = requirements.requiredBindingsFor(Map.of(
            "category", false, "component_id", false, "condition_id", false, "zone", true,
            "title", true, "description", true, "severity", true, "severity_id", false));

        assertEquals(Set.of(DefectFieldBinding.ZONE, DefectFieldBinding.TITLE,
            DefectFieldBinding.DESCRIPTION, DefectFieldBinding.SEVERITY), required);
    }

    @Test void validatesSubmittedTypedValuesOnlyWhenTheSchemaRequiresTheirBindings() {
        Map<DefectFieldBinding,Object> values = new java.util.EnumMap<>(DefectFieldBinding.class);
        values.put(DefectFieldBinding.ZONE,"G3");
        values.put(DefectFieldBinding.TITLE,"Latch issue");
        values.put(DefectFieldBinding.DESCRIPTION,"Latch is broken");
        values.put(DefectFieldBinding.SEVERITY,"MINOR");
        assertDoesNotThrow(() -> requirements.requireRequiredBindings(values,
            Set.of(DefectFieldBinding.ZONE,DefectFieldBinding.TITLE,DefectFieldBinding.DESCRIPTION,DefectFieldBinding.SEVERITY)));
        assertDoesNotThrow(() -> requirements.requireRequiredBindings(values,Set.of(DefectFieldBinding.SEVERITY)));
        values.remove(DefectFieldBinding.TITLE);
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->requirements.requireRequiredBindings(values,Set.of(DefectFieldBinding.TITLE)));
        assertTrue(error.getMessage().contains("Defect title"));
    }

    @Test void publicationRequiresOnlyBindingsNeededByCurrentDatabaseAndNotSynthesizedLegacyValues() {
        Set<DefectFieldBinding> required = Set.of(DefectFieldBinding.ZONE, DefectFieldBinding.TITLE,
            DefectFieldBinding.DESCRIPTION, DefectFieldBinding.SEVERITY);
        List<FieldDefinition> aircraftOnly = List.of(aircraft());
        assertFalse(requirements.publicationIssue("CABIN", aircraftOnly, required).isBlank());

        List<FieldDefinition> configured = List.of(aircraft(),
            field("zone", DefectFieldBinding.ZONE, FieldType.TEXT, true),
            field("title", DefectFieldBinding.TITLE, FieldType.TEXT, true),
            field("description", DefectFieldBinding.DESCRIPTION, FieldType.TEXTAREA, true),
            field("severity", DefectFieldBinding.SEVERITY, FieldType.DROPDOWN, true));
        assertEquals("", requirements.publicationIssue("CABIN", configured, required));
        assertFalse(requirements.publicationIssue("CABIN", configured.stream()
            .map(field -> field.binding() == DefectFieldBinding.TITLE
                ? new FieldDefinition(field.key(), field.label(), field.type(), field.helpText(), false, field.order(),
                    field.textRules(), field.numericRules(), field.dateRules(), field.optionSource(), field.optionFilter(),
                    field.fileRules(), field.binding()) : field).toList(), required).isBlank());
    }

    private static FieldDefinition aircraft() {
        return new FieldDefinition("aircraftId", "Aircraft Registration", FieldType.AIRCRAFT_SELECT,
            null, true, 0, null, null, null, OptionSource.AIRCRAFT, null, null, DefectFieldBinding.AIRCRAFT_ID);
    }

    private static FieldDefinition field(String key, DefectFieldBinding binding, FieldType type, boolean required) {
        OptionSource source = binding == DefectFieldBinding.SEVERITY ? OptionSource.DEFECT_SEVERITIES : null;
        return new FieldDefinition(key, binding.name(), type, null, required, 1, null, null, null, source,
            null, null, binding);
    }
}
