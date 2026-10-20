package com.ecabin.ledger.api;

import com.ecabin.ledger.api.ReportMetadataModels.ReportDefinition;
import com.ecabin.ledger.api.ReportMetadataModels.ReportSummary;
import com.ecabin.ledger.api.ReportMetadataModels.ReportTemplate;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.service.ReportMetadataService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReportMetadataController {
    private final CurrentUserResolver users;
    private final ReportMetadataService reports;

    public ReportMetadataController(CurrentUserResolver users, ReportMetadataService reports) {
        this.users = users;
        this.reports = reports;
    }

    @GetMapping("/reports")
    public List<ReportSummary> list(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        return reports.listReports(user);
    }

    @GetMapping("/reports/{reportId}")
    public ReportDefinition get(JwtAuthenticationToken auth, @PathVariable UUID reportId) {
        AuthenticatedUser user = users.require(auth);
        return reports.report(reportId, user);
    }

    @GetMapping("/report-templates")
    public List<ReportTemplate> templates(JwtAuthenticationToken auth) {
        AuthenticatedUser user = users.require(auth);
        return reports.activeTemplates(user);
    }
}
