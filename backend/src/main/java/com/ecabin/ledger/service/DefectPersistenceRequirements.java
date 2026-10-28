package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FieldBindingDefinition;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves form compatibility from the live database schema and the typed binding projection.
 * Form requiredness remains in the immutable form version; this class only answers whether
 * a configured projection can satisfy a persisted NOT NULL column without a database default.
 */
@Component
public final class DefectPersistenceRequirements {
    private final JdbcTemplate jdbc;
    private final FormFieldBindingCatalog catalog;
    private volatile Map<String, Boolean> requiredDefectColumns;

    public DefectPersistenceRequirements(JdbcTemplate jdbc, FormFieldBindingCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public Set<DefectFieldBinding> requiredDefectBindings() {
        return requiredBindingsFor(requiredDefectColumns());
    }

    Map<String, Boolean> requiredDefectColumns() {
        Map<String, Boolean> cached=requiredDefectColumns;
        if(cached!=null)return cached;
        synchronized(this){
            if(requiredDefectColumns!=null)return requiredDefectColumns;
            Map<String, Boolean> requiredColumns = jdbc.query(
            "SELECT column_name, is_nullable='NO' AND column_default IS NULL AS required " +
            "FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='defects_dtl'",
            rs -> {
                Map<String, Boolean> columns = new LinkedHashMap<>();
                while (rs.next()) columns.put(rs.getString("column_name"), rs.getBoolean("required"));
                return columns;
            });
            if (requiredColumns.isEmpty()) throw new IllegalStateException("The defects_dtl persistence schema is unavailable.");
            requiredDefectColumns=Map.copyOf(requiredColumns);
            return requiredDefectColumns;
        }
    }

    Set<DefectFieldBinding> requiredBindingsFor(Map<String, Boolean> requiredColumns) {
        Set<DefectFieldBinding> required = new LinkedHashSet<>();
        for (FieldBindingDefinition definition : catalog.all()) {
            if (!definition.formPurposes().contains(com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT)
                    || !"defects_dtl".equals(definition.destinationTable())) continue;
            if (definition.persistenceColumns().stream().anyMatch(column -> Boolean.TRUE.equals(requiredColumns.get(column))))
                required.add(definition.binding());
        }
        return Set.copyOf(required);
    }

    public void requireDatabaseRequiredValues(Map<String, ?> persistedValues) {
        for(Map.Entry<String,Boolean> column:requiredDefectColumns().entrySet()) {
            if(!column.getValue())continue;
            Object value=persistedValues.get(column.getKey());
            if(value==null||(value instanceof String text&&text.isBlank()))
                throw new IllegalArgumentException("The defect is missing “"+displayName(column.getKey())+"”, which is required by the current database schema.");
        }
    }

    public void requireRequiredBindings(Map<DefectFieldBinding, ?> submittedValues) {
        requireRequiredBindings(submittedValues,requiredDefectBindings());
    }

    void requireRequiredBindings(Map<DefectFieldBinding, ?> submittedValues,Set<DefectFieldBinding> requiredBindings) {
        for(DefectFieldBinding binding:requiredBindings) {
            Object value=submittedValues.get(binding);
            if(value==null||(value instanceof String text&&text.isBlank()))
                throw new IllegalArgumentException("The defect is missing “"+catalog.require(binding).name()+"”, which is required by the current database schema.");
        }
    }

    private String displayName(String column){
        return catalog.all().stream().filter(binding->binding.persistenceColumns().contains(column))
            .map(FieldBindingDefinition::name).findFirst().orElse(column.replace('_',' '));
    }

    public List<FieldBindingDefinition> catalogWithRequirements() {
        Set<DefectFieldBinding> required = requiredDefectBindings();
        return catalog.all().stream().map(binding -> binding.withPersistenceRequired(required.contains(binding.binding()))).toList();
    }

    public String publicationIssue(String areaCode, List<com.ecabin.ledger.api.FormModels.FieldDefinition> fields) {
        return publicationIssue(areaCode, fields, requiredDefectBindings());
    }

    String publicationIssue(String areaCode, List<com.ecabin.ledger.api.FormModels.FieldDefinition> fields,
            Set<DefectFieldBinding> requiredBindings) {
        Map<DefectFieldBinding, com.ecabin.ledger.api.FormModels.FieldDefinition> configured = fields.stream()
            .filter(field -> field.binding() != null)
            .collect(java.util.stream.Collectors.toMap(com.ecabin.ledger.api.FormModels.FieldDefinition::binding, field -> field, (first, ignored) -> first));
        Set<DefectFieldBinding> required = new LinkedHashSet<>(requiredBindings);
        if (DefectReportBindingRules.isLegacyGalleyProjection(areaCode, fields)) {
            required.remove(DefectFieldBinding.TITLE);
            required.remove(DefectFieldBinding.DESCRIPTION);
        }
        for (DefectFieldBinding binding : required) {
            com.ecabin.ledger.api.FormModels.FieldDefinition field = configured.get(binding);
            String name = catalog.require(binding).name();
            if (field == null)
                return "This form version must include “" + name + "” because the current database schema requires its persisted value.";
            if (!field.required() || Boolean.FALSE.equals(field.visible()) || Boolean.TRUE.equals(field.readOnly())
                    || field.visibleWhen() != null || field.requiredWhen() != null)
                return "“" + field.label() + "” must be visible, editable, and required because the current database schema requires its persisted value.";
        }
        return "";
    }
}
