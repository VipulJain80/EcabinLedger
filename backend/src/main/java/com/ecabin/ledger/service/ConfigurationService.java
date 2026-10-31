package com.ecabin.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecabin.ledger.api.ApiModels.NavigationItem;
import com.ecabin.ledger.api.ApiModels.ConditionOption;
import com.ecabin.ledger.security.Permission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped administration of the operator's operational reference data. */
@Service
public class ConfigurationService {
    public record MenuItem(String key, String label, String description) {}
    public record SaveRequest(String id, Map<String, String> fields) {}
    public record TablePage(List<Map<String,Object>> items, int page, int size, long totalElements, int totalPages) {}
    private record TableQuery(String from, String select, String where, List<Object> args, String orderBy) {}

    private enum Resource {
        ORGANIZATION("organizations", "Organization", "operators_mst", "id,name,application_name AS \"applicationName\",isactive,created_at,updated_at", Map.of("name", "name", "applicationName", "application_name"), false),
        FLEETS("fleets", "Fleets", "fleets_mst", "id,name,isactive,created_at,updated_at", Map.of("name", "name"), true),
        AIRCRAFT("aircraft", "Aircraft", "aircraft_mst", "id,fleet_id AS \"fleetId\",msn_number AS \"msnNumber\",tail_number AS \"tailNumber\",aircraft_type AS \"aircraftType\",isactive,created_at,updated_at", Map.of("fleetId", "fleet_id", "msnNumber", "msn_number", "tailNumber", "tail_number", "aircraftType", "aircraft_type"), true),
        CATEGORIES("defect-categories", "Defect categories", "defect_categories_mst", "id,code,name,description,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "description", "description"), true),
        CONDITIONS("defect-conditions", "Defect conditions", "defect_conditions_mst", "id,code,name,description,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "description", "description"), true),
        AREAS("areas", "Area Master", "cabin_areas_mst", "id,code,name,shortcode,description,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "shortcode", "shortcode", "description", "description"), true),
        COMPONENTS("components", "Components", "cabin_components_mst", "id,code,name,area_id AS \"areaId\",description,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "areaId", "area_id", "description", "description"), true),
        TEAMS("maintenance-teams", "Teams", "maintenance_teams_mst", "id,name,team_type AS \"teamType\",isactive,created_at,updated_at", Map.of("name", "name", "teamType", "team_type"), true),
        USERS("users", "Users and roles", "operator_memberships_mst", "external_user_id AS id,external_user_id AS \"externalUserId\",display_name AS \"displayName\",role,(SELECT COALESCE(string_agg(DISTINCT omt.team_name,'|' ORDER BY omt.team_name),'') FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1 WHERE omt.operator_id=operator_memberships_mst.operator_id AND omt.external_user_id=operator_memberships_mst.external_user_id AND omt.isactive=1) AS \"teamNames\",(SELECT COALESCE(string_agg(DISTINCT mt.id::text,'|' ORDER BY mt.id::text),'') FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1 WHERE omt.operator_id=operator_memberships_mst.operator_id AND omt.external_user_id=operator_memberships_mst.external_user_id AND omt.isactive=1) AS \"teamIds\",isactive,created_at,updated_at", Map.of("externalUserId", "external_user_id", "displayName", "display_name", "role", "role"), true),
        NAVIGATION("menu-items", "Menus & submenus", "navigation_menu_items_mst", "id,menu_key AS key,label,parent_id AS \"parentId\",required_permissions AS \"requiredPermissions\",icon,route_key AS \"routeKey\",display_order AS \"displayOrder\",isactive,created_at,updated_at", Map.of("key", "menu_key", "label", "label", "parentId", "parent_id", "requiredPermissions", "required_permissions", "icon", "icon", "routeKey", "route_key", "displayOrder", "display_order"), true),
        ROLES("roles", "Roles", "operator_roles_mst", "id,role_key AS \"roleKey\",name,description,permissions,isactive,system_role AS \"systemRole\",created_at,updated_at", Map.of("roleKey", "role_key", "name", "name", "description", "description", "permissions", "permissions"), true),
        SEVERITIES("severities", "Severity Master", "defect_severities_mst", "id,severity_code AS code,severity_name AS name,description,sort_order AS \"sortOrder\",background_color AS \"backgroundColor\",text_color AS \"textColor\",border_color AS \"borderColor\",icon,display_style AS \"displayStyle\",isactive,is_default AS \"isDefault\",created_at,updated_at", Map.of("code", "severity_code", "name", "severity_name", "description", "description", "sortOrder", "sort_order", "backgroundColor", "background_color", "textColor", "text_color", "borderColor", "border_color", "icon", "icon", "displayStyle", "display_style"), true),
        PERMISSIONS("permissions", "Permission Master", "permissions_mst", "permission_id AS id,permission_code AS code,permission_name AS name,description,module,is_system_permission AS \"systemPermission\",(permission_code<>'ADMINISTER_ORGANIZATION') AS \"organizationAssignable\",isactive,created_at,updated_at", Map.of(), false),
        ROLE_MAPPINGS("role-permissions", "Role-Permission Mapping", "operator_roles_mst", "id,role_key AS \"roleKey\",name,description,permissions,isactive,system_role AS \"systemRole\",created_at,updated_at", Map.of(), true);

        final String key, label, table, columns;
        final Map<String, String> fields;
        final boolean tenantOwned;
        Resource(String key, String label, String table, String columns, Map<String, String> fields, boolean tenantOwned) {
            this.key = key; this.label = label; this.table = table; this.columns = columns; this.fields = fields; this.tenantOwned = tenantOwned;
        }
        static Resource parse(String key) {
            for (Resource value : values()) if (value.key.equals(key)) return value;
            throw new IllegalArgumentException("Unknown configuration section.");
        }
    }

    private static final Set<String> PERMISSIONS = java.util.Arrays.stream(Permission.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> DEFECT_VIEW_ALL_NAVIGATION_ROUTES = Set.of("home", "operations", "defects");
    private static final Set<String> NAVIGATION_ROUTE_KEYS = Set.of("home","operations","reports","audit","configuration","defects","fleet","quality","organizations","fleets","aircraft","areas","defect-categories","defect-conditions","components","maintenance-teams","reviewer-routing","area-team-mapping","maintenance-sla-policies","review-queue","users","menu-items","roles","severities","permissions","role-permissions","api-rate-limits","user-login-monitoring","report-defect","report-master","email-notifications","workflow-statuses");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private TenantReferenceCache referenceCache;

    public ConfigurationService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setReferenceCache(TenantReferenceCache referenceCache) { this.referenceCache = referenceCache; }

    static boolean isSupportedNavigationRoute(String routeKey) { return NAVIGATION_ROUTE_KEYS.contains(routeKey); }
    static boolean isDefectViewAllNavigationRoute(String routeKey) { return DEFECT_VIEW_ALL_NAVIGATION_ROUTES.contains(routeKey); }
    static boolean canSeeReports(Set<Permission> grants) {
        return grants.contains(Permission.REPORT_VIEW) || grants.contains(Permission.ADMINISTER_ORGANIZATION);
    }

    public List<MenuItem> menu() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("organizations", "Organization", "Operator profile"));
        items.add(new MenuItem("fleets", "Fleets", "Fleet groupings"));
        items.add(new MenuItem("aircraft", "Aircraft", "Aircraft and registrations"));
        items.add(new MenuItem("areas", "Area Master", "Areas used to classify cabin components"));
        items.add(new MenuItem("defect-categories", "Defect categories", "Operational defect classification"));
        items.add(new MenuItem("defect-conditions", "Defect conditions", "Reusable condition labels and component applicability"));
        items.add(new MenuItem("components", "Components", "Cabin equipment reference"));
        items.add(new MenuItem("maintenance-teams", "Teams", "Manage Maintenance and Reviewer teams and user memberships"));
        items.add(new MenuItem("reviewer-routing", "Reviewer routing", "Choose the organization-wide team for new and returned defects"));
        items.add(new MenuItem("area-team-mapping", "Cabin Area–Team Mapping", "Map active cabin areas to eligible Maintenance teams and select the versioned new-defect routing mode"));
        items.add(new MenuItem("maintenance-sla-policies", "Maintenance SLA Policies", "Configure tenant maintenance target-date rules"));
        items.add(new MenuItem("users", "Users and roles", "Operator application memberships"));
        items.add(new MenuItem("menu-items", "Menus & submenus", "Control the organization navigation menu"));
        items.add(new MenuItem("roles", "Roles", "Create application roles and assign permissions"));
        items.add(new MenuItem("severities", "Severity Master", "Manage organization severity labels used by defect reports"));
        items.add(new MenuItem("permissions", "Permission Master", "Read-only catalog of supported application permissions"));
        items.add(new MenuItem("workflow-statuses", "Workflow Status Master", "Manage the global defect status labels and aliases"));
        items.add(new MenuItem("role-permissions", "Role-Permission Mapping", "Review each role’s effective supported permissions and restrictions"));
        items.add(new MenuItem("forms", "Form Master", "Versioned area-specific operational forms"));
        items.add(new MenuItem("report-master", "Report Master", "Configure tenant reports, filters, columns and role access"));
        items.add(new MenuItem("email-notifications", "Email Notifications", "Control organization-wide and workflow email delivery"));
        return List.copyOf(items);
    }

    /** Reads organization menu records and filters them with the authenticated role's server-side grants. */
    public List<NavigationItem> navigation(UUID operatorId, Set<Permission> grants) {
        List<Map<String,Object>> rows=jdbc.query("SELECT id,menu_key AS key,label,parent_id AS \"parentId\",icon,route_key AS section,required_permissions AS \"requiredPermissions\",display_order AS \"displayOrder\" FROM navigation_menu_items_mst WHERE operator_id=? AND isactive=1 ORDER BY display_order,label",new ColumnMapRowMapper(),operatorId);
        Map<String,List<NavigationItem>> children=new LinkedHashMap<>();
        for (Map<String,Object> row:rows) {
            if (!allowed(String.valueOf(row.get("requiredPermissions")),String.valueOf(row.get("section")),grants) || row.get("parentId")==null) continue;
            String parent=String.valueOf(row.get("parentId"));
            children.computeIfAbsent(parent,ignored->new ArrayList<>()).add(new NavigationItem(String.valueOf(row.get("key")),String.valueOf(row.get("label")),String.valueOf(row.get("icon")),String.valueOf(row.get("section")),List.of()));
        }
        List<NavigationItem> result=new ArrayList<>();
        for (Map<String,Object> row:rows) {
            if (row.get("parentId")!=null || !allowed(String.valueOf(row.get("requiredPermissions")),String.valueOf(row.get("section")),grants)) continue;
            String id=String.valueOf(row.get("id"));
            boolean hasChildren=rows.stream().anyMatch(candidate->id.equals(String.valueOf(candidate.get("parentId"))));
            List<NavigationItem> nested=children.getOrDefault(id,List.of());
            if (hasChildren && nested.isEmpty()) continue;
            result.add(new NavigationItem(String.valueOf(row.get("key")),String.valueOf(row.get("label")),String.valueOf(row.get("icon")),String.valueOf(row.get("section")),nested));
        }
        return List.copyOf(result);
    }

    private boolean allowed(String required,String routeKey,Set<Permission> grants) {
        // Reports is a shared shell: organization administrators may open it to
        // configure reports, but report-data APIs still require REPORT_* grants.
        if ("reports".equals(routeKey) && canSeeReports(grants)) return true;
        for(String value:required.split("\\|")) try {
            Permission permission=Permission.valueOf(value);
            if(grants.contains(permission) || (permission==Permission.VIEW && grants.contains(Permission.DEFECT_VIEW_ALL) && isDefectViewAllNavigationRoute(routeKey))) return true;
        } catch(IllegalArgumentException ignored) { /* Invalid grants fail closed. */ }
        return false;
    }

    public List<String> aircraftTypes(UUID operatorId) {
        java.util.function.Supplier<List<String>> load = () -> jdbc.queryForList("SELECT DISTINCT aircraft_type FROM aircraft_mst WHERE operator_id=? AND isactive=1 ORDER BY aircraft_type LIMIT 100", String.class, operatorId);
        return referenceCache == null ? load.get() : referenceCache.get(TenantReferenceCache.Resource.AIRCRAFT_TYPES, operatorId, "active", load);
    }
    public List<String> refreshAircraftTypes(UUID operatorId) {
        java.util.function.Supplier<List<String>> load=()->jdbc.queryForList("SELECT DISTINCT aircraft_type FROM aircraft_mst WHERE operator_id=? AND isactive=1 ORDER BY aircraft_type LIMIT 100",String.class,operatorId);
        return referenceCache==null?load.get():referenceCache.refresh(TenantReferenceCache.Resource.AIRCRAFT_TYPES,operatorId,"active",load);
    }

    /** Includes inactive codes needed to render historical severity badges; access is authorized by the API controller. */
    public List<Map<String,Object>> severityBadges(UUID operatorId) {
        java.util.function.Supplier<List<Map<String,Object>>> load=()->listUncached(operatorId,"severities",true);
        return referenceCache==null?load.get():referenceCache.get(TenantReferenceCache.Resource.SEVERITIES,operatorId,"all",load);
    }

    /** The refresh resource covers both selectable active options and historical badge definitions. */
    public void refreshSeverities(UUID operatorId) {
        refreshList(operatorId,"severities");
        if(referenceCache!=null)referenceCache.refresh(TenantReferenceCache.Resource.SEVERITIES,operatorId,"all",()->listUncached(operatorId,"severities",true));
    }

    /** Only returns active conditions mapped to the selected active tenant-owned component. */
    public List<ConditionOption> conditionsForComponent(UUID operatorId, UUID componentId) {
        java.util.function.Supplier<List<ConditionOption>> load = () -> {
            if (!exists("SELECT 1 FROM cabin_components_mst WHERE operator_id=? AND id=? AND isactive=1", operatorId, componentId))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Active component was not found for this organization.");
            return jdbc.query("SELECT c.id,c.code,c.name,c.description FROM component_conditions_mst m JOIN defect_conditions_mst c ON c.operator_id=m.operator_id AND c.id=m.condition_id AND c.isactive=1 WHERE m.operator_id=? AND m.component_id=? AND m.isactive=1 ORDER BY c.name,c.code", (rs,row) -> new ConditionOption(rs.getObject("id", UUID.class),rs.getString("code"),rs.getString("name"),rs.getString("description")),operatorId,componentId);
        };
        return referenceCache == null ? load.get() : referenceCache.get(TenantReferenceCache.Resource.CONDITIONS, operatorId, "component:" + componentId, load);
    }

    public List<ConditionOption> refreshConditionsForComponent(UUID operatorId, UUID componentId) {
        if(referenceCache==null)return conditionsForComponentUncached(operatorId,componentId);
        return referenceCache.refresh(TenantReferenceCache.Resource.CONDITIONS,operatorId,"component:"+componentId,()->conditionsForComponentUncached(operatorId,componentId));
    }

    private List<ConditionOption> conditionsForComponentUncached(UUID operatorId,UUID componentId) {
        if(!exists("SELECT 1 FROM cabin_components_mst WHERE operator_id=? AND id=? AND isactive=1",operatorId,componentId))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Active component was not found for this organization.");
        return jdbc.query("SELECT c.id,c.code,c.name,c.description FROM component_conditions_mst m JOIN defect_conditions_mst c ON c.operator_id=m.operator_id AND c.id=m.condition_id AND c.isactive=1 WHERE m.operator_id=? AND m.component_id=? AND m.isactive=1 ORDER BY c.name,c.code",(rs,row)->new ConditionOption(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getString("description")),operatorId,componentId);
    }

    public List<Map<String, Object>> list(UUID operatorId, String key, boolean includeInactive) {
        Resource resource = Resource.parse(key);
        TenantReferenceCache.Resource cacheResource = cacheResource(resource);
        if (!includeInactive && referenceCache != null && cacheResource != null)
            return referenceCache.get(cacheResource, operatorId, "active", () -> listUncached(operatorId, key, false));
        return listUncached(operatorId, key, includeInactive);
    }

    public List<Map<String,Object>> refreshList(UUID operatorId, String key) {
        Resource resource = Resource.parse(key);
        TenantReferenceCache.Resource cacheResource = cacheResource(resource);
        if (cacheResource == null) throw new IllegalArgumentException("This configuration resource cannot be manually refreshed.");
        return referenceCache == null ? listUncached(operatorId,key,false)
            : referenceCache.refresh(cacheResource,operatorId,"active",() -> listUncached(operatorId,key,false));
    }

    private List<Map<String,Object>> listUncached(UUID operatorId, String key, boolean includeInactive) {
        Resource resource = Resource.parse(key);
        if (resource == Resource.PERMISSIONS) return jdbc.query("SELECT " + resource.columns + " FROM permissions_mst WHERE (isactive=1 OR ?=true) ORDER BY module,permission_code", new ColumnMapRowMapper(), includeInactive);
        if (resource == Resource.ROLE_MAPPINGS) return rolePermissionRows(operatorId, includeInactive);
        String sql;
        if (resource == Resource.ORGANIZATION) {
            sql = "SELECT " + resource.columns + " FROM operators_mst WHERE id=?" + (includeInactive ? "" : " AND isactive=1") + " LIMIT 1";
            return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
        }
        if (resource == Resource.AIRCRAFT) {
            sql = "SELECT a.id,a.fleet_id AS \"fleetId\",a.msn_number AS \"msnNumber\",a.tail_number AS \"tailNumber\",a.aircraft_type AS \"aircraftType\",f.name AS \"fleetName\",a.isactive,a.created_at,a.updated_at " +
                "FROM aircraft_mst a JOIN fleets_mst f ON f.operator_id=a.operator_id AND f.id=a.fleet_id WHERE a.operator_id=?" +
                (includeInactive ? "" : " AND a.isactive=1") + " ORDER BY a.tail_number LIMIT 200";
            return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
        }
        if (resource == Resource.COMPONENTS) {
            return jdbc.query("SELECT c.id,c.code,c.name,c.area_id AS \"areaId\",a.code AS \"areaCode\",a.name AS \"areaName\",c.description,c.isactive,c.created_at,c.updated_at FROM cabin_components_mst c JOIN cabin_areas_mst a ON a.operator_id=c.operator_id AND a.id=c.area_id WHERE c.operator_id=?" + (includeInactive ? "" : " AND c.isactive=1") + " ORDER BY c.name LIMIT 200", new ColumnMapRowMapper(), operatorId);
        }
        if (resource == Resource.CONDITIONS) {
            return jdbc.query("SELECT c.id,c.code,c.name,c.description,COALESCE(string_agg(DISTINCT m.component_id::text,'|' ORDER BY m.component_id::text),'') AS \"componentIds\",COALESCE(string_agg(DISTINCT p.name,', ' ORDER BY p.name),'') AS \"applicableComponents\",c.isactive,c.created_at,c.updated_at FROM defect_conditions_mst c LEFT JOIN component_conditions_mst m ON m.operator_id=c.operator_id AND m.condition_id=c.id AND m.isactive=1 LEFT JOIN cabin_components_mst p ON p.operator_id=m.operator_id AND p.id=m.component_id AND p.isactive=1 WHERE c.operator_id=?" + (includeInactive ? "" : " AND c.isactive=1") + " GROUP BY c.id ORDER BY c.name LIMIT 200", new ColumnMapRowMapper(), operatorId);
        }
        if (resource == Resource.NAVIGATION) return jdbc.query("SELECT m.id,m.menu_key AS key,m.label,m.parent_id AS \"parentId\",p.label AS \"parentLabel\",m.required_permissions AS \"requiredPermissions\",m.icon,m.route_key AS \"routeKey\",m.display_order AS \"displayOrder\",m.isactive,m.created_at,m.updated_at FROM navigation_menu_items_mst m LEFT JOIN navigation_menu_items_mst p ON p.operator_id=m.operator_id AND p.id=m.parent_id WHERE m.operator_id=?" + (includeInactive ? "" : " AND m.isactive=1") + " ORDER BY m.display_order,m.label LIMIT 200", new ColumnMapRowMapper(), operatorId);
        if (resource == Resource.TEAMS) return jdbc.query("SELECT t.id,t.name,t.team_type AS \"teamType\",t.isactive,t.created_at,t.updated_at,(SELECT count(DISTINCT m.external_user_id) FROM operator_membership_teams_mst omt JOIN operator_memberships_mst m ON m.operator_id=omt.operator_id AND m.external_user_id=omt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE omt.operator_id=t.operator_id AND omt.team_name=t.name AND omt.isactive=1 AND ('|'||r.permissions||'|') LIKE CASE t.team_type WHEN 'REVIEWER' THEN '%|REVIEW|%' WHEN 'CLOSURE' THEN '%|APPROVE|%' ELSE '%|PERFORM_ACTION|%' END) AS \"eligibleMembers\" FROM maintenance_teams_mst t WHERE t.operator_id=?"+(includeInactive?"":" AND t.isactive=1")+" ORDER BY t.name LIMIT 200",new ColumnMapRowMapper(),operatorId);
        sql = "SELECT " + resource.columns + " FROM " + resource.table + " WHERE operator_id=?" +
            (includeInactive ? "" : " AND isactive=1") + " ORDER BY " + orderBy(resource) + " LIMIT 200";
        return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
    }

    private TenantReferenceCache.Resource cacheResource(Resource resource) {
        return switch (resource) {
            case CATEGORIES -> TenantReferenceCache.Resource.CATEGORIES;
            case AREAS -> TenantReferenceCache.Resource.AREAS;
            case COMPONENTS -> TenantReferenceCache.Resource.COMPONENTS;
            case CONDITIONS -> TenantReferenceCache.Resource.CONDITIONS;
            case SEVERITIES -> TenantReferenceCache.Resource.SEVERITIES;
            case FLEETS -> TenantReferenceCache.Resource.FLEETS;
            default -> null;
        };
    }

    private void invalidateReferenceData(UUID operatorId, Resource resource) {
        if (referenceCache == null) return;
        switch (resource) {
            case CATEGORIES -> referenceCache.invalidateAfterCommit(operatorId,TenantReferenceCache.Resource.CATEGORIES,TenantReferenceCache.Resource.FORM_OPTIONS);
            case AREAS -> referenceCache.invalidateAfterCommit(operatorId,TenantReferenceCache.Resource.AREAS,TenantReferenceCache.Resource.COMPONENTS,TenantReferenceCache.Resource.CONDITIONS,TenantReferenceCache.Resource.PUBLISHED_FORMS,TenantReferenceCache.Resource.FORM_OPTIONS);
            case COMPONENTS -> referenceCache.invalidateAfterCommit(operatorId,TenantReferenceCache.Resource.COMPONENTS,TenantReferenceCache.Resource.CONDITIONS,TenantReferenceCache.Resource.FORM_OPTIONS);
            case CONDITIONS -> referenceCache.invalidateAfterCommit(operatorId,TenantReferenceCache.Resource.CONDITIONS,TenantReferenceCache.Resource.FORM_OPTIONS);
            case SEVERITIES -> referenceCache.invalidateAfterCommit(operatorId,TenantReferenceCache.Resource.SEVERITIES,TenantReferenceCache.Resource.FORM_OPTIONS);
            case FLEETS -> referenceCache.invalidateAfterCommit(operatorId,TenantReferenceCache.Resource.FLEETS,TenantReferenceCache.Resource.FORM_OPTIONS);
            case AIRCRAFT -> referenceCache.invalidateAfterCommit(operatorId,TenantReferenceCache.Resource.AIRCRAFT_TYPES);
            default -> { }
        }
    }

    /** Server-side administrative table query; the legacy list API remains available to small reference lookups. */
    @Transactional(readOnly = true)
    public TablePage table(UUID operatorId, String key, int page, int size, String search, String status,
            String role, String area, String aircraftType, String sortBy, String sortDirection) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Choose a valid page and a page size from 1 to 100.");
        TableQuery query = buildTableQuery(operatorId, Resource.parse(key), search, status, role, area, aircraftType, sortBy, sortDirection);
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + query.from() + query.where(), Long.class, query.args().toArray());
        long totalElements = total == null ? 0 : total;
        List<Object> args = new ArrayList<>(query.args()); args.add(size); args.add((long) page * size);
        List<Map<String,Object>> rows = jdbc.query("SELECT " + query.select() + " FROM " + query.from() + query.where() + " ORDER BY " + query.orderBy() + " LIMIT ? OFFSET ?", new ColumnMapRowMapper(), args.toArray());
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / size));
        return new TablePage(rows, page, size, totalElements, totalPages);
    }

    /** Streams an XLSX worksheet from a tenant-scoped PostgreSQL cursor without paging or buffering all rows. */
    @Transactional(readOnly = true)
    public void exportExcel(UUID operatorId, String key, String search, String status, String role, String area,
            String aircraftType, String sortBy, String sortDirection, List<String> requestedColumns, OutputStream output) throws IOException {
        Resource resource = Resource.parse(key);
        TableQuery query = buildTableQuery(operatorId, resource, search, status, role, area, aircraftType, sortBy, sortDirection);
        List<String[]> available = exportColumns(resource);
        List<String[]> columns = requestedColumns == null ? available : requestedColumns.stream().distinct()
            .map(id -> available.stream().filter(column -> column[1].equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This table column cannot be exported: " + id)))
            .toList();
        if (columns.isEmpty()) throw new IllegalArgumentException("Select at least one visible data column to export.");
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + query.from() + query.where(), Long.class, query.args().toArray());
        XlsxStreamWriter writer = new XlsxStreamWriter(output, columns.stream().map(column -> column[0]).toList(), total == null ? 0 : total);
        try {
            PreparedStatementCreator statement = connection -> {
                var prepared = connection.prepareStatement("SELECT " + query.select() + " FROM " + query.from() + query.where() + " ORDER BY " + query.orderBy());
                prepared.setFetchSize(500);
                for (int i=0; i<query.args().size(); i++) prepared.setObject(i+1, query.args().get(i));
                return prepared;
            };
            RowCallbackHandler handler = result -> {
                List<String> values = new ArrayList<>(columns.size());
                for (String[] column : columns) {
                    Object value = result.getObject(column[1]);
                    values.add(value == null ? "" : String.valueOf(value));
                }
                try { writer.writeRow(values); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            };
            jdbc.query(statement, handler);
            writer.finish();
        } catch (UncheckedIOException ex) { throw ex.getCause(); }
    }

    private TableQuery buildTableQuery(UUID operatorId, Resource resource, String search, String status,
            String role, String area, String aircraftType, String sortBy, String sortDirection) {
        String from = tableFrom(resource);
        List<Object> args = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        if (resource == Resource.ORGANIZATION) { predicates.add("o.id=?"); args.add(operatorId); }
        else if (resource != Resource.PERMISSIONS) { predicates.add("r.operator_id=?"); args.add(operatorId); }
        String normalizedStatus = status == null || status.isBlank() ? "ACTIVE" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE","INACTIVE","ALL").contains(normalizedStatus)) throw new IllegalArgumentException("Select a valid status filter.");
        String activeColumn = resource == Resource.ORGANIZATION ? "o.isactive" : "r.isactive";
        if (normalizedStatus.equals("ACTIVE")) predicates.add(activeColumn + "=1");
        else if (normalizedStatus.equals("INACTIVE")) predicates.add(activeColumn + "=2");
        String normalizedSearch = search == null ? "" : search.trim();
        if (normalizedSearch.length() > 200) throw new IllegalArgumentException("Search is limited to 200 characters.");
        if (!normalizedSearch.isEmpty()) {
            List<String> searchColumns = searchColumns(resource);
            predicates.add("(" + String.join(" OR ", searchColumns.stream().map(column -> "POSITION(LOWER(?) IN LOWER(COALESCE(" + column + "::text,''))) > 0").toList()) + ")");
            for (int i=0; i<searchColumns.size(); i++) args.add(normalizedSearch);
        }
        if (role != null && !role.isBlank()) {
            if (resource != Resource.USERS || !exists("SELECT 1 FROM operator_roles_mst WHERE operator_id=? AND role_key=? AND isactive=1", operatorId, role.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Role filtering is not available for this table.");
            predicates.add("r.role=?"); args.add(role.toUpperCase(Locale.ROOT));
        }
        if (aircraftType != null && !aircraftType.isBlank()) {
            if (resource != Resource.AIRCRAFT || aircraftType.length() > 80) throw new IllegalArgumentException("Aircraft type filtering is not available for this table.");
            predicates.add("r.aircraft_type=?"); args.add(aircraftType.trim());
        }
        Map<String,String> sortable = sortableColumns(resource);
        String requestedSort = sortBy == null || sortBy.isBlank() ? defaultSort(resource) : sortBy;
        String column = sortable.get(requestedSort);
        if (column == null) throw new IllegalArgumentException("Sorting is not available for this column.");
        String direction = sortDirection == null || sortDirection.isBlank() ? "ASC" : sortDirection.toUpperCase(Locale.ROOT);
        if (!Set.of("ASC","DESC").contains(direction)) throw new IllegalArgumentException("Choose ascending or descending sort order.");
        String idColumn = resource == Resource.ORGANIZATION ? "o.id" : resource == Resource.USERS ? "r.external_user_id" : resource == Resource.PERMISSIONS ? "r.permission_id" : "r.id";
        String where = predicates.isEmpty() ? " WHERE TRUE" : " WHERE " + String.join(" AND ", predicates);
        return new TableQuery(from, tableSelect(resource), where, List.copyOf(args), column + " " + direction + " NULLS LAST," + idColumn + " ASC");
    }

    private String defaultSort(Resource resource) {
        return switch (resource) {
            case ORGANIZATION, FLEETS, TEAMS -> "name";
            case AIRCRAFT -> "tailNumber";
            case AREAS, CATEGORIES, CONDITIONS, COMPONENTS -> "name";
            case USERS -> "displayName";
            case NAVIGATION -> "displayOrder";
            case ROLES -> "name";
            case SEVERITIES -> "sortOrder";
            case PERMISSIONS -> "code";
            case ROLE_MAPPINGS -> "name";
        };
    }

    private List<Map<String,Object>> rolePermissionRows(UUID operatorId, boolean includeInactive) {
        return jdbc.query("SELECT " + tableSelect(Resource.ROLE_MAPPINGS) + " FROM " + tableFrom(Resource.ROLE_MAPPINGS) + " WHERE r.operator_id=?" + (includeInactive ? "" : " AND r.isactive=1") + " ORDER BY r.name,r.role_key LIMIT 200", new ColumnMapRowMapper(), operatorId);
    }

    private String tableFrom(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> "operators_mst o";
            case AIRCRAFT -> "aircraft_mst r JOIN fleets_mst f ON f.operator_id=r.operator_id AND f.id=r.fleet_id";
            case COMPONENTS -> "cabin_components_mst r JOIN cabin_areas_mst a ON a.operator_id=r.operator_id AND a.id=r.area_id";
            case USERS -> "operator_memberships_mst r LEFT JOIN LATERAL (SELECT max(e.occurred_at) AS last_activity_at,max(e.occurred_at) FILTER (WHERE e.event_type='LOGOUT') AS last_logout_at FROM user_login_events_dtl e WHERE e.operator_id=r.operator_id AND e.external_user_id=r.external_user_id) ev ON true";
            case NAVIGATION -> "navigation_menu_items_mst r LEFT JOIN navigation_menu_items_mst p ON p.operator_id=r.operator_id AND p.id=r.parent_id";
            case ROLES -> "operator_roles_mst r";
            case SEVERITIES -> "defect_severities_mst r";
            case PERMISSIONS -> "permissions_mst r";
            case ROLE_MAPPINGS -> "operator_roles_mst r LEFT JOIN LATERAL (SELECT string_agg(p.permission_code || ' · ' || p.permission_name || E'\\n' || p.description, E'\\n\\n' ORDER BY p.module,p.permission_code) AS permission_details FROM unnest(string_to_array(r.permissions,'|')) AS grants(permission_code) JOIN permissions_mst p ON p.permission_code=grants.permission_code AND p.isactive=1) mapped ON true";
            default -> resource.table + " r";
        };
    }

    private String tableSelect(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> "o.id,o.name,o.application_name AS \"applicationName\",o.isactive,o.created_at,o.updated_at";
            case FLEETS -> "r.id,r.name,r.isactive,r.created_at,r.updated_at";
            case AIRCRAFT -> "r.id,r.msn_number AS \"msnNumber\",r.tail_number AS \"tailNumber\",r.aircraft_type AS \"aircraftType\",r.fleet_id AS \"fleetId\",f.name AS \"fleetName\",r.isactive,r.created_at,r.updated_at";
            case AREAS -> "r.id,r.code,r.name,r.shortcode,r.description,r.isactive,r.created_at,r.updated_at";
            case CATEGORIES -> "r.id,r.code,r.name,r.description,r.isactive,r.created_at,r.updated_at";
            case CONDITIONS -> "r.id,r.code,r.name,r.description,COALESCE((SELECT string_agg(DISTINCT c.name,', ' ORDER BY c.name) FROM component_conditions_mst m JOIN cabin_components_mst c ON c.operator_id=m.operator_id AND c.id=m.component_id AND c.isactive=1 WHERE m.operator_id=r.operator_id AND m.condition_id=r.id AND m.isactive=1),'') AS \"applicableComponents\",r.isactive,r.created_at,r.updated_at";
            case COMPONENTS -> "r.id,r.code,r.name,r.area_id AS \"areaId\",a.code AS \"areaCode\",a.name AS \"areaName\",r.description,r.isactive,r.created_at,r.updated_at";
            case TEAMS -> "r.id,r.name,r.team_type AS \"teamType\",r.isactive,r.created_at,r.updated_at";
            case USERS -> "r.external_user_id AS id,r.external_user_id AS \"externalUserId\",r.display_name AS \"displayName\",r.role,r.last_login_at AS \"lastLoginAt\",ev.last_logout_at AS \"lastLogoutAt\",ev.last_activity_at AS \"lastActivityAt\",CASE WHEN r.last_login_at IS NULL THEN NULL ELSE floor(extract(epoch FROM (now()-r.last_login_at))/86400)::bigint END AS \"daysSinceLogin\",CASE WHEN r.last_login_at IS NULL THEN 'NEVER_LOGGED_IN' WHEN r.last_login_at > now()-interval '30 days' THEN 'ACTIVE_RECENTLY' ELSE 'INACTIVE_30_DAYS' END AS \"inactivityStatus\",COALESCE((SELECT string_agg(DISTINCT omt.team_name,'|' ORDER BY omt.team_name) FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1 WHERE omt.operator_id=r.operator_id AND omt.external_user_id=r.external_user_id AND omt.isactive=1),'') AS \"teamNames\",COALESCE((SELECT string_agg(DISTINCT mt.id::text,'|' ORDER BY mt.id::text) FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1 WHERE omt.operator_id=r.operator_id AND omt.external_user_id=r.external_user_id AND omt.isactive=1),'') AS \"teamIds\",r.isactive,r.created_at,r.updated_at";
            case NAVIGATION -> "r.id,r.menu_key AS key,r.label,r.parent_id AS \"parentId\",p.label AS \"parentLabel\",r.required_permissions AS \"requiredPermissions\",r.icon,r.route_key AS \"routeKey\",r.display_order AS \"displayOrder\",r.isactive,r.created_at,r.updated_at";
            case ROLES -> "r.id,r.role_key AS \"roleKey\",r.name,r.description,r.permissions,r.isactive,r.system_role AS \"systemRole\",r.created_at,r.updated_at";
            case SEVERITIES -> "r.id,r.severity_code AS code,r.severity_name AS name,r.description,r.sort_order AS \"sortOrder\",r.background_color AS \"backgroundColor\",r.text_color AS \"textColor\",r.border_color AS \"borderColor\",r.icon,r.display_style AS \"displayStyle\",r.isactive,r.is_default AS \"isDefault\",r.created_at,r.updated_at";
            case PERMISSIONS -> "r.permission_id AS id,r.permission_code AS code,r.permission_name AS name,r.description,r.module,r.is_system_permission AS \"systemPermission\",(r.permission_code<>'ADMINISTER_ORGANIZATION') AS \"organizationAssignable\",r.isactive,r.created_at,r.updated_at";
            case ROLE_MAPPINGS -> "r.id,r.role_key AS \"roleKey\",r.name,r.description,r.permissions AS \"permissionCodes\",COALESCE(mapped.permission_details,'') AS \"permissionDetails\",r.isactive,r.system_role AS \"systemRole\",r.updated_at";
        };
    }

    private List<String> searchColumns(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> List.of("o.name","o.application_name");
            case FLEETS -> List.of("r.name");
            case TEAMS -> List.of("r.name","r.team_type");
            case AIRCRAFT -> List.of("r.msn_number","r.tail_number","r.aircraft_type");
            case AREAS -> List.of("r.code","r.name","r.shortcode","r.description");
            case CATEGORIES -> List.of("r.code","r.name","r.description");
            case CONDITIONS -> List.of("r.code","r.name","r.description","COALESCE((SELECT string_agg(c.name,', ' ORDER BY c.name) FROM component_conditions_mst m JOIN cabin_components_mst c ON c.operator_id=m.operator_id AND c.id=m.component_id AND c.isactive=1 WHERE m.operator_id=r.operator_id AND m.condition_id=r.id AND m.isactive=1),'')");
            case COMPONENTS -> List.of("r.code","r.name","a.code","a.name");
            case USERS -> List.of("r.external_user_id","r.display_name","r.role");
            case NAVIGATION -> List.of("r.menu_key","r.label","r.required_permissions","r.route_key");
            case ROLES -> List.of("r.role_key","r.name","r.description","r.permissions");
            case SEVERITIES -> List.of("r.severity_code","r.severity_name","r.description");
            case PERMISSIONS -> List.of("r.permission_code","r.permission_name","r.description","r.module");
            case ROLE_MAPPINGS -> List.of("r.role_key","r.name","r.description","r.permissions","mapped.permission_details");
        };
    }

    private Map<String,String> sortableColumns(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> Map.of("name","o.name","applicationName","o.application_name","created_at","o.created_at","updated_at","o.updated_at");
            case FLEETS -> Map.of("name","r.name","created_at","r.created_at","updated_at","r.updated_at");
            case AIRCRAFT -> Map.of("msnNumber","r.msn_number","tailNumber","r.tail_number","aircraftType","r.aircraft_type","fleetName","f.name","created_at","r.created_at");
            case AREAS -> Map.of("code","r.code","name","r.name","shortcode","r.shortcode","description","r.description","created_at","r.created_at");
            case CATEGORIES -> Map.of("code","r.code","name","r.name","description","r.description","created_at","r.created_at");
            case CONDITIONS -> Map.of("code","r.code","name","r.name","description","r.description","applicableComponents","COALESCE((SELECT string_agg(c.name,', ' ORDER BY c.name) FROM component_conditions_mst m JOIN cabin_components_mst c ON c.operator_id=m.operator_id AND c.id=m.component_id AND c.isactive=1 WHERE m.operator_id=r.operator_id AND m.condition_id=r.id AND m.isactive=1),'')","created_at","r.created_at");
            case COMPONENTS -> Map.of("code","r.code","name","r.name","areaCode","a.code","areaName","a.name","created_at","r.created_at");
            case TEAMS -> Map.of("name","r.name","teamType","r.team_type","created_at","r.created_at","updated_at","r.updated_at");
            case USERS -> Map.of("displayName","r.display_name","externalUserId","r.external_user_id","role","r.role","lastLoginAt","r.last_login_at","lastLogoutAt","ev.last_logout_at","lastActivityAt","ev.last_activity_at","daysSinceLogin","r.last_login_at","inactivityStatus","r.last_login_at","created_at","r.created_at");
            case NAVIGATION -> Map.of("label","r.label","key","r.menu_key","parentLabel","p.label","requiredPermissions","r.required_permissions","routeKey","r.route_key","displayOrder","r.display_order","created_at","r.created_at");
            case ROLES -> Map.of("roleKey","r.role_key","name","r.name","description","r.description","permissions","r.permissions","created_at","r.created_at");
            case SEVERITIES -> Map.of("code","r.severity_code","name","r.severity_name","description","r.description","sortOrder","r.sort_order","created_at","r.created_at","updated_at","r.updated_at");
            case PERMISSIONS -> Map.of("code","r.permission_code","name","r.permission_name","description","r.description","module","r.module","created_at","r.created_at");
            case ROLE_MAPPINGS -> Map.of("roleKey","r.role_key","name","r.name","description","r.description","permissionCodes","r.permissions","permissionDetails","mapped.permission_details","updated_at","r.updated_at");
        };
    }

    private List<String[]> exportColumns(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> List.of(new String[]{"Organization","name"},new String[]{"Application name","applicationName"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case FLEETS -> List.of(new String[]{"Fleet","name"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case AIRCRAFT -> List.of(new String[]{"MSN Number","msnNumber"},new String[]{"Registration","tailNumber"},new String[]{"Aircraft type","aircraftType"},new String[]{"Fleet","fleetName"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case AREAS -> List.of(new String[]{"Area code","code"},new String[]{"Area name","name"},new String[]{"Shortcode","shortcode"},new String[]{"Description","description"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case CATEGORIES -> List.of(new String[]{"Code","code"},new String[]{"Category","name"},new String[]{"Description","description"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case CONDITIONS -> List.of(new String[]{"Condition code","code"},new String[]{"Condition","name"},new String[]{"Description","description"},new String[]{"Applicable components","applicableComponents"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case COMPONENTS -> List.of(new String[]{"Code","code"},new String[]{"Component","name"},new String[]{"Area","areaName"},new String[]{"Area code","areaCode"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case TEAMS -> List.of(new String[]{"Team","name"},new String[]{"Type","teamType"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case USERS -> List.of(new String[]{"Name","displayName"},new String[]{"Enterprise identity","externalUserId"},new String[]{"Role","role"},new String[]{"Teams","teamNames"},new String[]{"Last login","lastLoginAt"},new String[]{"Last logout","lastLogoutAt"},new String[]{"Days since login","daysSinceLogin"},new String[]{"Inactivity status","inactivityStatus"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case NAVIGATION -> List.of(new String[]{"Menu / submenu","label"},new String[]{"Key","key"},new String[]{"Parent menu","parentLabel"},new String[]{"Visible permissions","requiredPermissions"},new String[]{"Application section","routeKey"},new String[]{"Order","displayOrder"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case ROLES -> List.of(new String[]{"Role key","roleKey"},new String[]{"Role","name"},new String[]{"Description","description"},new String[]{"Permissions","permissions"},new String[]{"System role","systemRole"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case SEVERITIES -> List.of(new String[]{"Code","code"},new String[]{"Severity","name"},new String[]{"Description","description"},new String[]{"Order","sortOrder"},new String[]{"Background color","backgroundColor"},new String[]{"Text color","textColor"},new String[]{"Border color","borderColor"},new String[]{"Icon","icon"},new String[]{"Display style","displayStyle"},new String[]{"Default","isDefault"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case PERMISSIONS -> List.of(new String[]{"Code","code"},new String[]{"Permission","name"},new String[]{"Description","description"},new String[]{"Module","module"},new String[]{"System permission","systemPermission"},new String[]{"Status","isactive"});
            case ROLE_MAPPINGS -> List.of(new String[]{"Role key","roleKey"},new String[]{"Role","name"},new String[]{"Permission codes","permissionCodes"},new String[]{"Permission descriptions","permissionDetails"},new String[]{"Built-in","systemRole"},new String[]{"Updated","updated_at"});
        };
    }

    @Transactional
    public Map<String, Object> save(UUID operatorId, String actorId, String key, SaveRequest request) {
        setAuditActor(actorId);
        Resource resource = Resource.parse(key);
        if (resource == Resource.PERMISSIONS || resource == Resource.ROLE_MAPPINGS) throw new AccessDeniedException("The supported permission catalog and mapping view are read-only.");
        boolean creating = request.id() == null || request.id().isBlank();
        boolean editingAdministratorRole = resource == Resource.ROLES && !creating
            && exists("SELECT 1 FROM operator_roles_mst WHERE operator_id=? AND id=? AND role_key='ADMIN' AND isactive=1", operatorId, parseUuid(request.id()));
        Map<String, String> values = validate(resource, request.fields(), editingAdministratorRole);
        List<UUID> conditionComponentIds = resource == Resource.CONDITIONS ? requireConditionComponents(operatorId, request.fields().get("componentIds")) : List.of();
        if (resource == Resource.ORGANIZATION && creating) throw new IllegalArgumentException("An organization cannot be created from its own configuration page.");
        if (resource == Resource.ORGANIZATION && !operatorId.toString().equals(request.id())) throw new AccessDeniedException("Only the current organization can be updated.");
        if (resource == Resource.USERS && creating && "ADMIN".equals(values.get("role")) && !isAdmin(operatorId, actorId)) {
            throw new AccessDeniedException("Only an organization administrator can grant the ADMIN role.");
        }
        if (resource == Resource.USERS && "SUPERADMIN".equals(values.get("role"))) throw new IllegalArgumentException("SUPERADMIN is a platform-controlled role and cannot be assigned through organization membership management.");
        if (resource == Resource.USERS && !exists("SELECT 1 FROM operator_roles_mst WHERE operator_id=? AND role_key=? AND isactive=1", operatorId, values.get("role"))) throw new IllegalArgumentException("Choose an active role from the Roles master.");
        if (resource == Resource.USERS && !creating && values.containsKey("externalUserId")) {
            throw new IllegalArgumentException("A user's enterprise identity cannot be changed.");
        }
        UUID entityUuid = null;
        String entityId = request.id();
        if (resource != Resource.USERS) {
            if (!creating) entityUuid = parseUuid(request.id());
            else entityUuid = UUID.randomUUID();
            entityId = entityUuid.toString();
        } else if (creating) entityId = values.get("externalUserId");
        if (resource == Resource.AIRCRAFT) requireActiveFleet(operatorId, values.get("fleetId"));
        if (resource == Resource.COMPONENTS) requireActiveArea(operatorId, values.get("areaId"));
        if (resource == Resource.AREAS && exists("SELECT 1 FROM cabin_areas_mst WHERE operator_id=? AND shortcode=? AND isactive IN (1,2) AND id<>COALESCE(CAST(? AS uuid),'00000000-0000-0000-0000-000000000000'::uuid)", operatorId, values.get("shortcode"), creating ? null : request.id()))
            throw new IllegalArgumentException("This area shortcode is already in use in your organization. Choose a different shortcode.");
        List<UUID> selectedTeamIds = resource == Resource.USERS && request.fields().containsKey("teamIds")
            ? validateTeamIds(operatorId, request.fields().get("teamIds")) : null;

        if (resource == Resource.USERS && !creating) {
            jdbc.query("SELECT external_user_id FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=? AND isactive=1 FOR UPDATE",
                (ResultSetExtractor<Boolean>) rs -> rs.next(), operatorId, entityId);
        }

        Map<String, Object> previous = creating ? null : find(resource, operatorId, entityId);
        if (!creating && previous == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Configuration record was not found.");
        if (!creating && ((Number) previous.get("isactive")).intValue() != 1) throw new IllegalArgumentException("Reactivate this record before editing it.");
        if (resource == Resource.SEVERITIES && !creating && !String.valueOf(previous.get("code")).equals(values.get("code"))) throw new IllegalArgumentException("Severity codes are stable identifiers and cannot be changed after creation.");
        if(resource==Resource.TEAMS&&!creating&&!String.valueOf(previous.get("name")).equals(values.get("name"))&&(exists("SELECT 1 FROM operator_membership_teams_mst WHERE operator_id=? AND team_name=? LIMIT 1",operatorId,previous.get("name"))||exists("SELECT 1 FROM corrective_actions_dtl WHERE operator_id=? AND assigned_team=? LIMIT 1",operatorId,previous.get("name"))||exists("SELECT 1 FROM defects_dtl WHERE operator_id=? AND reviewer_team_id=? LIMIT 1",operatorId,entityUuid)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"A team with membership or assignment history cannot be renamed. Create the replacement team, move active memberships and work, then deactivate the old team.");
        if (resource == Resource.COMPONENTS && !creating && !String.valueOf(previous.get("areaId")).equals(values.get("areaId")) && exists("SELECT 1 FROM defects_dtl WHERE operator_id=? AND component_id=? LIMIT 1", operatorId, entityUuid))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "This component is referenced by defect history; its area cannot be changed.");
        if (resource == Resource.NAVIGATION) {
            String oldParent = previous == null || previous.get("parentId") == null ? "" : String.valueOf(previous.get("parentId"));
            String newParent = values.getOrDefault("parentId", oldParent);
            if (creating || !oldParent.equals(newParent)) validateMenuParent(operatorId, creating ? "00000000-0000-0000-0000-000000000000" : entityId, values);
        }
        if (resource == Resource.ROLES) {
            if (!creating && values.containsKey("roleKey") && !String.valueOf(previous.get("roleKey")).equals(values.get("roleKey"))) throw new IllegalArgumentException("A role key cannot be changed after creation.");
        }
        if (creating) insert(resource, operatorId, entityId, values);
        else update(resource, operatorId, entityId, values);
        if (resource == Resource.CONDITIONS) synchronizeConditionComponents(operatorId, entityUuid, conditionComponentIds);
        if (resource == Resource.USERS && selectedTeamIds != null) synchronizeUserTeams(operatorId, actorId, entityId, selectedTeamIds);
        Map<String, Object> current = find(resource, operatorId, entityId);
        audit(operatorId, actorId, resource, entityId, creating ? "MASTER_DATA_CREATED" : "MASTER_DATA_UPDATED",
            creating ? "Created " + resource.label.toLowerCase() + " record." : "Updated " + resource.label.toLowerCase() + " record.", previous, current);
        invalidateReferenceData(operatorId, resource);
        return current;
    }

    /** Applies the same field and tenant relationship validation used by interactive master maintenance. */
    public Map<String, String> validateImportRow(UUID operatorId, String key, Map<String, String> fields) {
        Resource resource = Resource.parse(key);
        if (resource == Resource.ORGANIZATION) throw new IllegalArgumentException("Organization data is provisioned through the operator onboarding workflow and cannot be imported.");
        Map<String, String> values = validate(resource, fields);
        if (resource == Resource.AIRCRAFT) requireActiveFleet(operatorId, values.get("fleetId"));
        if (resource == Resource.COMPONENTS) requireActiveArea(operatorId, values.get("areaId"));
        if (resource == Resource.CONDITIONS) values.put("componentIds", requireConditionComponents(operatorId, fields.get("componentIds")).stream().map(UUID::toString).collect(java.util.stream.Collectors.joining("|")));
        if (resource == Resource.USERS && !exists("SELECT 1 FROM operator_roles_mst WHERE operator_id=? AND role_key=? AND isactive=1", operatorId, values.get("role")))
            throw new IllegalArgumentException("Choose an active role from the Roles master.");
        if (resource == Resource.NAVIGATION) validateMenuParent(operatorId, "00000000-0000-0000-0000-000000000000", values);
        if (resource == Resource.ROLES && "ADMIN".equals(values.get("roleKey"))) throw new IllegalArgumentException("The built-in Administrator role cannot be imported.");
        return values;
    }

    @Transactional
    public void deactivate(UUID operatorId, String actorId, String key, String id, String reason) {
        setAuditActor(actorId);
        Resource resource = Resource.parse(key);
        if (resource == Resource.PERMISSIONS || resource == Resource.ROLE_MAPPINGS) throw new AccessDeniedException("The supported permission catalog and mapping view are read-only.");
        if (resource == Resource.ORGANIZATION) throw new IllegalArgumentException("The organization record cannot be soft-deleted here.");
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("A reason of up to 1000 characters is required.");
        String entityId = id;
        if (resource != Resource.USERS) entityId = parseUuid(id).toString();
        if (resource == Resource.USERS || resource == Resource.SEVERITIES) {
            // Serialize membership deactivation within the tenant so concurrent requests cannot both remove
            // the final active administrator after independently observing a count of two. Severity default
            // changes use the same organization lock so a default cannot be set concurrently with its deactivation.
            jdbc.query("SELECT id FROM operators_mst WHERE id=? FOR UPDATE", (ResultSetExtractor<Boolean>) rs -> rs.next(), operatorId);
        }
        Map<String, Object> previous = find(resource, operatorId, entityId);
        if (previous == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Configuration record was not found or is already inactive.");
        if (((Number) previous.get("isactive")).intValue() != 1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Configuration record was not found or is already inactive.");
        if (resource == Resource.FLEETS && exists("SELECT 1 FROM aircraft_mst WHERE operator_id=? AND fleet_id=? AND isactive=1 LIMIT 1", operatorId, UUID.fromString(entityId)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Move or deactivate the fleet's aircraft before deactivating this fleet.");
        if (resource == Resource.AREAS && (exists("SELECT 1 FROM cabin_components_mst WHERE operator_id=? AND area_id=? AND isactive=1 LIMIT 1", operatorId, UUID.fromString(entityId)) || exists("SELECT 1 FROM defects_dtl WHERE operator_id=? AND area_id=? AND isactive=1 AND status NOT IN ('CLOSED','CANCELLED','REJECTED') LIMIT 1", operatorId, UUID.fromString(entityId))))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Deactivate or reassign active components and resolve open defects in this area first.");
        if (resource == Resource.AIRCRAFT && exists("SELECT 1 FROM defects_dtl WHERE operator_id=? AND aircraft_id=? LIMIT 1", operatorId, UUID.fromString(entityId)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Aircraft with defect history cannot be deactivated.");
        if (resource == Resource.TEAMS && (exists("SELECT 1 FROM corrective_actions_dtl WHERE operator_id=? AND assigned_team=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') LIMIT 1", operatorId, previous.get("name")) || exists("SELECT 1 FROM operators_mst WHERE id=? AND (default_reviewer_team_id=? OR default_closure_team_id=?)", operatorId, UUID.fromString(entityId),UUID.fromString(entityId)) || exists("SELECT 1 FROM defects_dtl WHERE operator_id=? AND reviewer_team_id=? AND isactive=1 AND status NOT IN ('CLOSED','CANCELLED') LIMIT 1", operatorId, UUID.fromString(entityId))))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Reassign active work and update the default Reviewer route before deactivating this team.");
        if (resource == Resource.USERS) {
            if (actorId.equals(entityId)) throw new IllegalArgumentException("You cannot deactivate your own membership.");
            if (exists("SELECT 1 FROM corrective_actions_dtl WHERE operator_id=? AND assigned_user_id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') LIMIT 1", operatorId, entityId))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Reassign open corrective actions before deactivating this user.");
            if (exists("SELECT 1 FROM inspections_dtl WHERE operator_id=? AND inspector_user_id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS','ON_HOLD') LIMIT 1", operatorId, entityId))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Reassign open inspections before deactivating this user.");
            Map<String, Object> row = previous;
            if ("ADMIN".equals(row.get("role")) && jdbc.queryForObject("SELECT count(*) FROM operator_memberships_mst WHERE operator_id=? AND role='ADMIN' AND isactive=1", Integer.class, operatorId) <= 1)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "The organization must retain at least one active administrator.");
        }
        if (resource == Resource.NAVIGATION && exists("SELECT 1 FROM navigation_menu_items_mst WHERE operator_id=? AND parent_id=? AND isactive=1 LIMIT 1", operatorId, UUID.fromString(entityId)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Deactivate or move this menu's submenus before deactivating it.");
        if (resource == Resource.TEAMS && "MAINTENANCE".equals(previous.get("teamType")) && exists("SELECT 1 FROM area_maintenance_team_mappings_mst WHERE operator_id=? AND maintenance_team_id=? AND isactive=1 LIMIT 1", operatorId, UUID.fromString(entityId)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Deactivate or replace active cabin-area mappings before deactivating this Maintenance team.");
        if (resource == Resource.ROLES && ("ADMIN".equals(previous.get("roleKey")) || exists("SELECT 1 FROM operator_memberships_mst WHERE operator_id=? AND role=? AND isactive=1 LIMIT 1", operatorId, previous.get("roleKey"))))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "The Administrator role is protected. Reassign active users before deactivating another role.");
        String sql = resource == Resource.SEVERITIES
            ? "UPDATE defect_severities_mst SET isactive=2,is_default=false,updated_at=now() WHERE operator_id=? AND id=? AND isactive=1"
            : "UPDATE " + resource.table + " SET isactive=2,updated_at=now() WHERE operator_id=? AND " + (resource == Resource.USERS ? "external_user_id" : "id") + "=? AND isactive=1";
        int changed = jdbc.update(sql, operatorId, resource == Resource.USERS ? entityId : UUID.fromString(entityId));
        if (changed != 1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "The record changed while this request was being processed. Refresh and try again.");
        if (resource == Resource.TEAMS) {
            String teamName = String.valueOf(previous.get("name"));
            jdbc.update("UPDATE operator_membership_teams_mst SET isactive=2 WHERE operator_id=? AND team_name=? AND isactive=1", operatorId, teamName);
        }
        Map<String, Object> current = find(resource, operatorId, entityId);
        audit(operatorId, actorId, resource, entityId, "MASTER_DATA_DEACTIVATED", reason.trim(), previous, current);
        invalidateReferenceData(operatorId, resource);
    }

    @Transactional
    public void activate(UUID operatorId, String actorId, String key, String id, String reason) {
        setAuditActor(actorId);
        Resource resource = Resource.parse(key);
        if (resource == Resource.PERMISSIONS || resource == Resource.ROLE_MAPPINGS) throw new AccessDeniedException("The supported permission catalog and mapping view are read-only.");
        if (resource == Resource.ORGANIZATION) throw new IllegalArgumentException("The organization record is already managed as active.");
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("A reason of up to 1000 characters is required.");
        String entityId = resource == Resource.USERS ? id : parseUuid(id).toString();
        Map<String, Object> previous = find(resource, operatorId, entityId);
        if (previous == null || ((Number) previous.get("isactive")).intValue() != 2)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Inactive configuration record was not found.");
        if (resource == Resource.NAVIGATION && previous.get("parentId") != null && !exists("SELECT 1 FROM navigation_menu_items_mst WHERE operator_id=? AND id=? AND isactive=1", operatorId, previous.get("parentId")))
            throw new IllegalArgumentException("Reactivate the parent menu before reactivating this submenu.");
        if (resource == Resource.COMPONENTS) requireActiveArea(operatorId, String.valueOf(previous.get("areaId")));
        String idField = resource == Resource.USERS ? "external_user_id" : "id";
        Object recordId = resource == Resource.USERS ? entityId : UUID.fromString(entityId);
        jdbc.update("UPDATE " + resource.table + " SET isactive=1,updated_at=now() WHERE operator_id=? AND " + idField + "=? AND isactive=2", operatorId, recordId);
        Map<String, Object> current = find(resource, operatorId, entityId);
        audit(operatorId, actorId, resource, entityId, "MASTER_DATA_ACTIVATED", reason.trim(), previous, current);
        invalidateReferenceData(operatorId, resource);
    }

    /** Sets the tenant's sole active default severity without accepting a tenant ID from the request body. */
    @Transactional
    public Map<String,Object> setDefaultSeverity(UUID operatorId, String actorId, String id) {
        setAuditActor(actorId);
        UUID severityId = parseUuid(id);
        boolean organizationExists = Boolean.TRUE.equals(jdbc.query(
            "SELECT id FROM operators_mst WHERE id=? AND isactive=1 FOR UPDATE",
            (ResultSetExtractor<Boolean>) rs -> rs.next(), operatorId));
        if (!organizationExists) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Organization was not found.");

        Map<String,Object> target = find(Resource.SEVERITIES, operatorId, severityId.toString());
        if (target == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Severity was not found.");
        if (((Number) target.get("isactive")).intValue() != 1) throw new IllegalArgumentException("Only an active severity can be selected as the default.");

        UUID currentDefaultId = jdbc.query(
            "SELECT id FROM defect_severities_mst WHERE operator_id=? AND is_default=true",
            (ResultSetExtractor<UUID>) rs -> rs.next() ? rs.getObject("id", UUID.class) : null, operatorId);
        if (severityId.equals(currentDefaultId)) return target;

        jdbc.update("UPDATE defect_severities_mst SET is_default=false,updated_at=now() WHERE operator_id=? AND is_default=true", operatorId);
        int updated = jdbc.update("UPDATE defect_severities_mst SET is_default=true,updated_at=now() WHERE operator_id=? AND id=? AND isactive=1", operatorId, severityId);
        if (updated != 1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "The severity changed while the default was being updated. Refresh and try again.");

        Map<String,Object> previousState = new LinkedHashMap<>();
        previousState.put("defaultSeverityId", currentDefaultId);
        Map<String,Object> newState = new LinkedHashMap<>();
        newState.put("defaultSeverityId", severityId);
        audit(operatorId, actorId, Resource.SEVERITIES, severityId.toString(), "MASTER_DATA_DEFAULT_CHANGED",
            "Changed the organization's default severity.", previousState, newState);
        invalidateReferenceData(operatorId, Resource.SEVERITIES);
        return find(Resource.SEVERITIES, operatorId, severityId.toString());
    }

    private Map<String, String> validate(Resource resource, Map<String, String> fields) {
        return validate(resource, fields, false);
    }

    private Map<String, String> validate(Resource resource, Map<String, String> fields, boolean editingAdministratorRole) {
        if (fields == null) throw new IllegalArgumentException("Configuration fields are required.");
        Set<String> allowedFields = new java.util.HashSet<>(resource.fields.keySet());
        if (resource == Resource.CONDITIONS) allowedFields.add("componentIds");
        if (resource == Resource.USERS) allowedFields.add("teamIds");
        for (String key : fields.keySet()) if (!allowedFields.contains(key)) throw new IllegalArgumentException("Unsupported field for this configuration section: " + key);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if ((resource == Resource.CONDITIONS && entry.getKey().equals("componentIds")) || (resource == Resource.USERS && entry.getKey().equals("teamIds"))) continue;
            if (value.isEmpty() && !(resource == Resource.SEVERITIES && entry.getKey().equals("icon")) && !(Set.of(Resource.CATEGORIES, Resource.AREAS, Resource.COMPONENTS, Resource.CONDITIONS).contains(resource) && entry.getKey().equals("description")) && !(resource == Resource.NAVIGATION && entry.getKey().equals("parentId"))) continue;
            if (value.length() > maxLength(resource, entry.getKey())) throw new IllegalArgumentException(entry.getKey() + " is too long.");
            result.put(entry.getKey(), value);
        }
        Set<String> required = switch (resource) {
            case ORGANIZATION, FLEETS, TEAMS -> Set.of("name");
            case AIRCRAFT -> Set.of("fleetId", "tailNumber", "aircraftType");
            case AREAS -> Set.of("code", "name", "shortcode");
            case CATEGORIES, CONDITIONS -> Set.of("code", "name");
            case COMPONENTS -> Set.of("code", "name", "areaId");
            case USERS -> fields.containsKey("externalUserId") ? Set.of("externalUserId", "displayName", "role") : Set.of("displayName", "role");
            case NAVIGATION -> Set.of("key", "label", "requiredPermissions", "icon", "routeKey", "displayOrder");
            case ROLES -> fields.containsKey("roleKey") ? Set.of("roleKey", "name", "description", "permissions") : Set.of("name", "description", "permissions");
            case SEVERITIES -> Set.of("code","name","description","sortOrder","backgroundColor","textColor","borderColor","displayStyle");
            case PERMISSIONS, ROLE_MAPPINGS -> throw new AccessDeniedException("This configuration view is read-only.");
        };
        for (String field : required) if (!result.containsKey(field)) throw new IllegalArgumentException(field + " is required.");
        if (resource == Resource.AIRCRAFT) result.put("fleetId", parseUuid(result.get("fleetId")).toString());
        if (resource == Resource.COMPONENTS) result.put("areaId", parseUuid(result.get("areaId")).toString());
        if (resource == Resource.NAVIGATION) {
            result.put("key", result.get("key").toLowerCase(Locale.ROOT));
            result.put("routeKey", result.get("routeKey").toLowerCase(Locale.ROOT));
            if (!result.get("key").matches("[a-z0-9][a-z0-9-]{0,79}") || !result.get("routeKey").matches("[a-z0-9][a-z0-9-]{0,79}")) throw new IllegalArgumentException("Menu key and route must use lowercase letters, numbers and hyphens.");
            if (!isSupportedNavigationRoute(result.get("routeKey"))) throw new IllegalArgumentException("Choose an application section that is supported by this MVP.");
            String normalized=java.util.Arrays.stream(result.get("requiredPermissions").toUpperCase(Locale.ROOT).split("[,|]"))
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().peek(value -> { try { Permission.valueOf(value); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Select valid application permissions."); } }).collect(java.util.stream.Collectors.joining("|"));
            if (normalized.isBlank()) throw new IllegalArgumentException("At least one visibility permission is required.");
            result.put("requiredPermissions", normalized);
            try { int order=Integer.parseInt(result.get("displayOrder")); if(order<0 || order>9999) throw new NumberFormatException(); result.put("displayOrder",String.valueOf(order)); }
            catch(NumberFormatException ex) { throw new IllegalArgumentException("Display order must be between 0 and 9999."); }
            if (result.containsKey("parentId") && !result.get("parentId").isBlank()) result.put("parentId",parseUuid(result.get("parentId")).toString());
        }
        if (resource == Resource.ROLES) {
            if (fields.containsKey("roleKey")) {
                result.put("roleKey", result.get("roleKey").toUpperCase(Locale.ROOT).replace(' ', '_'));
                if (!result.get("roleKey").matches("[A-Z][A-Z0-9_]{1,47}")) throw new IllegalArgumentException("Role key must start with a letter and contain only letters, numbers, and underscores.");
                if ("SUPERADMIN".equals(result.get("roleKey"))) throw new IllegalArgumentException("SUPERADMIN is reserved for controlled platform provisioning.");
            }
            Set<String> granted = java.util.Arrays.stream(result.get("permissions").toUpperCase(Locale.ROOT).split("[|,]"))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toSet());
            if (granted.isEmpty() || !PERMISSIONS.containsAll(granted)) throw new IllegalArgumentException("Select at least one valid application permission.");
            if (granted.contains("ADMINISTER_ORGANIZATION") && !editingAdministratorRole) throw new IllegalArgumentException("Organization administration is reserved to the built-in Administrator role.");
            if (editingAdministratorRole && !granted.contains("ADMINISTER_ORGANIZATION")) throw new IllegalArgumentException("The built-in Administrator role must retain organization-administration permission.");
            result.put("permissions", granted.stream().sorted().collect(java.util.stream.Collectors.joining("|")));
        }
        if (resource == Resource.TEAMS) {
            String type=result.getOrDefault("teamType","MAINTENANCE").toUpperCase(Locale.ROOT);
            if (!Set.of("MAINTENANCE","REVIEWER","CLOSURE").contains(type)) throw new IllegalArgumentException("Team type must be MAINTENANCE, REVIEWER, or CLOSURE.");
            result.put("teamType",type);
        }
        if (resource == Resource.USERS && fields.containsKey("teamIds")) {
            List<UUID> ids = parseTeamIds(fields.get("teamIds"));
            result.put("teamIds", ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining("|")));
        }
        if (resource == Resource.USERS) {
            result.put("role", result.get("role").toUpperCase(Locale.ROOT));
            if ("SUPERADMIN".equals(result.get("role"))) throw new IllegalArgumentException("SUPERADMIN is a platform-controlled role and cannot be assigned through organization membership management.");
            if (result.containsKey("externalUserId") && !result.get("externalUserId").matches("[A-Za-z0-9._@+-]{1,200}")) throw new IllegalArgumentException("Enter a valid enterprise user identity.");
        }
        if (Set.of(Resource.AREAS, Resource.CATEGORIES, Resource.COMPONENTS, Resource.CONDITIONS, Resource.SEVERITIES).contains(resource)) {
            String code = result.get("code").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (!code.matches("[A-Z0-9_]{1," + (resource == Resource.SEVERITIES ? "24" : "48") + "}")) throw new IllegalArgumentException("Code may contain uppercase letters, digits and underscores.");
            result.put("code", code);
        }
        if (resource == Resource.AREAS) {
            result.put("shortcode", AreaShortcodeRules.normalize(result.get("shortcode")));
        }
        if (resource == Resource.SEVERITIES) {
            try { int order=Integer.parseInt(result.get("sortOrder")); if(order<0||order>9999) throw new NumberFormatException(); result.put("sortOrder",String.valueOf(order)); }
            catch(NumberFormatException ex) { throw new IllegalArgumentException("Display order must be between 0 and 9999."); }
            for (String key : List.of("backgroundColor", "textColor", "borderColor")) if (!result.getOrDefault(key, "").matches("^#[0-9A-Fa-f]{6}$")) throw new IllegalArgumentException("Enter a valid six-digit hex color for " + key + ".");
            String displayStyle = result.getOrDefault("displayStyle", "SOLID").toUpperCase(Locale.ROOT);
            if (!Set.of("SOLID", "OUTLINE", "SOFT").contains(displayStyle)) throw new IllegalArgumentException("Display style must be SOLID, OUTLINE, or SOFT.");
            result.put("displayStyle", displayStyle);
            if (result.getOrDefault("icon", "").length() > 32 || result.getOrDefault("icon", "").codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Badge icon must be 32 characters or fewer and contain no control characters.");
            BadgeColorRules.requireAccessible(result.get("backgroundColor"), result.get("textColor"), result.get("borderColor"), displayStyle);
        }
        return result;
    }

    private int maxLength(Resource resource, String field) {
        if (field.equals("roleKey")) return 48;
        if (field.equals("permissions")) return 500;
        if ((resource == Resource.ROLES || resource == Resource.SEVERITIES) && field.equals("name")) return 100;
        if (field.equals("description")) return 500;
        if (field.equals("displayName")) return 160;
        if (field.equals("externalUserId")) return 200;
        if (field.equals("teamIds")) return 2000;
        if (field.equals("aircraftType")) return 80;
        if (field.equals("tailNumber") || field.equals("msnNumber")) return 16;
        if (field.equals("code")) return resource == Resource.SEVERITIES ? 24 : 48;
        if (resource == Resource.AREAS && field.equals("shortcode")) return 10;
        if (field.equals("area")) return 32;
        if (field.equals("role")) return 48;
        return resource == Resource.ORGANIZATION ? 160 : 120;
    }

    private void requireActiveFleet(UUID operatorId, String fleetId) {
        UUID id = UUID.fromString(fleetId);
        if (!exists("SELECT 1 FROM fleets_mst WHERE operator_id=? AND id=? AND isactive=1", operatorId, id)) throw new IllegalArgumentException("Select an active fleet belonging to this organization.");
    }

    private void requireActiveArea(UUID operatorId, String areaId) {
        UUID id = UUID.fromString(areaId);
        if (!exists("SELECT 1 FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1", operatorId, id))
            throw new IllegalArgumentException("Choose an active area belonging to this organization.");
    }

    private List<UUID> requireConditionComponents(UUID operatorId, String componentIds) {
        if (componentIds == null || componentIds.isBlank()) throw new IllegalArgumentException("Select at least one applicable active component.");
        List<UUID> ids = new ArrayList<>();
        for (String raw : componentIds.split("\\|")) {
            UUID id = parseUuid(raw.trim());
            if (ids.contains(id)) throw new IllegalArgumentException("Each applicable component can be selected only once.");
            if (!exists("SELECT 1 FROM cabin_components_mst WHERE operator_id=? AND id=? AND isactive=1", operatorId, id))
                throw new IllegalArgumentException("Every applicable component must be active and belong to this organization.");
            ids.add(id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Select at least one applicable active component.");
        return List.copyOf(ids);
    }

    private void synchronizeConditionComponents(UUID operatorId, UUID conditionId, List<UUID> selected) {
        List<UUID> current = jdbc.query("SELECT component_id FROM component_conditions_mst WHERE operator_id=? AND condition_id=? AND isactive=1", (rs,row) -> rs.getObject(1, UUID.class), operatorId, conditionId);
        for (UUID componentId : current) {
            if (!selected.contains(componentId)) jdbc.update("UPDATE component_conditions_mst SET isactive=2,updated_at=now() WHERE operator_id=? AND condition_id=? AND component_id=? AND isactive=1", operatorId, conditionId, componentId);
        }
        for (UUID componentId : selected) {
            if (!current.contains(componentId)) jdbc.update("INSERT INTO component_conditions_mst(operator_id,component_id,condition_id) VALUES(?,?,?) ON CONFLICT(operator_id,component_id,condition_id) WHERE isactive=1 DO NOTHING", operatorId, componentId, conditionId);
        }
    }

    private List<UUID> parseTeamIds(String teamIds) {
        if (teamIds == null || teamIds.isBlank()) return List.of();
        List<UUID> ids = new ArrayList<>();
        for (String raw : teamIds.split("\\|")) {
            UUID id = parseUuid(raw.trim());
            if (ids.contains(id)) throw new IllegalArgumentException("Each team may only be selected once.");
            ids.add(id);
        }
        if (ids.size() > 50) throw new IllegalArgumentException("Choose no more than 50 teams.");
        return List.copyOf(ids);
    }

    private List<UUID> validateTeamIds(UUID operatorId, String teamIds) {
        List<UUID> ids = parseTeamIds(teamIds);
        for (UUID id : ids) if (!exists("SELECT 1 FROM maintenance_teams_mst WHERE operator_id=? AND id=? AND isactive=1", operatorId, id))
            throw new IllegalArgumentException("Every selected team must be active and belong to this organization.");
        return ids;
    }

    /** Synchronizes only changed memberships and keeps removals as inactive historical rows. */
    private void synchronizeUserTeams(UUID operatorId, String actorId, String externalUserId, List<UUID> selectedTeamIds) {
        record CurrentTeam(UUID id, String name) {}
        List<CurrentTeam> current = jdbc.query("SELECT t.id,omt.team_name FROM operator_membership_teams_mst omt LEFT JOIN maintenance_teams_mst t ON t.operator_id=omt.operator_id AND t.name=omt.team_name WHERE omt.operator_id=? AND omt.external_user_id=? AND omt.isactive=1", (rs, row) -> new CurrentTeam(rs.getObject("id", UUID.class), rs.getString("team_name")), operatorId, externalUserId);
        List<UUID> currentIds = current.stream().map(CurrentTeam::id).filter(java.util.Objects::nonNull).toList();
        for (CurrentTeam team : current) if (team.id() == null || !selectedTeamIds.contains(team.id())) {
            jdbc.update("UPDATE operator_membership_teams_mst SET isactive=2,updated_at=now() WHERE operator_id=? AND external_user_id=? AND team_name=? AND isactive=1", operatorId, externalUserId, team.name());
            auditTeamMembership(operatorId, actorId, externalUserId, "TEAM_MEMBERSHIP_REMOVED", team.name());
        }
        for (UUID teamId : selectedTeamIds) if (!currentIds.contains(teamId)) {
            String teamName = jdbc.queryForObject("SELECT name FROM maintenance_teams_mst WHERE operator_id=? AND id=? AND isactive=1", String.class, operatorId, teamId);
            jdbc.update("INSERT INTO operator_membership_teams_mst(operator_id,external_user_id,team_name,isactive) VALUES(?,?,?,1) ON CONFLICT(operator_id,external_user_id,team_name) DO UPDATE SET isactive=1,updated_at=now()", operatorId, externalUserId, teamName);
            auditTeamMembership(operatorId, actorId, externalUserId, "TEAM_MEMBERSHIP_ADDED", teamName);
        }
    }

    private void auditTeamMembership(UUID operatorId, String actorId, String externalUserId, String action, String teamName) {
        UUID entity=UUID.nameUUIDFromBytes((operatorId+":"+externalUserId).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,?,?,?,'TEAM_MEMBERSHIP',?,?::jsonb,?::jsonb)",
            operatorId, action, "Team membership changed for "+externalUserId, actorId, entity, json(Map.of("team",teamName,"isactive",action.endsWith("REMOVED")?1:2)), json(Map.of("team",teamName,"isactive",action.endsWith("ADDED")?1:2)));
    }

    private void validateMenuParent(UUID operatorId, String id, Map<String,String> values) {
        String parent=values.get("parentId");
        if(parent==null || parent.isBlank()) {
            if(exists("SELECT 1 FROM navigation_menu_items_mst WHERE operator_id=? AND parent_id=? AND isactive=1 LIMIT 1",operatorId,UUID.fromString(id))) throw new IllegalArgumentException("Move or deactivate this menu's submenus before making it top-level.");
            return;
        }
        if(parent.equals(id)) throw new IllegalArgumentException("A menu cannot be its own parent.");
        if(!exists("SELECT 1 FROM navigation_menu_items_mst WHERE operator_id=? AND id=? AND parent_id IS NULL AND isactive=1",operatorId,UUID.fromString(parent))) throw new IllegalArgumentException("Choose an active top-level menu from this organization.");
    }

    private void insert(Resource resource, UUID operatorId, String id, Map<String, String> values) {
        if (resource == Resource.ORGANIZATION) throw new IllegalArgumentException("Organization cannot be created here.");
        if (resource == Resource.USERS) {
            jdbc.update("INSERT INTO operator_memberships_mst(operator_id,external_user_id,display_name,role,isactive) VALUES(?,?,?,?,1)", operatorId, values.get("externalUserId"), values.get("displayName"), values.get("role"));
            return;
        }
        List<String> keys = new ArrayList<>(values.keySet());
        List<Object> args = new ArrayList<>();
        StringBuilder columns = new StringBuilder("id,operator_id");
        StringBuilder marks = new StringBuilder("?,?,");
        args.add(UUID.fromString(id)); args.add(operatorId);
        for (String key : keys) { columns.append(',').append(resource.fields.get(key)); marks.append("?,"); args.add(dbValue(resource, key, values.get(key))); }
        columns.append(",isactive"); marks.append('1');
        jdbc.update("INSERT INTO " + resource.table + "(" + columns + ") VALUES(" + marks + ")", args.toArray());
    }

    private void update(Resource resource, UUID operatorId, String id, Map<String, String> values) {
        List<Object> args = new ArrayList<>();
        StringBuilder sets = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (resource == Resource.USERS && entry.getKey().equals("teamIds")) continue;
            if (sets.length() > 0) sets.append(',');
            sets.append(resource.fields.get(entry.getKey())).append("=?");
            args.add(dbValue(resource, entry.getKey(), entry.getValue()));
        }
        if (resource == Resource.ORGANIZATION) {
            jdbc.update("UPDATE operators_mst SET name=?,application_name=COALESCE(?,application_name),updated_at=now() WHERE id=? AND isactive=1", values.get("name"), values.get("applicationName"), UUID.fromString(id));
            return;
        }
        if (resource == Resource.USERS) {
            sets.append(",updated_at=now()"); args.add(operatorId); args.add(id);
            jdbc.update("UPDATE operator_memberships_mst SET " + sets + " WHERE operator_id=? AND external_user_id=? AND isactive=1", args.toArray());
            return;
        }
        sets.append(",updated_at=now()"); args.add(operatorId); args.add(UUID.fromString(id));
        jdbc.update("UPDATE " + resource.table + " SET " + sets + " WHERE operator_id=? AND id=? AND isactive=1", args.toArray());
    }

    private Map<String, Object> find(Resource resource, UUID operatorId, String id) {
        if (resource == Resource.ORGANIZATION) {
            List<Map<String, Object>> row = jdbc.query("SELECT " + resource.columns + " FROM operators_mst WHERE id=? AND isactive IN (1,2)", new ColumnMapRowMapper(), UUID.fromString(id));
            return row.stream().findFirst().orElse(null);
        }
        String idField = resource == Resource.USERS ? "external_user_id" : "id";
        String sql = "SELECT " + resource.columns + " FROM " + resource.table + " WHERE operator_id=? AND " + idField + "=? AND isactive IN (1,2)";
        List<Map<String, Object>> rows = jdbc.query(sql, new ColumnMapRowMapper(), operatorId, resource == Resource.USERS ? id : UUID.fromString(id));
        if (rows.isEmpty()) return null;
        Map<String,Object> result = new LinkedHashMap<>(rows.get(0));
        if (resource == Resource.CONDITIONS) {
            String components = jdbc.queryForObject("SELECT COALESCE(string_agg(component_id::text,'|' ORDER BY component_id::text),'') FROM component_conditions_mst WHERE operator_id=? AND condition_id=? AND isactive=1", String.class, operatorId, UUID.fromString(id));
            result.put("componentIds", components == null ? "" : components);
        }
        return result;
    }

    private void audit(UUID operatorId, String actorId, Resource resource, String id, String action, String note, Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb)",
            operatorId, action, note, actorId, resource.key.toUpperCase(Locale.ROOT).replace('-', '_'), auditEntityId(operatorId, resource, id), json(before), json(after));
    }

    private UUID auditEntityId(UUID operatorId, Resource resource, String id) {
        if (resource != Resource.USERS) return UUID.fromString(id);
        return UUID.nameUUIDFromBytes((operatorId + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String json(Map<String, Object> value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not record the configuration audit event."); }
    }

    private String orderBy(Resource resource) {
        return switch (resource) {
            case AIRCRAFT -> "tail_number";
            case CATEGORIES, CONDITIONS, AREAS, COMPONENTS -> "name";
            case SEVERITIES -> "sort_order,severity_name";
            case USERS -> "display_name";
            default -> "name";
        };
    }

    private Object dbValue(Resource resource, String field, String value) {
        if (resource == Resource.AIRCRAFT && field.equals("fleetId")) return UUID.fromString(value);
        if (resource == Resource.NAVIGATION && field.equals("parentId")) return value == null || value.isBlank() ? null : UUID.fromString(value);
        if (resource == Resource.COMPONENTS && field.equals("areaId")) return UUID.fromString(value);
        if (resource == Resource.NAVIGATION && field.equals("displayOrder")) return Integer.valueOf(value);
        if (resource == Resource.SEVERITIES && field.equals("sortOrder")) return Integer.valueOf(value);
        return value;
    }

    private boolean isAdmin(UUID operatorId, String actorId) {
        return exists("SELECT 1 FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=? AND role='ADMIN' AND isactive=1", operatorId, actorId);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.query(sql, (ResultSetExtractor<Boolean>) rs -> rs.next(), args));
    }

    private void setAuditActor(String actorId) { jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)", String.class, actorId); }

    private UUID parseUuid(String id) {
        try { return UUID.fromString(id); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("A valid record ID is required."); }
    }
}
