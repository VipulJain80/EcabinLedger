package com.ecabin.ledger.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FormModels {
    private FormModels() {}
    public enum FieldType { TEXT, TEXTAREA, NUMBER, DROPDOWN, MULTI_SELECT, DATE, CHECKBOX, FILE_UPLOAD, IMAGE_UPLOAD, AIRCRAFT_SELECT, SECTION }
    public enum FormPurpose { DEFECT_REPORT, REVIEW_DECISION, INSPECTION, CORRECTIVE_ACTION, VERIFICATION, CLOSURE_APPROVAL, REOPEN }
    public enum WorkflowStage { INITIAL_REPORT, REPORTED, UNDER_REVIEW, INSPECTION_REQUIRED, INSPECTION_IN_PROGRESS, INSPECTION_COMPLETE, ACTION_ASSIGNED, IN_PROGRESS, AWAITING_VERIFICATION, VERIFIED, APPROVAL_REQUIRED, CLOSED, REOPENED }
    public enum OptionSource { DEFECT_CATEGORIES, DEFECT_SEVERITIES, @Deprecated GALLEY_SEVERITIES, REVIEW_DECISIONS, CLOSURE_DECISIONS, MAINTENANCE_ACTION_STATUSES, CABIN_AREAS, CABIN_ZONES, COMPONENTS, DEFECT_CONDITIONS, AIRCRAFT, FLEETS, MAINTENANCE_TEAMS }
    public enum DefectFieldBinding { DEFECT_CATEGORY, COMPONENT_ID, CONDITION_ID, ZONE, ROW_NUMBER, SEAT_REFERENCE, TITLE, DESCRIPTION, REMARKS, SEVERITY, EVIDENCE_ATTACHMENTS, AIRCRAFT_ID, REVIEW_DECISION, REVIEW_MAINTENANCE_TEAM, REVIEW_REJECTION_REASON, REVIEW_REMARK, MAINTENANCE_STATUS, SLA_TARGET_DATE, MAINTENANCE_REMARK, CLOSURE_DECISION, CLOSURE_REMARK }
    public record TextRules(Integer minLength, Integer maxLength, Boolean alphaOnly, Boolean alphanumeric, String pattern) {}
    public record NumericRules(BigDecimal min, BigDecimal max, Integer precision) {}
    public record DateRules(String minDate, String maxDate) {}
    public record OptionFilter(String areaFieldKey, String componentFieldKey) {}
    /** Narrow, data-only conditional visibility rule; not an expression or executable predicate. */
    public record VisibilityRule(String fieldKey, @JsonProperty("equals") String expectedValue) {}
    public record RequiredRule(String fieldKey, @JsonProperty("equals") String expectedValue) {}
    public record FileRules(List<String> extensions, List<String> mimeTypes, Long maxFileSizeBytes, Integer minCount, Integer maxCount, Long maxTotalSizeBytes) {
        /** Keeps JSON and Java callers using the pre-total-limit contract source compatible. */
        public FileRules(List<String> extensions,List<String> mimeTypes,Long maxFileSizeBytes,Integer minCount,Integer maxCount) {
            this(extensions,mimeTypes,maxFileSizeBytes,minCount,maxCount,null);
        }
    }
    public record FieldDefinition(@NotBlank @Size(max=80) String key, @NotBlank @Size(max=120) String label,
        @NotNull FieldType type, @Size(max=500) String helpText, boolean required, int order,
        TextRules textRules, NumericRules numericRules, DateRules dateRules, OptionSource optionSource,
        OptionFilter optionFilter, FileRules fileRules, DefectFieldBinding binding, Boolean visible, Boolean readOnly, VisibilityRule visibleWhen, RequiredRule requiredWhen,
        @Size(max=10000) String defaultValue) {
        public FieldDefinition(String key,String label,FieldType type,String helpText,boolean required,int order,TextRules textRules,NumericRules numericRules,DateRules dateRules,OptionSource optionSource,OptionFilter optionFilter,FileRules fileRules,DefectFieldBinding binding,Boolean visible,Boolean readOnly,VisibilityRule visibleWhen,RequiredRule requiredWhen) {
            this(key,label,type,helpText,required,order,textRules,numericRules,dateRules,optionSource,optionFilter,fileRules,binding,visible,readOnly,visibleWhen,requiredWhen,null);
        }
        public FieldDefinition(String key,String label,FieldType type,String helpText,boolean required,int order,TextRules textRules,NumericRules numericRules,DateRules dateRules,OptionSource optionSource,OptionFilter optionFilter,FileRules fileRules,DefectFieldBinding binding,Boolean visible,Boolean readOnly,VisibilityRule visibleWhen) {
            this(key,label,type,helpText,required,order,textRules,numericRules,dateRules,optionSource,optionFilter,fileRules,binding,visible,readOnly,visibleWhen,null,null);
        }
        public FieldDefinition(String key,String label,FieldType type,String helpText,boolean required,int order,TextRules textRules,NumericRules numericRules,DateRules dateRules,OptionSource optionSource,OptionFilter optionFilter,FileRules fileRules,DefectFieldBinding binding,Boolean visible,Boolean readOnly) {
            this(key,label,type,helpText,required,order,textRules,numericRules,dateRules,optionSource,optionFilter,fileRules,binding,visible,readOnly,null,null,null);
        }
        public FieldDefinition(String key,String label,FieldType type,String helpText,boolean required,int order,TextRules textRules,NumericRules numericRules,DateRules dateRules,OptionSource optionSource,OptionFilter optionFilter,FileRules fileRules) {
            this(key,label,type,helpText,required,order,textRules,numericRules,dateRules,optionSource,optionFilter,fileRules,null,true,false,null,null,null);
        }
        public FieldDefinition(String key,String label,FieldType type,String helpText,boolean required,int order,TextRules textRules,NumericRules numericRules,DateRules dateRules,OptionSource optionSource,OptionFilter optionFilter,FileRules fileRules,DefectFieldBinding binding) {
            this(key,label,type,helpText,required,order,textRules,numericRules,dateRules,optionSource,optionFilter,fileRules,binding,true,false,null,null,null);
        }
    }
    public record FormConfiguration(@NotNull @Size(max=100) List<@NotNull FieldDefinition> fields) {}
    public record CreateFormRequest(@NotBlank @Size(max=80) String formKey, @NotBlank @Size(max=160) String name, UUID areaId, List<UUID> areaIds, FormPurpose purpose, WorkflowStage workflowStage) {
        public CreateFormRequest(String formKey,String name,UUID areaId,FormPurpose purpose,WorkflowStage workflowStage) { this(formKey,name,areaId,areaId==null?List.of():List.of(areaId),purpose,workflowStage); }
        public CreateFormRequest(String formKey,String name,UUID areaId) { this(formKey,name,areaId,areaId==null?List.of():List.of(areaId),FormPurpose.DEFECT_REPORT,WorkflowStage.INITIAL_REPORT); }
        public List<UUID> effectiveAreaIds(){return areaIds==null||areaIds.isEmpty()?(areaId==null?List.of():List.of(areaId)):areaIds;}
    }
    public record DuplicateFormRequest(@NotBlank @Size(max=80) String formKey,@NotBlank @Size(max=160) String name,
        UUID areaId,List<UUID> areaIds,@NotNull FormPurpose purpose,@NotNull WorkflowStage workflowStage,UUID sourceVersionId) {
        public DuplicateFormRequest(String formKey,String name,UUID areaId,FormPurpose purpose,WorkflowStage workflowStage,UUID sourceVersionId){this(formKey,name,areaId,areaId==null?List.of():List.of(areaId),purpose,workflowStage,sourceVersionId);}
        public List<UUID> effectiveAreaIds(){return areaIds==null||areaIds.isEmpty()?(areaId==null?List.of():List.of(areaId)):areaIds;}
    }
    public record RenameFormRequest(@NotBlank @Size(max=160) String name,@NotBlank @Size(max=2000) String reason) {}
    public record DeleteFormRequest(@NotBlank @Size(max=1000) String reason) {}
    public record ReassignFormAreaRequest(@NotNull UUID areaId, @NotBlank @Size(max=2000) String reason) {}
    public record UpdateFormMappingRequest(UUID areaId,List<UUID> areaIds,@NotNull FormPurpose purpose,@NotNull WorkflowStage workflowStage,@NotBlank @Size(max=2000) String reason) {
        public UpdateFormMappingRequest(UUID areaId,FormPurpose purpose,WorkflowStage workflowStage,String reason){this(areaId,areaId==null?List.of():List.of(areaId),purpose,workflowStage,reason);}
        public List<UUID> effectiveAreaIds(){return areaIds==null||areaIds.isEmpty()?(areaId==null?List.of():List.of(areaId)):areaIds;}
    }
    public record FormMappingOption(UUID areaId,String areaCode,FormPurpose purpose,WorkflowStage workflowStage,boolean mapped) {
        public FormMappingOption(UUID areaId,String areaCode,FormPurpose purpose,WorkflowStage workflowStage){this(areaId,areaCode,purpose,workflowStage,false);}
    }
    public record FormArea(UUID id,String name,String code) {}
    public record SaveDraftRequest(@NotNull FormConfiguration configuration) {}
    public record PublishRequest(@NotNull UUID versionId) {}
    public record RestoreFormVersionRequest(@NotNull Boolean replaceExistingDraft,UUID expectedDraftVersionId,@NotBlank @Size(max=2000) String reason) {}
    public record DeactivateRequest(@NotBlank @Size(max=1000) String reason) {}
    public record FormSummary(UUID id, String formKey, String name, UUID areaId, String areaName, String areaNames, FormPurpose purpose, WorkflowStage workflowStage, int isactive,
        Integer publishedVersion, UUID publishedVersionId, Integer draftVersion, UUID draftVersionId, Instant updatedAt) {
        public FormSummary(UUID id,String formKey,String name,UUID areaId,String areaName,FormPurpose purpose,WorkflowStage workflowStage,int isactive,Integer publishedVersion,UUID publishedVersionId,Integer draftVersion,UUID draftVersionId,Instant updatedAt){this(id,formKey,name,areaId,areaName,areaName,purpose,workflowStage,isactive,publishedVersion,publishedVersionId,draftVersion,draftVersionId,updatedAt);}
    }
    public record FormTablePage(List<FormSummary> items,int page,int size,long totalElements,int totalPages) {}
    public record FormVersion(UUID id, UUID formId, int versionNumber, String status, FormConfiguration configuration,
        String createdBy, Instant createdAt, String publishedBy, Instant publishedAt, String formName, UUID areaId,
        FormPurpose purpose, WorkflowStage workflowStage,List<UUID> areaIds) {
        public FormVersion(UUID id,UUID formId,int versionNumber,String status,FormConfiguration configuration,String createdBy,Instant createdAt,String publishedBy,Instant publishedAt,String formName,UUID areaId,FormPurpose purpose,WorkflowStage workflowStage){this(id,formId,versionNumber,status,configuration,createdBy,createdAt,publishedBy,publishedAt,formName,areaId,purpose,workflowStage,areaId==null?List.of():List.of(areaId));}
    }
    public record DeletedForm(UUID id,String formKey,String name,UUID areaId,String areaName,FormPurpose purpose,WorkflowStage workflowStage) {}
    public record FormForArea(UUID id, String formKey, String name, UUID areaId, FormPurpose purpose, WorkflowStage workflowStage, UUID versionId, int versionNumber,
        FormConfiguration configuration) {}
    public record FormOption(UUID id, String code, String label) {}
    public record AircraftOption(UUID id, String tailNumber, String msnNumber, String aircraftType) {}
    public record AircraftOptionPage(List<AircraftOption> items, int page, int size, long totalElements, int totalPages) {}
    public record FieldBindingDefinition(DefectFieldBinding binding, String stableKey, String name, String domainProperty,
        String destinationTable, String destinationColumn, String dataType, List<FieldType> supportedFieldTypes,
        List<String> cabinAreaCodes, List<FormPurpose> formPurposes, String requiredPolicy, OptionSource referenceDataSource,
        String validation, String classification, boolean multipleAllowed, String dependsOnBinding) {}
    public record FormDataSnapshot(String defectCategory, UUID componentId, UUID conditionId, String zone,
        Integer rowNumber, String seatReference, String title, String description, UUID severityId) {}
    public record SubmitFormRequest(@NotNull UUID formId, @NotNull UUID versionId, FormPurpose purpose, @NotNull Map<String,Object> answers) {
        public SubmitFormRequest(UUID formId,UUID versionId,Map<String,Object> answers) { this(formId,versionId,null,answers); }
    }
    public record FormSubmission(UUID id, UUID defectId, UUID formId, UUID versionId, int versionNumber, FormPurpose purpose, WorkflowStage workflowStage,
        String submittedBy, FormDataSnapshot snapshot, Instant submittedAt) {}
    public record AttachmentView(UUID id, UUID defectId, UUID formSubmissionId, String fieldKey, String filename,
        String mimeType, long sizeBytes, String scanStatus, Instant uploadedAt, String source, String uploadedBy) {}
}
