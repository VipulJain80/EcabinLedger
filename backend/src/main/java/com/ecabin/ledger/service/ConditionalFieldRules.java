package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.VisibilityRule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared validation and evaluation for the versioned, data-only equality visibility rule. */
final class ConditionalFieldRules {
    private ConditionalFieldRules() {}

    static void validate(List<FieldDefinition> fields) {
        Map<String, FieldDefinition> byKey = new HashMap<>();
        for (FieldDefinition field : fields) byKey.put(field.key(), field);
        for (FieldDefinition field : fields) {
            VisibilityRule rule = field.visibleWhen();
            if (rule == null) continue;
            FieldDefinition source = byKey.get(rule.fieldKey());
            if (source == null || field.key().equals(rule.fieldKey()) || source.type() == FieldType.SECTION
                    || source.type() == FieldType.AIRCRAFT_SELECT || Boolean.TRUE.equals(source.readOnly())
                    || field.type() == FieldType.SECTION || rule.expectedValue() == null || rule.expectedValue().isBlank())
                throw new IllegalArgumentException("Conditional visibility for “"+field.label()+"” must reference another configured answer and an exact, non-empty value.");
        }
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (FieldDefinition field : fields) visit(field, byKey, visited, active);
    }

    private static void visit(FieldDefinition field, Map<String, FieldDefinition> byKey, Set<String> visited, Set<String> active) {
        if (visited.contains(field.key())) return;
        if (!active.add(field.key())) throw new IllegalArgumentException("Conditional visibility rules cannot contain a cycle.");
        VisibilityRule rule = field.visibleWhen();
        if (rule != null) visit(byKey.get(rule.fieldKey()), byKey, visited, active);
        active.remove(field.key());
        visited.add(field.key());
    }

    static boolean isVisible(FieldDefinition field, Map<String, FieldDefinition> byKey,
            Map<String, Object> answers, Set<String> evaluating) {
        if (field == null || Boolean.FALSE.equals(field.visible()) || evaluating.contains(field.key())) return false;
        Set<String> path = new HashSet<>(evaluating);
        path.add(field.key());
        VisibilityRule rule = field.visibleWhen();
        boolean visible = true;
        if (rule != null) {
            FieldDefinition source = byKey.get(rule.fieldKey());
            visible = source != null && isVisible(source, byKey, answers, path)
                    && matches(answers.get(rule.fieldKey()), rule.expectedValue());
        }
        return visible;
    }

    private static boolean matches(Object value, String expected) {
        if (value == null) return false;
        if (value instanceof List<?> values) return values.stream().anyMatch(item -> item != null && expected.equals(String.valueOf(item)));
        return expected.equals(String.valueOf(value));
    }
}
