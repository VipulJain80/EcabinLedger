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

/** Request shape for report queries. It deliberately has no tenant, actor, role, or routine fields. */
public final class ReportQueryModels {
    private ReportQueryModels() {}

    public record Sort(@NotBlank @Size(max = 64) @Pattern(regexp = "^[a-z][a-zA-Z0-9_]{0,63}$") String key,
            @NotBlank @Pattern(regexp = "(?i)ASC|DESC") String direction) {}

    public record QueryRequest(@NotNull @Size(max = 64) Map<String, Object> filters,
            @NotNull @Min(0) Integer page, @NotNull @Min(1) @Max(100) Integer size,
            @Size(max = 3) List<@Valid Sort> sort) {}

    /** Export uses the same validated filters and sort allowlist as queries, but always exports all pages. */
    public record ExportRequest(@NotNull @Size(max = 64) Map<String, Object> filters,
            @Size(max = 3) List<@Valid Sort> sort) {}

    public record QueryReport(UUID id, String code, String name) {}

    public record QueryColumn(String key, String label, String type, Map<String, Object> format,
            boolean sortable, boolean exportable) {}

    /** Each row id is supplied by the registered function from a stable source identity. */
    public record QueryResponse(QueryReport report, List<QueryColumn> columns, List<Map<String, Object>> rows,
            int page, int size, long totalElements, long totalPages) {}

    public record LookupValue(String value, String label) {}
}
