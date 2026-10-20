package com.ecabin.ledger.service;

import com.ecabin.ledger.api.ReportAdministrationModels.*;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Organization-administrator configuration operations. This service never invokes a database routine. */
@Service
public class ReportAdministrationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final Pattern SAFE_KEY = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");
    private static final Set<String> FILTER_TYPES = Set.of("TEXT", "NUMBER", "BOOLEAN", "DATE", "SINGLE_SELECT", "MULTI_SELECT", "LOOKUP_SINGLE", "LOOKUP_MULTI");
    private static final Set<String> COLUMN_TYPES = Set.of("TEXT", "NUMBER", "BOOLEAN", "DATE", "DATETIME", "UUID");
    private static final RowMapper<ReportAdminSummary> SUMMARY = (rs, n) -> new ReportAdminSummary(
        rs.getObject("id", UUID.class), rs.getString("report_code"), rs.getInt("definition_version"),
        rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"), rs.getBoolean("is_active"),
        rs.getBoolean("is_current"), rs.getLong("version"), rs.getObject("source_template_id", UUID.class),
        (Integer) rs.getObject("source_template_version"));
    private static final RowMapper<FilterDefinition> FILTER = (rs, n) -> new FilterDefinition(
        rs.getObject("id", UUID.class), rs.getString("filter_key"), rs.getString("label"), rs.getString("filter_type"),
        rs.getString("parameter_key"), rs.getBoolean("is_required"), rs.getInt("sort_order"), readJson(rs.getString("configuration")),
        rs.getObject("lookup_source_id", UUID.class), rs.getString("lookup_key"));
    private static final RowMapper<ColumnDefinition> COLUMN = (rs, n) -> new ColumnDefinition(
        rs.getObject("id", UUID.class), rs.getString("column_key"), rs.getString("label"), rs.getString("data_type"),
        rs.getInt("sort_order"), rs.getBoolean("is_visible"), rs.getBoolean("is_sortable"), rs.getBoolean("is_exportable"),
        readJson(rs.getString("format_configuration")));

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public ReportAdministrationService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<RoutineOption> activeRoutines(AuthenticatedUser user) {
        requireAdmin(user);
        Map<UUID, RoutineBuilder> routines = jdbc.query("""
            SELECT r.id,r.routine_key,r.contract_version,p.parameter_key,p.parameter_type,p.is_required,p.is_filterable,p.sort_order
            FROM report_routines_mst r LEFT JOIN report_routine_parameters_mst p
              ON p.routine_id=r.id AND p.contract_version=r.contract_version
            WHERE r.is_active=true AND r.contract_version=2 AND r.routine_kind='REPORT'
              AND r.scope_policy='DEFECT_VISIBLE_SCOPE_V1'
            ORDER BY r.routine_key,r.contract_version DESC,p.sort_order,p.parameter_key
            """, (org.springframework.jdbc.core.ResultSetExtractor<Map<UUID, RoutineBuilder>>) rs -> {
                Map<UUID, RoutineBuilder> result = new LinkedHashMap<>();
                while (rs.next()) {
                    UUID id = rs.getObject("id", UUID.class);
                    RoutineBuilder routine = result.get(id);
                    if (routine == null) {
                        routine = new RoutineBuilder(id, rs.getString("routine_key"), rs.getInt("contract_version"));
                        result.put(id, routine);
                    }
                    if (rs.getString("parameter_key") != null) routine.parameters.add(new RoutineParameterOption(
                        rs.getString("parameter_key"), rs.getString("parameter_type"), rs.getBoolean("is_required"),
                        rs.getBoolean("is_filterable"), rs.getInt("sort_order")));
                }
                return result;
            });
        return routines.values().stream().map(r -> new RoutineOption(r.id, r.key, r.version, List.copyOf(r.parameters))).toList();
    }

    @Transactional(readOnly = true)
    public List<LookupOption> activeLookups(AuthenticatedUser user) {
        requireAdmin(user);
        return jdbc.query("SELECT l.id,l.lookup_key,l.value_type FROM report_lookup_sources_mst l JOIN report_routines_mst r ON r.id=l.routine_id AND r.is_active=true AND r.contract_version=2 AND r.routine_kind='LOOKUP' AND r.scope_policy='DEFECT_VISIBLE_SCOPE_V1' WHERE l.is_active=true ORDER BY l.lookup_key",
            (rs, n) -> new LookupOption(rs.getObject("id", UUID.class), rs.getString("lookup_key"), rs.getString("value_type")));
    }

    @Transactional(readOnly = true)
    public ReportPage list(AuthenticatedUser user, int page, int size, String search, String status) {
        requireAdmin(user);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Choose a page and a page size from 1 to 100.");
        String normalizedStatus = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ALL", "ACTIVE", "INACTIVE").contains(normalizedStatus)) throw new IllegalArgumentException("Status must be ACTIVE, INACTIVE, or ALL.");
        String term = search == null ? "" : search.trim();
        if (term.length() > 160) throw new IllegalArgumentException("Search text must be 160 characters or fewer.");
        String where = " WHERE operator_id=? AND is_current=true" +
            (normalizedStatus.equals("ALL") ? "" : normalizedStatus.equals("ACTIVE") ? " AND is_active=true" : " AND is_active=false") +
            (term.isBlank() ? "" : " AND (report_code ILIKE ? OR name ILIKE ? OR COALESCE(description,'') ILIKE ?)");
        List<Object> args = new ArrayList<>(); args.add(user.organizationId());
        if (!term.isBlank()) { String like = "%" + escapeLike(term) + "%"; args.add(like); args.add(like); args.add(like); }
        Long count = jdbc.queryForObject("SELECT count(*) FROM reports_mst" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) page * size);
        List<ReportAdminSummary> rows = jdbc.query(summarySql() + where + " ORDER BY sort_order,name,report_code LIMIT ? OFFSET ?", SUMMARY, pageArgs.toArray());
        long total = count == null ? 0 : count;
        return new ReportPage(rows, page, size, total, Math.max(1, (int) Math.ceil((double) total / size)));
    }

    @Transactional(readOnly = true)
    public ReportAdminDetail get(UUID id, AuthenticatedUser user) {
        requireAdmin(user);
        ReportAdminSummary report = report(id, user.organizationId(), false);
        return detail(report, user.organizationId());
    }

    @Transactional
    public ReportAdminDetail enableTemplate(UUID templateId, int templateVersion, EnableTemplateRequest request, AuthenticatedUser user) {
        requireAdmin(user);
        if (templateVersion < 1) throw new IllegalArgumentException("Template version must be positive.");
        TemplateSource source = jdbc.query("""
            SELECT t.id,t.template_code,t.template_version,t.name,t.description,t.routine_id,t.sort_order,r.contract_version
            FROM report_templates_mst t JOIN report_routines_mst r ON r.id=t.routine_id AND r.is_active=true
              AND r.contract_version=2 AND r.routine_kind='REPORT' AND r.scope_policy='DEFECT_VISIBLE_SCOPE_V1'
            WHERE t.id=? AND t.template_version=? AND t.is_active=true
            """, rs -> rs.next() ? new TemplateSource(rs.getObject("id", UUID.class), rs.getString("template_code"),
                rs.getInt("template_version"), rs.getString("name"), rs.getString("description"),
                rs.getObject("routine_id", UUID.class), rs.getInt("sort_order"), rs.getInt("contract_version")) : null, templateId, templateVersion);
        if (source == null) throw notFound("Active approved report template version was not found.");
        String code = request == null || request.reportCode() == null || request.reportCode().isBlank()
            ? source.code() : request.reportCode().trim();
        validateReportCode(code);
        if (exists("SELECT 1 FROM reports_mst WHERE operator_id=? AND source_template_id=? AND source_template_version=?",
            user.organizationId(), templateId, templateVersion)) throw conflict("This tenant has already enabled that template version.");
        if (exists("SELECT 1 FROM reports_mst WHERE operator_id=? AND report_code=?", user.organizationId(), code))
            throw conflict("A report with this code already exists. Choose a different tenant report code to avoid replacing its configuration.");
        List<FilterDefinition> templateFilters = jdbc.query("""
            SELECT f.id,f.filter_key,f.label,f.filter_type,f.parameter_key,f.is_required,f.sort_order,f.configuration,
                   f.lookup_source_id,l.lookup_key
            FROM report_template_filters_mst f LEFT JOIN report_lookup_sources_mst l ON l.id=f.lookup_source_id
            WHERE f.template_id=? AND f.template_version=? ORDER BY f.sort_order,f.filter_key
            """, FILTER, templateId, templateVersion);
        List<ColumnDefinition> templateColumns = jdbc.query("""
            SELECT id,column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable,format_configuration
            FROM report_template_columns_mst WHERE template_id=? AND template_version=? ORDER BY sort_order,column_key
            """, COLUMN, templateId, templateVersion);
        validateTemplateLookups(templateFilters);
        validateTemplateDateRanges(templateFilters);
        validateTemplateParameterMappings(templateFilters, source.routineId(), source.contractVersion());
        if (templateColumns.isEmpty()) throw conflict("The approved template has no columns and cannot be enabled.");

        UUID reportId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO reports_mst(id,operator_id,report_code,definition_version,name,description,routine_id,source_template_id,
                source_template_version,is_active,is_current,sort_order,version,created_by,updated_by)
            VALUES(?,?,?,1,?,?,?,?,?,false,true,?,0,?,?)
            """, reportId, user.organizationId(), code, source.name(), source.description(), source.routineId(),
            source.id(), source.version(), source.sortOrder(), user.externalUserId(), user.externalUserId());
        jdbc.update("""
            INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,is_required,sort_order,
                configuration,lookup_source_id,created_by,updated_by)
            SELECT ?,?,filter_key,label,filter_type,parameter_key,is_required,sort_order,configuration,lookup_source_id,?,?
            FROM report_template_filters_mst WHERE template_id=? AND template_version=?
            """, user.organizationId(), reportId, user.externalUserId(), user.externalUserId(), templateId, templateVersion);
        jdbc.update("""
            INSERT INTO report_columns_mst(operator_id,report_id,column_key,label,data_type,sort_order,is_visible,is_sortable,
                is_exportable,format_configuration,created_by,updated_by)
            SELECT ?,?,column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable,format_configuration,?,?
            FROM report_template_columns_mst WHERE template_id=? AND template_version=?
            """, user.organizationId(), reportId, user.externalUserId(), user.externalUserId(), templateId, templateVersion);
        audit(user, reportId, "REPORT_TEMPLATE_ENABLED", null, auditSnapshot(code, source.name(), source.description(), source.sortOrder(),
            false, source.id(), source.version(), templateFilters, templateColumns, List.of()));
        return detail(report(reportId, user.organizationId(), false), user.organizationId());
    }

    @Transactional
    public ReportAdminDetail create(CreateReportRequest request, AuthenticatedUser user) {
        requireAdmin(user);
        validateReportCode(request.reportCode());
        String code = request.reportCode().trim();
        if (exists("SELECT 1 FROM reports_mst WHERE operator_id=? AND report_code=?", user.organizationId(), code))
            throw conflict("A report with this code already exists.");
        RoutineRef routine = ensureActiveRoutine(request.routineId());
        validateFilters(request.filters(), routine.id(), routine.contractVersion()); validateColumns(request.columns());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO reports_mst(id,operator_id,report_code,definition_version,name,description,routine_id,is_active,is_current,
                sort_order,version,created_by,updated_by)
            VALUES(?,?,?,1,?,?,?,false,true,?,0,?,?)
            """, id, user.organizationId(), code, request.name().trim(), blankToNull(request.description()), request.routineId(),
            request.sortOrder(), user.externalUserId(), user.externalUserId());
        insertFilters(id, user, request.filters()); insertColumns(id, user, request.columns());
        audit(user, id, "REPORT_CREATED", null, auditCreateSnapshot(code, request.name().trim(), blankToNull(request.description()),
            request.sortOrder(), request.filters(), request.columns()));
        return detail(report(id, user.organizationId(), false), user.organizationId());
    }

    @Transactional
    public ReportAdminDetail update(UUID id, UpdateReportRequest request, AuthenticatedUser user) {
        requireAdmin(user);
        ReportAdminSummary before = lockCurrentReport(id, user);
        checkVersion(before, request.expectedVersion());
        RoutineRef routine = routineForReport(id, user.organizationId());
        validateFilters(request.filters(), routine.id(), routine.contractVersion()); validateColumns(request.columns());
        List<FilterDefinition> oldFilters = getChildFilters(id, user.organizationId());
        List<ColumnDefinition> oldColumns = getChildColumns(id, user.organizationId());
        updateVersionedMetadata(id, user, before.version(), request.name().trim(), blankToNull(request.description()), request.sortOrder());
        jdbc.update("DELETE FROM report_filters_mst WHERE operator_id=? AND report_id=?", user.organizationId(), id);
        jdbc.update("DELETE FROM report_columns_mst WHERE operator_id=? AND report_id=?", user.organizationId(), id);
        insertFilters(id, user, request.filters()); insertColumns(id, user, request.columns());
        ReportAdminSummary after = report(id, user.organizationId(), false);
        audit(user, id, "REPORT_UPDATED", auditBeforeSnapshot(before, oldFilters, oldColumns),
            auditUpdateSnapshot(after, request.filters(), request.columns()));
        return detail(after, user.organizationId());
    }

    @Transactional
    public ReportAdminDetail setActive(UUID id, ActivationRequest request, AuthenticatedUser user) {
        requireAdmin(user);
        ReportAdminSummary before = lockCurrentReport(id, user);
        checkVersion(before, request.expectedVersion());
        if (request.active() && !exists("SELECT 1 FROM report_routines_mst r JOIN reports_mst p ON p.routine_id=r.id WHERE p.operator_id=? AND p.id=? AND r.is_active=true", user.organizationId(), id))
            throw conflict("The registered routine is inactive; this report cannot be activated.");
        if (request.active() && !exists("SELECT 1 FROM report_columns_mst WHERE operator_id=? AND report_id=?", user.organizationId(), id))
            throw conflict("Add at least one result column before activating this report.");
        updateActive(id, user, before.version(), request.active());
        ReportAdminSummary after = report(id, user.organizationId(), false);
        audit(user, id, request.active() ? "REPORT_ACTIVATED" : "REPORT_DEACTIVATED", auditSnapshot(before), auditSnapshot(after));
        return detail(after, user.organizationId());
    }

    @Transactional
    public ReportAdminDetail replaceRoleAccess(UUID id, RoleAccessRequest request, AuthenticatedUser user) {
        requireAdmin(user);
        ReportAdminSummary before = lockCurrentReport(id, user);
        checkVersion(before, request.expectedVersion());
        validateRoleAccess(request.roleAccess(), user.organizationId());
        List<RoleGrant> oldAccess = getRoleAccess(id, user.organizationId());
        updateVersionOnly(id, user, before.version());
        jdbc.update("DELETE FROM report_role_access_mst WHERE operator_id=? AND report_id=?", user.organizationId(), id);
        for (RoleGrantInput grant : request.roleAccess()) jdbc.update("""
            INSERT INTO report_role_access_mst(operator_id,report_id,role_key,can_view,can_execute,can_export,created_by,updated_by)
            VALUES(?,?,?,?,?,?,?,?)
            """, user.organizationId(), id, grant.roleKey(), grant.canView(), grant.canExecute(), grant.canExport(),
            user.externalUserId(), user.externalUserId());
        ReportAdminSummary after = report(id, user.organizationId(), false);
        audit(user, id, "REPORT_ACCESS_UPDATED", roleAudit(oldAccess), roleAuditInputs(request.roleAccess()));
        return detail(after, user.organizationId());
    }

    private void validateFilters(List<FilterInput> filters, UUID routineId, int contractVersion) {
        if (filters == null) throw new IllegalArgumentException("Filter definitions are required; submit an empty list when no filters are needed.");
        Set<String> keys = new HashSet<>(), parameters = new HashSet<>();
        List<FilterParameter> mappings = new ArrayList<>();
        for (FilterInput filter : filters) {
            if (filter == null) throw new IllegalArgumentException("Filter definitions cannot be null.");
            if (!SAFE_KEY.matcher(filter.filterKey()).matches() || !SAFE_KEY.matcher(filter.parameterKey()).matches()) throw new IllegalArgumentException("Filter keys and parameter mappings must use a stable lower camel-case identifier.");
            if (blank(filter.label()) || filter.label().trim().length() > 100) throw new IllegalArgumentException("Filter labels must contain 1 to 100 characters.");
            if (!FILTER_TYPES.contains(filter.filterType())) throw new IllegalArgumentException("Choose a supported report filter type.");
            if (filter.sortOrder() < 0 || filter.sortOrder() > 99999) throw new IllegalArgumentException("Filter order must be between 0 and 99999.");
            if (!keys.add(filter.filterKey()) || !parameters.add(filter.parameterKey())) throw new IllegalArgumentException("Filter keys and parameter mappings must be unique within the report.");
            validateFilterConfiguration(filter);
            boolean lookup = filter.filterType().startsWith("LOOKUP_");
            if (lookup != (filter.lookupSourceId() != null)) throw new IllegalArgumentException("Registered lookup filters require a lookup source, and other filter types cannot specify one.");
            if (lookup && !activeLookupExists(filter.lookupSourceId()))
                throw new IllegalArgumentException("Choose an active registered lookup source.");
            mappings.add(new FilterParameter(filter.parameterKey(), filter.filterType(), filter.required(), filter.lookupSourceId()));
        }
        validateDateRangeBounds(filters.stream().map(filter -> Map.entry(filter.filterType(),
            filter.configuration() == null ? Map.<String,Object>of() : filter.configuration())).toList());
        validateParameterMappings(mappings, routineId, contractVersion);
    }

    private void validateTemplateDateRanges(List<FilterDefinition> filters) {
        validateDateRangeBounds(filters.stream().map(filter -> Map.entry(filter.filterType(), filter.configuration())).toList());
    }

    private void validateDateRangeBounds(List<Map.Entry<String,Map<String,Object>>> filters) {
        Map<String,Set<String>> groups=new LinkedHashMap<>();
        for(var entry:filters) {
            Map<String,Object> config=entry.getValue()==null?Map.of():entry.getValue();
            boolean group=config.containsKey("rangeGroup"), bound=config.containsKey("rangeBound");
            if(group!=bound)throw new IllegalArgumentException("Date-range metadata must include both a group and a bound.");
            if(!group)continue;
            Object rawGroup=config.get("rangeGroup"),rawBound=config.get("rangeBound");
            if(!"DATE".equals(entry.getKey())||!(rawGroup instanceof String key)||!SAFE_KEY.matcher(key).matches()
                    ||!(rawBound instanceof String direction)||!Set.of("FROM","TO").contains(direction))
                throw new IllegalArgumentException("Date-range metadata is valid only for DATE filters and requires a FROM or TO bound.");
            if(!groups.computeIfAbsent(key,k->new HashSet<>()).add(direction))
                throw new IllegalArgumentException("Each date-range group must have exactly one FROM and one TO filter.");
        }
        for(Set<String> group:groups.values())if(!group.equals(Set.of("FROM","TO")))
            throw new IllegalArgumentException("Each date-range group must have exactly one FROM and one TO filter.");
    }

    private void validateTemplateParameterMappings(List<FilterDefinition> filters, UUID routineId, int contractVersion) {
        List<FilterParameter> mappings = filters.stream().map(filter -> new FilterParameter(filter.parameterKey(),
            filter.filterType(), filter.required(), filter.lookupSourceId())).toList();
        validateParameterMappings(mappings, routineId, contractVersion);
    }

    private void validateParameterMappings(List<FilterParameter> filters, UUID routineId, int contractVersion) {
        Map<String, RoutineParameterOption> available = new LinkedHashMap<>();
        jdbc.query("SELECT parameter_key,parameter_type,is_required,is_filterable,sort_order FROM report_routine_parameters_mst WHERE routine_id=? AND contract_version=? ORDER BY sort_order,parameter_key",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> available.put(rs.getString("parameter_key"),
                new RoutineParameterOption(rs.getString("parameter_key"), rs.getString("parameter_type"),
                    rs.getBoolean("is_required"), rs.getBoolean("is_filterable"), rs.getInt("sort_order"))),
            routineId, contractVersion);
        Set<String> mapped = new HashSet<>();
        for (FilterParameter filter : filters) {
            RoutineParameterOption parameter = available.get(filter.key());
            if (parameter == null) throw new IllegalArgumentException("The parameter mapping is not part of the selected routine's approved contract.");
            if (!parameter.filterable()) throw new IllegalArgumentException("This routine parameter is reserved for trusted application context and cannot be configured as a filter.");
            if (parameter.required() && !filter.required()) throw new IllegalArgumentException("This routine parameter is required and its report filter must also be required.");
            String expected = expectedParameterType(filter);
            if (!parameter.parameterType().equals(expected)) throw new IllegalArgumentException("The filter type does not match the selected routine parameter type.");
            mapped.add(parameter.parameterKey());
        }
        for (RoutineParameterOption parameter : available.values()) if (parameter.filterable() && parameter.required() && !mapped.contains(parameter.parameterKey()))
            throw new IllegalArgumentException("Every required filterable routine parameter must have a report filter mapping.");
    }

    private String expectedParameterType(FilterParameter filter) {
        return switch (filter.type()) {
            case "TEXT", "SINGLE_SELECT" -> "TEXT";
            case "NUMBER" -> "NUMBER";
            case "BOOLEAN" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "MULTI_SELECT" -> "TEXT_ARRAY";
            case "LOOKUP_SINGLE", "LOOKUP_MULTI" -> {
                String valueType = jdbc.queryForObject("SELECT value_type FROM report_lookup_sources_mst WHERE id=? AND is_active=true", String.class, filter.lookupSourceId());
                yield valueType + (filter.type().equals("LOOKUP_MULTI") ? "_ARRAY" : "");
            }
            default -> throw new IllegalArgumentException("Choose a supported report filter type.");
        };
    }

    private void validateFilterConfiguration(FilterInput filter) {
        Map<String, Object> config = filter.configuration() == null ? Map.of() : filter.configuration();
        Set<String> allowed = switch (filter.filterType()) {
            case "TEXT" -> Set.of("placeholder", "maxLength");
            case "NUMBER" -> Set.of("placeholder", "min", "max");
            case "DATE" -> Set.of("rangeGroup", "rangeBound");
            case "SINGLE_SELECT", "MULTI_SELECT" -> Set.of("placeholder", "options");
            case "LOOKUP_SINGLE", "LOOKUP_MULTI" -> Set.of("placeholder");
            default -> Set.of();
        };
        if (!allowed.containsAll(config.keySet())) throw new IllegalArgumentException("Filter configuration contains unsupported or executable settings.");
        if (config.values().stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Filter configuration values cannot be null.");
        Object placeholder = config.get("placeholder");
        if (placeholder != null && (!(placeholder instanceof String text) || text.length() > 160)) throw new IllegalArgumentException("Filter placeholder must be a string of at most 160 characters.");
        if (filter.filterType().equals("TEXT") && config.containsKey("maxLength")) {
            int max = integer(config.get("maxLength"), "Text maximum length");
            if (max < 1 || max > 4000) throw new IllegalArgumentException("Text maximum length must be from 1 to 4000.");
        }
        if (filter.filterType().equals("NUMBER")) {
            BigDecimal min = decimal(config.get("min"), "Minimum"); BigDecimal max = decimal(config.get("max"), "Maximum");
            if (min != null && max != null && min.compareTo(max) > 0) throw new IllegalArgumentException("Filter minimum cannot be greater than its maximum.");
        }
        boolean staticSelect = filter.filterType().equals("SINGLE_SELECT") || filter.filterType().equals("MULTI_SELECT");
        if (staticSelect) validateOptions(config.get("options"));
        else if (config.containsKey("options")) throw new IllegalArgumentException("Options are supported only for static select filters.");
    }

    private void validateOptions(Object raw) {
        if (!(raw instanceof List<?> options) || options.isEmpty() || options.size() > 200) throw new IllegalArgumentException("Select filters require between 1 and 200 configured options.");
        Set<String> values = new HashSet<>();
        for (Object rawOption : options) {
            if (!(rawOption instanceof Map<?, ?> option) || !option.keySet().stream().allMatch(key -> Set.of("value", "label").contains(String.valueOf(key))))
                throw new IllegalArgumentException("Select options must contain only value and label fields.");
            Object value = option.get("value"), label = option.get("label");
            if (!(value instanceof String v) || v.isBlank() || v.length() > 120 || !(label instanceof String l) || l.isBlank() || l.length() > 120 || !values.add(v))
                throw new IllegalArgumentException("Select option values and labels must be unique, nonempty strings of at most 120 characters.");
        }
    }

    private void validateColumns(List<ColumnInput> columns) {
        Set<String> keys = new HashSet<>();
        for (ColumnInput column : columns) {
            if (column == null) throw new IllegalArgumentException("Column definitions cannot be null.");
            if (!SAFE_KEY.matcher(column.columnKey()).matches() || !keys.add(column.columnKey())) throw new IllegalArgumentException("Column keys must be stable lower camel-case identifiers and unique.");
            if (blank(column.label()) || column.label().trim().length() > 100) throw new IllegalArgumentException("Column labels must contain 1 to 100 characters.");
            if (!COLUMN_TYPES.contains(column.dataType())) throw new IllegalArgumentException("Choose a supported report column data type.");
            if (column.sortOrder() < 0 || column.sortOrder() > 99999) throw new IllegalArgumentException("Column order must be between 0 and 99999.");
            validateFormat(column.formatConfiguration());
        }
    }

    private void validateFormat(Map<String, Object> raw) {
        Map<String, Object> config = raw == null ? Map.of() : raw;
        if (!Set.of("dateFormat", "decimalPlaces", "currencyCode").containsAll(config.keySet())) throw new IllegalArgumentException("Column formatting contains unsupported settings.");
        if (config.values().stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Column formatting values cannot be null.");
        Object date = config.get("dateFormat");
        if (date != null && (!(date instanceof String format) || !Set.of("dd MMM yyyy", "dd MMM yyyy HH:mm", "yyyy-MM-dd", "yyyy-MM-dd HH:mm").contains(format)))
            throw new IllegalArgumentException("Choose a supported date format.");
        if (config.containsKey("decimalPlaces")) { int places = integer(config.get("decimalPlaces"), "Decimal places"); if (places < 0 || places > 8) throw new IllegalArgumentException("Decimal places must be between 0 and 8."); }
        Object currency = config.get("currencyCode");
        if (currency != null && (!(currency instanceof String code) || !code.matches("^[A-Z]{3}$"))) throw new IllegalArgumentException("Currency code must use three uppercase letters.");
    }

    private void validateRoleAccess(List<RoleGrantInput> grants, UUID tenant) {
        Set<String> keys = new HashSet<>();
        for (RoleGrantInput grant : grants) {
            if (!keys.add(grant.roleKey())) throw new IllegalArgumentException("A role may appear only once in report access settings.");
            if (grant.canExecute() && !grant.canView()) throw new IllegalArgumentException("Report execution requires view access.");
            if (grant.canExport() && (!grant.canExecute() || !grant.canView())) throw new IllegalArgumentException("Report export requires both view and execution access.");
        }
        if (keys.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        List<Object> args = new ArrayList<>(); args.add(tenant); args.addAll(keys);
        List<String> activeRoles = jdbc.queryForList("SELECT role_key FROM operator_roles_mst WHERE operator_id=? AND isactive=1 AND role_key IN (" + placeholders + ")", String.class, args.toArray());
        if (activeRoles.size() != keys.size()) throw notFound("One or more active tenant roles were not found.");
    }

    private void validateTemplateLookups(List<FilterDefinition> filters) {
        for (FilterDefinition filter : filters) if (filter.filterType().startsWith("LOOKUP_") &&
            (filter.lookupSourceId() == null || filter.lookupKey() == null || !activeLookupExists(filter.lookupSourceId())))
            throw conflict("The approved template references an inactive or missing lookup source.");
    }

    private void insertFilters(UUID reportId, AuthenticatedUser user, List<FilterInput> filters) {
        for (FilterInput filter : filters) jdbc.update("""
            INSERT INTO report_filters_mst(operator_id,report_id,filter_key,label,filter_type,parameter_key,is_required,sort_order,
                configuration,lookup_source_id,created_by,updated_by)
            VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?)
            """, user.organizationId(), reportId, filter.filterKey().trim(), filter.label().trim(), filter.filterType(),
            filter.parameterKey().trim(), filter.required(), filter.sortOrder(), json(filter.configuration()), filter.lookupSourceId(),
            user.externalUserId(), user.externalUserId());
    }

    private void insertColumns(UUID reportId, AuthenticatedUser user, List<ColumnInput> columns) {
        for (ColumnInput column : columns) jdbc.update("""
            INSERT INTO report_columns_mst(operator_id,report_id,column_key,label,data_type,sort_order,is_visible,is_sortable,
                is_exportable,format_configuration,created_by,updated_by)
            VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
            """, user.organizationId(), reportId, column.columnKey().trim(), column.label().trim(), column.dataType(),
            column.sortOrder(), column.visible(), column.sortable(), column.exportable(), json(column.formatConfiguration()),
            user.externalUserId(), user.externalUserId());
    }

    private void updateVersionedMetadata(UUID id, AuthenticatedUser user, long version, String name, String description, int sortOrder) {
        int changed = jdbc.update("UPDATE reports_mst SET name=?,description=?,sort_order=?,version=version+1,updated_at=now(),updated_by=? WHERE operator_id=? AND id=? AND version=? AND is_current=true",
            name.trim(), description, sortOrder, user.externalUserId(), user.organizationId(), id, version);
        if (changed != 1) throw conflict("The report changed while it was being edited. Reload it and try again.");
    }

    private void updateVersionOnly(UUID id, AuthenticatedUser user, long version) {
        int changed = jdbc.update("UPDATE reports_mst SET version=version+1,updated_at=now(),updated_by=? WHERE operator_id=? AND id=? AND version=? AND is_current=true",
            user.externalUserId(), user.organizationId(), id, version);
        if (changed != 1) throw conflict("The report changed while it was being edited. Reload it and try again.");
    }

    private void updateActive(UUID id, AuthenticatedUser user, long version, boolean active) {
        int changed = jdbc.update("UPDATE reports_mst SET is_active=?,version=version+1,updated_at=now(),updated_by=? WHERE operator_id=? AND id=? AND version=? AND is_current=true",
            active, user.externalUserId(), user.organizationId(), id, version);
        if (changed != 1) throw conflict("The report changed while it was being edited. Reload it and try again.");
    }

    private ReportAdminSummary lockCurrentReport(UUID id, AuthenticatedUser user) {
        ReportAdminSummary report = jdbc.query(summarySql() + " WHERE operator_id=? AND id=? AND is_current=true FOR UPDATE",
            rs -> rs.next() ? SUMMARY.mapRow(rs, 0) : null, user.organizationId(), id);
        if (report == null) throw notFound("Current report definition was not found.");
        return report;
    }

    private void checkVersion(ReportAdminSummary report, Long expected) {
        if (expected == null || report.version() != expected) throw conflict("The report changed while it was being edited. Reload it and try again.");
    }

    private ReportAdminSummary report(UUID id, UUID tenant, boolean lock) {
        ReportAdminSummary report = jdbc.query(summarySql() + " WHERE operator_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? SUMMARY.mapRow(rs, 0) : null, tenant, id);
        if (report == null) throw notFound("Report definition was not found.");
        return report;
    }

    private ReportAdminDetail detail(ReportAdminSummary report, UUID tenant) {
        UUID routineId = jdbc.query("SELECT routine_id FROM reports_mst WHERE operator_id=? AND id=?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenant, report.id());
        List<FilterDefinition> filters = getChildFilters(report.id(), tenant);
        List<ColumnDefinition> columns = getChildColumns(report.id(), tenant);
        List<RoleGrant> access = getRoleAccess(report.id(), tenant);
        return new ReportAdminDetail(report, routineId, filters, columns, access);
    }

    private List<FilterDefinition> getChildFilters(UUID id, UUID tenant) {
        return jdbc.query("""
            SELECT f.id,f.filter_key,f.label,f.filter_type,f.parameter_key,f.is_required,f.sort_order,f.configuration,
                   f.lookup_source_id,l.lookup_key
            FROM report_filters_mst f LEFT JOIN report_lookup_sources_mst l ON l.id=f.lookup_source_id
            WHERE f.operator_id=? AND f.report_id=? ORDER BY f.sort_order,f.filter_key
            """, FILTER, tenant, id);
    }

    private List<ColumnDefinition> getChildColumns(UUID id, UUID tenant) {
        return jdbc.query("SELECT id,column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable,format_configuration FROM report_columns_mst WHERE operator_id=? AND report_id=? ORDER BY sort_order,column_key", COLUMN, tenant, id);
    }

    private List<RoleGrant> getRoleAccess(UUID id, UUID tenant) {
        return jdbc.query("""
            SELECT a.role_key,r.name,a.can_view,a.can_execute,a.can_export FROM report_role_access_mst a
            JOIN operator_roles_mst r ON r.operator_id=a.operator_id AND r.role_key=a.role_key
            WHERE a.operator_id=? AND a.report_id=? ORDER BY r.name,a.role_key
            """, (rs, n) -> new RoleGrant(rs.getString("role_key"), rs.getString("name"), rs.getBoolean("can_view"),
            rs.getBoolean("can_execute"), rs.getBoolean("can_export")), tenant, id);
    }

    private void requireAdmin(AuthenticatedUser user) {
        if (user == null || user.organizationId() == null || user.externalUserId() == null || user.role() == null ||
            !exists("SELECT 1 FROM operator_memberships_mst m JOIN operators_mst o ON o.id=m.operator_id AND o.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE m.operator_id=? AND m.external_user_id=? AND m.role=? AND m.isactive=1",
                user.organizationId(), user.externalUserId(), user.role())) throw new AccessDeniedException("An active organization membership is required.");
        permissions.require(user, Permission.ADMINISTER_ORGANIZATION);
    }

    private RoutineRef ensureActiveRoutine(UUID routineId) {
        if (routineId == null) throw notFound("Active registered report routine was not found.");
        Integer version = jdbc.query("SELECT contract_version FROM report_routines_mst WHERE id=? AND is_active=true AND contract_version=2 AND routine_kind='REPORT' AND scope_policy='DEFECT_VISIBLE_SCOPE_V1'",
            rs -> rs.next() ? rs.getInt(1) : null, routineId);
        if (version == null) throw notFound("Active registered report routine was not found.");
        return new RoutineRef(routineId, version);
    }

    private RoutineRef routineForReport(UUID reportId, UUID tenant) {
        return jdbc.query("SELECT p.routine_id,r.contract_version FROM reports_mst p JOIN report_routines_mst r ON r.id=p.routine_id WHERE p.operator_id=? AND p.id=?",
            rs -> rs.next() ? new RoutineRef(rs.getObject("routine_id", UUID.class), rs.getInt("contract_version")) : null,
            tenant, reportId);
    }

    private boolean activeLookupExists(UUID lookupId) {
        return lookupId != null && exists("SELECT 1 FROM report_lookup_sources_mst l JOIN report_routines_mst r ON r.id=l.routine_id AND r.is_active=true AND r.contract_version=2 AND r.routine_kind='LOOKUP' AND r.scope_policy='DEFECT_VISIBLE_SCOPE_V1' WHERE l.id=? AND l.is_active=true", lookupId);
    }

    private void audit(AuthenticatedUser user, UUID reportId, String event, Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("""
            INSERT INTO defect_events_dtl(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id)
            VALUES(?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
            """, user.organizationId(), event, "Report configuration " + event.toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ".",
            user.externalUserId(), "REPORT_DEFINITION", reportId, before == null ? null : json(before), json(after), requestId());
    }

    private Map<String, Object> auditSnapshot(ReportAdminSummary report) {
        Map<String, Object> snapshot = new LinkedHashMap<>(); snapshot.put("reportCode", report.reportCode()); snapshot.put("definitionVersion", report.definitionVersion());
        snapshot.put("name", report.name()); snapshot.put("description", report.description()); snapshot.put("sortOrder", report.sortOrder()); snapshot.put("active", report.active());
        snapshot.put("sourceTemplateId", report.sourceTemplateId()); snapshot.put("sourceTemplateVersion", report.sourceTemplateVersion()); return snapshot;
    }

    private Map<String, Object> auditBeforeSnapshot(ReportAdminSummary report, List<FilterDefinition> filters, List<ColumnDefinition> columns) {
        Map<String, Object> snapshot = auditSnapshot(report); snapshot.put("filterKeys", filters.stream().map(FilterDefinition::filterKey).toList());
        snapshot.put("columnKeys", columns.stream().map(ColumnDefinition::columnKey).toList()); return snapshot;
    }

    private Map<String, Object> auditSnapshot(String code, String name, String description, int order, boolean active,
            UUID templateId, Integer templateVersion, List<FilterDefinition> filters, List<ColumnDefinition> columns, List<RoleGrant> access) {
        Map<String, Object> snapshot = new LinkedHashMap<>(); snapshot.put("reportCode", code); snapshot.put("name", name);
        snapshot.put("description", description); snapshot.put("sortOrder", order); snapshot.put("active", active);
        snapshot.put("sourceTemplateId", templateId); snapshot.put("sourceTemplateVersion", templateVersion);
        snapshot.put("filterKeys", filters.stream().map(FilterDefinition::filterKey).toList()); snapshot.put("columnKeys", columns.stream().map(ColumnDefinition::columnKey).toList());
        snapshot.put("roleKeys", access.stream().map(RoleGrant::roleKey).toList()); return snapshot;
    }

    private Map<String, Object> auditUpdateSnapshot(ReportAdminSummary report, List<FilterInput> filters, List<ColumnInput> columns) {
        Map<String, Object> snapshot = auditSnapshot(report); snapshot.put("filterKeys", filters.stream().map(FilterInput::filterKey).toList());
        snapshot.put("columnKeys", columns.stream().map(ColumnInput::columnKey).toList()); return snapshot;
    }

    private Map<String, Object> auditCreateSnapshot(String code, String name, String description, int order,
            List<FilterInput> filters, List<ColumnInput> columns) {
        Map<String, Object> snapshot = new LinkedHashMap<>(); snapshot.put("reportCode", code); snapshot.put("name", name);
        snapshot.put("description", description); snapshot.put("sortOrder", order); snapshot.put("active", false);
        snapshot.put("filterKeys", filters.stream().map(FilterInput::filterKey).toList());
        snapshot.put("columnKeys", columns.stream().map(ColumnInput::columnKey).toList()); return snapshot;
    }

    private Map<String, Object> roleAudit(List<RoleGrant> grants) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RoleGrant grant : grants) result.put(grant.roleKey(), Map.of("canView", grant.canView(), "canExecute", grant.canExecute(), "canExport", grant.canExport()));
        return result;
    }

    private Map<String, Object> roleAuditInputs(List<RoleGrantInput> grants) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RoleGrantInput grant : grants) result.put(grant.roleKey(), Map.of("canView", grant.canView(), "canExecute", grant.canExecute(), "canExport", grant.canExport()));
        return result;
    }

    private boolean exists(String sql, Object... args) {
        return jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), args);
    }
    private static String summarySql() { return "SELECT id,report_code,definition_version,name,description,sort_order,is_active,is_current,version,source_template_id,source_template_version FROM reports_mst"; }
    private static String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private static String json(Object value) { try { return JSON.writeValueAsString(value == null ? Map.of() : value); } catch (JsonProcessingException ex) { throw new IllegalArgumentException("Report metadata must be valid JSON data."); } }
    private static Map<String, Object> readJson(String value) { try { return JSON.readValue(value, JSON_OBJECT); } catch (Exception ex) { throw new IllegalStateException("Stored report metadata is invalid.", ex); } }
    private static int integer(Object value, String label) { if (!(value instanceof Number number)) throw new IllegalArgumentException(label + " must be a whole number."); try { return new BigDecimal(number.toString()).intValueExact(); } catch (ArithmeticException | NumberFormatException ex) { throw new IllegalArgumentException(label + " must be a whole number."); } }
    private static BigDecimal decimal(Object value, String label) { if (value == null) return null; if (!(value instanceof Number number)) throw new IllegalArgumentException(label + " must be numeric."); try { return new BigDecimal(number.toString()); } catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " must be numeric."); } }
    private static void validateReportCode(String code) { if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,79}$")) throw new IllegalArgumentException("Report code must be uppercase letters, numbers, and underscores, beginning with a letter."); }
    private static UUID requestId() { try { return UUID.fromString(MDC.get("requestId")); } catch (RuntimeException ex) { return null; } }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private record TemplateSource(UUID id, String code, int version, String name, String description,
            UUID routineId, int sortOrder, int contractVersion) {}
    private record RoutineRef(UUID id, int contractVersion) {}
    private record FilterParameter(String key, String type, boolean required, UUID lookupSourceId) {}
    private static final class RoutineBuilder {
        final UUID id;
        final String key;
        final int version;
        final List<RoutineParameterOption> parameters = new ArrayList<>();
        RoutineBuilder(UUID id, String key, int version) { this.id = id; this.key = key; this.version = version; }
    }
}
