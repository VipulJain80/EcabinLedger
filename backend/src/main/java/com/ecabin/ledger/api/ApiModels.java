package com.ecabin.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public record CreateDefectRequest(
        @NotBlank @Size(max = 16) String tailNumber,
        @NotBlank @Size(max = 24) String location,
        @NotBlank @Size(max = 32) String category,
        @NotBlank @Size(max = 80) String zone,
        @Min(1) @Max(200) Integer rowNumber,
        @Size(max = 8) String seatReference,
        @Size(max = 120) String component,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 4000) String description,
        @NotBlank @Size(max = 16) String severity
    ) {}

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

    public record ActionCompletionRequest(@NotBlank @Size(max = 2000) String completionNotes) {}

    public record VerificationRequest(
        @NotBlank @Size(max = 16) String result,
        @NotBlank @Size(max = 2000) String notes,
        @NotBlank @Size(max = 240) String evidenceReference
    ) {}

    public record ApprovalRequest(
        @NotBlank @Size(max = 16) String decision,
        @NotBlank @Size(max = 2000) String reason,
        @Size(max = 240) String evidenceReference
    ) {}

    public record WorkflowRecord(UUID id, String status) {}

    public record DefectView(UUID id, String reference, String tailNumber, String aircraftType,
        String location, String category, String zone, Integer rowNumber, String seatReference,
        String component, String title, String description, String severity, String status,
        String reportedBy, String assignedTo, UUID activeActionId, Instant dueAt, Instant reportedAt, Instant updatedAt) {}

    public record DefectEventView(UUID id, String eventType, String fromStatus, String toStatus,
        String note, String evidenceReference, String inspectionOutcome, String actorId, Instant occurredAt) {}

    public record DefectPage(List<DefectView> items, String nextCursor) {}
    public record DashboardSummary(long openDefects, long criticalDefects, long reportedToday, long closedThisMonth) {}
    public record FleetStatusRow(UUID id, String msnNumber, String tailNumber, String aircraftType, long openDefects) {}
    public record FleetStatusPage(List<FleetStatusRow> items, int page, int size, long totalElements, int totalPages) {}
    public record CurrentUserView(String organizationName, String displayName, String role, Set<String> permissions) {}
    public record NavigationItem(String key, String label, String icon, String section, List<NavigationItem> children) {}
    public record ErrorBody(ErrorDetail error) {}
    public record ErrorDetail(String code, String message) {}
}
