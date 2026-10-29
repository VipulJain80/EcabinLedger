package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FieldBindingDefinition;
import com.ecabin.ledger.api.FormModels.FieldDefinition;
import com.ecabin.ledger.api.FormModels.FieldType;
import com.ecabin.ledger.api.FormModels.OptionSource;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates supported bindings while keeping form design separate from domain persistence rules. */
final class DefectReportBindingRules {
    private DefectReportBindingRules() {}

    /** The only universally required defect-report binding is Aircraft Registration. */
    static Map<DefectFieldBinding,FieldDefinition> requireComplete(List<FieldDefinition> fields) {
        Map<DefectFieldBinding,FieldDefinition> mapped=validateConfigured(fields);
        FieldDefinition aircraft=mapped.get(DefectFieldBinding.AIRCRAFT_ID);
        if(aircraft==null || !aircraft.required() || Boolean.FALSE.equals(aircraft.visible()) || Boolean.TRUE.equals(aircraft.readOnly())
            || aircraft.visibleWhen()!=null || aircraft.requiredWhen()!=null)
            throw new IllegalArgumentException("Every defect-report form must include a visible, editable, required Aircraft Registration selector (AIRCRAFT_SELECT with the Aircraft Master source).");
        validateConditionDependency(mapped);
        return Map.copyOf(mapped);
    }

    /** Form-level validity and database compatibility are checked separately by their authoritative validators. */
    static Map<DefectFieldBinding,FieldDefinition> requirePublicationCompatible(String areaCode,List<FieldDefinition> fields) {
        locationForAreaCode(areaCode);
        return requireComplete(fields);
    }

    /** Explicit area-to-domain projection used at publication and submission boundaries. */
    static String locationForAreaCode(String areaCode) {
        return switch(areaCode==null?"":areaCode) {
            case "CABIN","ENTRY_AREA" -> "CABIN";
            case "GALLEY" -> "GALLEY";
            case "LAVATORY" -> "LAVATORY";
            case "ATTENDANT_SEAT" -> "ATTENDANT_SEAT";
            default -> throw new IllegalArgumentException("This area has no supported defect-location mapping. Configure a domain location mapping before publishing a defect-report form.");
        };
    }

    private static Map<DefectFieldBinding,FieldDefinition> validateConfigured(List<FieldDefinition> fields) {
        Map<DefectFieldBinding,FieldDefinition> mapped=new EnumMap<>(DefectFieldBinding.class);
        FormFieldBindingCatalog catalog=new FormFieldBindingCatalog();
        for(FieldDefinition field:fields){
            if(field.binding()==null)continue;
            catalog.require(field.binding());
            if(Boolean.FALSE.equals(field.visible())&&field.required())throw new IllegalArgumentException("Required defect field "+field.label()+" cannot be hidden.");
            if(Boolean.TRUE.equals(field.readOnly())&&field.required())throw new IllegalArgumentException("Required defect field "+field.label()+" must be editable by the reporter.");
            if(mapped.putIfAbsent(field.binding(),field)!=null&&field.binding()!=DefectFieldBinding.EVIDENCE_ATTACHMENTS)
                throw new IllegalArgumentException("Only one field may be bound to "+field.binding().name().replace('_',' ')+".");
            validateField(field);
        }
        return mapped;
    }

    private static void validateConditionDependency(Map<DefectFieldBinding,FieldDefinition> mapped) {
        FieldDefinition condition=mapped.get(DefectFieldBinding.CONDITION_ID);
        if(condition==null)return;
        FieldDefinition component=mapped.get(DefectFieldBinding.COMPONENT_ID);
        if(component==null||condition.optionFilter()==null||!component.key().equals(condition.optionFilter().componentFieldKey()))
            throw new IllegalArgumentException("The condition field must use the configured component-bound field as its dependency.");
    }

    private static void validateField(FieldDefinition field) {
        boolean valid=switch(field.binding()) {
            case DEFECT_CATEGORY -> field.type()==FieldType.DROPDOWN&&field.optionSource()==OptionSource.DEFECT_CATEGORIES;
            case COMPONENT_ID -> field.type()==FieldType.DROPDOWN&&field.optionSource()==OptionSource.COMPONENTS;
            case CONDITION_ID -> field.type()==FieldType.DROPDOWN&&field.optionSource()==OptionSource.DEFECT_CONDITIONS;
            // Legacy published Galley versions are read using this alias and normalized in new drafts.
            case SEVERITY -> field.type()==FieldType.DROPDOWN&&(field.optionSource()==OptionSource.DEFECT_SEVERITIES||field.optionSource()==OptionSource.GALLEY_SEVERITIES);
            case ROW_NUMBER -> field.type()==FieldType.NUMBER;
            case ZONE,SEAT_REFERENCE,TITLE,DESCRIPTION,REMARKS -> field.type()==FieldType.TEXT||field.type()==FieldType.TEXTAREA;
            case EVIDENCE_ATTACHMENTS -> field.type()==FieldType.FILE_UPLOAD||field.type()==FieldType.IMAGE_UPLOAD;
            case AIRCRAFT_ID -> field.type()==FieldType.AIRCRAFT_SELECT&&field.optionSource()==OptionSource.AIRCRAFT;
            case REVIEW_DECISION,REVIEW_MAINTENANCE_TEAM,REVIEW_REJECTION_REASON,REVIEW_REMARK,MAINTENANCE_STATUS,SLA_TARGET_DATE,MAINTENANCE_REMARK,CLOSURE_DECISION,CLOSURE_REMARK -> false;
        };
        if(!valid)throw new IllegalArgumentException("Field “"+field.label()+"” uses an incompatible type or reference source for its defect-property binding.");
    }
}
