package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.FormModels.*;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Allow-list for form fields that have an explicit, typed persistence implementation.
 * Destination metadata is documentation only; writes are implemented in domain services.
 */
@Component
public final class FormFieldBindingCatalog {
    public static final String UNSUPPORTED_FIELD_MESSAGE =
        "This field does not have an approved database mapping. It cannot be published until the backend field and relational storage are implemented.";

    private static final List<String> DEFECT_AREAS=List.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT");
    private static final List<FieldBindingDefinition> FIELDS = List.of(
        binding(DefectFieldBinding.DEFECT_CATEGORY,"Defect category","category","defects_dtl","category","varchar(32)",List.of(FieldType.DROPDOWN),DEFECT_AREAS,"REQUIRED","DEFECT_CATEGORIES","Active category code owned by the authenticated organization.",true,null),
        binding(DefectFieldBinding.COMPONENT_ID,"Component","componentId","defects_dtl","component_id","uuid",List.of(FieldType.DROPDOWN),DEFECT_AREAS,"REQUIRED","COMPONENTS","Active component must belong to the selected cabin area and organization.",true,null),
        binding(DefectFieldBinding.CONDITION_ID,"Defect condition","conditionId","defects_dtl","condition_id","uuid",List.of(FieldType.DROPDOWN),DEFECT_AREAS,"REQUIRED","DEFECT_CONDITIONS","Active condition must be mapped to the selected component.",true,"COMPONENT_ID"),
        binding(DefectFieldBinding.ZONE,"Location","zone","defects_dtl","zone","varchar(80)",List.of(FieldType.TEXT,FieldType.TEXTAREA),List.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT"),"REQUIRED",null,"Physical cabin location detail such as galley number, lavatory number, attendant seat number, row, or cabin position; max 80 characters.",true,null),
        binding(DefectFieldBinding.ROW_NUMBER,"Row number","rowNumber","defects_dtl","row_number","smallint",List.of(FieldType.NUMBER),List.of("CABIN"),"CONFIGURABLE",null,"Whole number from 1 through 200.",true,null),
        binding(DefectFieldBinding.SEAT_REFERENCE,"Seat number","seatReference","defects_dtl","seat_reference","varchar(8)",List.of(FieldType.TEXT),List.of("CABIN","ATTENDANT_SEAT"),"CONFIGURABLE",null,"Optional seat identifier up to 8 characters; applicable to passenger cabin and attendant-seat areas.",true,null),
        binding(DefectFieldBinding.TITLE,"Defect title","title","defects_dtl","title","varchar(160)",List.of(FieldType.TEXT,FieldType.TEXTAREA),List.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT"),"REQUIRED",null,"Non-blank text up to 160 characters.",true,null),
        binding(DefectFieldBinding.DESCRIPTION,"Defect description","description","defects_dtl","description","varchar(4000)",List.of(FieldType.TEXTAREA),List.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT"),"REQUIRED",null,"Non-blank text up to 4000 characters.",true,null),
        binding(DefectFieldBinding.REMARKS,"Remarks","remarks","defects_dtl","description","varchar(4000)",List.of(FieldType.TEXTAREA),List.of("GALLEY"),"CONFIGURABLE",null,"Optional remarks are combined with the required condition description when creating a Galley defect.",false,null),
        binding(DefectFieldBinding.SEVERITY,"Severity","severityId","defects_dtl","severity_id","uuid",List.of(FieldType.DROPDOWN),DEFECT_AREAS,"REQUIRED","DEFECT_SEVERITIES","Active severity belonging to the authenticated organization.",true,null),
        binding(DefectFieldBinding.EVIDENCE_ATTACHMENTS,"Evidence attachments","attachments","attachments_dtl","object_key","private object reference",List.of(FieldType.FILE_UPLOAD,FieldType.IMAGE_UPLOAD),List.of("CABIN","GALLEY","LAVATORY","ENTRY_AREA","ATTENDANT_SEAT"),"CONFIGURABLE",null,"Private object storage; metadata is tenant-scoped and file must pass validation and malware scan.",true,null),
        binding(DefectFieldBinding.AIRCRAFT_ID,"Aircraft registration","aircraftId","aircraft_mst","id","uuid",List.of(FieldType.AIRCRAFT_SELECT),DEFECT_AREAS,"REQUIRED","AIRCRAFT","Select an active aircraft from the authenticated tenant. The submitted UUID is resolved and validated by the defect service; it is not stored as a form answer.",false,null)
    );

    private static FieldBindingDefinition binding(DefectFieldBinding key, String name, String property, String table,
        String column, String dataType, List<FieldType> types, List<String> areas, String required,
        String source, String validation, boolean multiple, String dependency) {
        OptionSource optionSource = source == null ? null : OptionSource.valueOf(source);
        List<FormPurpose> purposes = key==DefectFieldBinding.EVIDENCE_ATTACHMENTS
            ? List.of(FormPurpose.values()) : List.of(FormPurpose.DEFECT_REPORT);
        return new FieldBindingDefinition(key,key==DefectFieldBinding.AIRCRAFT_ID?"aircraftId":key.name().toLowerCase(java.util.Locale.ROOT),name,property,table,column,dataType,
            types,areas,purposes,required,optionSource,validation,
            key==DefectFieldBinding.EVIDENCE_ATTACHMENTS?"SYSTEM_MANAGED_REFERENCE":key==DefectFieldBinding.AIRCRAFT_ID?"SYSTEM_CONTROLLED_SELECTION":"USER_EDITABLE",multiple,dependency);
    }

    public List<FieldBindingDefinition> all() { return FIELDS; }
    public FieldBindingDefinition require(DefectFieldBinding binding) {
        return FIELDS.stream().filter(entry -> entry.binding()==binding).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(UNSUPPORTED_FIELD_MESSAGE));
    }
    public Map<DefectFieldBinding,FieldBindingDefinition> byKey() {
        return FIELDS.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(FieldBindingDefinition::binding, entry -> entry));
    }
}
