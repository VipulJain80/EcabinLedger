package com.ecabin.ledger.api;

import com.ecabin.ledger.api.ReportAdministrationModels.*;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.service.ReportAdministrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organization-administrator API for tenant-owned report configuration. */
@Validated
@RestController
@RequestMapping("/api/v1/report-admin")
public class ReportAdministrationController {
    private final CurrentUserResolver users;
    private final ReportAdministrationService administration;

    public ReportAdministrationController(CurrentUserResolver users, ReportAdministrationService administration) {
        this.users = users;
        this.administration = administration;
    }

    @GetMapping("/routines")
    public List<RoutineOption> routines(JwtAuthenticationToken auth) {
        return administration.activeRoutines(users.require(auth));
    }

    @GetMapping("/lookups")
    public List<LookupOption> lookups(JwtAuthenticationToken auth) {
        return administration.activeLookups(users.require(auth));
    }

    @GetMapping("/reports")
    public ReportPage list(JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ALL") String status) {
        return administration.list(users.require(auth), page, size, search, status);
    }

    @GetMapping("/reports/{reportId}")
    public ReportAdminDetail get(JwtAuthenticationToken auth, @PathVariable UUID reportId) {
        return administration.get(reportId, users.require(auth));
    }

    @PostMapping("/templates/{templateId}/versions/{templateVersion}/enable")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportAdminDetail enableTemplate(JwtAuthenticationToken auth, @PathVariable UUID templateId,
            @PathVariable @Min(1) int templateVersion, @Valid @RequestBody(required = false) EnableTemplateRequest request) {
        return administration.enableTemplate(templateId, templateVersion, request, users.require(auth));
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportAdminDetail create(JwtAuthenticationToken auth, @Valid @RequestBody CreateReportRequest request) {
        return administration.create(request, users.require(auth));
    }

    @PostMapping("/reports/{reportId}")
    public ReportAdminDetail update(JwtAuthenticationToken auth, @PathVariable UUID reportId,
            @Valid @RequestBody UpdateReportRequest request) {
        return administration.update(reportId, request, users.require(auth));
    }

    @PostMapping("/reports/{reportId}/activation")
    public ReportAdminDetail activation(JwtAuthenticationToken auth, @PathVariable UUID reportId,
            @Valid @RequestBody ActivationRequest request) {
        return administration.setActive(reportId, request, users.require(auth));
    }

    @PostMapping("/reports/{reportId}/role-access")
    public ReportAdminDetail roleAccess(JwtAuthenticationToken auth, @PathVariable UUID reportId,
            @Valid @RequestBody RoleAccessRequest request) {
        return administration.replaceRoleAccess(reportId, request, users.require(auth));
    }
}
