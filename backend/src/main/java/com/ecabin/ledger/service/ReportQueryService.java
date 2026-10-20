package com.ecabin.ledger.service;

import com.ecabin.ledger.api.ReportApiException;
import com.ecabin.ledger.api.ReportQueryModels.*;
import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes only deployment-reviewed PostgreSQL functions under the v2 report contract. */
@Service
public class ReportQueryService {
    private static final Logger log = LoggerFactory.getLogger(ReportQueryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_OFFSET = 100_000L;

    private record Report(UUID id, String code, String name, UUID routineId, String routineKey,
            String schema, String function, int contractVersion, boolean routineActive,
            String routineKind, String scopePolicy, int timeoutMs, int maxPageSize) {}
    private record Filter(UUID reportId, String key, String label, String type, String parameterKey,
            boolean required, Map<String, Object> configuration, UUID lookupSourceId,
            String lookupKey, boolean lookupActive, String lookupValueType,
            UUID lookupRoutineId, String lookupRoutineKey, String lookupSchema,
            String lookupFunction, int lookupContractVersion, boolean lookupRoutineActive,
            String lookupRoutineKind, String lookupScopePolicy, int lookupTimeoutMs,
            int lookupMaxResults) {}
    private record Column(String key, String label, String type, int order, boolean visible,
            boolean sortable, boolean exportable, Map<String, Object> format) {}
    record RoutineParameter(String key, String type, boolean required, boolean filterable) {}
    private record RawResult(long total, String rowId, Map<String, Object> row) {}

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final ReportQueryAuditService auditService;

    /** Compatibility constructor used by focused unit tests. */
    public ReportQueryService(JdbcTemplate jdbc, PermissionService permissions) {
        this(jdbc, permissions, null);
    }

    @Autowired
    public ReportQueryService(JdbcTemplate jdbc, PermissionService permissions, ReportQueryAuditService auditService) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public QueryResponse query(UUID reportId, QueryRequest request, AuthenticatedUser user, UUID requestId) {
        long started = System.nanoTime();
        requireActiveMembership(user);
        permissions.require(user, Permission.REPORT_VIEW);
        permissions.require(user, Permission.REPORT_EXECUTE);
        // DEFECT_VISIBLE_SCOPE_V1 is the same read boundary used by
        // GET /defects: VIEW or the explicit tenant-wide DEFECT_VIEW_ALL grant.
        permissions.requireDefectRead(user);
        Report report = authorizedReport(reportId, user);
        try {
            if (request == null || request.page() == null || request.size() == null || request.filters() == null)
                throw invalidQuery("The report query request is incomplete.");
            long offset;
            try { offset = Math.multiplyExact((long) request.page(), (long) request.size()); }
            catch (ArithmeticException ex) { throw invalidQuery("The requested report page is outside the supported range."); }
            if (request.page() < 0 || request.size() < 1 || request.size() > MAX_PAGE_SIZE || offset > MAX_OFFSET)
                throw invalidQuery("Choose a supported report page and page size.");
            if (request.sort() != null && request.sort().size() > 3) throw invalidQuery("Use no more than three sort columns.");
            if (request.size() > report.maxPageSize()) throw invalidQuery("Choose a supported report page and page size.");

            List<Filter> filters = filters(reportId, user.organizationId());
            List<Column> columns = columns(reportId, user.organizationId());
            if (columns.isEmpty()) throw configuration("The report has no configured result columns.");
            validateRoutine(report);
            if (!"REPORT".equals(report.routineKind())) throw configuration("The registered routine is not a report provider.");
            requireInstalledFunction(report, false);
            Map<String, RoutineParameter> parameters = routineParameters(report.routineId(), report.contractVersion());
            List<Map<String, String>> sort = validateSort(request.sort(), columns);
            Map<String, Object> routineFilters = validateFilters(request.filters(), filters, parameters, user, report);

            // SET LOCAL is scoped to this read-only transaction and applies to the function itself.
            jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class,
                Math.max(100, report.timeoutMs()) + "ms");
            List<RawResult> raw = invokeReport(report, user, routineFilters, request.page(), request.size(), sort);
            QueryResponse response = validateAndMap(report, columns, raw, request.page(), request.size(), offset);
            audit(user, reportId, requestId, "QUERY", "SUCCESS", elapsed(started), response.rows().size());
            log.info("report_query outcome=SUCCESS reportId={} tenantId={} actorId={} requestId={} durationMs={} rowCount={}",
                reportId, user.organizationId(), user.externalUserId(), requestId, elapsed(started), response.rows().size());
            return response;
        } catch (ReportApiException ex) {
            String outcome = "INVALID_REPORT_CONFIGURATION".equals(ex.code()) ? "CONFIGURATION_BLOCKED" : "INVALID_REQUEST";
            audit(user, reportId, requestId, "QUERY", outcome, elapsed(started), 0);
            log.info("report_query outcome={} reportId={} tenantId={} actorId={} requestId={} durationMs={} rowCount=0",
                outcome, reportId, user.organizationId(), user.externalUserId(), requestId, elapsed(started));
            throw ex;
        } catch (DataAccessException ex) {
            boolean timeout = isTimeout(ex);
            boolean missingRoutine = isMissingRoutine(ex);
            String outcome=timeout?"TIMEOUT":missingRoutine?"CONFIGURATION_BLOCKED":"DATABASE_ERROR";
            audit(user, reportId, requestId, "QUERY", outcome, elapsed(started), 0);
            log.warn("report_query outcome={} reportId={} tenantId={} actorId={} requestId={} durationMs={} rowCount=0 cause={}",
                outcome, reportId, user.organizationId(), user.externalUserId(), requestId,
                elapsed(started), ex.getClass().getSimpleName());
            if (timeout) throw new ReportApiException(HttpStatus.GATEWAY_TIMEOUT, "REPORT_QUERY_TIMEOUT",
                "The report took too long to run. Narrow the filters and try again.");
            if (missingRoutine) throw configuration("The approved report function is not installed in the database.");
            throw new ReportApiException(HttpStatus.BAD_GATEWAY, "REPORT_QUERY_FAILED",
                "The report could not be completed. Try again later.");
        }
    }

