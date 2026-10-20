package com.ecabin.ledger.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public, non-executable report metadata. Internal routine identifiers and parameter names are omitted. */
public final class ReportMetadataModels {
    private ReportMetadataModels() {}

    public record ReportSummary(UUID id, String reportCode, int definitionVersion, String name,
            String description, int sortOrder, boolean canExecute, boolean canExport) {}

    public record ReportFilter(String filterKey, String label, String filterType, boolean required,
            int sortOrder, Map<String, Object> configuration, String lookupKey) {}

    public record ReportColumn(String columnKey, String label, String dataType, int sortOrder,
            boolean visible, boolean sortable, boolean exportable, Map<String, Object> formatConfiguration) {}

    public record ReportDefinition(UUID id, String reportCode, int definitionVersion, String name,
            String description, boolean canExecute, boolean canExport,
            List<ReportFilter> filters, List<ReportColumn> columns) {}

    public record ReportTemplate(UUID id, String templateCode, int templateVersion, String name,
            String description, int sortOrder, List<ReportFilter> filters, List<ReportColumn> columns) {}
}
