package com.ecabin.ledger.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tenant-scoped report configuration DTOs. Routine implementation details are never returned. */
public final class ReportAdministrationModels {
    private ReportAdministrationModels() {}

    public record RoutineParameterOption(String parameterKey, String parameterType, boolean required, boolean filterable, int sortOrder) {}

    public record RoutineOption(UUID id, String routineKey, int contractVersion, List<RoutineParameterOption> parameters) {}

    public record LookupOption(UUID id, String lookupKey, String valueType) {}

    public record ReportPage(List<ReportAdminSummary> items, int page, int size, long totalElements, int totalPages) {}

    public record ReportAdminSummary(UUID id, String reportCode, int definitionVersion, String name,
            String description, int sortOrder, boolean active, boolean current, long version,
            UUID sourceTemplateId, Integer sourceTemplateVersion) {}

    public record FilterDefinition(UUID id, String filterKey, String label, String filterType,
            String parameterKey, boolean required, int sortOrder, Map<String, Object> configuration,
            UUID lookupSourceId, String lookupKey) {}

    public record ColumnDefinition(UUID id, String columnKey, String label, String dataType,
            int sortOrder, boolean visible, boolean sortable, boolean exportable,
            Map<String, Object> formatConfiguration) {}

    public record RoleGrant(String roleKey, String roleName, boolean canView, boolean canExecute, boolean canExport) {}

    public record ReportAdminDetail(ReportAdminSummary report, UUID routineId,
            List<FilterDefinition> filters, List<ColumnDefinition> columns, List<RoleGrant> roleAccess) {}

    public record EnableTemplateRequest(
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,79}$") String reportCode) {}

    public record CreateReportRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,79}$") String reportCode,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description,
            @NotNull UUID routineId,
            @Min(0) @Max(99999) int sortOrder,
            @NotNull @Size(max = 100) List<@Valid FilterInput> filters,
            @NotNull @Size(min = 1, max = 100) List<@Valid ColumnInput> columns) {}

    public record UpdateReportRequest(
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description,
            @Min(0) @Max(99999) int sortOrder,
            @NotNull @Size(max = 100) List<@Valid FilterInput> filters,
            @NotNull @Size(min = 1, max = 100) List<@Valid ColumnInput> columns) {}

    public record FilterInput(
            @NotBlank @Pattern(regexp = "^[a-z][a-zA-Z0-9_]{0,63}$") String filterKey,
            @NotBlank @Size(max = 100) String label,
            @NotBlank @Pattern(regexp = "^(TEXT|NUMBER|BOOLEAN|DATE|SINGLE_SELECT|MULTI_SELECT|LOOKUP_SINGLE|LOOKUP_MULTI)$") String filterType,
            @NotBlank @Pattern(regexp = "^[a-z][a-zA-Z0-9_]{0,63}$") String parameterKey,
            boolean required,
            @Min(0) @Max(99999) int sortOrder,
            Map<String, Object> configuration,
            UUID lookupSourceId) {}

    public record ColumnInput(
            @NotBlank @Pattern(regexp = "^[a-z][a-zA-Z0-9_]{0,63}$") String columnKey,
            @NotBlank @Size(max = 100) String label,
            @NotBlank @Pattern(regexp = "^(TEXT|NUMBER|BOOLEAN|DATE|DATETIME|UUID)$") String dataType,
            @Min(0) @Max(99999) int sortOrder,
            boolean visible,
            boolean sortable,
            boolean exportable,
            Map<String, Object> formatConfiguration) {}

    public record ActivationRequest(@NotNull @Min(0) Long expectedVersion, @NotNull Boolean active) {}

    public record RoleAccessRequest(@NotNull @Min(0) Long expectedVersion,
            @NotNull @Size(max = 200) List<@Valid RoleGrantInput> roleAccess) {}

    public record RoleGrantInput(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,47}$") String roleKey,
            boolean canView, boolean canExecute, boolean canExport) {}
}