    @Transactional(readOnly = true)
    public List<LookupValue> lookupOptions(String lookupKey, AuthenticatedUser user, UUID requestId) {
        long started = System.nanoTime();
        requireActiveMembership(user);
        permissions.require(user, Permission.REPORT_VIEW);
        if (lookupKey == null || !lookupKey.matches("[A-Z][A-Z0-9_]{0,79}")) throw notFound();
        Filter filter = authorizedLookupFilter(lookupKey, user);
        try {
            if (!filter.lookupActive()) throw configuration("The registered report lookup is inactive.");
            Report lookupRoutine = new Report(filter.reportId(), null, null, filter.lookupRoutineId(),
                filter.lookupRoutineKey(), filter.lookupSchema(), filter.lookupFunction(), filter.lookupContractVersion(),
                filter.lookupRoutineActive(), filter.lookupRoutineKind(), filter.lookupScopePolicy(),
                filter.lookupTimeoutMs(), filter.lookupMaxResults());
            validateRoutine(lookupRoutine);
            if (!"LOOKUP".equals(lookupRoutine.routineKind())) throw configuration("The registered routine is not a lookup provider.");
            requireInstalledFunction(lookupRoutine, true);
            jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class,
                Math.max(100, filter.lookupTimeoutMs()) + "ms");
            List<LookupValue> values = invokeLookup(filter, user);
            audit(user, filter.reportId(), requestId, "LOOKUP", "SUCCESS", elapsed(started), values.size());
            log.info("report_lookup outcome=SUCCESS reportId={} tenantId={} actorId={} requestId={} durationMs={} rowCount={}",
                filter.reportId(), user.organizationId(), user.externalUserId(), requestId, elapsed(started), values.size());
            return values;
        } catch (ReportApiException ex) {
            audit(user, filter.reportId(), requestId, "LOOKUP", "CONFIGURATION_BLOCKED", elapsed(started), 0);
            log.info("report_lookup outcome=CONFIGURATION_BLOCKED reportId={} tenantId={} actorId={} requestId={} durationMs={} rowCount=0",
                filter.reportId(),user.organizationId(),user.externalUserId(),requestId,elapsed(started));
            throw ex;
        } catch (DataAccessException ex) {
            boolean timeout = isTimeout(ex);
            boolean missingRoutine = isMissingRoutine(ex);
            String outcome=timeout?"TIMEOUT":missingRoutine?"CONFIGURATION_BLOCKED":"DATABASE_ERROR";
            audit(user, filter.reportId(), requestId, "LOOKUP", outcome, elapsed(started), 0);
            log.warn("report_lookup outcome={} reportId={} tenantId={} actorId={} requestId={} durationMs={} rowCount=0 cause={}",
                outcome,filter.reportId(),user.organizationId(),user.externalUserId(),requestId,elapsed(started),ex.getClass().getSimpleName());
            if (timeout) throw new ReportApiException(HttpStatus.GATEWAY_TIMEOUT, "REPORT_LOOKUP_TIMEOUT",
                "The report filter options took too long to load.");
            if (missingRoutine) throw configuration("The approved report lookup function is not installed in the database.");
            throw new ReportApiException(HttpStatus.BAD_GATEWAY, "REPORT_LOOKUP_FAILED",
                "Report filter options could not be loaded.");
        }
    }

    private Report authorizedReport(UUID reportId, AuthenticatedUser user) {
        Report report = jdbc.query("""
            SELECT r.id,r.report_code,r.name,r.routine_id,rt.routine_key,rt.schema_name,rt.function_name,
                   rt.contract_version,rt.is_active,rt.routine_kind,rt.scope_policy,rt.timeout_ms,rt.max_page_size
            FROM reports_mst r
            JOIN report_role_access_mst a ON a.operator_id=r.operator_id AND a.report_id=r.id
            JOIN report_routines_mst rt ON rt.id=r.routine_id
            WHERE r.operator_id=? AND r.id=? AND r.is_active=true AND r.is_current=true
              AND a.role_key=? AND a.can_view=true AND a.can_execute=true
            """, (rs, n) -> new Report(rs.getObject("id", UUID.class), rs.getString("report_code"),
                rs.getString("name"), rs.getObject("routine_id", UUID.class), rs.getString("routine_key"),
                rs.getString("schema_name"), rs.getString("function_name"), rs.getInt("contract_version"),
                rs.getBoolean("is_active"), rs.getString("routine_kind"), rs.getString("scope_policy"),
                rs.getInt("timeout_ms"), rs.getInt("max_page_size")), user.organizationId(), reportId, user.role())
            .stream().findFirst().orElseThrow(ReportQueryService::notFound);
        return report;
    }

    private Filter authorizedLookupFilter(String lookupKey, AuthenticatedUser user) {
        Filter filter = jdbc.query("""
            SELECT r.id AS report_id,f.filter_key,f.label,f.filter_type,f.parameter_key,f.is_required,f.configuration,
                   l.id AS lookup_id,l.is_active AS lookup_active,l.value_type,l.max_results,
                   rt.id AS routine_id,rt.routine_key,rt.schema_name,rt.function_name,rt.contract_version,
                   rt.is_active AS routine_active,rt.routine_kind,rt.scope_policy,rt.timeout_ms
            FROM reports_mst r
            JOIN report_role_access_mst a ON a.operator_id=r.operator_id AND a.report_id=r.id
            JOIN report_filters_mst f ON f.operator_id=r.operator_id AND f.report_id=r.id
            JOIN report_lookup_sources_mst l ON l.id=f.lookup_source_id
            JOIN report_routines_mst rt ON rt.id=l.routine_id
            WHERE r.operator_id=? AND r.is_active=true AND r.is_current=true AND a.role_key=? AND a.can_view=true
              AND l.lookup_key=? AND l.is_active=true AND rt.routine_kind='LOOKUP'
            ORDER BY r.sort_order,r.report_code,f.sort_order,f.filter_key LIMIT 1
            """, rs -> rs.next() ? new Filter(rs.getObject("report_id",UUID.class),rs.getString("filter_key"),rs.getString("label"),rs.getString("filter_type"),
                rs.getString("parameter_key"),rs.getBoolean("is_required"),readJsonMap(rs.getString("configuration")),
                rs.getObject("lookup_id",UUID.class),lookupKey,rs.getBoolean("lookup_active"),rs.getString("value_type"),
                rs.getObject("routine_id",UUID.class),rs.getString("routine_key"),rs.getString("schema_name"),
                rs.getString("function_name"),rs.getInt("contract_version"),rs.getBoolean("routine_active"),
                rs.getString("routine_kind"),rs.getString("scope_policy"),rs.getInt("timeout_ms"),rs.getInt("max_results")) : null,
            user.organizationId(),user.role(),lookupKey);
        if (filter == null) throw notFound();
        return filter;
    }

    private List<Filter> filters(UUID reportId, UUID tenant) {
        return jdbc.query("""
            SELECT f.filter_key,f.label,f.filter_type,f.parameter_key,f.is_required,f.configuration,f.lookup_source_id,
                   l.lookup_key,l.is_active AS lookup_active,l.value_type,l.max_results,
                   rt.id AS lookup_routine_id,rt.routine_key AS lookup_routine_key,rt.schema_name AS lookup_schema,
                   rt.function_name AS lookup_function,rt.contract_version AS lookup_contract_version,
                   rt.is_active AS lookup_routine_active,rt.routine_kind AS lookup_routine_kind,
                   rt.scope_policy AS lookup_scope_policy,rt.timeout_ms AS lookup_timeout_ms
            FROM report_filters_mst f LEFT JOIN report_lookup_sources_mst l ON l.id=f.lookup_source_id
            LEFT JOIN report_routines_mst rt ON rt.id=l.routine_id
            WHERE f.operator_id=? AND f.report_id=? ORDER BY f.sort_order,f.filter_key
            """, (rs,n) -> new Filter(f(reportId,rs),rs.getString("filter_key"),rs.getString("label"),
                rs.getString("filter_type"),rs.getString("parameter_key"),rs.getBoolean("is_required"),
                readJsonMap(rs.getString("configuration")),rs.getObject("lookup_source_id",UUID.class),rs.getString("lookup_key"),
                rs.getObject("lookup_source_id")!=null && rs.getBoolean("lookup_active"),rs.getString("value_type"),
                rs.getObject("lookup_routine_id",UUID.class),rs.getString("lookup_routine_key"),rs.getString("lookup_schema"),
                rs.getString("lookup_function"),rs.getObject("lookup_contract_version")==null?0:rs.getInt("lookup_contract_version"),
                rs.getObject("lookup_routine_id")!=null && rs.getBoolean("lookup_routine_active"),rs.getString("lookup_routine_kind"),
                rs.getString("lookup_scope_policy"),rs.getObject("lookup_timeout_ms")==null?0:rs.getInt("lookup_timeout_ms"),
                rs.getObject("max_results")==null?0:rs.getInt("max_results")),tenant,reportId);
    }

    // Report id is only carried internally when resolving lookup source visibility.
    private static UUID f(UUID reportId, ResultSet rs) { return reportId; }

    private List<Column> columns(UUID reportId, UUID tenant) {
        return jdbc.query("SELECT column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable,format_configuration FROM report_columns_mst WHERE operator_id=? AND report_id=? ORDER BY sort_order,column_key",
            (rs,n) -> new Column(rs.getString("column_key"),rs.getString("label"),rs.getString("data_type"),rs.getInt("sort_order"),
                rs.getBoolean("is_visible"),rs.getBoolean("is_sortable"),rs.getBoolean("is_exportable"),readJsonMap(rs.getString("format_configuration"))),tenant,reportId);
    }

    Map<String, RoutineParameter> routineParameters(UUID routineId, int version) {
        Map<String,RoutineParameter> parameters = new LinkedHashMap<>();
        List<RoutineParameter> rows = jdbc.query(
            "SELECT parameter_key,parameter_type,is_required,is_filterable FROM report_routine_parameters_mst WHERE routine_id=? AND contract_version=? ORDER BY sort_order,parameter_key",
            (rs, rowNum) -> new RoutineParameter(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)),
            routineId, version);
        rows.forEach(parameter -> parameters.put(parameter.key(), parameter));
        return parameters;
    }

    private Map<String,Object> validateFilters(Map<String,Object> supplied, List<Filter> definitions,
            Map<String,RoutineParameter> parameters, AuthenticatedUser user, Report report) {
        if (supplied == null) throw invalid("Filter values are required; use an empty object when no filters are supplied.");
        Map<String,Filter> byKey = new LinkedHashMap<>();
        for(Filter filter:definitions) byKey.put(filter.key(),filter);
        validateDateRangeMetadata(definitions);
        for(String key:supplied.keySet()) if(!byKey.containsKey(key)) throw invalid("The request contains an unknown report filter.");
        Map<String,Object> values = new LinkedHashMap<>();
        for(Filter filter:definitions) {
            RoutineParameter parameter=parameters.get(filter.parameterKey());
            boolean typeMatches = parameter != null && parameterTypeMatches(filter, parameter);
            if(parameter==null || !parameter.filterable() || parameter.required()&&!filter.required() || !typeMatches) {
                // Configuration metadata is safe to log; never include submitted filter values.
                String databaseSchema = jdbc.queryForObject("SELECT current_database() || '/' || current_schema()", String.class);
                log.warn("report_filter_contract_mismatch reportId={} filterKey={} filterType={} parameterKey={} " +
                        "routineId={} contractVersion={} databaseSchema={} registeredParameterKeys={} " +
                        "parameterFound={} parameterType={} expectedType={} filterRequired={} parameterRequired={} filterable={} lookupValueType={}",
                    report.id(), filter.key(), filter.type(), filter.parameterKey(), report.routineId(), report.contractVersion(),
                    databaseSchema, parameters.keySet(), parameter != null,
                    parameter == null ? null : parameter.type(), expectedParameterType(filter), filter.required(),
                    parameter != null && parameter.required(), parameter != null && parameter.filterable(), filter.lookupValueType());
                throw configuration("A report filter no longer matches its registered routine contract.");
            }
            boolean present=supplied.containsKey(filter.key());
            Object raw=supplied.get(filter.key());
            if(filter.required() && (!present || raw==null || isEmpty(raw))) throw invalid("Complete all required report filters.");
            if(!present) continue; // omission remains distinct from an explicitly supplied null/empty value
            Object value=validateFilterValue(filter,raw,user,report);
            values.put(filter.parameterKey(),value);
        }
        validateDateRanges(definitions,supplied);
        for(RoutineParameter parameter:parameters.values()) if(parameter.filterable() && parameter.required()
                && definitions.stream().noneMatch(f->f.parameterKey().equals(parameter.key())))
            throw configuration("The report is missing a required registered filter.");
        // Resolve dynamic values only after the entire submitted request has passed local validation.
        for(Filter filter:definitions) {
            if(!supplied.containsKey(filter.key())||supplied.get(filter.key())==null||!filter.type().startsWith("LOOKUP_"))continue;
            Set<String> allowed=lookupValues(filter,user);
            Object raw=supplied.get(filter.key());
            if(filter.type().equals("LOOKUP_SINGLE")) {
                String value=(String)raw;
                if(!allowed.contains(value))throw invalid("Choose a valid report lookup option.");
                values.put(filter.parameterKey(),convertLookupValue(value,filter.lookupValueType()));
            } else {
                @SuppressWarnings("unchecked") List<String> requested=(List<String>)raw;
                if(requested.stream().anyMatch(value->!allowed.contains(value)))throw invalid("Choose valid report lookup options.");
                values.put(filter.parameterKey(),requested.stream().map(value->convertLookupValue(value,filter.lookupValueType())).toList());
            }
        }
        return values;
    }

    private void validateDateRangeMetadata(List<Filter> definitions) {
        Map<String,Set<String>> bounds=new LinkedHashMap<>();
        for(Filter filter:definitions) {
            boolean hasGroup=filter.configuration().containsKey("rangeGroup");
            boolean hasBound=filter.configuration().containsKey("rangeBound");
            if(hasGroup!=hasBound)throw configuration("A date-range filter has incomplete metadata.");
            if(!hasGroup)continue;
            Object rawGroup=filter.configuration().get("rangeGroup"),rawBound=filter.configuration().get("rangeBound");
            if(!"DATE".equals(filter.type())||!(rawGroup instanceof String group)||!group.matches("^[a-z][a-zA-Z0-9_]{0,63}$")
                    ||!(rawBound instanceof String bound)||!Set.of("FROM","TO").contains(bound))
                throw configuration("A date-range filter has invalid metadata.");
            if(!bounds.computeIfAbsent(group,k->new HashSet<>()).add(bound))throw configuration("A date range has duplicate bounds.");
        }
        for(Set<String> group:bounds.values())if(!group.equals(Set.of("FROM","TO")))
            throw configuration("A configured date range must include both From and To filters.");
    }

    private void validateDateRanges(List<Filter> definitions,Map<String,Object> supplied) {
        Map<String,String> from=new LinkedHashMap<>(),to=new LinkedHashMap<>();
        for(Filter filter:definitions) {
            Object group=filter.configuration().get("rangeGroup"),bound=filter.configuration().get("rangeBound");
            if(!(group instanceof String key)||!(bound instanceof String direction))continue;
            Object value=supplied.get(filter.key());
            if(value==null)continue;
            if(!(value instanceof String date))throw invalid("A date range contains an invalid value.");
            if(direction.equals("FROM"))from.put(key,date);else to.put(key,date);
        }
        for(String key:from.keySet())if(to.containsKey(key)&&LocalDate.parse(from.get(key)).isAfter(LocalDate.parse(to.get(key))))
            throw invalid("A date range start must be on or before its end.");
    }

    private Object validateFilterValue(Filter filter,Object raw,AuthenticatedUser user,Report report) {
        if(raw==null) return null;
        try {
            return switch(filter.type()) {
                case "TEXT" -> validateText(filter,raw);
                case "NUMBER" -> validateNumber(filter,raw);
                case "BOOLEAN" -> { if(!(raw instanceof Boolean)) throw invalid("A report filter has an invalid value type."); yield raw; }
                case "DATE" -> { if(!(raw instanceof String s)) throw invalid("A report filter has an invalid value type."); yield LocalDate.parse(s).toString(); }
                case "SINGLE_SELECT" -> validateStaticSingle(filter,raw);
                case "MULTI_SELECT" -> validateStaticMulti(filter,raw);
                case "LOOKUP_SINGLE" -> validateLookupSingle(raw);
                case "LOOKUP_MULTI" -> validateLookupMulti(raw);
                default -> throw configuration("The report contains an unsupported filter type.");
            };
        } catch(ReportApiException ex) { throw ex; }
        catch(RuntimeException ex) { throw invalid("A report filter has an invalid value."); }
    }

    private Object validateText(Filter filter,Object raw) {
        if(!(raw instanceof String text)) throw invalid("A report filter has an invalid value type.");
        int max=integerConfig(filter.configuration().get("maxLength"),4000);
        if(text.length()>max) throw invalid("A text filter exceeds its configured length.");
        return text;
    }
    private Object validateNumber(Filter filter,Object raw) {
        if(!(raw instanceof Number number)) throw invalid("A report filter has an invalid value type.");
        BigDecimal value=new BigDecimal(number.toString());
        BigDecimal min=decimalConfig(filter.configuration().get("min")), max=decimalConfig(filter.configuration().get("max"));
        if(min!=null&&value.compareTo(min)<0 || max!=null&&value.compareTo(max)>0) throw invalid("A number is outside its configured range.");
        return value;
    }
    private Object validateStaticSingle(Filter filter,Object raw) {
        if(!(raw instanceof String value)||value.length()>120||!staticOptions(filter).contains(value)) throw invalid("Choose a supported report filter option.");
        return value;
    }
    private Object validateStaticMulti(Filter filter,Object raw) {
        if(!(raw instanceof List<?> list)||list.size()>200) throw invalid("A multi-select filter has invalid values.");
        Set<String> allowed=staticOptions(filter); List<String> values=new ArrayList<>();
        for(Object item:list) { if(!(item instanceof String value)||value.length()>120||!allowed.contains(value)||values.contains(value)) throw invalid("Choose supported report filter options."); values.add(value); }
        return values;
    }
    private Object validateLookupSingle(Object raw) {
        if(!(raw instanceof String value)||value.length()>120) throw invalid("Choose a valid report lookup option.");
        return value;
    }
    private Object validateLookupMulti(Object raw) {
        if(!(raw instanceof List<?> list)||list.size()>200) throw invalid("A lookup filter has invalid values.");
        Set<String> seen=new HashSet<>(); List<String> values=new ArrayList<>();
        for(Object item:list) { if(!(item instanceof String value)||value.length()>120||!seen.add(value)) throw invalid("Choose valid report lookup options."); values.add(value); }
        return values;
    }

    private Set<String> staticOptions(Filter filter) {
        Object raw=filter.configuration().get("options");
        if(!(raw instanceof List<?> options)||options.isEmpty()||options.size()>200) throw configuration("A static report select has invalid option metadata.");
        Set<String> values=new HashSet<>();
        for(Object item:options) {
            if(!(item instanceof Map<?,?> map)||!(map.get("value") instanceof String value)||value.isBlank()
                    ||!(map.get("label") instanceof String label)||label.isBlank()||!values.add(value))
                throw configuration("A static report select has invalid option metadata.");
        }
        return values;
    }

    private Set<String> lookupValues(Filter filter,AuthenticatedUser user) {
        if(!filter.lookupActive()||filter.lookupRoutineId()==null) throw configuration("A report lookup provider is inactive.");
        validateRoutine(new Report(null,null,null,filter.lookupRoutineId(),filter.lookupRoutineKey(),filter.lookupSchema(),
            filter.lookupFunction(),filter.lookupContractVersion(),filter.lookupRoutineActive(),filter.lookupRoutineKind(),
            filter.lookupScopePolicy(),filter.lookupTimeoutMs(),filter.lookupMaxResults()));
        if(!"LOOKUP".equals(filter.lookupRoutineKind()))throw configuration("A report filter is linked to a non-lookup routine.");
        if(filter.lookupTimeoutMs()<1||filter.lookupMaxResults()<1) throw configuration("A report lookup provider has invalid limits.");
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)",String.class,Math.max(100,filter.lookupTimeoutMs())+"ms");
        return invokeLookup(filter,user).stream().map(LookupValue::value).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<Map<String,String>> validateSort(List<Sort> requested,List<Column> columns) {
        Map<String,Column> byKey=new LinkedHashMap<>(); for(Column column:columns) byKey.put(column.key(),column);
        List<Sort> sort=requested==null?List.of():requested;
        Set<String> keys=new HashSet<>(); List<Map<String,String>> result=new ArrayList<>();
        for(Sort entry:sort) {
            Column column=entry==null?null:byKey.get(entry.key());
            if(column==null||!column.sortable()||!keys.add(entry.key())) throw invalid("Sort by a configured sortable report column.");
            String direction=entry.direction().toUpperCase(Locale.ROOT);
            if(!direction.equals("ASC")&&!direction.equals("DESC")) throw invalid("Choose ASC or DESC sorting.");
            result.add(Map.of("key",entry.key(),"direction",direction));
        }
        return result;
    }

    private List<RawResult> invokeReport(Report report,AuthenticatedUser user,Map<String,Object> filters,int page,int size,
            List<Map<String,String>> sort) {
        String sql="SELECT total_rows,row_id,row_data FROM "+qualifiedRoutine(report.schema(),report.function())+
            "(?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)";
        String filtersJson=writeJson(filters),sortJson=writeJson(sort);
        PreparedStatementCreator creator=connection->{
            PreparedStatement ps=connection.prepareStatement(sql);
            ps.setObject(1,user.organizationId()); ps.setString(2,user.externalUserId()); ps.setString(3,user.role());
            ps.setString(4,report.scopePolicy()); ps.setString(5,filtersJson); ps.setInt(6,page); ps.setInt(7,size); ps.setString(8,sortJson);
            ps.setQueryTimeout(Math.max(1,(report.timeoutMs()+999)/1000)); return ps;
        };
        return jdbc.query(creator,(ResultSetExtractor<List<RawResult>>)rs->{
            var metadata=rs.getMetaData();
            if(metadata.getColumnCount()!=3||!"total_rows".equalsIgnoreCase(metadata.getColumnLabel(1))
                    ||!"row_id".equalsIgnoreCase(metadata.getColumnLabel(2))||!"row_data".equalsIgnoreCase(metadata.getColumnLabel(3))
                    ||metadata.getColumnType(1)!=Types.BIGINT
                    ||!(metadata.getColumnType(2)==Types.VARCHAR||metadata.getColumnType(2)==Types.CHAR)
                    ||metadata.getColumnType(3)!=Types.OTHER)
                throw configuration("The report function does not match the registered v2 result contract.");
            List<RawResult> rows=new ArrayList<>(); while(rs.next()) {
                long total=rs.getLong("total_rows"); if(rs.wasNull()||total<0) throw configuration("The report function returned an invalid total count.");
                String rowId=rs.getString("row_id"), json=rs.getString("row_data");
                Map<String,Object> row=null;
                if(json!=null) { if(json.length()>1_000_000)throw configuration("The report function returned an oversized row."); try { row=JSON.readValue(json,JSON_MAP); } catch(Exception ex) { throw configuration("The report function returned an invalid row."); } }
                if((rowId==null)!=(row==null)) throw configuration("The report function returned an invalid empty-page marker.");
                rows.add(new RawResult(total,rowId,row));
                if(rows.size()>size+1) throw configuration("The report function returned too many rows.");
            }
            return rows;
        });
    }

    private QueryResponse validateAndMap(Report report,List<Column> allColumns,List<RawResult> raw,int page,int size,long offset) {
        if(raw.isEmpty()) throw configuration("The report function omitted its required total-count marker.");
        long total=raw.get(0).total();
        for(RawResult row:raw) if(row.total()!=total) throw configuration("The report function returned inconsistent total counts.");
        List<RawResult> data=raw.stream().filter(r->r.rowId()!=null).toList();
        boolean hasMarker=raw.stream().anyMatch(r->r.rowId()==null);
        if(hasMarker&&(raw.size()!=1||total==0&&offset==0&&data.size()>0)) throw configuration("The report function returned an invalid page marker.");
        long expected=offset>=total?0:Math.min((long)size,total-offset);
        if(data.size()!=expected || (expected==0&&!hasMarker) || (expected>0&&hasMarker))
            throw configuration("The report function returned an incomplete page.");
        Map<String,Column> byKey=new LinkedHashMap<>(); for(Column c:allColumns) byKey.put(c.key(),c);
        Set<String> rowIds=new HashSet<>(); List<Map<String,Object>> output=new ArrayList<>();
        for(RawResult result:data) {
            if(result.rowId().isBlank()||result.rowId().length()>200||!rowIds.add(result.rowId()))
                throw configuration("The report function returned a missing or duplicate stable row identity.");
            Map<String,Object> values=result.row();
            if(values==null||!values.keySet().equals(byKey.keySet())) throw configuration("The report function result does not match configured report columns.");
            for(Column column:allColumns) validateResultValue(column,values.get(column.key()));
            if(byKey.containsKey("id")&&!java.util.Objects.equals(String.valueOf(values.get("id")),result.rowId()))
                throw configuration("The report row identity conflicts with its configured id column.");
            Map<String,Object> row=new LinkedHashMap<>(); row.put("id",result.rowId());
            for(Column column:allColumns) if(column.visible()&&!column.key().equals("id")) row.put(column.key(),values.get(column.key()));
            output.add(row);
        }
        List<QueryColumn> visible=allColumns.stream().filter(Column::visible).map(c->new QueryColumn(c.key(),c.label(),c.type(),c.format(),c.sortable(),c.exportable())).toList();
        long totalPages=total==0?0:((total-1)/size)+1;
        return new QueryResponse(new QueryReport(report.id(),report.code(),report.name()),visible,output,page,size,total,totalPages);
    }

    private void validateResultValue(Column c,Object value) {
        if(value==null)return;
        boolean valid=switch(c.type()) {
            case "TEXT" -> value instanceof String s && s.length()<=4000;
            case "NUMBER" -> value instanceof Number;
            case "BOOLEAN" -> value instanceof Boolean;
            case "DATE" -> value instanceof String s && parseDate(s);
            case "DATETIME" -> value instanceof String s && parseDateTime(s);
            case "UUID" -> value instanceof String s && parseUuid(s);
            default -> false;
        };
        if(!valid)throw configuration("The report function returned a value with an invalid configured type.");
    }

    private List<LookupValue> invokeLookup(Filter filter,AuthenticatedUser user) {
        String sql="SELECT option_value,display_label FROM "+qualifiedRoutine(filter.lookupSchema(),filter.lookupFunction())+"(?, ?, ?, ?, ?)";
        PreparedStatementCreator creator=connection->{
            PreparedStatement ps=connection.prepareStatement(sql); ps.setObject(1,user.organizationId());
            ps.setString(2,user.externalUserId()); ps.setString(3,user.role()); ps.setString(4,filter.lookupScopePolicy());
            ps.setInt(5,filter.lookupMaxResults()); ps.setQueryTimeout(Math.max(1,(filter.lookupTimeoutMs()+999)/1000)); return ps;
        };
        return jdbc.query(creator,(ResultSetExtractor<List<LookupValue>>)rs->{
            var metadata=rs.getMetaData();
            if(metadata.getColumnCount()!=2||!"option_value".equalsIgnoreCase(metadata.getColumnLabel(1))
                    ||!"display_label".equalsIgnoreCase(metadata.getColumnLabel(2))
                    ||!(metadata.getColumnType(1)==Types.VARCHAR||metadata.getColumnType(1)==Types.CHAR)
                    ||!(metadata.getColumnType(2)==Types.VARCHAR||metadata.getColumnType(2)==Types.CHAR))
                throw configuration("The lookup function does not match the registered option contract.");
            List<LookupValue> values=new ArrayList<>(); Set<String> unique=new HashSet<>();
            while(rs.next()) {
                String value=rs.getString("option_value"), label=rs.getString("display_label");
                if(value==null||value.isBlank()||value.length()>120||label==null||label.isBlank()||label.length()>160||!unique.add(value))
                    throw configuration("The registered lookup returned invalid options.");
                validateLookupValueType(value,filter.lookupValueType());
                values.add(new LookupValue(value,label));
                if(values.size()>filter.lookupMaxResults())throw configuration("The registered lookup exceeded its result limit.");
            }
            return List.copyOf(values);
        });
    }

    private void validateRoutine(Report report) {
        if(!report.routineActive()||report.contractVersion()!=2||!"DEFECT_VISIBLE_SCOPE_V1".equals(report.scopePolicy())
                ||(!"REPORT".equals(report.routineKind())&& !"LOOKUP".equals(report.routineKind()))
                ||!safeIdentifier(report.schema())||!safeIdentifier(report.function()))
            throw configuration("The report function is not registered under a supported, reviewed execution contract.");
    }
    private void requireInstalledFunction(Report report,boolean lookup) {
        String args=lookup?"p.pronargs=5 AND p.proargtypes[0]='uuid'::regtype::oid AND p.proargtypes[1]='text'::regtype::oid AND p.proargtypes[2]='text'::regtype::oid AND p.proargtypes[3]='text'::regtype::oid AND p.proargtypes[4]='integer'::regtype::oid"
            :"p.pronargs=8 AND p.proargtypes[0]='uuid'::regtype::oid AND p.proargtypes[1]='text'::regtype::oid AND p.proargtypes[2]='text'::regtype::oid AND p.proargtypes[3]='text'::regtype::oid AND p.proargtypes[4]='jsonb'::regtype::oid AND p.proargtypes[5]='integer'::regtype::oid AND p.proargtypes[6]='integer'::regtype::oid AND p.proargtypes[7]='jsonb'::regtype::oid";
        String sql="SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=? AND p.proname=? AND "+args+" AND p.proretset=true AND p.prosecdef=false AND p.provolatile='s')";
        Boolean valid=jdbc.queryForObject(sql,Boolean.class,report.schema(),report.function());
        if(!Boolean.TRUE.equals(valid))throw configuration("The registered function is missing or does not match its read-only execution signature.");
    }
    private String qualifiedRoutine(String schema,String function) {
        if(!safeIdentifier(schema)||!safeIdentifier(function))throw configuration("The registered report function identifier is invalid.");
        return '"'+schema+'"'+'.'+'"'+function+'"';
    }
    private boolean parameterTypeMatches(Filter f,RoutineParameter p) {
        return p.type().equals(expectedParameterType(f));
    }
    private String expectedParameterType(Filter f) {
        String expected=switch(f.type()) {
            case "TEXT","SINGLE_SELECT" -> "TEXT"; case "NUMBER" -> "NUMBER"; case "BOOLEAN" -> "BOOLEAN";
            case "DATE" -> "DATE"; case "MULTI_SELECT" -> "TEXT_ARRAY";
            case "LOOKUP_SINGLE" -> f.lookupValueType()+"";
            case "LOOKUP_MULTI" -> f.lookupValueType()+"_ARRAY";
            default -> "";
        };
        // Registry lookup value types use TEXT/NUMBER/UUID; UUID is a PostgreSQL string on JSON transport.
        if(f.type().startsWith("LOOKUP_")&&"UUID".equals(f.lookupValueType())) expected=f.type().equals("LOOKUP_MULTI")?"UUID_ARRAY":"UUID";
        return expected;
    }
    private static boolean safeIdentifier(String value){return value!=null&&IDENTIFIER.matcher(value).matches();}
    private static String writeJson(Object value){try{return JSON.writeValueAsString(value);}catch(Exception ex){throw invalid("Report filters could not be encoded.");}}
    private static Map<String,Object> readJsonMap(String value){try{return JSON.readValue(value,JSON_MAP);}catch(Exception ex){throw configuration("Report metadata contains invalid configuration.");}}
    private static int integerConfig(Object raw,int fallback){if(raw==null)return fallback;if(raw instanceof Number n)return n.intValue();throw configuration("A report filter has invalid configuration.");}
    private static BigDecimal decimalConfig(Object raw){if(raw==null)return null;try{return new BigDecimal(raw.toString());}catch(Exception ex){throw configuration("A report filter has invalid numeric configuration.");}}
    private static boolean isEmpty(Object value){return value instanceof String s&&s.isBlank()||value instanceof List<?> l&&l.isEmpty();}
    private static boolean parseDate(String s){try{LocalDate.parse(s);return true;}catch(Exception ex){return false;}}
    private static boolean parseDateTime(String s){try{OffsetDateTime.parse(s);return true;}catch(Exception ex){try{Instant.parse(s);return true;}catch(Exception ignored){return false;}}}
    private static boolean parseUuid(String s){try{UUID.fromString(s);return true;}catch(Exception ex){return false;}}
    private static Object convertLookupValue(String value,String type){validateLookupValueType(value,type);return "NUMBER".equals(type)?new BigDecimal(value):value;}
    private static void validateLookupValueType(String value,String type){try{switch(type){case "TEXT"->{if(value.length()>120)throw invalid("Invalid lookup value.");}case "NUMBER"->new BigDecimal(value);case "UUID"->UUID.fromString(value);default->throw configuration("The lookup source has an unsupported value type.");}}catch(ReportApiException ex){throw ex;}catch(Exception ex){throw configuration("The registered lookup returned a value with an invalid type.");}}
    private static boolean isTimeout(Throwable ex){for(Throwable t=ex;t!=null;t=t.getCause())if(t instanceof SQLException sql&&"57014".equals(sql.getSQLState())||t instanceof org.springframework.dao.QueryTimeoutException)return true;return false;}
    private static boolean isMissingRoutine(Throwable ex){for(Throwable t=ex;t!=null;t=t.getCause())if(t instanceof SQLException sql&&Set.of("42883","3F000").contains(sql.getSQLState()))return true;return false;}
    private void requireActiveMembership(AuthenticatedUser user) {
        if(user==null)throw new AccessDeniedException("An active organization membership is required.");
        Boolean active=jdbc.query("""
            SELECT EXISTS(SELECT 1 FROM operator_memberships_mst m
              JOIN operators_mst o ON o.id=m.operator_id AND o.isactive=1
              JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1
              WHERE m.operator_id=? AND m.external_user_id=? AND m.role=? AND m.isactive=1)
            """,rs->{rs.next();return rs.getBoolean(1);},user.organizationId(),user.externalUserId(),user.role());
        if(!Boolean.TRUE.equals(active))throw new AccessDeniedException("An active organization membership is required.");
    }
    private void audit(AuthenticatedUser user,UUID reportId,UUID requestId,String event,String outcome,long duration,int rows){
        if(auditService!=null)auditService.record(user,reportId,requestId,event,outcome,duration,rows);
        else jdbc.update("INSERT INTO report_query_events_mst(operator_id,report_id,actor_id,request_id,event_type,outcome,duration_ms,returned_row_count) VALUES(?,?,?,?,?,?,?,?)",
            user.organizationId(),reportId,user.externalUserId(),requestId,event,outcome,duration,rows);
    }
    private static long elapsed(long started){return Math.max(0,(System.nanoTime()-started)/1_000_000);}
    private static ReportApiException invalid(String message){return new ReportApiException(HttpStatus.BAD_REQUEST,"INVALID_REPORT_FILTER",message);}
    private static ReportApiException invalidQuery(String message){return new ReportApiException(HttpStatus.BAD_REQUEST,"INVALID_REPORT_QUERY",message);}
    private static ReportApiException configuration(String message){return new ReportApiException(HttpStatus.CONFLICT,"INVALID_REPORT_CONFIGURATION",message);}
    private static ReportApiException notFound(){return new ReportApiException(HttpStatus.NOT_FOUND,"not_found","Report was not found.");}
}
