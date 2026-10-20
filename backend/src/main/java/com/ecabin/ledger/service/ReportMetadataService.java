package com.ecabin.ledger.service;

import com.ecabin.ledger.api.ReportMetadataModels.ReportColumn;
import com.ecabin.ledger.api.ReportMetadataModels.ReportDefinition;
import com.ecabin.ledger.api.ReportMetadataModels.ReportFilter;
import com.ecabin.ledger.api.ReportMetadataModels.ReportSummary;
import com.ecabin.ledger.api.ReportMetadataModels.ReportTemplate;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reads only tenant-authorized report definitions and active global template metadata. */
@Service
public class ReportMetadataService {
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final RowMapper<ReportSummary> SUMMARY_MAPPER = (rs, row) -> new ReportSummary(
        rs.getObject("id", UUID.class), rs.getString("report_code"), rs.getInt("definition_version"),
        rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"),
        rs.getBoolean("can_execute"), rs.getBoolean("can_export"));
    private static final RowMapper<ReportFilter> FILTER_MAPPER = (rs, row) -> new ReportFilter(
        rs.getString("filter_key"), rs.getString("label"), rs.getString("filter_type"),
        rs.getBoolean("is_required"), rs.getInt("sort_order"), jsonObject(rs.getString("configuration")),
        rs.getString("lookup_key"));
    private static final RowMapper<ReportColumn> COLUMN_MAPPER = (rs, row) -> new ReportColumn(
        rs.getString("column_key"), rs.getString("label"), rs.getString("data_type"),
        rs.getInt("sort_order"), rs.getBoolean("is_visible"), rs.getBoolean("is_sortable"),
        rs.getBoolean("is_exportable"), jsonObject(rs.getString("format_configuration")));
    private static final RowMapper<ReportTemplate> TEMPLATE_MAPPER = (rs, row) -> {
        UUID id = rs.getObject("id", UUID.class);
        int version = rs.getInt("template_version");
        return new ReportTemplate(id, rs.getString("template_code"), version, rs.getString("name"),
            rs.getString("description"), rs.getInt("sort_order"), List.of(), List.of());
    };

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    public ReportMetadataService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    public List<ReportSummary> listReports(AuthenticatedUser user) {
        require(user, Permission.REPORT_VIEW);
        return jdbc.query("""
            SELECT r.id,r.report_code,r.definition_version,r.name,r.description,r.sort_order,a.can_execute,a.can_export
            FROM reports_mst r
            JOIN report_role_access_mst a ON a.operator_id=r.operator_id AND a.report_id=r.id AND a.role_key=?
            WHERE r.operator_id=? AND r.is_active=true AND r.is_current=true
              AND a.can_view=true
            ORDER BY r.sort_order,r.name,r.report_code
            """, SUMMARY_MAPPER, user.role(), user.organizationId());
    }

    public ReportDefinition report(UUID reportId, AuthenticatedUser user) {
        require(user, Permission.REPORT_VIEW);
        ReportSummary summary = jdbc.query("""
            SELECT r.id,r.report_code,r.definition_version,r.name,r.description,r.sort_order,a.can_execute,a.can_export
            FROM reports_mst r
            JOIN report_role_access_mst a ON a.operator_id=r.operator_id AND a.report_id=r.id AND a.role_key=?
            WHERE r.id=? AND r.operator_id=? AND r.is_active=true AND r.is_current=true
              AND a.can_view=true
            """, rs -> rs.next() ? SUMMARY_MAPPER.mapRow(rs, 0) : null,
            user.role(), reportId, user.organizationId());
        if (summary == null) throw notFound();

        List<ReportFilter> filters = jdbc.query("""
            SELECT f.filter_key,f.label,f.filter_type,f.is_required,f.sort_order,f.configuration,
                   l.lookup_key
            FROM report_filters_mst f
            LEFT JOIN report_lookup_sources_mst l ON l.id=f.lookup_source_id AND l.is_active=true
            WHERE f.operator_id=? AND f.report_id=?
            ORDER BY f.sort_order,f.filter_key
            """, FILTER_MAPPER, user.organizationId(), reportId);
        List<ReportColumn> columns = jdbc.query("""
            SELECT column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable,format_configuration
            FROM report_columns_mst WHERE operator_id=? AND report_id=?
            ORDER BY sort_order,column_key
            """, COLUMN_MAPPER, user.organizationId(), reportId);
        return new ReportDefinition(summary.id(), summary.reportCode(), summary.definitionVersion(),
            summary.name(), summary.description(), summary.canExecute(), summary.canExport(), filters, columns);
    }

    public List<ReportTemplate> activeTemplates(AuthenticatedUser user) {
        require(user, Permission.ADMINISTER_ORGANIZATION);
        List<ReportTemplate> templates = jdbc.query("""
            SELECT t.id,t.template_code,t.template_version,t.name,t.description,t.sort_order
            FROM report_templates_mst t
            JOIN report_routines_mst r ON r.id=t.routine_id AND r.contract_version=2
              AND r.routine_kind='REPORT' AND r.scope_policy='DEFECT_VISIBLE_SCOPE_V1'
            WHERE t.is_active=true AND r.is_active=true
            ORDER BY t.sort_order,t.name,t.template_code,t.template_version DESC
            """, TEMPLATE_MAPPER);
        return templates.stream().map(template -> new ReportTemplate(
            template.id(), template.templateCode(), template.templateVersion(), template.name(),
            template.description(), template.sortOrder(), templateFilters(template.id(), template.templateVersion()),
            templateColumns(template.id(), template.templateVersion()))).toList();
    }

    private List<ReportFilter> templateFilters(UUID templateId, int templateVersion) {
        return jdbc.query("""
            SELECT f.filter_key,f.label,f.filter_type,f.is_required,f.sort_order,f.configuration,l.lookup_key
            FROM report_template_filters_mst f
            LEFT JOIN report_lookup_sources_mst l ON l.id=f.lookup_source_id AND l.is_active=true
            WHERE f.template_id=? AND f.template_version=?
            ORDER BY f.sort_order,f.filter_key
            """, FILTER_MAPPER, templateId, templateVersion);
    }

    private List<ReportColumn> templateColumns(UUID templateId, int templateVersion) {
        return jdbc.query("""
            SELECT column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable,format_configuration
            FROM report_template_columns_mst WHERE template_id=? AND template_version=?
            ORDER BY sort_order,column_key
            """, COLUMN_MAPPER, templateId, templateVersion);
    }

    private void require(AuthenticatedUser user, Permission permission) {
        if (user == null) throw new AccessDeniedException("An active organization membership is required.");
        permissions.require(user, permission);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Report was not found.");
    }

    private static Map<String, Object> jsonObject(String value) {
        try {
            return OBJECT_MAPPER.readValue(value, JSON_OBJECT);
        } catch (IOException ex) {
            throw new IllegalStateException("Report metadata contains invalid JSON configuration.", ex);
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
}
