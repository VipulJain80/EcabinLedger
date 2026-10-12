package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.OptionSource;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Validates the explicit bindings between a published report design and required defect properties. */
final class DefectReportBindingRules {
    private static final EnumSet<DefectFieldBinding> REQUIRED = EnumSet.of(
        DefectFieldBinding.DEFECT_CATEGORY, DefectFieldBinding.COMPONENT_ID, DefectFieldBinding.CONDITION_ID,
        DefectFieldBinding.ZONE, DefectFieldBinding.TITLE, DefectFieldBinding.DESCRIPTION, DefectFieldBinding.SEVERITY, DefectFieldBinding.AIRCRAFT_ID);

    private DefectReportBindingRules() {}

    static boolean isGalleyCompact(List<FieldDefinition> fields){return fields.stream().anyMatch(field->field.binding()==DefectFieldBinding.REMARKS);}

    static Map<DefectFieldBinding,FieldDefinition> requireComplete(List<FieldDefinition> fields) {
        Map<DefectFieldBinding,FieldDefinition> mapped = new EnumMap<>(DefectFieldBinding.class);
        for (FieldDefinition field : fields) {
            if (field.binding() == null) continue;
            FormFieldBindingCatalog catalog=new FormFieldBindingCatalog();
            catalog.require(field.binding());
            if(Boolean.FALSE.equals(field.visible()))throw new IllegalArgumentException("Required defect field "+field.label()+" cannot be hidden.");
            if(Boolean.TRUE.equals(field.readOnly())&&field.required())throw new IllegalArgumentException("Required defect field "+field.label()+" must be editable by the reporter.");
            if (mapped.putIfAbsent(field.binding(), field) != null && field.binding()!=DefectFieldBinding.EVIDENCE_ATTACHMENTS)
                throw new IllegalArgumentException("Only one field may be bound to " + field.binding().name().replace('_',' ') + ".");
            validateField(field);
        }
        for (DefectFieldBinding binding : REQUIRED) {
            FieldDefinition field = mapped.get(binding);
            if (field == null || !field.required())
                throw new IllegalArgumentException("The published report form must include a required " + binding.name().replace('_',' ').toLowerCase(java.util.Locale.ROOT) + " field binding.");
        }
        FieldDefinition condition = mapped.get(DefectFieldBinding.CONDITION_ID);
        FieldDefinition component = mapped.get(DefectFieldBinding.COMPONENT_ID);
        if (condition.optionFilter() == null || !component.key().equals(condition.optionFilter().componentFieldKey()))
            throw new IllegalArgumentException("The condition field must use the component-bound field as its dependency.");
        return Map.copyOf(mapped);
    }

    /** The compact Galley report uses area/component/condition context to populate required defect properties. */
    static Map<DefectFieldBinding,FieldDefinition> requireGalleyReport(List<FieldDefinition> fields) {
        Map<DefectFieldBinding,FieldDefinition> mapped=new EnumMap<>(DefectFieldBinding.class);
        for(FieldDefinition field:fields){
            if(field.binding()==null)continue;
            new FormFieldBindingCatalog().require(field.binding());
            if(Boolean.FALSE.equals(field.visible()))throw new IllegalArgumentException("Required defect field "+field.label()+" cannot be hidden.");
            if(Boolean.TRUE.equals(field.readOnly()))throw new IllegalArgumentException("Galley report fields must be editable.");
            if(mapped.putIfAbsent(field.binding(),field)!=null&&field.binding()!=DefectFieldBinding.EVIDENCE_ATTACHMENTS)throw new IllegalArgumentException("Only one field may be bound to "+field.binding().name().replace('_',' ')+".");
            validateField(field);
        }
        for(DefectFieldBinding required:EnumSet.of(DefectFieldBinding.COMPONENT_ID,DefectFieldBinding.CONDITION_ID,DefectFieldBinding.ZONE,DefectFieldBinding.SEVERITY,DefectFieldBinding.EVIDENCE_ATTACHMENTS,DefectFieldBinding.AIRCRAFT_ID)){
            FieldDefinition field=mapped.get(required);
            if(field==null||!field.required())throw new IllegalArgumentException("The Galley report requires "+galleyFieldName(required)+".");
        }
        FieldDefinition component=mapped.get(DefectFieldBinding.COMPONENT_ID),condition=mapped.get(DefectFieldBinding.CONDITION_ID),evidence=mapped.get(DefectFieldBinding.EVIDENCE_ATTACHMENTS);
        if(condition.optionFilter()==null||!component.key().equals(condition.optionFilter().componentFieldKey()))throw new IllegalArgumentException("The condition field must use the component-bound field as its dependency.");
        if(evidence.type()!=FieldType.IMAGE_UPLOAD||evidence.fileRules()==null||evidence.fileRules().minCount()<1)throw new IllegalArgumentException("Galley Area requires at least one image attachment.");
        FieldDefinition remarks=mapped.get(DefectFieldBinding.REMARKS);
        if(remarks!=null&&remarks.required())throw new IllegalArgumentException("Remarks must remain optional in the Galley report.");
        if(mapped.containsKey(DefectFieldBinding.DEFECT_CATEGORY)||mapped.containsKey(DefectFieldBinding.TITLE)||mapped.containsKey(DefectFieldBinding.DESCRIPTION))throw new IllegalArgumentException("Galley category, title and description are generated from the selected area, component, condition and remarks.");
        return Map.copyOf(mapped);
    }

    private static String galleyFieldName(DefectFieldBinding binding) {
        return switch(binding) {
            case AIRCRAFT_ID -> "a required Aircraft Registration selector (AIRCRAFT_SELECT with the Aircraft Master source)";
            case COMPONENT_ID -> "a required Component selector";
            case CONDITION_ID -> "a required Defect Condition selector";
            case ZONE -> "a required Galley Number / Location field";
            case SEVERITY -> "a required Severity selector";
            case EVIDENCE_ATTACHMENTS -> "a required Evidence Image Upload field";
            default -> "a required "+binding.name().replace('_',' ').toLowerCase(java.util.Locale.ROOT)+" field";
        };
    }

    private static void validateField(FieldDefinition field) {
        boolean valid = switch (field.binding()) {
            case DEFECT_CATEGORY -> field.type() == FieldType.DROPDOWN && field.optionSource() == OptionSource.DEFECT_CATEGORIES;
            case COMPONENT_ID -> field.type() == FieldType.DROPDOWN && field.optionSource() == OptionSource.COMPONENTS;
            case CONDITION_ID -> field.type() == FieldType.DROPDOWN && field.optionSource() == OptionSource.DEFECT_CONDITIONS;
            case SEVERITY -> field.type() == FieldType.DROPDOWN && (field.optionSource() == OptionSource.DEFECT_SEVERITIES || field.optionSource() == OptionSource.GALLEY_SEVERITIES);
            case ROW_NUMBER -> field.type() == FieldType.NUMBER;
            case ZONE, SEAT_REFERENCE, TITLE, DESCRIPTION, REMARKS -> field.type() == FieldType.TEXT || field.type() == FieldType.TEXTAREA;
            case EVIDENCE_ATTACHMENTS -> field.type() == FieldType.FILE_UPLOAD || field.type() == FieldType.IMAGE_UPLOAD;
            case AIRCRAFT_ID -> field.type() == FieldType.AIRCRAFT_SELECT && field.optionSource() == OptionSource.AIRCRAFT;
        };
        if (!valid) throw new IllegalArgumentException("Field “" + field.label() + "” uses an incompatible type or reference source for its defect-property binding.");
    }
}
