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
        assertFalse(fields.stream().anyMatch(f->f.destinationTable().contains("${")||f.destinationColumn().contains(";")));
    }

    @Test void catalogConstrainsTypeAreaPurposeAndReferenceSource(){
        var row=catalog.require(DefectFieldBinding.ROW_NUMBER);
        assertEquals(Set.of(FieldType.NUMBER),Set.copyOf(row.supportedFieldTypes()));
        assertEquals(Set.of("CABIN"),Set.copyOf(row.cabinAreaCodes()));
        assertTrue(row.formPurposes().contains(FormPurpose.DEFECT_REPORT));
        assertEquals("CONFIGURABLE",row.requiredPolicy());
        var condition=catalog.require(DefectFieldBinding.CONDITION_ID);
        assertEquals("COMPONENT_ID",condition.dependsOnBinding());
        assertEquals("DEFECT_CONDITIONS",condition.referenceDataSource().name());
        var location=catalog.require(DefectFieldBinding.ZONE);
        assertEquals("Location",location.name());
        assertEquals("zone",location.destinationColumn());
        assertEquals(Set.of(FieldType.TEXT,FieldType.TEXTAREA),Set.copyOf(location.supportedFieldTypes()));
        assertTrue(location.validation().contains("lavatory number"));
        assertTrue(location.validation().contains("attendant seat number"));
        assertEquals(Set.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT"),Set.copyOf(location.cabinAreaCodes()));
        assertEquals(Set.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT"),Set.copyOf(catalog.require(DefectFieldBinding.DEFECT_CATEGORY).cabinAreaCodes()));
    }

    @Test void unsupportedBindingsFailClosedWithActionableMessage(){
        assertThrows(IllegalArgumentException.class,()->catalog.require(null));
    }
}
