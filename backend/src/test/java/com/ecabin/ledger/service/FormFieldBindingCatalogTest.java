package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.FormModels.DefectFieldBinding;
import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.FieldType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormFieldBindingCatalogTest {
    private final FormFieldBindingCatalog catalog=new FormFieldBindingCatalog();

    @Test void exposesOnlyVerifiedRelationalBindingsAndApprovedDestinations(){
        var fields=catalog.all();
        assertEquals(Set.of(DefectFieldBinding.values()),fields.stream().map(f->f.binding()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("defects_dtl",catalog.require(DefectFieldBinding.SEAT_REFERENCE).destinationTable());
        assertEquals("seat_reference",catalog.require(DefectFieldBinding.SEAT_REFERENCE).destinationColumn());
        assertEquals("severity_id",catalog.require(DefectFieldBinding.SEVERITY).destinationColumn());
        assertEquals("attachments_dtl",catalog.require(DefectFieldBinding.EVIDENCE_ATTACHMENTS).destinationTable());
        var aircraft=catalog.require(DefectFieldBinding.AIRCRAFT_ID);
        assertEquals("aircraftId",aircraft.stableKey());
        assertEquals("aircraft_mst",aircraft.destinationTable());
        assertEquals("id",aircraft.destinationColumn());
        assertEquals(Set.of(FieldType.AIRCRAFT_SELECT),Set.copyOf(aircraft.supportedFieldTypes()));
        assertEquals("AIRCRAFT",aircraft.referenceDataSource().name());
        assertEquals("SYSTEM_CONTROLLED_SELECTION",aircraft.classification());
        assertFalse(fields.stream().anyMatch(f->f.destinationTable().contains("${")||f.destinationColumn().contains(";")));
    }

    @Test void catalogConstrainsTypeAreaPurposeAndReferenceSource(){
        var row=catalog.require(DefectFieldBinding.ROW_NUMBER);
        assertEquals(Set.of(FieldType.NUMBER),Set.copyOf(row.supportedFieldTypes()));
        assertEquals(Set.of("*"),Set.copyOf(row.cabinAreaCodes()));
        assertTrue(row.formPurposes().contains(FormPurpose.DEFECT_REPORT));
        assertEquals("CONFIGURABLE",row.requiredPolicy());
        var condition=catalog.require(DefectFieldBinding.CONDITION_ID);
        assertEquals("COMPONENT_ID",condition.dependsOnBinding());
        assertEquals("DEFECT_CONDITIONS",condition.referenceDataSource().name());
        var location=catalog.require(DefectFieldBinding.ZONE);
        assertEquals("Location",location.name());
        assertEquals("zone",location.destinationColumn());
        assertEquals(Set.of(FieldType.TEXT,FieldType.TEXTAREA),Set.copyOf(location.supportedFieldTypes()));
        assertEquals(Set.of("*"),Set.copyOf(location.cabinAreaCodes()));
        assertEquals(Set.of("*"),Set.copyOf(catalog.require(DefectFieldBinding.DEFECT_CATEGORY).cabinAreaCodes()));
    }

    @Test void defectBindingMetadataSeparatesSupportedFieldsFromFormRequiredness(){
        for(DefectFieldBinding binding:Set.of(DefectFieldBinding.COMPONENT_ID,DefectFieldBinding.CONDITION_ID,DefectFieldBinding.ZONE,DefectFieldBinding.TITLE,DefectFieldBinding.DESCRIPTION,DefectFieldBinding.SEVERITY))
            assertEquals("CONFIGURABLE",catalog.require(binding).requiredPolicy(),binding.name());
        assertEquals("CONFIGURABLE",catalog.require(DefectFieldBinding.DEFECT_CATEGORY).requiredPolicy());
        assertEquals("REQUIRED",catalog.require(DefectFieldBinding.AIRCRAFT_ID).requiredPolicy());
    }

    @Test void reviewerBindingsAreAreaNeutralAndTheirWorkflowPurposeIsExplicit(){
        for(DefectFieldBinding binding:Set.of(DefectFieldBinding.REVIEW_DECISION,DefectFieldBinding.REVIEW_MAINTENANCE_TEAM,
            DefectFieldBinding.REVIEW_REJECTION_REASON,DefectFieldBinding.REVIEW_REMARK)){
            var definition=catalog.require(binding);
            assertEquals(Set.of("*"),Set.copyOf(definition.cabinAreaCodes()),binding.name());
            assertTrue(definition.formPurposes().contains(FormPurpose.REVIEW_DECISION),binding.name());
        }
    }

    @Test void unsupportedBindingsFailClosedWithActionableMessage(){
        assertThrows(IllegalArgumentException.class,()->catalog.require(null));
    }

    @Test void closureBindingsAreApprovedForEveryActiveAndFutureCabinArea(){
        Set<String> expected=Set.of("*");
        assertEquals(expected,Set.copyOf(catalog.require(DefectFieldBinding.CLOSURE_DECISION).cabinAreaCodes()));
        assertEquals(expected,Set.copyOf(catalog.require(DefectFieldBinding.CLOSURE_REMARK).cabinAreaCodes()));
        assertTrue(catalog.require(DefectFieldBinding.CLOSURE_DECISION).formPurposes().contains(FormPurpose.CLOSURE_APPROVAL));
    }
}
