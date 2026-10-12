package com.ecabin.ledger.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** One bounded, tenant-owned preview/commit pipeline shared by all configuration masters. */
@Service
public class ConfigurationImportService {
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_ROWS = 5_000;
    private static final TypeReference<List<Map<String, Object>>> JSON_ROWS = new TypeReference<>() {};
    public record Template(String resource, String label, List<String> headers, String sample) {}
    public record RowIssue(int rowNumber, Map<String,String> values, List<FieldIssue> errors) {}
    public record FieldIssue(String field, String message) {}
    public record Validation(UUID importId, String resource, int validRows, int rejectedRows, Instant expiresAt, List<RowIssue> rows) {}
    public record ImportResult(UUID importId, String resource, int created, int updated, int skipped, int rejected) {}

    private record Definition(String label, List<String> headers, List<String> sample, String uniqueField) {}
    private record Staged(int rowNumber, Map<String,String> fields) {}

    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
        Map.entry("fleets", new Definition("Fleets", List.of("fleet_name"), List.of("Main Fleet"), "name")),
        Map.entry("aircraft", new Definition("Aircraft", List.of("msn_number","registration","aircraft_type","fleet_name"), List.of("MSN-001","VT-ABC","A320-200","Main Fleet"), "tailNumber")),
        Map.entry("areas", new Definition("Area Master", List.of("area_code","area_name","description"), List.of("ENTRY_AREA","Entry Area","Aircraft entry area"), "code")),
        Map.entry("defect-categories", new Definition("Defect Categories", List.of("category_code","category_name","description"), List.of("SEAT","Passenger Seat","Passenger seating component"), "code")),
        Map.entry("defect-conditions", new Definition("Defect Conditions", List.of("condition_code","condition_name","description","applicable_component_codes"), List.of("CRACKED","Cracked","Illustrative condition label only; not an approved maintenance procedure.","TRAY_TABLE|VINYL"), "code")),
        Map.entry("components", new Definition("Components", List.of("component_code","component_name","area_code","description"), List.of("VINYL","Vinyl","ENTRY_AREA","Entry area vinyl flooring"), "code")),
        Map.entry("maintenance-teams", new Definition("Maintenance Teams", List.of("team_name"), List.of("Cabin Maintenance A"), "name")),
        Map.entry("users", new Definition("Users / memberships", List.of("external_user_id","display_name","role"), List.of("existing.enterprise.subject","Demo User","VIEWER"), "externalUserId")),
        Map.entry("menu-items", new Definition("Menus & submenus", List.of("menu_key","label","parent_key","required_permissions","icon","route_key","display_order"), List.of("cabin-operations","Cabin Operations","","VIEW","✈","operations","100"), "key")),
        Map.entry("roles", new Definition("Roles", List.of("role_key","role_name","description","permissions"), List.of("CABIN_VIEWER","Cabin Viewer","Organization-defined role with VIEW and AUDIT access.","VIEW|AUDIT"), "roleKey")),
        Map.entry("severities", new Definition("Severity Master", List.of("severity_code","severity_name","description","sort_order"), List.of("LOW","Low","Starter display label only; not an airworthiness classification or maintenance threshold.","10"), "code"))
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConfigurationService configuration;
    public ConfigurationImportService(JdbcTemplate jdbc, ObjectMapper mapper, ConfigurationService configuration) {
        this.jdbc = jdbc; this.mapper = mapper; this.configuration = configuration;
    }

    public Template template(String resource) {
        if (resource.equals("organizations")) throw new IllegalArgumentException("Organizations are provisioned through operator onboarding and cannot be imported.");
        Definition definition = definition(resource);
        return new Template(resource, definition.label(), definition.headers(), csv(List.of(definition.headers(), definition.sample())));
    }

