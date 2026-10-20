package com.ecabin.ledger.api;

import com.ecabin.ledger.api.ReportQueryModels.QueryRequest;
import com.ecabin.ledger.api.ReportQueryModels.QueryResponse;
import com.ecabin.ledger.api.ReportQueryModels.LookupValue;
import com.ecabin.ledger.api.ReportQueryModels.ExportRequest;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.service.ReportExportService;
import com.ecabin.ledger.service.ReportQueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reports")
public class ReportQueryController {
    private final CurrentUserResolver users;
    private final ReportQueryService queries;
    private final ReportExportService exports;

    public ReportQueryController(CurrentUserResolver users, ReportQueryService queries, ReportExportService exports) {
        this.users = users;
        this.queries = queries;
        this.exports = exports;
    }

    @PostMapping("/{reportId}/query")
    @ResponseStatus(HttpStatus.OK)
    public QueryResponse query(JwtAuthenticationToken auth, @PathVariable UUID reportId,
            @Valid @RequestBody QueryRequest request) {
        AuthenticatedUser user = users.require(auth);
        return queries.query(reportId, request, user, requestId());
    }

    @PostMapping(value = "/{reportId}/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<StreamingResponseBody> export(JwtAuthenticationToken auth, @PathVariable UUID reportId,
            @Valid @RequestBody ExportRequest request) {
        AuthenticatedUser user = users.require(auth);
        ReportExportService.ExportPlan plan = exports.prepare(reportId, request, user, requestId());
        ReportExportService.ExportFile file;
        try {
            file = exports.generate(plan);
        } catch (java.io.IOException ex) {
            throw new com.ecabin.ledger.api.ReportApiException(HttpStatus.BAD_GATEWAY, "REPORT_EXPORT_FAILED",
                "The report export could not be prepared. Try again later.");
        }
        StreamingResponseBody body = output -> exports.stream(file, output);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment().filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .body(body);
    }

    @GetMapping("/lookups/{lookupKey}")
    public List<LookupValue> lookup(JwtAuthenticationToken auth, @PathVariable String lookupKey) {
        AuthenticatedUser user = users.require(auth);
        return queries.lookupOptions(lookupKey, user, requestId());
    }

    private static UUID requestId() {
        try { return UUID.fromString(MDC.get("requestId")); }
        catch (RuntimeException ex) { return null; }
    }
}
