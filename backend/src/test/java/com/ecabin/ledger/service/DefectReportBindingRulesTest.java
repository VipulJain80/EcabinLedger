package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ecabin.ledger.api.FormModels.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefectReportBindingRulesTest {
    @Test void completePublishedDesignMapsMandatoryFieldsAndDependentReferenceData() {
        var fields=complete();
        var mapped=DefectReportBindingRules.requireComplete(fields);
        assertEquals("category",mapped.get(DefectFieldBinding.DEFECT_CATEGORY).key());
        assertEquals("component",mapped.get(DefectFieldBinding.COMPONENT_ID).key());
        assertEquals("component",mapped.get(DefectFieldBinding.CONDITION_ID).optionFilter().componentFieldKey());
    }

    @Test void missingRequiredBindingIsRejectedRatherThanDefaulted() {
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(complete().stream().filter(field->field.binding()!=DefectFieldBinding.SEVERITY).toList()));
    }

    @Test void incompatibleReferenceSourceAndDuplicateBindingAreRejected() {
        var invalid=complete().stream().map(field->field.binding()==DefectFieldBinding.DEFECT_CATEGORY?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),field.required(),field.order(),field.textRules(),field.numericRules(),field.dateRules(),OptionSource.COMPONENTS,field.optionFilter(),field.fileRules(),field.binding()):field).toList();
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(invalid));
        var duplicate=new java.util.ArrayList<>(complete());duplicate.add(complete().get(0));
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(duplicate));
    }

    @Test void compactGalleyDesignRequiresSixFieldsAndCleanEvidenceConfiguration() {
        var fields=List.of(
            field("location","Galley Number / Location",FieldType.TEXT,null,null,DefectFieldBinding.ZONE,true),
            field("component","Component",FieldType.DROPDOWN,OptionSource.COMPONENTS,null,DefectFieldBinding.COMPONENT_ID,true),
            field("condition","Defect Condition",FieldType.DROPDOWN,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component"),DefectFieldBinding.CONDITION_ID,true),
            field("severity","Severity",FieldType.DROPDOWN,OptionSource.GALLEY_SEVERITIES,null,DefectFieldBinding.SEVERITY,true),
            field("remarks","Remarks",FieldType.TEXTAREA,null,null,DefectFieldBinding.REMARKS,false),
            new FieldDefinition("evidence","Evidence Image Upload",FieldType.IMAGE_UPLOAD,null,true,5,null,null,null,null,null,new FileRules(List.of("jpg","jpeg","png","webp"),List.of("image/jpeg","image/png","image/webp"),5_242_880L,1,1),DefectFieldBinding.EVIDENCE_ATTACHMENTS));
        var mapped=DefectReportBindingRules.requireGalleyReport(fields);
        assertEquals(6,mapped.size());
        assertFalse(mapped.get(DefectFieldBinding.REMARKS).required());
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireGalleyReport(fields.stream().filter(field->field.binding()!=DefectFieldBinding.EVIDENCE_ATTACHMENTS).toList()));
        var requiredRemarks=fields.stream().map(field->field.binding()==DefectFieldBinding.REMARKS?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),true,field.order(),field.textRules(),field.numericRules(),field.dateRules(),field.optionSource(),field.optionFilter(),field.fileRules(),field.binding()):field).toList();
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireGalleyReport(requiredRemarks));
    }

    private List<FieldDefinition> complete(){return List.of(
        field("category","Category",FieldType.DROPDOWN,OptionSource.DEFECT_CATEGORIES,null,DefectFieldBinding.DEFECT_CATEGORY,true),
        field("component","Component",FieldType.DROPDOWN,OptionSource.COMPONENTS,null,DefectFieldBinding.COMPONENT_ID,true),
        field("condition","Condition",FieldType.DROPDOWN,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component"),DefectFieldBinding.CONDITION_ID,true),
        field("zone","Location",FieldType.TEXT,null,null,DefectFieldBinding.ZONE,true),
        field("title","Title",FieldType.TEXT,null,null,DefectFieldBinding.TITLE,true),
        field("description","Details",FieldType.TEXTAREA,null,null,DefectFieldBinding.DESCRIPTION,true),
        field("severity","Severity",FieldType.DROPDOWN,OptionSource.DEFECT_SEVERITIES,null,DefectFieldBinding.SEVERITY,true));}
    private FieldDefinition field(String key,String label,FieldType type,OptionSource source,OptionFilter filter,DefectFieldBinding binding,boolean required){return new FieldDefinition(key,label,type,null,required,0,null,null,null,source,filter,null,binding);}
}
