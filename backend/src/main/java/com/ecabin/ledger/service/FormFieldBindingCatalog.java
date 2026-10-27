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

    private static final List<FieldBindingDefinition> FIELDS = List.of(
        binding(DefectFieldBinding.DEFECT_CATEGORY,"Defect category","category","defects_dtl","category","varchar(32)",List.of(FieldType.DROPDOWN),List.of("*"),"CONFIGURABLE","DEFECT_CATEGORIES","Active category code owned by the authenticated organization.",true,null),
        binding(DefectFieldBinding.COMPONENT_ID,"Component","componentId","defects_dtl","component_id","uuid",List.of(FieldType.DROPDOWN),List.of("*"),"CONFIGURABLE","COMPONENTS","Active component must belong to the selected cabin area and organization.",true,null),
        binding(DefectFieldBinding.CONDITION_ID,"Defect condition","conditionId","defects_dtl","condition_id","uuid",List.of(FieldType.DROPDOWN),List.of("*"),"CONFIGURABLE","DEFECT_CONDITIONS","Active condition must be mapped to the selected component.",true,"COMPONENT_ID"),
        binding(DefectFieldBinding.ZONE,"Location","zone","defects_dtl","zone","varchar(80)",List.of(FieldType.TEXT,FieldType.TEXTAREA),List.of("*"),"CONFIGURABLE",null,"Physical location detail; max 80 characters.",true,null),
        binding(DefectFieldBinding.ROW_NUMBER,"Row number","rowNumber","defects_dtl","row_number","smallint",List.of(FieldType.NUMBER),List.of("*"),"CONFIGURABLE",null,"Whole number from 1 through 200.",true,null),
        binding(DefectFieldBinding.SEAT_REFERENCE,"Seat number","seatReference","defects_dtl","seat_reference","varchar(8)",List.of(FieldType.TEXT),List.of("*"),"CONFIGURABLE",null,"Optional seat identifier up to 8 characters.",true,null),
        binding(DefectFieldBinding.TITLE,"Defect title","title","defects_dtl","title","varchar(160)",List.of(FieldType.TEXT,FieldType.TEXTAREA),List.of("*"),"CONFIGURABLE",null,"Non-blank text up to 160 characters.",true,null),
        binding(DefectFieldBinding.DESCRIPTION,"Defect description","description","defects_dtl","description","varchar(4000)",List.of(FieldType.TEXTAREA),List.of("*"),"CONFIGURABLE",null,"Non-blank text up to 4000 characters.",true,null),
        binding(DefectFieldBinding.REMARKS,"Remarks","remarks","defects_dtl","description","varchar(4000)",List.of(FieldType.TEXTAREA),List.of("*"),"CONFIGURABLE",null,"Optional remarks are appended to the defect description when configured.",false,null),
        binding(DefectFieldBinding.SEVERITY,"Severity","severityId","defects_dtl","severity_id","uuid",List.of(FieldType.DROPDOWN),List.of("*"),"CONFIGURABLE","DEFECT_SEVERITIES","Active severity belonging to the authenticated organization.",true,null),
        binding(DefectFieldBinding.EVIDENCE_ATTACHMENTS,"Evidence attachments","attachments","attachments_dtl","object_key","private object reference",List.of(FieldType.FILE_UPLOAD,FieldType.IMAGE_UPLOAD),List.of("*"),"CONFIGURABLE",null,"Private object storage; metadata is tenant-scoped and each file must pass configured type, content, and size validation.",true,null),
        binding(DefectFieldBinding.AIRCRAFT_ID,"Aircraft registration","aircraftId","aircraft_mst","id","uuid",List.of(FieldType.AIRCRAFT_SELECT),List.of("*"),"REQUIRED","AIRCRAFT","Select an active aircraft from the authenticated tenant. The submitted UUID is resolved and validated by the defect service; it is not stored as a form answer.",false,null),
        binding(DefectFieldBinding.REVIEW_DECISION,"Reviewer decision","decision","defect_events_dtl","new_state","workflow event",List.of(FieldType.DROPDOWN),List.of("*"),"REQUIRED","REVIEW_DECISIONS","Only APPROVE or REJECT is accepted by the authenticated Reviewer workflow endpoint.",false,null),
        binding(DefectFieldBinding.REVIEW_MAINTENANCE_TEAM,"Maintenance team","maintenanceTeamId","defect_events_dtl","new_state","workflow event",List.of(FieldType.DROPDOWN),List.of("*"),"REQUIRED_ON_APPROVE","MAINTENANCE_TEAMS","Options and eligibility are resolved per case from the Reviewer decision-options endpoint; approval validates active tenant ownership and membership.",false,"REVIEW_DECISION"),
        binding(DefectFieldBinding.REVIEW_REJECTION_REASON,"Rejection reason","rejectionReason","defect_events_dtl","new_state","varchar(2000) workflow event value",List.of(FieldType.TEXTAREA),List.of("*"),"REQUIRED_ON_REJECT",null,"Required on REJECT; maximum 2000 characters, stored in the immutable decision event.",false,"REVIEW_DECISION"),
        binding(DefectFieldBinding.REVIEW_REMARK,"Reviewer remark","remark","defect_events_dtl","new_state","workflow event",List.of(FieldType.TEXTAREA),List.of("*"),"OPTIONAL",null,"Optional Reviewer context, maximum 2000 characters; recorded only through the append-only decision event.",false,null),
        binding(DefectFieldBinding.MAINTENANCE_STATUS,"Maintenance status","status","corrective_actions_dtl","status","varchar(20)",List.of(FieldType.DROPDOWN),List.of("*"),"REQUIRED","MAINTENANCE_ACTION_STATUSES","Only Work in Progress, On Hold, or Close Submitted; each maps to a server-controlled action transition.",false,null),
        binding(DefectFieldBinding.SLA_TARGET_DATE,"SLA target date","slaTargetDate","corrective_actions_dtl","due_at","timestamptz (business-timezone calendar date)",List.of(FieldType.DATE),List.of("*"),"REQUIRED",null,"SLA policy for the selected status is enforced by the workflow service.",false,null),
        binding(DefectFieldBinding.MAINTENANCE_REMARK,"Maintenance remark","remark","defect_events_dtl","new_state","varchar(2000) workflow event value",List.of(FieldType.TEXTAREA),List.of("*"),"OPTIONAL",null,"Optional maintenance context, maximum 2000 characters; captured in append-only defect event history.",false,null)
        ,binding(DefectFieldBinding.CLOSURE_DECISION,"Closure decision","decision","closure_approvals_dtl","decision","varchar(16) workflow decision",List.of(FieldType.DROPDOWN),List.of("*"),"REQUIRED","CLOSURE_DECISIONS","Available for any active tenant cabin area; only ACCEPT or DECLINE is accepted by the closure workflow.",false,null)
        ,binding(DefectFieldBinding.CLOSURE_REMARK,"Closure remarks","remark","closure_approvals_dtl","reason","varchar(2000) workflow decision",List.of(FieldType.TEXTAREA),List.of("*"),"REQUIRED_ON_DECLINE",null,"Available for any active tenant cabin area. Optional when accepting; required with DECLINE, maximum 2000 characters. Stored with immutable closure decision history.",false,"CLOSURE_DECISION")
    );

    private static FieldBindingDefinition binding(DefectFieldBinding key, String name, String property, String table,
        String column, String dataType, List<FieldType> types, List<String> areas, String required,
        String source, String validation, boolean multiple, String dependency) {
        OptionSource optionSource = source == null ? null : OptionSource.valueOf(source);
        List<FormPurpose> purposes = key==DefectFieldBinding.EVIDENCE_ATTACHMENTS
            ? List.of(FormPurpose.DEFECT_REPORT,FormPurpose.INSPECTION,FormPurpose.CORRECTIVE_ACTION,FormPurpose.VERIFICATION,FormPurpose.CLOSURE_APPROVAL,FormPurpose.REOPEN)
            : key==DefectFieldBinding.MAINTENANCE_STATUS||key==DefectFieldBinding.SLA_TARGET_DATE||key==DefectFieldBinding.MAINTENANCE_REMARK
                ? List.of(FormPurpose.CORRECTIVE_ACTION)
            : key==DefectFieldBinding.CLOSURE_DECISION||key==DefectFieldBinding.CLOSURE_REMARK
                ? List.of(FormPurpose.CLOSURE_APPROVAL)
            : key==DefectFieldBinding.REVIEW_DECISION||key==DefectFieldBinding.REVIEW_MAINTENANCE_TEAM||key==DefectFieldBinding.REVIEW_REJECTION_REASON||key==DefectFieldBinding.REVIEW_REMARK
                ? List.of(FormPurpose.REVIEW_DECISION) : List.of(FormPurpose.DEFECT_REPORT);
        return new FieldBindingDefinition(key,key==DefectFieldBinding.AIRCRAFT_ID?"aircraftId":key.name().toLowerCase(java.util.Locale.ROOT),name,property,table,column,dataType,
            types,areas,purposes,required,optionSource,validation,
            key==DefectFieldBinding.EVIDENCE_ATTACHMENTS?"SYSTEM_MANAGED_REFERENCE":key==DefectFieldBinding.AIRCRAFT_ID?"SYSTEM_CONTROLLED_SELECTION":key==DefectFieldBinding.CLOSURE_DECISION||key==DefectFieldBinding.CLOSURE_REMARK||key==DefectFieldBinding.REVIEW_DECISION||key==DefectFieldBinding.REVIEW_MAINTENANCE_TEAM||key==DefectFieldBinding.REVIEW_REJECTION_REASON||key==DefectFieldBinding.REVIEW_REMARK||key==DefectFieldBinding.MAINTENANCE_STATUS||key==DefectFieldBinding.SLA_TARGET_DATE||key==DefectFieldBinding.MAINTENANCE_REMARK?"WORKFLOW_CONTROLLED":"USER_EDITABLE",multiple,dependency);
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
