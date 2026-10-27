package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ecabin.ledger.api.FormModels.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefectReportBindingRulesTest {
    @Test void completePublishedDesignMapsConfiguredFieldsAndDependentReferenceData() {
        var fields=complete();
        var mapped=DefectReportBindingRules.requireComplete(fields);
        assertEquals("category",mapped.get(DefectFieldBinding.DEFECT_CATEGORY).key());
        assertEquals("component",mapped.get(DefectFieldBinding.COMPONENT_ID).key());
        assertEquals("component",mapped.get(DefectFieldBinding.CONDITION_ID).optionFilter().componentFieldKey());
    }

    @Test void domainPersistenceRequirementsAreSeparateFromFormRequirednessAndUseGeneratedProjection() {
        assertEquals(List.of(DefectFieldBinding.DEFECT_CATEGORY,DefectFieldBinding.COMPONENT_ID,DefectFieldBinding.CONDITION_ID,
            DefectFieldBinding.ZONE,DefectFieldBinding.TITLE,DefectFieldBinding.DESCRIPTION,DefectFieldBinding.SEVERITY),
            DefectReportBindingRules.requiredDomainValues(false));
        assertEquals(List.of(DefectFieldBinding.COMPONENT_ID,DefectFieldBinding.CONDITION_ID,DefectFieldBinding.ZONE,DefectFieldBinding.SEVERITY),
            DefectReportBindingRules.requiredDomainValues(true));
    }

    @Test void aircraftIsTheOnlyUniversallyRequiredDefectBinding() {
        var small=complete().stream().filter(field->Set.of(DefectFieldBinding.AIRCRAFT_ID,DefectFieldBinding.DEFECT_CATEGORY).contains(field.binding())).toList();
        assertEquals(Set.of(DefectFieldBinding.AIRCRAFT_ID,DefectFieldBinding.DEFECT_CATEGORY),DefectReportBindingRules.requireComplete(small).keySet());
        var aircraftOnly=small.stream().filter(field->field.binding()==DefectFieldBinding.AIRCRAFT_ID).toList();
        assertEquals(Set.of(DefectFieldBinding.AIRCRAFT_ID),DefectReportBindingRules.requireComplete(aircraftOnly).keySet());
        var optional=complete().stream().map(field->Set.of(DefectFieldBinding.COMPONENT_ID,DefectFieldBinding.CONDITION_ID,DefectFieldBinding.ZONE,DefectFieldBinding.TITLE,DefectFieldBinding.DESCRIPTION,DefectFieldBinding.SEVERITY).contains(field.binding())
            ?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),false,field.order(),field.textRules(),field.numericRules(),field.dateRules(),field.optionSource(),field.optionFilter(),field.fileRules(),field.binding()):field).toList();
        assertDoesNotThrow(()->DefectReportBindingRules.requireComplete(optional));
        var noCategory=complete().stream().filter(field->field.binding()!=DefectFieldBinding.DEFECT_CATEGORY).toList();
        assertDoesNotThrow(()->DefectReportBindingRules.requireComplete(noCategory));
        var noDomainFields=complete().stream().filter(field->field.binding()==DefectFieldBinding.AIRCRAFT_ID).toList();
        assertDoesNotThrow(()->DefectReportBindingRules.requireComplete(noDomainFields));
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(small.stream().map(field->field.binding()==DefectFieldBinding.AIRCRAFT_ID?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),false,field.order(),field.textRules(),field.numericRules(),field.dateRules(),field.optionSource(),field.optionFilter(),field.fileRules(),field.binding()):field).toList()));
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(small.stream().map(field->field.binding()==DefectFieldBinding.AIRCRAFT_ID?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),field.required(),field.order(),field.textRules(),field.numericRules(),field.dateRules(),field.optionSource(),field.optionFilter(),field.fileRules(),field.binding(),false,false,null,null):field).toList()));
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(small.stream().map(field->field.binding()==DefectFieldBinding.AIRCRAFT_ID?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),field.required(),field.order(),field.textRules(),field.numericRules(),field.dateRules(),field.optionSource(),field.optionFilter(),field.fileRules(),field.binding(),true,true,null,null):field).toList()));
    }

    @Test void incompatibleReferenceSourceAndDuplicateBindingAreRejected() {
        var invalid=complete().stream().map(field->field.binding()==DefectFieldBinding.DEFECT_CATEGORY?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),field.required(),field.order(),field.textRules(),field.numericRules(),field.dateRules(),OptionSource.COMPONENTS,field.optionFilter(),field.fileRules(),field.binding()):field).toList();
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(invalid));
        var duplicate=new java.util.ArrayList<>(complete());duplicate.add(complete().get(0));
        assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireComplete(duplicate));
    }

    @Test void legacyCompactGalleyDesignRemainsReadableWithoutUniversalEvidenceRequirement() {
        var fields=List.of(
            field("aircraftId","Aircraft Registration",FieldType.AIRCRAFT_SELECT,OptionSource.AIRCRAFT,null,DefectFieldBinding.AIRCRAFT_ID,true),
            field("location","Galley Number / Location",FieldType.TEXT,null,null,DefectFieldBinding.ZONE,true),
            field("component","Component",FieldType.DROPDOWN,OptionSource.COMPONENTS,null,DefectFieldBinding.COMPONENT_ID,true),
            field("condition","Defect Condition",FieldType.DROPDOWN,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component"),DefectFieldBinding.CONDITION_ID,true),
            field("severity","Severity",FieldType.DROPDOWN,OptionSource.GALLEY_SEVERITIES,null,DefectFieldBinding.SEVERITY,true),
            field("remarks","Remarks",FieldType.TEXTAREA,null,null,DefectFieldBinding.REMARKS,false),
            new FieldDefinition("evidence","Evidence Image Upload",FieldType.IMAGE_UPLOAD,null,true,5,null,null,null,null,null,new FileRules(List.of("jpg","jpeg","png","webp"),List.of("image/jpeg","image/png","image/webp"),5_242_880L,1,1),DefectFieldBinding.EVIDENCE_ATTACHMENTS));
        var mapped=DefectReportBindingRules.requireGalleyReport(fields);
        assertEquals(7,mapped.size());
        assertFalse(mapped.get(DefectFieldBinding.REMARKS).required());
        var smaller=fields.stream().filter(field->Set.of(DefectFieldBinding.AIRCRAFT_ID,DefectFieldBinding.REMARKS).contains(field.binding())).toList();
        assertDoesNotThrow(()->DefectReportBindingRules.requireGalleyReport(smaller));
        assertTrue(DefectReportBindingRules.isLegacyGalleyProjection("GALLEY",fields));
        assertFalse(DefectReportBindingRules.isLegacyGalleyProjection("CABIN",fields));
        var requiredRemarks=fields.stream().map(field->field.binding()==DefectFieldBinding.REMARKS?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),true,field.order(),field.textRules(),field.numericRules(),field.dateRules(),field.optionSource(),field.optionFilter(),field.fileRules(),field.binding()):field).toList();
        assertDoesNotThrow(()->DefectReportBindingRules.requireGalleyReport(requiredRemarks));
    }

    @Test void severityBindingAcceptsCanonicalSourceAndLegacyPublishedAlias() {
        var canonical=complete();
        assertEquals(OptionSource.DEFECT_SEVERITIES,DefectReportBindingRules.requireComplete(canonical).get(DefectFieldBinding.SEVERITY).optionSource());
        var legacy=canonical.stream().map(field->field.binding()==DefectFieldBinding.SEVERITY
            ?new FieldDefinition(field.key(),field.label(),field.type(),field.helpText(),field.required(),field.order(),field.textRules(),field.numericRules(),field.dateRules(),OptionSource.GALLEY_SEVERITIES,field.optionFilter(),field.fileRules(),field.binding(),field.visible(),field.readOnly(),field.visibleWhen(),field.requiredWhen())
            :field).toList();
        assertDoesNotThrow(()->DefectReportBindingRules.requireComplete(legacy));
    }

    @Test void missingAircraftBindingNamesTheConfiguredAircraftRegistrationSelector() {
        var missingAircraft=List.of(
            field("location","Galley Number / Location",FieldType.TEXT,null,null,DefectFieldBinding.ZONE,true),
            field("component","Component",FieldType.DROPDOWN,OptionSource.COMPONENTS,null,DefectFieldBinding.COMPONENT_ID,true),
            field("condition","Defect Condition",FieldType.DROPDOWN,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component"),DefectFieldBinding.CONDITION_ID,true),
            field("severity","Severity",FieldType.DROPDOWN,OptionSource.GALLEY_SEVERITIES,null,DefectFieldBinding.SEVERITY,true),
            field("remarks","Remarks",FieldType.TEXTAREA,null,null,DefectFieldBinding.REMARKS,false),
            new FieldDefinition("evidence","Evidence Image Upload",FieldType.IMAGE_UPLOAD,null,true,5,null,null,null,null,null,new FileRules(List.of("jpg"),List.of("image/jpeg"),5_242_880L,1,1),DefectFieldBinding.EVIDENCE_ATTACHMENTS));
        var error=assertThrows(IllegalArgumentException.class,()->DefectReportBindingRules.requireGalleyReport(missingAircraft));
        assertTrue(error.getMessage().contains("Aircraft Registration selector"));
        assertTrue(error.getMessage().contains("AIRCRAFT_SELECT"));
    }

    @Test void remarksBindingIsApplicableToEveryAreaAndDoesNotForceAStandardForm() {
        var remarks=field("remarks","Remarks",FieldType.TEXTAREA,null,null,DefectFieldBinding.REMARKS,false);
        assertEquals(List.of("*"),new FormFieldBindingCatalog().require(DefectFieldBinding.REMARKS).cabinAreaCodes());
        for(String area:List.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT","AIRPORT_TAXI"))
            assertDoesNotThrow(()->DefectReportBindingRules.requireComplete(List.of(aircraft(),remarks)),area);
    }

    private List<FieldDefinition> complete(){return List.of(
        field("aircraftId","Aircraft Registration",FieldType.AIRCRAFT_SELECT,OptionSource.AIRCRAFT,null,DefectFieldBinding.AIRCRAFT_ID,true),
        field("category","Category",FieldType.DROPDOWN,OptionSource.DEFECT_CATEGORIES,null,DefectFieldBinding.DEFECT_CATEGORY,true),
        field("component","Component",FieldType.DROPDOWN,OptionSource.COMPONENTS,null,DefectFieldBinding.COMPONENT_ID,true),
        field("condition","Condition",FieldType.DROPDOWN,OptionSource.DEFECT_CONDITIONS,new OptionFilter(null,"component"),DefectFieldBinding.CONDITION_ID,true),
        field("zone","Location",FieldType.TEXT,null,null,DefectFieldBinding.ZONE,true),
        field("title","Title",FieldType.TEXT,null,null,DefectFieldBinding.TITLE,true),
        field("description","Details",FieldType.TEXTAREA,null,null,DefectFieldBinding.DESCRIPTION,true),
        field("severity","Severity",FieldType.DROPDOWN,OptionSource.DEFECT_SEVERITIES,null,DefectFieldBinding.SEVERITY,true));}
    private FieldDefinition aircraft(){return field("aircraftId","Aircraft Registration",FieldType.AIRCRAFT_SELECT,OptionSource.AIRCRAFT,null,DefectFieldBinding.AIRCRAFT_ID,true);}
    private FieldDefinition field(String key,String label,FieldType type,OptionSource source,OptionFilter filter,DefectFieldBinding binding,boolean required){return new FieldDefinition(key,label,type,null,required,0,null,null,null,source,filter,null,binding);}
}