    @Transactional
    public Validation validate(UUID operatorId, String actorId, String resource, MultipartFile file) {
        setAuditActor(actorId);
        Definition definition = definition(resource);
        if (resource.equals("organizations")) throw new IllegalArgumentException("Organizations are provisioned through operator onboarding and cannot be imported.");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a CSV file to upload.");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv")) throw new IllegalArgumentException("Choose a CSV file with a .csv extension.");
        if (file.getSize() > MAX_FILE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV files must be 5 MB or smaller.");
        String text = decode(file);
        List<List<String>> records = parse(text);
        if (records.isEmpty()) throw new IllegalArgumentException("The CSV must contain a header row and at least one data row.");
        if (!records.get(0).equals(definition.headers())) throw new IllegalArgumentException("CSV headers must exactly match: " + String.join(",", definition.headers()));
        List<List<String>> data = records.subList(1, records.size());
        if (data.isEmpty()) throw new IllegalArgumentException("The CSV does not contain any data rows.");
        if (data.size() > MAX_ROWS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV files may contain at most 5,000 data rows.");

        List<Staged> accepted = new ArrayList<>();
        List<RowIssue> report = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < data.size(); i++) {
            int rowNumber = i + 2;
            List<String> cells = data.get(i);
            Map<String,String> raw = new LinkedHashMap<>();
            List<FieldIssue> issues = new ArrayList<>();
            if (cells.size() != definition.headers().size()) {
                for (int c=0; c<cells.size(); c++) raw.put(c<definition.headers().size()?definition.headers().get(c):"unexpected_column_"+(c+1),cells.get(c));
                issues.add(new FieldIssue("row", "Expected " + definition.headers().size() + " columns but found " + cells.size() + "."));
            } else {
                for (int c=0; c<cells.size(); c++) raw.put(definition.headers().get(c), cells.get(c).trim());
                try {
                    Map<String,String> fields = resolve(operatorId, resource, raw);
                    Map<String,String> valid = configuration.validateImportRow(operatorId, resource, fields);
                    String unique = uniqueValue(resource, raw, valid);
                    if (!seen.add(unique.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate unique value in this CSV file.");
                    if (existsAlready(operatorId, resource, unique)) throw new IllegalArgumentException("A record with this unique value already exists; imports use create-only mode and do not overwrite existing records.");
                    accepted.add(new Staged(rowNumber, valid));
                } catch (RuntimeException ex) {
                    String message = safeMessage(ex);
                    issues.add(new FieldIssue(fieldFor(resource, message), message));
                }
            }
            report.add(new RowIssue(rowNumber, raw, issues));
        }
        UUID reference = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(900);
        try {
            jdbc.update("DELETE FROM configuration_imports_dtl WHERE expires_at < now() AND status='PENDING'");
            jdbc.update("INSERT INTO configuration_imports_dtl(id,operator_id,actor_id,resource_key,rows_json,errors_json,row_count,expires_at) VALUES(?,?,?,?,?::jsonb,?::jsonb,?,?)",
                reference, operatorId, actorId, resource, mapper.writeValueAsString(accepted), mapper.writeValueAsString(report.stream().filter(row -> !row.errors().isEmpty()).toList()), accepted.size(), java.sql.Timestamp.from(expires));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save the validated import preview.", ex);
        }
        return new Validation(reference, resource, accepted.size(), report.size()-accepted.size(), expires, List.copyOf(report));
    }

    @Transactional
    public ImportResult commit(UUID operatorId, String actorId, String resource, UUID reference) {
        setAuditActor(actorId);
        var rows = jdbc.query("SELECT rows_json::text,errors_json::text,row_count,status,result_json::text FROM configuration_imports_dtl WHERE id=? AND operator_id=? AND actor_id=? AND resource_key=? AND expires_at>now() FOR UPDATE",
            new ColumnMapRowMapper(), reference, operatorId, actorId, resource);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Validated import preview was not found or has expired.");
        Map<String,Object> row = rows.get(0);
        if ("COMPLETED".equals(row.get("status")) && row.get("result_json") != null) {
            try { return mapper.readValue(String.valueOf(row.get("result_json")), ImportResult.class); }
            catch (Exception ex) { throw new IllegalStateException("The completed import receipt could not be read."); }
        }
        if (!"PENDING".equals(row.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This import is no longer available.");
        try {
            List<Map<String,Object>> staged = mapper.readValue(String.valueOf(row.get("rows_json")), JSON_ROWS);
            List<Map<String,Object>> errors = mapper.readValue(String.valueOf(row.get("errors_json")), JSON_ROWS);
            int created=0;
            for (Map<String,Object> value : staged) {
                @SuppressWarnings("unchecked") Map<String,String> fields = (Map<String,String>) value.get("fields");
                configuration.save(operatorId, actorId, resource, new ConfigurationService.SaveRequest(null, fields));
                created++;
            }
            ImportResult result = new ImportResult(reference, resource, created, 0, 0, errors.size());
            jdbc.update("UPDATE configuration_imports_dtl SET status='COMPLETED',result_json=?::jsonb WHERE id=? AND status='PENDING'", mapper.writeValueAsString(result), reference);
            return result;
        } catch (ResponseStatusException | IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("The import could not be committed; no rows were written.", ex); }
    }

    private Map<String,String> resolve(UUID operatorId, String resource, Map<String,String> raw) {
        Map<String,String> fields = new LinkedHashMap<>();
        switch (resource) {
            case "areas" -> { fields.put("code", raw.get("area_code")); fields.put("name", raw.get("area_name")); fields.put("description", raw.get("description")); }
            case "components" -> { fields.put("code", raw.get("component_code")); fields.put("name", raw.get("component_name")); fields.put("description", raw.get("description")); fields.put("areaId", activeId("cabin_areas_mst","code",raw.get("area_code"),operatorId,"area_code")); }
            case "fleets" -> fields.put("name", raw.get("fleet_name"));
            case "aircraft" -> { fields.put("msnNumber",raw.get("msn_number")); fields.put("tailNumber",raw.get("registration")); fields.put("aircraftType",raw.get("aircraft_type")); fields.put("fleetId",activeId("fleets","name",raw.get("fleet_name"),operatorId,"fleet_name")); }
            case "defect-categories" -> { fields.put("code",raw.get("category_code")); fields.put("name",raw.get("category_name")); fields.put("description",raw.get("description")); }
            case "defect-conditions" -> { fields.put("code",raw.get("condition_code")); fields.put("name",raw.get("condition_name")); fields.put("description",raw.get("description")); fields.put("componentIds",activeComponentIds(operatorId,raw.get("applicable_component_codes"))); }
            case "maintenance-teams" -> fields.put("name",raw.get("team_name"));
            case "users" -> { fields.put("externalUserId",raw.get("external_user_id")); fields.put("displayName",raw.get("display_name")); fields.put("role",raw.get("role")); }
            case "menu-items" -> { fields.put("key",raw.get("menu_key")); fields.put("label",raw.get("label")); fields.put("requiredPermissions",raw.get("required_permissions")); fields.put("icon",raw.get("icon")); fields.put("routeKey",raw.get("route_key")); fields.put("displayOrder",raw.get("display_order")); String parent=raw.get("parent_key"); fields.put("parentId", parent==null||parent.isBlank()?"":activeId("navigation_menu_items_mst","menu_key",parent,operatorId,"parent_key")); }
            case "roles" -> { fields.put("roleKey",raw.get("role_key")); fields.put("name",raw.get("role_name")); fields.put("description",raw.get("description")); fields.put("permissions",raw.get("permissions")); }
            case "severities" -> { fields.put("code",raw.get("severity_code")); fields.put("name",raw.get("severity_name")); fields.put("description",raw.get("description")); fields.put("sortOrder",raw.get("sort_order")); }
            case "organizations" -> throw new IllegalArgumentException("Organizations are provisioned through operator onboarding and cannot be imported.");
            default -> throw new IllegalArgumentException("Unknown configuration import resource.");
        }
        return fields;
    }

    private String activeId(String table, String column, String value, UUID operatorId, String field) {
        if(value==null||value.isBlank()) return "";
        Set<String> safeTables=Set.of("cabin_areas_mst","fleets_mst","navigation_menu_items_mst");
        if(!safeTables.contains(table) || !Set.of("code","name","menu_key").contains(column)) throw new IllegalArgumentException("Invalid reference lookup.");
        List<UUID> ids=jdbc.query("SELECT id FROM "+table+" WHERE operator_id=? AND "+column+"=? AND isactive=1",(rs,n)->rs.getObject(1,UUID.class),operatorId,value.trim());
        if(ids.size()!=1) throw new IllegalArgumentException("No unique active tenant-owned record matches " + field + " '" + value.trim() + "'.");
        return ids.get(0).toString();
    }

    private String activeComponentIds(UUID operatorId, String codes) {
        if (codes == null || codes.isBlank()) return "";
        List<String> ids = new ArrayList<>();
        for (String raw : codes.split("\\|")) {
            String code = raw.trim().toUpperCase(Locale.ROOT);
            List<UUID> matches = jdbc.query("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code=? AND isactive=1", (rs,n) -> rs.getObject(1,UUID.class), operatorId, code);
            if (matches.size() != 1) throw new IllegalArgumentException("No unique active tenant-owned component matches component code '" + code + "'.");
            if (ids.contains(matches.get(0).toString())) throw new IllegalArgumentException("Applicable component codes must not contain duplicates.");
            ids.add(matches.get(0).toString());
        }
        return String.join("|",ids);
    }

    private boolean existsAlready(UUID operatorId,String resource,String unique) {
        String sql=switch(resource) {
            case "fleets" -> "SELECT EXISTS(SELECT 1 FROM fleets_mst WHERE operator_id=? AND name=?)";
            case "aircraft" -> "SELECT EXISTS(SELECT 1 FROM aircraft_mst WHERE operator_id=? AND tail_number=?)";
            case "areas" -> "SELECT EXISTS(SELECT 1 FROM cabin_areas_mst WHERE operator_id=? AND code=?)";
            case "defect-categories" -> "SELECT EXISTS(SELECT 1 FROM defect_categories_mst WHERE operator_id=? AND code=?)";
            case "defect-conditions" -> "SELECT EXISTS(SELECT 1 FROM defect_conditions_mst WHERE operator_id=? AND code=?)";
            case "components" -> "SELECT EXISTS(SELECT 1 FROM cabin_components_mst WHERE operator_id=? AND code=?)";
            case "maintenance-teams" -> "SELECT EXISTS(SELECT 1 FROM maintenance_teams_mst WHERE operator_id=? AND name=?)";
            case "users" -> "SELECT EXISTS(SELECT 1 FROM operator_memberships_mst WHERE operator_id=? AND external_user_id=?)";
            case "menu-items" -> "SELECT EXISTS(SELECT 1 FROM navigation_menu_items_mst WHERE operator_id=? AND menu_key=?)";
            case "roles" -> "SELECT EXISTS(SELECT 1 FROM operator_roles_mst WHERE operator_id=? AND role_key=?)";
            case "severities" -> "SELECT EXISTS(SELECT 1 FROM defect_severities_mst WHERE operator_id=? AND severity_code=?)";
            default -> throw new IllegalArgumentException("Imports are not available for this resource.");
        };
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,operatorId,unique));
    }

    private String uniqueValue(String resource,Map<String,String> raw,Map<String,String> fields) {
        String field=switch(resource) { case "aircraft"->"tailNumber"; case "fleets","maintenance-teams"->"name"; case "areas","components","defect-categories","defect-conditions","severities"->"code"; case "users"->"externalUserId"; case "menu-items"->"key"; case "roles"->"roleKey"; default->"name"; };
        String source=switch(resource) { case "aircraft"->"registration"; case "fleets"->"fleet_name"; case "maintenance-teams"->"team_name"; case "areas"->"area_code"; case "components"->"component_code"; case "defect-categories"->"category_code"; case "defect-conditions"->"condition_code"; case "severities"->"severity_code"; case "users"->"external_user_id"; case "menu-items"->"menu_key"; case "roles"->"role_key"; default->"organization_name"; };
        String value=fields.get(field);
        if(value==null||value.isBlank()) value=raw.get(source);
        if(value==null||value.isBlank()) throw new IllegalArgumentException(source+" is required.");
        return value.trim();
    }
    private String fieldFor(String resource,String message) {
        String value=message.toLowerCase(Locale.ROOT);
        return switch(resource) {
            case "aircraft" -> value.contains("fleet")?"fleet_name":value.contains("aircrafttype")?"aircraft_type":value.contains("msn")?"msn_number":"registration";
            case "components" -> value.contains("area")?"area_code":value.contains("name")?"component_name":value.contains("description")?"description":"component_code";
            case "areas" -> value.contains("name")?"area_name":value.contains("description")?"description":"area_code";
            case "defect-categories" -> value.contains("name")?"category_name":value.contains("description")?"description":"category_code";
            case "defect-conditions" -> value.contains("component")?"applicable_component_codes":value.contains("name")?"condition_name":value.contains("description")?"description":"condition_code";
            case "users" -> value.contains("role")?"role":value.contains("display")?"display_name":"external_user_id";
            case "menu-items" -> value.contains("parent")?"parent_key":value.contains("permission")?"required_permissions":value.contains("route")?"route_key":value.contains("order")?"display_order":"menu_key";
            case "roles" -> value.contains("permission")?"permissions":value.contains("name")?"role_name":"role_key";
            case "severities" -> value.contains("order")?"sort_order":value.contains("description")?"description":value.contains("name")?"severity_name":"severity_code";
            case "fleets" -> "fleet_name";
            case "maintenance-teams" -> "team_name";
            default -> "row";
        };
    }
    private Definition definition(String resource) { Definition definition=DEFINITIONS.get(resource); if(definition==null) throw new IllegalArgumentException("Unknown configuration import resource."); return definition; }
    private String safeMessage(RuntimeException ex) { String message=ex.getMessage(); return message==null?"Row validation failed.":message.substring(0,Math.min(500,message.length())); }
    private void setAuditActor(String actorId) { jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)", String.class, actorId); }

    private String decode(MultipartFile file) {
        try {
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            String text=decoder.decode(ByteBuffer.wrap(file.getBytes())).toString();
            return text.startsWith("\uFEFF")?text.substring(1):text;
        } catch(CharacterCodingException ex) { throw new IllegalArgumentException("CSV must use valid UTF-8 encoding."); }
        catch(java.io.IOException ex) { throw new IllegalArgumentException("Could not read the uploaded CSV file."); }
    }

    List<List<String>> parse(String text) {
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder field=new StringBuilder(); boolean quoted=false,closed=false,fieldStarted=false;
        for(int i=0;i<text.length();i++) { char c=text.charAt(i);
            if(quoted) { if(c=='"') { if(i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;} else {quoted=false;closed=true;} } else field.append(c); continue; }
            if(closed && c!=',' && c!='\r' && c!='\n' && !Character.isWhitespace(c)) throw new IllegalArgumentException("Unexpected character after a quoted CSV field.");
            if(c=='"') { if(fieldStarted||field.length()>0) throw new IllegalArgumentException("A quote appeared inside an unquoted CSV field."); quoted=true;fieldStarted=true; }
            else if(c==',') { row.add(field.toString());field.setLength(0);closed=false;fieldStarted=false; }
            else if(c=='\r'||c=='\n') { if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++; row.add(field.toString()); if(row.stream().anyMatch(v->!v.isBlank())) rows.add(List.copyOf(row)); row.clear();field.setLength(0);closed=false;fieldStarted=false; }
            else { field.append(c); if(!Character.isWhitespace(c))fieldStarted=true; }
        }
        if(quoted) throw new IllegalArgumentException("CSV contains an unterminated quoted field.");
        if(field.length()>0||!row.isEmpty()||closed) { row.add(field.toString()); if(row.stream().anyMatch(v->!v.isBlank()))rows.add(List.copyOf(row)); }
        return rows;
    }
    private String csv(List<List<String>> rows) { StringBuilder out=new StringBuilder(); for(List<String> row:rows){for(int i=0;i<row.size();i++){if(i>0)out.append(',');String value=row.get(i)==null?"":row.get(i);out.append('"').append(value.replace("\"","\"\"")).append('"');}out.append("\r\n");}return out.toString(); }

    @Scheduled(fixedDelayString = "PT1H")
    public void purgeExpiredPreviews() { jdbc.update("DELETE FROM configuration_imports_dtl WHERE expires_at < now()"); }
}
