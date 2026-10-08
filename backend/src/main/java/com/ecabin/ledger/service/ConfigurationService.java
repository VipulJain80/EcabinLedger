package com.ecabin.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecabin.ledger.api.ApiModels.NavigationItem;
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
        ORGANIZATION("organizations", "Organization", "operators", "id,name,isactive,created_at,updated_at", Map.of("name", "name"), false),
        FLEETS("fleets", "Fleets", "fleets", "id,name,isactive,created_at,updated_at", Map.of("name", "name"), true),
        AIRCRAFT("aircraft", "Aircraft", "aircraft", "id,fleet_id AS \"fleetId\",msn_number AS \"msnNumber\",tail_number AS \"tailNumber\",aircraft_type AS \"aircraftType\",isactive,created_at,updated_at", Map.of("fleetId", "fleet_id", "msnNumber", "msn_number", "tailNumber", "tail_number", "aircraftType", "aircraft_type"), true),
        ZONES("cabin-zones", "Cabin zones", "cabin_zones", "id,code,name,area,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "area", "area"), true),
        CATEGORIES("defect-categories", "Defect categories", "defect_categories", "id,code,name,description,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "description", "description"), true),
        COMPONENTS("components", "Components", "cabin_components", "id,code,name,isactive,created_at,updated_at", Map.of("code", "code", "name", "name"), true),
        TEAMS("maintenance-teams", "Maintenance teams", "maintenance_teams", "id,name,isactive,created_at,updated_at", Map.of("name", "name"), true),
        USERS("users", "Users and roles", "operator_memberships", "external_user_id AS id,external_user_id AS \"externalUserId\",display_name AS \"displayName\",role,isactive,created_at,updated_at", Map.of("externalUserId", "external_user_id", "displayName", "display_name", "role", "role"), true),
        NAVIGATION("menu-items", "Menus & submenus", "navigation_menu_items", "id,menu_key AS key,label,parent_id AS \"parentId\",required_permissions AS \"requiredPermissions\",icon,route_key AS \"routeKey\",display_order AS \"displayOrder\",isactive,created_at,updated_at", Map.of("key", "menu_key", "label", "label", "parentId", "parent_id", "requiredPermissions", "required_permissions", "icon", "icon", "routeKey", "route_key", "displayOrder", "display_order"), true),
        ROLES("roles", "Roles", "operator_roles", "id,role_key AS \"roleKey\",name,permissions,isactive,system_role AS \"systemRole\",created_at,updated_at", Map.of("roleKey", "role_key", "name", "name", "permissions", "permissions"), true);

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

    private static final Set<String> AREAS = Set.of("CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT");
    private static final Set<String> PERMISSIONS = java.util.Arrays.stream(Permission.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ConfigurationService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public List<MenuItem> menu() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("organizations", "Organization", "Operator profile"));
        items.add(new MenuItem("fleets", "Fleets", "Fleet groupings"));
        items.add(new MenuItem("aircraft", "Aircraft", "Aircraft and registrations"));
        items.add(new MenuItem("cabin-zones", "Cabin zones", "Cabin, galley and lavatory locations"));
        items.add(new MenuItem("defect-categories", "Defect categories", "Operational defect classification"));
        items.add(new MenuItem("components", "Components", "Cabin equipment reference"));
        items.add(new MenuItem("maintenance-teams", "Maintenance teams", "Corrective action teams"));
        items.add(new MenuItem("users", "Users and roles", "Operator application memberships"));
        items.add(new MenuItem("menu-items", "Menus & submenus", "Control the organization navigation menu"));
        items.add(new MenuItem("roles", "Roles", "Create application roles and assign permissions"));
        return List.copyOf(items);
    }

    /** Reads organization menu records and filters them with the authenticated role's server-side grants. */
    public List<NavigationItem> navigation(UUID operatorId, Set<Permission> grants) {
        List<Map<String,Object>> rows=jdbc.query("SELECT id,menu_key AS key,label,parent_id AS \"parentId\",icon,route_key AS section,required_permissions AS \"requiredPermissions\",display_order AS \"displayOrder\" FROM navigation_menu_items WHERE operator_id=? AND isactive=1 ORDER BY display_order,label",new ColumnMapRowMapper(),operatorId);
        Map<String,List<NavigationItem>> children=new LinkedHashMap<>();
        for (Map<String,Object> row:rows) {
            if (!allowed(String.valueOf(row.get("requiredPermissions")),grants) || row.get("parentId")==null) continue;
            String parent=String.valueOf(row.get("parentId"));
            children.computeIfAbsent(parent,ignored->new ArrayList<>()).add(new NavigationItem(String.valueOf(row.get("key")),String.valueOf(row.get("label")),String.valueOf(row.get("icon")),String.valueOf(row.get("section")),List.of()));
        }
        List<NavigationItem> result=new ArrayList<>();
        for (Map<String,Object> row:rows) {
            if (row.get("parentId")!=null || !allowed(String.valueOf(row.get("requiredPermissions")),grants)) continue;
            String id=String.valueOf(row.get("id"));
            boolean hasChildren=rows.stream().anyMatch(candidate->id.equals(String.valueOf(candidate.get("parentId"))));
            List<NavigationItem> nested=children.getOrDefault(id,List.of());
            if (hasChildren && nested.isEmpty()) continue;
            result.add(new NavigationItem(String.valueOf(row.get("key")),String.valueOf(row.get("label")),String.valueOf(row.get("icon")),String.valueOf(row.get("section")),nested));
        }
        return List.copyOf(result);
    }

    private boolean allowed(String required,Set<Permission> grants) {
        for(String value:required.split("\\|")) try { if(grants.contains(Permission.valueOf(value))) return true; } catch(IllegalArgumentException ignored) { /* Invalid grants fail closed. */ }
        return false;
    }

    public List<String> aircraftTypes(UUID operatorId) {
        return jdbc.queryForList("SELECT DISTINCT aircraft_type FROM aircraft WHERE operator_id=? AND isactive=1 ORDER BY aircraft_type LIMIT 100", String.class, operatorId);
    }

    public List<Map<String, Object>> list(UUID operatorId, String key, boolean includeInactive) {
        Resource resource = Resource.parse(key);
        String sql;
        if (resource == Resource.ORGANIZATION) {
            sql = "SELECT " + resource.columns + " FROM operators WHERE id=?" + (includeInactive ? "" : " AND isactive=1") + " LIMIT 1";
            return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
        }
        if (resource == Resource.AIRCRAFT) {
            sql = "SELECT a.id,a.fleet_id AS \"fleetId\",a.msn_number AS \"msnNumber\",a.tail_number AS \"tailNumber\",a.aircraft_type AS \"aircraftType\",f.name AS \"fleetName\",a.isactive,a.created_at,a.updated_at " +
                "FROM aircraft a JOIN fleets f ON f.operator_id=a.operator_id AND f.id=a.fleet_id WHERE a.operator_id=?" +
                (includeInactive ? "" : " AND a.isactive=1") + " ORDER BY a.tail_number LIMIT 200";
            return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
        }
        if (resource == Resource.NAVIGATION) return jdbc.query("SELECT m.id,m.menu_key AS key,m.label,m.parent_id AS \"parentId\",p.label AS \"parentLabel\",m.required_permissions AS \"requiredPermissions\",m.icon,m.route_key AS \"routeKey\",m.display_order AS \"displayOrder\",m.isactive,m.created_at,m.updated_at FROM navigation_menu_items m LEFT JOIN navigation_menu_items p ON p.operator_id=m.operator_id AND p.id=m.parent_id WHERE m.operator_id=?" + (includeInactive ? "" : " AND m.isactive=1") + " ORDER BY m.display_order,m.label LIMIT 200", new ColumnMapRowMapper(), operatorId);
        sql = "SELECT " + resource.columns + " FROM " + resource.table + " WHERE operator_id=?" +
            (includeInactive ? "" : " AND isactive=1") + " ORDER BY " + orderBy(resource) + " LIMIT 200";
        return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
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
            String aircraftType, String sortBy, String sortDirection, OutputStream output) throws IOException {
        Resource resource = Resource.parse(key);
        TableQuery query = buildTableQuery(operatorId, resource, search, status, role, area, aircraftType, sortBy, sortDirection);
        List<String[]> columns = exportColumns(resource);
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
        else { predicates.add("r.operator_id=?"); args.add(operatorId); }
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
            if (resource != Resource.USERS || !exists("SELECT 1 FROM operator_roles WHERE operator_id=? AND role_key=? AND isactive=1", operatorId, role.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Role filtering is not available for this table.");
            predicates.add("r.role=?"); args.add(role.toUpperCase(Locale.ROOT));
        }
        if (area != null && !area.isBlank()) {
            if (resource != Resource.ZONES || !AREAS.contains(area.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Area filtering is not available for this table.");
            predicates.add("r.area=?"); args.add(area.toUpperCase(Locale.ROOT));
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
        String idColumn = resource == Resource.ORGANIZATION ? "o.id" : resource == Resource.USERS ? "r.external_user_id" : "r.id";
        return new TableQuery(from, tableSelect(resource), " WHERE " + String.join(" AND ", predicates), List.copyOf(args), column + " " + direction + " NULLS LAST," + idColumn + " ASC");
    }

    private String defaultSort(Resource resource) {
        return switch (resource) {
            case ORGANIZATION, FLEETS, TEAMS -> "name";
            case AIRCRAFT -> "tailNumber";
            case ZONES, CATEGORIES, COMPONENTS -> "name";
            case USERS -> "displayName";
            case NAVIGATION -> "displayOrder";
            case ROLES -> "name";
        };
    }

    private String tableFrom(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> "operators o";
            case AIRCRAFT -> "aircraft r JOIN fleets f ON f.operator_id=r.operator_id AND f.id=r.fleet_id";
            case USERS -> "operator_memberships r";
            case NAVIGATION -> "navigation_menu_items r LEFT JOIN navigation_menu_items p ON p.operator_id=r.operator_id AND p.id=r.parent_id";
            case ROLES -> "operator_roles r";
            default -> resource.table + " r";
        };
    }

    private String tableSelect(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> "o.id,o.name,o.isactive,o.created_at,o.updated_at";
            case FLEETS -> "r.id,r.name,r.isactive,r.created_at,r.updated_at";
            case AIRCRAFT -> "r.id,r.msn_number AS \"msnNumber\",r.tail_number AS \"tailNumber\",r.aircraft_type AS \"aircraftType\",r.fleet_id AS \"fleetId\",f.name AS \"fleetName\",r.isactive,r.created_at,r.updated_at";
            case ZONES -> "r.id,r.code,r.name,r.area,r.isactive,r.created_at,r.updated_at";
            case CATEGORIES -> "r.id,r.code,r.name,r.description,r.isactive,r.created_at,r.updated_at";
            case COMPONENTS -> "r.id,r.code,r.name,r.isactive,r.created_at,r.updated_at";
            case TEAMS -> "r.id,r.name,r.isactive,r.created_at,r.updated_at";
            case USERS -> "r.external_user_id AS id,r.external_user_id AS \"externalUserId\",r.display_name AS \"displayName\",r.role,r.isactive,r.created_at,r.updated_at";
            case NAVIGATION -> "r.id,r.menu_key AS key,r.label,r.parent_id AS \"parentId\",p.label AS \"parentLabel\",r.required_permissions AS \"requiredPermissions\",r.icon,r.route_key AS \"routeKey\",r.display_order AS \"displayOrder\",r.isactive,r.created_at,r.updated_at";
            case ROLES -> "r.id,r.role_key AS \"roleKey\",r.name,r.permissions,r.isactive,r.system_role AS \"systemRole\",r.created_at,r.updated_at";
        };
    }

    private List<String> searchColumns(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> List.of("o.name");
            case FLEETS, TEAMS -> List.of("r.name");
            case AIRCRAFT -> List.of("r.msn_number","r.tail_number","r.aircraft_type");
            case ZONES -> List.of("r.code","r.name","r.area");
            case CATEGORIES -> List.of("r.code","r.name","r.description");
            case COMPONENTS -> List.of("r.code","r.name");
            case USERS -> List.of("r.external_user_id","r.display_name","r.role");
            case NAVIGATION -> List.of("r.menu_key","r.label","r.required_permissions","r.route_key");
            case ROLES -> List.of("r.role_key","r.name","r.permissions");
        };
    }

    private Map<String,String> sortableColumns(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> Map.of("name","o.name","created_at","o.created_at","updated_at","o.updated_at");
            case FLEETS -> Map.of("name","r.name","created_at","r.created_at","updated_at","r.updated_at");
            case AIRCRAFT -> Map.of("msnNumber","r.msn_number","tailNumber","r.tail_number","aircraftType","r.aircraft_type","fleetName","f.name","created_at","r.created_at");
            case ZONES -> Map.of("code","r.code","name","r.name","area","r.area","created_at","r.created_at");
            case CATEGORIES -> Map.of("code","r.code","name","r.name","description","r.description","created_at","r.created_at");
            case COMPONENTS -> Map.of("code","r.code","name","r.name","created_at","r.created_at");
            case TEAMS -> Map.of("name","r.name","created_at","r.created_at","updated_at","r.updated_at");
            case USERS -> Map.of("displayName","r.display_name","externalUserId","r.external_user_id","role","r.role","created_at","r.created_at");
            case NAVIGATION -> Map.of("label","r.label","key","r.menu_key","parentLabel","p.label","requiredPermissions","r.required_permissions","routeKey","r.route_key","displayOrder","r.display_order","created_at","r.created_at");
            case ROLES -> Map.of("roleKey","r.role_key","name","r.name","permissions","r.permissions","created_at","r.created_at");
        };
    }

    private List<String[]> exportColumns(Resource resource) {
        return switch (resource) {
            case ORGANIZATION -> List.of(new String[]{"Organization","name"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case FLEETS -> List.of(new String[]{"Fleet","name"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case AIRCRAFT -> List.of(new String[]{"MSN Number","msnNumber"},new String[]{"Registration","tailNumber"},new String[]{"Aircraft type","aircraftType"},new String[]{"Fleet","fleetName"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case ZONES -> List.of(new String[]{"Code","code"},new String[]{"Cabin zone","name"},new String[]{"Area","area"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case CATEGORIES -> List.of(new String[]{"Code","code"},new String[]{"Category","name"},new String[]{"Description","description"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case COMPONENTS -> List.of(new String[]{"Code","code"},new String[]{"Component","name"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case TEAMS -> List.of(new String[]{"Maintenance team","name"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case USERS -> List.of(new String[]{"Name","displayName"},new String[]{"Enterprise identity","externalUserId"},new String[]{"Role","role"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case NAVIGATION -> List.of(new String[]{"Menu / submenu","label"},new String[]{"Key","key"},new String[]{"Parent menu","parentLabel"},new String[]{"Visible permissions","requiredPermissions"},new String[]{"Application section","routeKey"},new String[]{"Order","displayOrder"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
            case ROLES -> List.of(new String[]{"Role key","roleKey"},new String[]{"Role","name"},new String[]{"Permissions","permissions"},new String[]{"System role","systemRole"},new String[]{"Status","isactive"},new String[]{"Created","created_at"});
        };
    }

    @Transactional
    public Map<String, Object> save(UUID operatorId, String actorId, String key, SaveRequest request) {
        Resource resource = Resource.parse(key);
        Map<String, String> values = validate(resource, request.fields());
        boolean creating = request.id() == null || request.id().isBlank();
        if (resource == Resource.ORGANIZATION && creating) throw new IllegalArgumentException("An organization cannot be created from its own configuration page.");
        if (resource == Resource.ORGANIZATION && !operatorId.toString().equals(request.id())) throw new AccessDeniedException("Only the current organization can be updated.");
        if (resource == Resource.USERS && creating && "ADMIN".equals(values.get("role")) && !isAdmin(operatorId, actorId)) {
            throw new AccessDeniedException("Only an organization administrator can grant the ADMIN role.");
        }
        if (resource == Resource.USERS && !exists("SELECT 1 FROM operator_roles WHERE operator_id=? AND role_key=? AND isactive=1", operatorId, values.get("role"))) throw new IllegalArgumentException("Choose an active role from the Roles master.");
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

        Map<String, Object> previous = creating ? null : find(resource, operatorId, entityId);
        if (!creating && previous == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Configuration record was not found.");
        if (!creating && ((Number) previous.get("isactive")).intValue() != 1) throw new IllegalArgumentException("Reactivate this record before editing it.");
        if (resource == Resource.NAVIGATION) {
            String oldParent = previous == null || previous.get("parentId") == null ? "" : String.valueOf(previous.get("parentId"));
            String newParent = values.getOrDefault("parentId", oldParent);
            if (creating || !oldParent.equals(newParent)) validateMenuParent(operatorId, creating ? "00000000-0000-0000-0000-000000000000" : entityId, values);
        }
        if (resource == Resource.ROLES) {
            if (!creating && !String.valueOf(previous.get("roleKey")).equals(values.get("roleKey"))) throw new IllegalArgumentException("A role key cannot be changed after creation.");
            if (!creating && "ADMIN".equals(previous.get("roleKey"))) throw new IllegalArgumentException("The built-in Administrator role is protected and cannot be edited.");
        }
        if (creating) insert(resource, operatorId, entityId, values);
        else update(resource, operatorId, entityId, values);
        Map<String, Object> current = find(resource, operatorId, entityId);
        audit(operatorId, actorId, resource, entityId, creating ? "MASTER_DATA_CREATED" : "MASTER_DATA_UPDATED",
            creating ? "Created " + resource.label.toLowerCase() + " record." : "Updated " + resource.label.toLowerCase() + " record.", previous, current);
        return current;
    }

    @Transactional
    public void deactivate(UUID operatorId, String actorId, String key, String id, String reason) {
        Resource resource = Resource.parse(key);
        if (resource == Resource.ORGANIZATION) throw new IllegalArgumentException("The organization record cannot be soft-deleted here.");
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("A reason of up to 1000 characters is required.");
        String entityId = id;
        if (resource != Resource.USERS) entityId = parseUuid(id).toString();
        Map<String, Object> previous = find(resource, operatorId, entityId);
        if (previous == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Configuration record was not found or is already inactive.");
        if (resource == Resource.FLEETS && exists("SELECT 1 FROM aircraft WHERE operator_id=? AND fleet_id=? AND isactive=1 LIMIT 1", operatorId, UUID.fromString(entityId)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Move or deactivate the fleet's aircraft before deactivating this fleet.");
        if (resource == Resource.AIRCRAFT && exists("SELECT 1 FROM defects WHERE operator_id=? AND aircraft_id=? LIMIT 1", operatorId, UUID.fromString(entityId)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Aircraft with defect history cannot be deactivated.");
        if (resource == Resource.TEAMS && exists("SELECT 1 FROM corrective_actions WHERE operator_id=? AND assigned_team=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS') LIMIT 1", operatorId, previous.get("name")))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Reassign open corrective actions before deactivating this maintenance team.");
        if (resource == Resource.USERS) {
            if (actorId.equals(entityId)) throw new IllegalArgumentException("You cannot deactivate your own membership.");
            if (exists("SELECT 1 FROM corrective_actions WHERE operator_id=? AND assigned_user_id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS') LIMIT 1", operatorId, entityId))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Reassign open corrective actions before deactivating this user.");
            if (exists("SELECT 1 FROM inspections WHERE operator_id=? AND inspector_user_id=? AND isactive=1 AND status IN ('ASSIGNED','IN_PROGRESS') LIMIT 1", operatorId, entityId))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Reassign open inspections before deactivating this user.");
            Map<String, Object> row = previous;
            if ("ADMIN".equals(row.get("role")) && jdbc.queryForObject("SELECT count(*) FROM operator_memberships WHERE operator_id=? AND role='ADMIN' AND isactive=1", Integer.class, operatorId) <= 1)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "The organization must retain at least one active administrator.");
        }
        if (resource == Resource.NAVIGATION && exists("SELECT 1 FROM navigation_menu_items WHERE operator_id=? AND parent_id=? AND isactive=1 LIMIT 1", operatorId, UUID.fromString(entityId)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Deactivate or move this menu's submenus before deactivating it.");
        if (resource == Resource.ROLES && ("ADMIN".equals(previous.get("roleKey")) || exists("SELECT 1 FROM operator_memberships WHERE operator_id=? AND role=? AND isactive=1 LIMIT 1", operatorId, previous.get("roleKey"))))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "The Administrator role is protected. Reassign active users before deactivating another role.");
        String sql = "UPDATE " + resource.table + " SET isactive=2,updated_at=now() WHERE operator_id=? AND " + (resource == Resource.USERS ? "external_user_id" : "id") + "=? AND isactive=1";
        jdbc.update(sql, operatorId, resource == Resource.USERS ? entityId : UUID.fromString(entityId));
        if (resource == Resource.TEAMS) {
            String teamName = String.valueOf(previous.get("name"));
            jdbc.update("UPDATE operator_membership_teams SET isactive=2 WHERE operator_id=? AND team_name=? AND isactive=1", operatorId, teamName);
        }
        Map<String, Object> current = find(resource, operatorId, entityId);
        audit(operatorId, actorId, resource, entityId, "MASTER_DATA_DEACTIVATED", reason.trim(), previous, current);
    }

    @Transactional
    public void activate(UUID operatorId, String actorId, String key, String id, String reason) {
        Resource resource = Resource.parse(key);
        if (resource == Resource.ORGANIZATION) throw new IllegalArgumentException("The organization record is already managed as active.");
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("A reason of up to 1000 characters is required.");
        String entityId = resource == Resource.USERS ? id : parseUuid(id).toString();
        Map<String, Object> previous = find(resource, operatorId, entityId);
        if (previous == null || ((Number) previous.get("isactive")).intValue() != 2)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Inactive configuration record was not found.");
        if (resource == Resource.NAVIGATION && previous.get("parentId") != null && !exists("SELECT 1 FROM navigation_menu_items WHERE operator_id=? AND id=? AND isactive=1", operatorId, previous.get("parentId")))
            throw new IllegalArgumentException("Reactivate the parent menu before reactivating this submenu.");
        String idField = resource == Resource.USERS ? "external_user_id" : "id";
        Object recordId = resource == Resource.USERS ? entityId : UUID.fromString(entityId);
        jdbc.update("UPDATE " + resource.table + " SET isactive=1,updated_at=now() WHERE operator_id=? AND " + idField + "=? AND isactive=2", operatorId, recordId);
        Map<String, Object> current = find(resource, operatorId, entityId);
        audit(operatorId, actorId, resource, entityId, "MASTER_DATA_ACTIVATED", reason.trim(), previous, current);
    }

    private Map<String, String> validate(Resource resource, Map<String, String> fields) {
        if (fields == null) throw new IllegalArgumentException("Configuration fields are required.");
        for (String key : fields.keySet()) if (!resource.fields.containsKey(key)) throw new IllegalArgumentException("Unsupported field for this configuration section: " + key);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.isEmpty() && !(resource == Resource.CATEGORIES && entry.getKey().equals("description")) && !(resource == Resource.NAVIGATION && entry.getKey().equals("parentId"))) continue;
            if (value.length() > maxLength(resource, entry.getKey())) throw new IllegalArgumentException(entry.getKey() + " is too long.");
            result.put(entry.getKey(), value);
        }
        Set<String> required = switch (resource) {
            case ORGANIZATION, FLEETS, TEAMS -> Set.of("name");
            case AIRCRAFT -> Set.of("fleetId", "tailNumber", "aircraftType");
            case ZONES -> Set.of("code", "name", "area");
            case CATEGORIES, COMPONENTS -> Set.of("code", "name");
            case USERS -> fields.containsKey("externalUserId") ? Set.of("externalUserId", "displayName", "role") : Set.of("displayName", "role");
            case NAVIGATION -> Set.of("key", "label", "requiredPermissions", "icon", "routeKey", "displayOrder");
            case ROLES -> fields.containsKey("roleKey") ? Set.of("roleKey", "name", "permissions") : Set.of("name", "permissions");
        };
        for (String field : required) if (!result.containsKey(field)) throw new IllegalArgumentException(field + " is required.");
        if (resource == Resource.AIRCRAFT) result.put("fleetId", parseUuid(result.get("fleetId")).toString());
        if (resource == Resource.NAVIGATION) {
            result.put("key", result.get("key").toLowerCase(Locale.ROOT));
            result.put("routeKey", result.get("routeKey").toLowerCase(Locale.ROOT));
            if (!result.get("key").matches("[a-z0-9][a-z0-9-]{0,79}") || !result.get("routeKey").matches("[a-z0-9][a-z0-9-]{0,79}")) throw new IllegalArgumentException("Menu key and route must use lowercase letters, numbers and hyphens.");
            if (!Set.of("home","operations","reports","audit","configuration","defects","fleet","quality","organizations","fleets","aircraft","cabin-zones","defect-categories","components","maintenance-teams","users","menu-items","roles").contains(result.get("routeKey"))) throw new IllegalArgumentException("Choose an application section that is supported by this MVP.");
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
            }
            Set<String> granted = java.util.Arrays.stream(result.get("permissions").toUpperCase(Locale.ROOT).split("[|,]"))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toSet());
            if (granted.isEmpty() || !PERMISSIONS.containsAll(granted)) throw new IllegalArgumentException("Select at least one valid application permission.");
            if (!"ADMIN".equals(result.get("roleKey")) && granted.contains("ADMINISTER_ORGANIZATION")) throw new IllegalArgumentException("Only the built-in ADMIN role can administer organization access.");
            result.put("permissions", granted.stream().sorted().collect(java.util.stream.Collectors.joining("|")));
        }
        if (resource == Resource.ZONES) {
            result.put("area", result.get("area").trim().toUpperCase(Locale.ROOT));
            if (!AREAS.contains(result.get("area"))) throw new IllegalArgumentException("Unsupported cabin zone area.");
        }
        if (resource == Resource.USERS) {
            result.put("role", result.get("role").toUpperCase(Locale.ROOT));
            if (result.containsKey("externalUserId") && !result.get("externalUserId").matches("[A-Za-z0-9._@+-]{1,200}")) throw new IllegalArgumentException("Enter a valid enterprise user identity.");
        }
        if (Set.of(Resource.ZONES, Resource.CATEGORIES, Resource.COMPONENTS).contains(resource)) {
            String code = result.get("code").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (!code.matches("[A-Z0-9_]{1,48}")) throw new IllegalArgumentException("Code may contain uppercase letters, digits and underscores.");
            result.put("code", code);
        }
        return result;
    }

    private int maxLength(Resource resource, String field) {
        if (field.equals("roleKey")) return 48;
        if (field.equals("permissions")) return 500;
        if (resource == Resource.ROLES && field.equals("name")) return 100;
        if (field.equals("description")) return 500;
        if (field.equals("displayName")) return 160;
        if (field.equals("externalUserId")) return 200;
        if (field.equals("aircraftType")) return 80;
        if (field.equals("tailNumber") || field.equals("msnNumber")) return 16;
        if (field.equals("code")) return 48;
        if (field.equals("area")) return 32;
        if (field.equals("role")) return 48;
        return resource == Resource.ORGANIZATION ? 160 : 120;
    }

    private void requireActiveFleet(UUID operatorId, String fleetId) {
        UUID id = UUID.fromString(fleetId);
        if (!exists("SELECT 1 FROM fleets WHERE operator_id=? AND id=? AND isactive=1", operatorId, id)) throw new IllegalArgumentException("Select an active fleet belonging to this organization.");
    }

    private void validateMenuParent(UUID operatorId, String id, Map<String,String> values) {
        String parent=values.get("parentId");
        if(parent==null || parent.isBlank()) {
            if(exists("SELECT 1 FROM navigation_menu_items WHERE operator_id=? AND parent_id=? AND isactive=1 LIMIT 1",operatorId,UUID.fromString(id))) throw new IllegalArgumentException("Move or deactivate this menu's submenus before making it top-level.");
            return;
        }
        if(parent.equals(id)) throw new IllegalArgumentException("A menu cannot be its own parent.");
        if(!exists("SELECT 1 FROM navigation_menu_items WHERE operator_id=? AND id=? AND parent_id IS NULL AND isactive=1",operatorId,UUID.fromString(parent))) throw new IllegalArgumentException("Choose an active top-level menu from this organization.");
    }

    private void insert(Resource resource, UUID operatorId, String id, Map<String, String> values) {
        if (resource == Resource.ORGANIZATION) throw new IllegalArgumentException("Organization cannot be created here.");
        if (resource == Resource.USERS) {
            jdbc.update("INSERT INTO operator_memberships(operator_id,external_user_id,display_name,role,isactive) VALUES(?,?,?,?,1)", operatorId, values.get("externalUserId"), values.get("displayName"), values.get("role"));
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
            if (sets.length() > 0) sets.append(',');
            sets.append(resource.fields.get(entry.getKey())).append("=?");
            args.add(dbValue(resource, entry.getKey(), entry.getValue()));
        }
        if (resource == Resource.ORGANIZATION) {
            jdbc.update("UPDATE operators SET name=?,updated_at=now() WHERE id=? AND isactive=1", values.get("name"), UUID.fromString(id));
            return;
        }
        if (resource == Resource.USERS) {
            sets.append(",updated_at=now()"); args.add(operatorId); args.add(id);
            jdbc.update("UPDATE operator_memberships SET " + sets + " WHERE operator_id=? AND external_user_id=? AND isactive=1", args.toArray());
            return;
        }
        sets.append(",updated_at=now()"); args.add(operatorId); args.add(UUID.fromString(id));
        jdbc.update("UPDATE " + resource.table + " SET " + sets + " WHERE operator_id=? AND id=? AND isactive=1", args.toArray());
    }

    private Map<String, Object> find(Resource resource, UUID operatorId, String id) {
        if (resource == Resource.ORGANIZATION) {
            List<Map<String, Object>> row = jdbc.query("SELECT " + resource.columns + " FROM operators WHERE id=? AND isactive IN (1,2)", new ColumnMapRowMapper(), UUID.fromString(id));
            return row.stream().findFirst().orElse(null);
        }
        String idField = resource == Resource.USERS ? "external_user_id" : "id";
        String sql = "SELECT " + resource.columns + " FROM " + resource.table + " WHERE operator_id=? AND " + idField + "=? AND isactive IN (1,2)";
        List<Map<String, Object>> rows = jdbc.query(sql, new ColumnMapRowMapper(), operatorId, resource == Resource.USERS ? id : UUID.fromString(id));
        return rows.stream().findFirst().orElse(null);
    }

    private void audit(UUID operatorId, String actorId, Resource resource, String id, String action, String note, Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("INSERT INTO defect_events(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb)",
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
            case CATEGORIES, ZONES, COMPONENTS -> "name";
            case USERS -> "display_name";
            default -> "name";
        };
    }

    private Object dbValue(Resource resource, String field, String value) {
        if (resource == Resource.AIRCRAFT && field.equals("fleetId")) return UUID.fromString(value);
        if (resource == Resource.NAVIGATION && field.equals("parentId")) return value == null || value.isBlank() ? null : UUID.fromString(value);
        if (resource == Resource.NAVIGATION && field.equals("displayOrder")) return Integer.valueOf(value);
        return value;
    }

    private boolean isAdmin(UUID operatorId, String actorId) {
        return exists("SELECT 1 FROM operator_memberships WHERE operator_id=? AND external_user_id=? AND role='ADMIN' AND isactive=1", operatorId, actorId);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.query(sql, (ResultSetExtractor<Boolean>) rs -> rs.next(), args));
    }

    private UUID parseUuid(String id) {
        try { return UUID.fromString(id); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("A valid record ID is required."); }
    }
}
