package com.ecabin.ledger.service;

import com.ecabin.ledger.api.ReportApiException;
import com.ecabin.ledger.api.ReportQueryModels.ExportRequest;
import com.ecabin.ledger.api.ReportQueryModels.QueryRequest;
import com.ecabin.ledger.api.ReportQueryModels.QueryResponse;
import com.ecabin.ledger.api.ReportQueryModels.QueryColumn;
import com.ecabin.ledger.api.ReportQueryModels.Sort;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import java.io.IOException;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, fully authorized XLSX streaming over the same validated report query contract. */
@Service
public class ReportExportService {
    public record ExportPlan(UUID reportId, AuthenticatedUser user, UUID requestId,
            String filename, Map<String, Object> filters, List<Sort> sort,
            int pageSize, long totalRows, List<QueryColumn> columns, long startedNanos) {}
    public record ExportFile(Path path, String filename) {}

    private static final int ABSOLUTE_MAX_EXPORT_ROWS = 100_000;
    private static final int MAX_QUERY_PAGE = 100;
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final ReportQueryService queries;
    private final ReportQueryAuditService audit;
    private final int maxRows;
    private final long maxDurationMs;
    private final long maxBytes;

    public ReportExportService(JdbcTemplate jdbc, PermissionService permissions, ReportQueryService queries,
            ReportQueryAuditService audit,
            @Value("${app.reports.export.max-rows:10000}") int maxRows,
            @Value("${app.reports.export.max-duration-ms:120000}") long maxDurationMs,
            @Value("${app.reports.export.max-bytes:52428800}") long maxBytes) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.queries = queries;
        this.audit = audit;
        this.maxRows = Math.max(1, Math.min(ABSOLUTE_MAX_EXPORT_ROWS, maxRows));
        this.maxDurationMs = Math.max(1000, Math.min(900_000, maxDurationMs));
        this.maxBytes = Math.max(1_048_576, Math.min(536_870_912L, maxBytes));
    }

    public ExportPlan prepare(UUID reportId, ExportRequest request, AuthenticatedUser user, UUID requestId) {
        long started = System.nanoTime();
        try {
            requireActiveMembership(user);
            permissions.require(user, Permission.REPORT_VIEW);
            permissions.require(user, Permission.REPORT_EXECUTE);
            permissions.require(user, Permission.REPORT_EXPORT);
            ExportMetadata metadata = jdbc.query("""
                SELECT r.name,rt.max_page_size
                FROM reports_mst r
                JOIN report_role_access_mst a ON a.operator_id=r.operator_id AND a.report_id=r.id AND a.role_key=?
                JOIN report_routines_mst rt ON rt.id=r.routine_id
                WHERE r.operator_id=? AND r.id=? AND r.is_active=true AND r.is_current=true
                  AND a.can_view=true AND a.can_execute=true AND a.can_export=true
                """, rs -> rs.next() ? new ExportMetadata(rs.getString(1), rs.getInt(2)) : null,
                user.role(), user.organizationId(), reportId);
            if (metadata == null) throw notFound();
            if (request == null || request.filters() == null)
                throw new ReportApiException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_QUERY", "Report filters are required.");
            int pageSize = Math.max(1, Math.min(25, Math.min(MAX_QUERY_PAGE, metadata.maxPageSize())));
            QueryResponse first = queries.query(reportId,
                new QueryRequest(request.filters(), 0, pageSize, request.sort()), user, requestId);
            if (first.totalElements() > maxRows) {
                audit.recordExport(user, reportId, requestId, "EXPORT_LIMIT_EXCEEDED", elapsed(started), 0);
                throw new ReportApiException(HttpStatus.PAYLOAD_TOO_LARGE, "REPORT_EXPORT_LIMIT_EXCEEDED",
                    "This report exceeds the configured Excel export limit. Narrow the filters and try again.");
            }
            List<QueryColumn> exportColumns = first.columns().stream().filter(QueryColumn::exportable).toList();
            if (exportColumns.isEmpty()) throw new ReportApiException(HttpStatus.CONFLICT,
                "INVALID_REPORT_CONFIGURATION", "This report has no exportable columns configured.");
            return new ExportPlan(reportId, user, requestId, safeFilename(metadata.name()), request.filters(),
                request.sort() == null ? List.of() : List.copyOf(request.sort()), pageSize,
                first.totalElements(), exportColumns, started);
        } catch (ReportApiException ex) {
            if (!"REPORT_EXPORT_LIMIT_EXCEEDED".equals(ex.code()))
                recordExportWhenReportExists(user, reportId, requestId, outcome(ex), started);
            throw ex;
        } catch (AccessDeniedException ex) {
            recordDeniedWhenReportExists(user, reportId, requestId, started);
            throw ex;
        } catch (org.springframework.dao.DataAccessException ex) {
            recordExportWhenReportExists(user, reportId, requestId, "DATABASE_ERROR", started);
            throw new ReportApiException(HttpStatus.BAD_GATEWAY, "REPORT_EXPORT_FAILED",
                "The report export could not be prepared. Try again later.");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportFile generate(ExportPlan plan) throws IOException {
        long rowsWritten = 0;
        Path path;
        try {
            path = Files.createTempFile("ecabin-report-", ".xlsx");
        } catch (IOException ex) {
            recordExportSafely(plan, "DATABASE_ERROR");
            throw new ReportApiException(HttpStatus.BAD_GATEWAY, "REPORT_EXPORT_FAILED",
                "The report export could not be prepared. Try again later.");
        }
        path.toFile().deleteOnExit();
        Set<String> rowIds = new HashSet<>();
        try {
            try (OutputStream fileOutput = Files.newOutputStream(path);
                 OutputStream limited = new BoundedOutputStream(fileOutput, maxBytes)) {
                XlsxStreamWriter writer = new XlsxStreamWriter(limited,
                    plan.columns().stream().map(QueryColumn::label).toList(), plan.totalRows());
                QueryResponse page = queries.query(plan.reportId(),
                    new QueryRequest(plan.filters(), 0, plan.pageSize(), plan.sort()), plan.user(), plan.requestId());
                for (int pageIndex = 0; ; pageIndex++) {
                    ensureWithinDuration(plan.startedNanos());
                    if (page.totalElements() != plan.totalRows() || page.page() != pageIndex)
                        throw configuration("Report rows changed during export. Run the export again.");
                    for (Map<String, Object> row : page.rows()) {
                        if (rowsWritten >= maxRows) throw tooLarge();
                        Object rowId = row.get("id");
                        if (!(rowId instanceof String id) || id.isBlank() || !rowIds.add(id))
                            throw configuration("The report returned an invalid or duplicate row identity during export.");
                        writer.writeRow(plan.columns().stream().map(column -> cell(row.get(column.key()))).toList());
                        rowsWritten++;
                    }
                    if (rowsWritten >= plan.totalRows()) break;
                    page = queries.query(plan.reportId(), new QueryRequest(plan.filters(), pageIndex + 1,
                        plan.pageSize(), plan.sort()), plan.user(), plan.requestId());
                }
                if (rowsWritten != plan.totalRows()) throw configuration("The report export returned an incomplete result.");
                ensureWithinDuration(plan.startedNanos());
                writer.finish();
                limited.flush();
            }
            audit.recordExport(plan.user(), plan.reportId(), plan.requestId(), "SUCCESS", elapsed(plan.startedNanos()), rowsWritten);
            return new ExportFile(path, plan.filename());
        } catch (RuntimeException | IOException ex) {
            String outcome = ex instanceof ReportApiException reportError
                ? ("REPORT_QUERY_TIMEOUT".equals(reportError.code()) || "REPORT_EXPORT_TIMEOUT".equals(reportError.code())
                    ? "TIMEOUT" : "REPORT_EXPORT_LIMIT_EXCEEDED".equals(reportError.code()) ? "EXPORT_LIMIT_EXCEEDED"
                    : "INVALID_REPORT_CONFIGURATION".equals(reportError.code()) ? "CONFIGURATION_BLOCKED"
                    : reportError.status() == HttpStatus.BAD_REQUEST ? "INVALID_REQUEST" : "DATABASE_ERROR")
                : ex instanceof AccessDeniedException ? "ACCESS_DENIED"
                : ex instanceof ExportSizeLimitException ? "EXPORT_LIMIT_EXCEEDED" : "DATABASE_ERROR";
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            audit.recordExport(plan.user(), plan.reportId(), plan.requestId(), outcome, elapsed(plan.startedNanos()), 0);
            if (ex instanceof ExportSizeLimitException) throw tooLarge();
            throw ex;
        }
    }

    public void stream(ExportFile file, OutputStream output) throws IOException {
        try (FileInputStream input = new FileInputStream(file.path().toFile())) {
            input.transferTo(output);
            output.flush();
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    private record ExportMetadata(String name, int maxPageSize) {}

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long maximum;
        private long written;
        private BoundedOutputStream(OutputStream output, long maximum) { super(output); this.maximum = maximum; }
        @Override public void write(int value) throws IOException { ensure(1); out.write(value); written++; }
        @Override public void write(byte[] values, int offset, int length) throws IOException { ensure(length); out.write(values, offset, length); written += length; }
        private void ensure(int length) throws IOException {
            if (length < 0 || written + length > maximum) throw new ExportSizeLimitException();
        }
    }

    private static final class ExportSizeLimitException extends IOException {
        private ExportSizeLimitException() { super("Report export exceeded its configured size limit."); }
    }

    private void requireActiveMembership(AuthenticatedUser user) {
        if (user == null) throw new AccessDeniedException("An active organization membership is required.");
        Boolean active = jdbc.query("""
            SELECT EXISTS(SELECT 1 FROM operator_memberships_mst m
              JOIN operators_mst o ON o.id=m.operator_id AND o.isactive=1
              JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1
              WHERE m.operator_id=? AND m.external_user_id=? AND m.role=? AND m.isactive=1)
            """, rs -> { rs.next(); return rs.getBoolean(1); },
            user.organizationId(), user.externalUserId(), user.role());
        if (!Boolean.TRUE.equals(active)) throw new AccessDeniedException("An active organization membership is required.");
    }

    private void recordDeniedWhenReportExists(AuthenticatedUser user, UUID reportId, UUID requestId, long started) {
        recordExportWhenReportExists(user, reportId, requestId, "ACCESS_DENIED", started);
    }

    private void recordExportWhenReportExists(AuthenticatedUser user, UUID reportId, UUID requestId,
            String outcome, long started) {
        if (user == null || reportId == null) return;
        try {
            Boolean reportExists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reports_mst WHERE operator_id=? AND id=?)", Boolean.class,
                user.organizationId(), reportId);
            if (Boolean.TRUE.equals(reportExists))
                audit.recordExport(user, reportId, requestId, outcome, elapsed(started), 0);
        } catch (org.springframework.dao.DataAccessException ignored) {
            // A failed denial audit must not disclose report existence or replace the authorization response.
        }
    }

    private void recordExportSafely(ExportPlan plan, String outcome) {
        try {
            audit.recordExport(plan.user(), plan.reportId(), plan.requestId(), outcome,
                elapsed(plan.startedNanos()), 0);
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Do not replace the sanitized export failure with a database detail from audit storage.
        }
    }

    private static String cell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.length() > 32_767) throw configuration("A report value is too large for an Excel cell.");
        return text;
    }
    private static String safeFilename(String name) {
        String normalized = Normalizer.normalize(name == null ? "report" : name, Normalizer.Form.NFKD)
            .replaceAll("[^\\p{ASCII}]", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        return (normalized.isBlank() ? "report" : normalized) + ".xlsx";
    }
    private void ensureWithinDuration(long started) {
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > maxDurationMs)
            throw new ReportApiException(HttpStatus.GATEWAY_TIMEOUT, "REPORT_EXPORT_TIMEOUT",
                "The report export took too long. Narrow the filters and try again.");
    }
    private static String outcome(ReportApiException ex) {
        return ex.status() == HttpStatus.GATEWAY_TIMEOUT ? "TIMEOUT" :
            ex.status() == HttpStatus.BAD_REQUEST ? "INVALID_REQUEST" :
            "not_found".equals(ex.code()) ? "ACCESS_DENIED" : "CONFIGURATION_BLOCKED";
    }
    private static long elapsed(long started) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
    private static ReportApiException notFound() { return new ReportApiException(HttpStatus.NOT_FOUND, "not_found", "Report was not found."); }
    private static ReportApiException configuration(String message) { return new ReportApiException(HttpStatus.CONFLICT, "INVALID_REPORT_CONFIGURATION", message); }
    private ReportApiException tooLarge() { return new ReportApiException(HttpStatus.PAYLOAD_TOO_LARGE, "REPORT_EXPORT_LIMIT_EXCEEDED", "This report exceeds the configured Excel export limit."); }
}
