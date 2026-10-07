package com.ecabin.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private enum Resource {
        ORGANIZATION("organizations", "Organization", "operators", "id,name,isactive,created_at,updated_at", Map.of("name", "name"), false),
        FLEETS("fleets", "Fleets", "fleets", "id,name,isactive,created_at,updated_at", Map.of("name", "name"), true),
        AIRCRAFT("aircraft", "Aircraft", "aircraft", "id,fleet_id AS \"fleetId\",tail_number AS \"tailNumber\",aircraft_type AS \"aircraftType\",isactive,created_at,updated_at", Map.of("fleetId", "fleet_id", "tailNumber", "tail_number", "aircraftType", "aircraft_type"), true),
        ZONES("cabin-zones", "Cabin zones", "cabin_zones", "id,code,name,area,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "area", "area"), true),
        CATEGORIES("defect-categories", "Defect categories", "defect_categories", "id,code,name,description,isactive,created_at,updated_at", Map.of("code", "code", "name", "name", "description", "description"), true),
        COMPONENTS("components", "Components", "cabin_components", "id,code,name,isactive,created_at,updated_at", Map.of("code", "code", "name", "name"), true),
        TEAMS("maintenance-teams", "Maintenance teams", "maintenance_teams", "id,name,isactive,created_at,updated_at", Map.of("name", "name"), true),
        USERS("users", "Users and roles", "operator_memberships", "external_user_id AS id,external_user_id AS \"externalUserId\",display_name AS \"displayName\",role,isactive,created_at,updated_at", Map.of("externalUserId", "external_user_id", "displayName", "display_name", "role", "role"), true);

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
    private static final Set<String> ROLES = Set.of("ADMIN", "SUPERVISOR", "INSPECTOR", "MAINTENANCE_TECHNICIAN", "QUALITY_COMPLIANCE", "VIEWER");
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
        return List.copyOf(items);
    }

    public List<Map<String, Object>> list(UUID operatorId, String key, boolean includeInactive) {
        Resource resource = Resource.parse(key);
        String sql;
        if (resource == Resource.ORGANIZATION) {
            sql = "SELECT " + resource.columns + " FROM operators WHERE id=?" + (includeInactive ? "" : " AND isactive=1") + " LIMIT 1";
            return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
        }
        if (resource == Resource.AIRCRAFT) {
            sql = "SELECT a.id,a.fleet_id AS \"fleetId\",a.tail_number AS \"tailNumber\",a.aircraft_type AS \"aircraftType\",f.name AS \"fleetName\",a.isactive,a.created_at,a.updated_at " +
                "FROM aircraft a JOIN fleets f ON f.operator_id=a.operator_id AND f.id=a.fleet_id WHERE a.operator_id=?" +
                (includeInactive ? "" : " AND a.isactive=1") + " ORDER BY a.tail_number LIMIT 200";
            return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
        }
        sql = "SELECT " + resource.columns + " FROM " + resource.table + " WHERE operator_id=?" +
            (includeInactive ? "" : " AND isactive=1") + " ORDER BY " + orderBy(resource) + " LIMIT 200";
        return jdbc.query(sql, new ColumnMapRowMapper(), operatorId);
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
            if (value.isEmpty() && !(resource == Resource.CATEGORIES && entry.getKey().equals("description"))) continue;
            if (value.length() > maxLength(resource, entry.getKey())) throw new IllegalArgumentException(entry.getKey() + " is too long.");
            result.put(entry.getKey(), value);
        }
        Set<String> required = switch (resource) {
            case ORGANIZATION, FLEETS, TEAMS -> Set.of("name");
            case AIRCRAFT -> Set.of("fleetId", "tailNumber", "aircraftType");
            case ZONES -> Set.of("code", "name", "area");
            case CATEGORIES, COMPONENTS -> Set.of("code", "name");
            case USERS -> fields.containsKey("externalUserId") ? Set.of("externalUserId", "displayName", "role") : Set.of("displayName", "role");
        };
        for (String field : required) if (!result.containsKey(field)) throw new IllegalArgumentException(field + " is required.");
        if (resource == Resource.AIRCRAFT) result.put("fleetId", parseUuid(result.get("fleetId")).toString());
        if (resource == Resource.ZONES) {
            result.put("area", result.get("area").trim().toUpperCase(Locale.ROOT));
            if (!AREAS.contains(result.get("area"))) throw new IllegalArgumentException("Unsupported cabin zone area.");
        }
        if (resource == Resource.USERS) {
            result.put("role", result.get("role").toUpperCase(Locale.ROOT));
            if (!ROLES.contains(result.get("role"))) throw new IllegalArgumentException("Unsupported application role.");
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
        if (field.equals("description")) return 500;
        if (field.equals("displayName")) return 160;
        if (field.equals("externalUserId")) return 200;
        if (field.equals("aircraftType")) return 80;
        if (field.equals("tailNumber")) return 16;
        if (field.equals("code")) return 48;
        if (field.equals("area") || field.equals("role")) return 32;
        return resource == Resource.ORGANIZATION ? 160 : 120;
    }

    private void requireActiveFleet(UUID operatorId, String fleetId) {
        UUID id = UUID.fromString(fleetId);
        if (!exists("SELECT 1 FROM fleets WHERE operator_id=? AND id=? AND isactive=1", operatorId, id)) throw new IllegalArgumentException("Select an active fleet belonging to this organization.");
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
