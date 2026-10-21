package com.ecabin.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record CreateDefectRequest(
        @NotBlank @Size(max = 16) String tailNumber,
        @Size(max = 24) String location,
        @Size(max = 32) String category,
        @NotNull UUID areaId,
        UUID componentId,
        UUID conditionId,
        @Size(max = 80) String zone,
        @Min(1) @Max(200) Integer rowNumber,
        @Size(max = 8) String seatReference,
        @Size(max = 120) String component,
        @Size(max = 160) String title,
        @Size(max = 4000) String description,
        @Size(max = 24) String severity,
        UUID aircraftId,
        UUID formId,
        UUID formVersionId,
        Map<String,Object> formAnswers
    ) {
        public CreateDefectRequest(String tailNumber,String location,String category,UUID areaId,UUID componentId,UUID conditionId,String zone,Integer rowNumber,String seatReference,String component,String title,String description,String severity,UUID aircraftId,UUID formId,UUID formVersionId) {
            this(tailNumber,location,category,areaId,componentId,conditionId,zone,rowNumber,seatReference,component,title,description,severity,aircraftId,formId,formVersionId,Map.of());
        }
        public CreateDefectRequest(String tailNumber, String location, String category, UUID areaId,
                UUID componentId, UUID conditionId, String zone, Integer rowNumber, String seatReference,
                String component, String title, String description, String severity) {
            this(tailNumber, location, category, areaId, componentId, conditionId, zone, rowNumber,
                seatReference, component, title, description, severity, null, null, null, Map.of());
        }
    }

    public record TransitionRequest(
        @NotBlank @Size(max = 20) String status,
        @NotBlank @Size(max = 2000) String note,
        @Size(max = 240) String evidenceReference,
        @Size(max = 16) String inspectionOutcome
    ) {}

    public record InspectionRequest(
        @NotBlank @Size(max = 24) String result,
        @NotBlank @Size(max = 4000) String findings,
        @Size(max = 2000) String notes
    ) {}

    public record CorrectiveActionRequest(
        @Size(max = 200) String assignedUserId,
        @Size(max = 120) String assignedTeam,
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 16) String priority,
        Instant dueAt
    ) {}

    public record CorrectiveActionReassignmentRequest(
        @NotBlank @Size(max = 120) String assignedTeam,
        @Size(max = 200) String assignedUserId,
        @NotBlank @Size(max = 2000) String reason
    ) {}

    public record ActionCompletionRequest(@NotBlank @Size(max = 2000) String completionNotes) {}
    public record MaintenanceActionUpdateRequest(@NotNull UUID formId,@NotNull UUID formVersionId,@NotBlank String status,LocalDate slaTargetDate,@Size(max=2000) String remark,Map<String,List<UUID>> attachments) {
        public MaintenanceActionUpdateRequest(UUID formId,UUID formVersionId,String status,LocalDate slaTargetDate,String remark) {
            this(formId,formVersionId,status,slaTargetDate,remark,Map.of());
        }
    }
    public record MaintenanceSlaPolicy(String statusKey,boolean configured,boolean dateRequired,boolean allowFuture,Integer maxFutureDays,boolean allowPast,Integer maxPastDays,String businessTimezone) {}
    public record MaintenanceSlaPolicyUpdate(String statusKey,boolean dateRequired,boolean allowFuture,Integer maxFutureDays,boolean allowPast,Integer maxPastDays,String businessTimezone,@NotBlank @Size(max=1000) String reason) {}
    public record MaintenanceSlaEffective(MaintenanceSlaPolicy policy,LocalDate today,LocalDate minDate,LocalDate maxDate) {}

    public record VerificationRequest(
        @NotBlank @Size(max = 16) String result,
        @NotBlank @Size(max = 2000) String notes,
        @NotBlank @Size(max = 240) String evidenceReference
    ) {}

    public record ApprovalRequest(
        @NotBlank @Size(max = 16) String decision,
        @Size(max = 2000) String reason,
        @Size(max = 240) String evidenceReference,
        UUID formId,
        UUID versionId
    ) {
        public ApprovalRequest(String decision,String reason,String evidenceReference){this(decision,reason,evidenceReference,null,null);}
    }

    public record ReviewerDecisionRequest(
        @NotBlank @Size(max = 16) String decision,
        UUID maintenanceTeamId,
        @Size(max = 2000) String rejectionReason,
        @Size(max = 2000) String remark,
        @NotNull UUID formId,
        @NotNull UUID versionId
    ) {
        public ReviewerDecisionRequest(String decision,UUID maintenanceTeamId,String remark,UUID formId,UUID versionId){this(decision,maintenanceTeamId,null,remark,formId,versionId);}
        public ReviewerDecisionRequest(String decision,UUID maintenanceTeamId,String remark){this(decision,maintenanceTeamId,null,remark,null,null);}
    }

    public record ReviewerDecisionTeam(UUID id, String name) {}
    public record ReviewerDecisionOptions(String areaName, String status, List<ReviewerDecisionTeam> maintenanceTeams) {}

    public record WorkflowRecord(UUID id, String status) {}

    public record DefectView(UUID id, String reference, String tailNumber, String aircraftType,
        String location, String category, String zone, Integer rowNumber, String seatReference,
        String component, UUID areaId, UUID componentId, UUID conditionId, String conditionName, String title, String description, String severity, String severityName, String status,
        String reportedBy, String assignedTo, UUID activeActionId, Instant dueAt, Instant reportedAt, Instant updatedAt,
        String assignedTeam, String routingAttention, Instant closedAt) {}

    public record SubmittedFormValue(String label, String value) {}
    /** Whitelisted, user-facing values projected from the immutable form snapshot or event state. */
    public record DefectEventDetails(String formId, String formName, String formPurpose, String workflowStage,
        Integer formVersionNumber, List<SubmittedFormValue> submittedDetails, String maintenanceStatus,
        String slaTargetDate, String previousSlaTargetDate, String actionStatus, String previousActionStatus,
        String maintenanceRemark, String decision, String maintenanceTeam, UUID evidenceAttachmentId) {}
    public record DefectEventView(UUID id, String eventType, String fromStatus, String toStatus,
        String note, String evidenceReference, String inspectionOutcome, String actorId, Instant occurredAt,
        DefectEventDetails details) {}

    public record DefectPage(List<DefectView> items, String nextCursor) {}
    public record DashboardSummary(long openDefects, long criticalDefects, long reportedToday, long closedThisMonth) {}
    public record FleetStatusRow(UUID id, String msnNumber, String tailNumber, String aircraftType, long openDefects) {}
    public record FleetStatusPage(List<FleetStatusRow> items, int page, int size, long totalElements, int totalPages) {}
    public record FleetDefectSummary(long totalDefects, long awaitingReview, long inMaintenance, long closed) {}
    public record AircraftDefectPage(List<DefectView> items, int page, int size, long totalElements, int totalPages) {}
    public record MaintenanceWorkQueueRow(UUID id, String defectId, String aircraftRegistration, String msnNumber,
        String aircraftType, String zone, String component, String condition, String description, String assignedTeam,
        String defectStatus, String correctiveActionStatus, Instant assignmentDate, Instant reportedAt) {}
    public record MaintenanceWorkQueuePage(List<MaintenanceWorkQueueRow> items, int page, int size, long totalElements, int totalPages) {}
    public record ReviewerQueueRow(UUID id, String defectId, String aircraftRegistration, String msnNumber, String aircraftType,
        String zone, String component, String severity, String severityName, String status, String reviewerTeam, String maintenanceTeam, boolean routingNeedsAttention, Instant reportedAt) {}
    public record ReviewerQueuePage(List<ReviewerQueueRow> items, int page, int size, long totalElements, int totalPages) {}
    public record ReviewerTeamOption(UUID id,String name,int eligibleMembers) {}
    public record ConditionOption(UUID id, String code, String name, String description) {}
    public record CurrentUserView(String organizationName, String applicationName, String displayName, String role, Set<String> permissions) {}
    public record NavigationItem(String key, String label, String icon, String section, List<NavigationItem> children) {}
    public record ErrorBody(ErrorDetail error) {}
    public record ErrorDetail(String code, String message) {}
    public record WorkflowStatusView(UUID id, String moduleCode, String statusCode, String displayName,
        String aliasName, String description, boolean isActive, int sortOrder, boolean isInitial, boolean isTerminal,
        String backgroundColor, String textColor, String borderColor, String icon, String displayStyle) {}
    public record WorkflowStatusPage(List<WorkflowStatusView> items, int page, int size, long totalElements, int totalPages) {}
    public record WorkflowStatusUpdateRequest(
        @jakarta.validation.constraints.Size(max = 100) String displayName,
        @jakarta.validation.constraints.Size(max = 100) String aliasName,
        @jakarta.validation.constraints.Size(max = 500) String description,
        @jakarta.validation.constraints.Min(0) Integer sortOrder,
        Boolean isActive,
        @jakarta.validation.constraints.Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String backgroundColor,
        @jakarta.validation.constraints.Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String textColor,
        @jakarta.validation.constraints.Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String borderColor,
        @jakarta.validation.constraints.Size(max = 32) String icon,
        @jakarta.validation.constraints.Pattern(regexp = "^(SOLID|OUTLINE|SOFT)$") String displayStyle,
        @NotBlank @Size(max = 1000) String reason
    ) {}
}
