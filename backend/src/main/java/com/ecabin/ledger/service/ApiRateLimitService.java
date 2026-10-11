package com.ecabin.ledger.service;

import com.ecabin.ledger.security.AuthenticatedUser;
import com.ecabin.ledger.security.Permission;
import com.ecabin.ledger.security.PermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tenant policy management and shared PostgreSQL fixed-window enforcement. */
@Service
public class ApiRateLimitService {
    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitService.class);
    public static final Set<String> ROUTES = Set.of("ORDINARY_READS", "DEFECT_CREATE", "WORKFLOW_MUTATIONS", "EXPENSIVE_SEARCH", "CSV_IMPORTS", "CSV_EXPORTS", "EVIDENCE_UPLOADS");
    public record Policy(UUID id, String routeKey, String name, String description, String scopeType, int maxRequests,
                         int windowSeconds, boolean active, Instant createdAt, Instant updatedAt) {}
    public record PolicyInput(String routeKey, String name, String description, String scopeType, Integer maxRequests,
                              Integer windowSeconds, String reason) {}
    public record Page<T>(List<T> items, int page, int size, long total) {}
    public record Evaluation(boolean allowed, long count, int limit, long retryAfterSeconds, String routeKey) {}
    private record Rule(UUID id, String routeKey, String scopeType, int maxRequests, int windowSeconds, boolean active) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final PermissionService permissions;

    public ApiRateLimitService(JdbcTemplate jdbc, ObjectMapper json, MeterRegistry metrics, PermissionService permissions) {
        this.jdbc = jdbc; this.json = json; this.metrics = metrics; this.permissions = permissions;
    }

    public String classify(String method, String path) {
        if ("POST".equals(method) && path.matches("/api/v1/defects/?")) return "DEFECT_CREATE";
        if (path.matches("/api/v1/[^/]+/imports(/validate)?/?") || path.matches("/api/v1/configuration/[^/]+/imports(/validate)?/?")) return "CSV_IMPORTS";
        if (path.matches("/api/v1/.*/attachments/?") || path.matches("/api/v1/attachments/[^/]+/remove/?")) return "EVIDENCE_UPLOADS";
        if ("GET".equals(method) && (path.endsWith("/export") || path.matches("/api/v1/fleet-status/export/?"))) return "CSV_EXPORTS";
        if ("GET".equals(method) && (path.startsWith("/api/v1/dashboard/") || path.startsWith("/api/v1/fleet-status") || path.startsWith("/api/v1/review-queue") || path.startsWith("/api/v1/maintenance-work-queue") || path.matches("/api/v1/aircraft/[^/]+/defects/?"))) return "EXPENSIVE_SEARCH";
        return "GET".equals(method) ? "ORDINARY_READS" : "WORKFLOW_MUTATIONS";
    }

    /** Every replica reads the current rows and increments the same database counter atomically. */
    public Evaluation evaluate(AuthenticatedUser user, String routeKey) {
        try {
            List<Rule> tenant = jdbc.query("SELECT id,route_key,scope_type,max_requests,window_seconds,isactive FROM api_rate_limit_policies_mst WHERE operator_id=? AND route_key=? ORDER BY CASE scope_type WHEN 'TENANT' THEN 0 ELSE 1 END", (rs, n) -> new Rule(rs.getObject("id", UUID.class), rs.getString("route_key"), rs.getString("scope_type"), rs.getInt("max_requests"), rs.getInt("window_seconds"), rs.getInt("isactive") == 1), user.organizationId(), routeKey);
            Rule userRule = tenant.stream().filter(r -> r.scopeType.equals("PER_USER")).findFirst().orElseThrow(() -> new DataAccessException("Missing default rate limit policy") {});
            Rule override = tenant.stream().filter(r -> r.scopeType.equals("TENANT") && r.active).findFirst().orElse(null);
            Rule effective = override != null ? override : userRule.active ? userRule : builtIn(user.organizationId(), routeKey, userRule.id);
            String scopeType = override != null ? "TENANT" : "PER_USER";
            String scopeValue = override != null ? user.organizationId().toString() : user.externalUserId();
            Instant now = Instant.now();
            Instant bucket = now.truncatedTo(ChronoUnit.SECONDS);
            long epoch = bucket.getEpochSecond();
            bucket = Instant.ofEpochSecond(epoch - Math.floorMod(epoch, effective.windowSeconds));
            String digest = digest(scopeType + ":" + scopeValue);
            long count = jdbc.queryForObject("INSERT INTO api_rate_limit_usage_dtl(operator_id,policy_id,scope_hash,window_start,request_count,rejected_count) VALUES(?,?,?,?,1,CASE WHEN 1>? THEN 1 ELSE 0 END) ON CONFLICT(operator_id,policy_id,scope_hash,window_start) DO UPDATE SET request_count=api_rate_limit_usage_dtl.request_count+1,rejected_count=api_rate_limit_usage_dtl.rejected_count+CASE WHEN api_rate_limit_usage_dtl.request_count+1>? THEN 1 ELSE 0 END,updated_at=now() RETURNING request_count", Long.class, user.organizationId(), effective.id, digest, java.sql.Timestamp.from(bucket), effective.maxRequests, effective.maxRequests);
            metrics.counter("ecabin.api.rate_limit.evaluated", "category", routeKey.toLowerCase()).increment();
            boolean allowed = count <= effective.maxRequests;
            if (!allowed) metrics.counter("ecabin.api.rate_limit.rejected", "category", routeKey.toLowerCase()).increment();
            long retry = Math.max(1, bucket.plusSeconds(effective.windowSeconds).getEpochSecond() - now.getEpochSecond());
            return new Evaluation(allowed, count, effective.maxRequests, retry, routeKey);
        } catch (DataAccessException ex) {
            log.error("Shared API rate-limit enforcement query failed; refusing request", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Rate-limit enforcement is temporarily unavailable.");
        }
    }

    public boolean evaluatePublic(String routeKey, String remoteAddress, int limit) {
        Instant now = Instant.now(); Instant bucket = now.truncatedTo(ChronoUnit.MINUTES);
        String hash = digest(remoteAddress == null ? "unknown" : remoteAddress);
        try {
            Long count = jdbc.queryForObject("INSERT INTO api_rate_limit_public_usage_dtl(route_key,scope_hash,window_start,request_count,rejected_count) VALUES(?,?,?,1,CASE WHEN 1>? THEN 1 ELSE 0 END) ON CONFLICT(route_key,scope_hash,window_start) DO UPDATE SET request_count=api_rate_limit_public_usage_dtl.request_count+1,rejected_count=api_rate_limit_public_usage_dtl.rejected_count+CASE WHEN api_rate_limit_public_usage_dtl.request_count+1>? THEN 1 ELSE 0 END,updated_at=now() RETURNING request_count", Long.class, routeKey, hash, java.sql.Timestamp.from(bucket), limit, limit);
            return count != null && count <= limit;
        } catch (DataAccessException ex) { log.error("Public API rate-limit enforcement query failed; refusing request", ex); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Rate-limit enforcement is temporarily unavailable."); }
    }

    public Page<Policy> list(AuthenticatedUser user, int page, int size, String search, String routeKey, String status) {
        requireAdmin(user); int offset = page * size; String q = search == null ? "" : search.trim();
        if (routeKey != null && !ROUTES.contains(routeKey)) throw new IllegalArgumentException("Select an approved API category.");
        String route = routeKey != null && ROUTES.contains(routeKey) ? routeKey : null;
        String active = "ACTIVE".equals(status) ? "1" : "INACTIVE".equals(status) ? "2" : null;
        Long total = jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_policies_mst WHERE operator_id=? AND (?='' OR policy_name ILIKE '%'||?||'%' OR description ILIKE '%'||?||'%') AND (CAST(? AS varchar) IS NULL OR route_key=?) AND (CAST(? AS varchar) IS NULL OR isactive=CAST(? AS smallint))", Long.class,user.organizationId(),q,q,q,route,route,active,active);
        List<Policy> values = jdbc.query("SELECT * FROM api_rate_limit_policies_mst WHERE operator_id=? AND (?='' OR policy_name ILIKE '%'||?||'%' OR description ILIKE '%'||?||'%') AND (CAST(? AS varchar) IS NULL OR route_key=?) AND (CAST(? AS varchar) IS NULL OR isactive=CAST(? AS smallint)) ORDER BY route_key,scope_type LIMIT ? OFFSET ?", (rs,n)->readPolicy(rs),user.organizationId(),q,q,q,route,route,active,active,size,offset);
        return new Page<>(values,page,size,total == null ? 0 : total);
    }

    public Policy get(AuthenticatedUser user, UUID id) { requireAdmin(user); return jdbc.query("SELECT * FROM api_rate_limit_policies_mst WHERE operator_id=? AND id=?", (rs,n)->readPolicy(rs),user.organizationId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Rate-limit policy not found.")); }

    @Transactional
    public Policy create(AuthenticatedUser user, PolicyInput input, String requestId) {
        requireAdmin(user); validate(input);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO api_rate_limit_policies_mst(id,operator_id,route_key,policy_name,description,scope_type,max_requests,window_seconds,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?)",id,user.organizationId(),input.routeKey(),input.name().trim(),input.description()==null?"":input.description().trim(),input.scopeType(),input.maxRequests(),input.windowSeconds(),user.externalUserId(),user.externalUserId());
        Policy policy=get(user,id); audit(user,policy,"CREATED",null,input.reason(),requestId); return policy;
    }

    @Transactional
    public Policy update(AuthenticatedUser user, UUID id, PolicyInput input, String requestId) {
        requireAdmin(user); validate(input); Policy old=get(user,id);
        if (!old.routeKey().equals(input.routeKey()) || !old.scopeType().equals(input.scopeType())) throw new IllegalArgumentException("Route category and enforcement scope are fixed when a policy is created.");
        jdbc.update("UPDATE api_rate_limit_policies_mst SET policy_name=?,description=?,max_requests=?,window_seconds=?,updated_at=now(),updated_by=? WHERE operator_id=? AND id=?",input.name().trim(),input.description()==null?"":input.description().trim(),input.maxRequests(),input.windowSeconds(),user.externalUserId(),user.organizationId(),id);
        Policy policy=get(user,id); audit(user,policy,"UPDATED",old,input.reason(),requestId); return policy;
    }

    @Transactional
    public Policy setActive(AuthenticatedUser user, UUID id, boolean active, String reason, String requestId) {
        requireAdmin(user); if(reason==null||reason.isBlank()||reason.length()>1000) throw new IllegalArgumentException("A change reason between 1 and 1000 characters is required.");
        Policy old=get(user,id); jdbc.update("UPDATE api_rate_limit_policies_mst SET isactive=?,updated_at=now(),updated_by=? WHERE operator_id=? AND id=?",active?1:2,user.externalUserId(),user.organizationId(),id);
        Policy policy=get(user,id); audit(user,policy,active?"ACTIVATED":"DEACTIVATED",old,reason,requestId); return policy;
    }

    public Map<String,Object> usage(AuthenticatedUser user) {
        requireAdmin(user);
        Map<String,Object> summary=jdbc.queryForMap("SELECT COALESCE(sum(request_count),0) AS total_requests,COALESCE(sum(rejected_count),0) AS blocked_requests FROM api_rate_limit_usage_dtl WHERE operator_id=? AND window_start>=now()-interval '24 hours'",user.organizationId());
        List<Map<String,Object>> categories=jdbc.queryForList("SELECT p.route_key,p.policy_name,p.scope_type,p.max_requests,p.window_seconds,p.isactive,COALESCE(sum(u.request_count),0) AS requests_24h,COALESCE(sum(u.rejected_count),0) AS rejected_24h,COALESCE(max(u.request_count),0) AS peak_bucket_requests FROM api_rate_limit_policies_mst p LEFT JOIN api_rate_limit_usage_dtl u ON u.operator_id=p.operator_id AND u.policy_id=p.id AND u.window_start>=now()-interval '24 hours' WHERE p.operator_id=? GROUP BY p.id ORDER BY p.route_key,p.scope_type",user.organizationId());
        return Map.of("windowHours",24,"totalRequests",summary.get("total_requests"),"blockedRequests",summary.get("blocked_requests"),"categories",categories,"source","postgresql_collected_counters");
    }

    public List<Map<String,Object>> effective(AuthenticatedUser user) {
        requireAdmin(user);
        return jdbc.query("SELECT d.route_key,d.max_requests AS default_limit,d.window_seconds AS default_window,d.isactive AS default_active,o.max_requests AS tenant_limit,o.window_seconds AS tenant_window,o.isactive AS tenant_active,CASE WHEN o.id IS NOT NULL AND o.isactive=1 THEN o.max_requests WHEN d.isactive=1 THEN d.max_requests WHEN d.route_key='ORDINARY_READS' THEN 120 WHEN d.route_key IN ('CSV_IMPORTS','CSV_EXPORTS','EVIDENCE_UPLOADS') THEN 10 ELSE 30 END AS effective_limit,CASE WHEN o.id IS NOT NULL AND o.isactive=1 THEN o.window_seconds WHEN d.isactive=1 THEN d.window_seconds ELSE 60 END AS effective_window,CASE WHEN o.id IS NOT NULL AND o.isactive=1 THEN 'TENANT_OVERRIDE' WHEN d.isactive=1 THEN 'PER_USER' ELSE 'SERVER_DEFAULT' END AS source FROM api_rate_limit_policies_mst d LEFT JOIN api_rate_limit_policies_mst o ON o.operator_id=d.operator_id AND o.route_key=d.route_key AND o.scope_type='TENANT' WHERE d.operator_id=? AND d.scope_type='PER_USER' ORDER BY d.route_key",(rs,n)->Map.<String,Object>of("routeKey",rs.getString("route_key"),"effectiveLimit",rs.getInt("effective_limit"),"effectiveWindowSeconds",rs.getInt("effective_window"),"source",rs.getString("source"),"defaultActive",rs.getInt("default_active")==1,"tenantOverrideConfigured",rs.getObject("tenant_limit")!=null,"tenantOverrideActive",rs.getObject("tenant_active")!=null&&rs.getInt("tenant_active")==1),user.organizationId());
    }

    public Page<Map<String,Object>> history(AuthenticatedUser user,int page,int size) {
        requireAdmin(user); long total=jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_policy_events_dtl WHERE operator_id=?",Long.class,user.organizationId());
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT e.id,e.policy_id,e.actor_id,e.event_type,e.reason,e.previous_values,e.new_values,e.request_id,e.occurred_at,p.policy_name,p.route_key FROM api_rate_limit_policy_events_dtl e JOIN api_rate_limit_policies_mst p ON p.id=e.policy_id AND p.operator_id=e.operator_id WHERE e.operator_id=? ORDER BY e.occurred_at DESC LIMIT ? OFFSET ?",user.organizationId(),size,page*size);
        return new Page<>(rows,page,size,total);
    }

    @Scheduled(cron = "0 17 * * * *")
    public void purgeOldUsage() {
        jdbc.update("DELETE FROM api_rate_limit_usage_dtl WHERE window_start < now()-interval '30 days'");
        jdbc.update("DELETE FROM api_rate_limit_public_usage_dtl WHERE window_start < now()-interval '30 days'");
    }

    public List<Map<String,Object>> catalog(AuthenticatedUser user) {
        requireAdmin(user);
        return ROUTES.stream().sorted().map(key->Map.<String,Object>of("routeKey",key,"routePattern",routePattern(key),"methods",methods(key),"defaultLimit",defaultLimit(key),"defaultWindowSeconds",60,"maximumLimit",maximumLimit(key),"maximumWindowSeconds",3600,"supportsBurst",false)).toList();
    }

    void validate(PolicyInput input) {
        if(input==null||input.routeKey()==null||!ROUTES.contains(input.routeKey())) throw new IllegalArgumentException("Select an approved API category.");
        if(input.name()==null||input.name().isBlank()||input.name().length()>120) throw new IllegalArgumentException("Policy name is required and must be at most 120 characters.");
        if(input.scopeType()==null||!Set.of("PER_USER","TENANT").contains(input.scopeType())) throw new IllegalArgumentException("Select per-user or tenant enforcement scope.");
        if(input.maxRequests()==null||input.maxRequests()<1||input.maxRequests()>maximumLimit(input.routeKey())) throw new IllegalArgumentException("Request limit is outside the centrally allowed range for this category.");
        if(input.windowSeconds()==null||input.windowSeconds()<1||input.windowSeconds()>3600) throw new IllegalArgumentException("Time window must be between 1 and 3600 seconds.");
        if(input.description()!=null&&input.description().length()>500) throw new IllegalArgumentException("Description must be at most 500 characters.");
        if(input.reason()==null||input.reason().isBlank()||input.reason().length()>1000) throw new IllegalArgumentException("A change reason between 1 and 1000 characters is required.");
    }
    private int maximumLimit(String key) { return switch(key) { case "ORDINARY_READS" -> 1200; case "CSV_IMPORTS","CSV_EXPORTS","EVIDENCE_UPLOADS" -> 100; default -> 300; }; }
    private int defaultLimit(String key) { return switch(key) { case "ORDINARY_READS" -> 120; case "CSV_IMPORTS","CSV_EXPORTS","EVIDENCE_UPLOADS" -> 10; default -> 30; }; }
    private String routePattern(String key) { return switch(key) { case "DEFECT_CREATE" -> "POST /api/v1/defects"; case "CSV_IMPORTS" -> "POST /api/v1/configuration/{resource}/imports/**"; case "CSV_EXPORTS" -> "GET /api/v1/configuration/{resource}/export"; case "EVIDENCE_UPLOADS" -> "POST /api/v1/defects/{id}/forms/**/attachments"; case "EXPENSIVE_SEARCH" -> "GET /api/v1/dashboard/**, /fleet-status/**, /review-queue, /maintenance-work-queue, /aircraft/{id}/defects"; case "ORDINARY_READS" -> "GET other approved /api/v1/**"; default -> "POST other approved /api/v1/**"; }; }
    private List<String> methods(String key) { return switch(key) { case "DEFECT_CREATE","CSV_IMPORTS","EVIDENCE_UPLOADS","WORKFLOW_MUTATIONS" -> List.of("POST"); case "CSV_EXPORTS","EXPENSIVE_SEARCH","ORDINARY_READS" -> List.of("GET"); default -> List.of("GET","POST"); }; }
    private Rule builtIn(UUID tenant,String key,UUID id) { return new Rule(id,key,"PER_USER",defaultLimit(key),60,true); }
    private void requireAdmin(AuthenticatedUser user) { permissions.require(user, Permission.ADMINISTER_ORGANIZATION); }
    private Policy readPolicy(java.sql.ResultSet rs) throws java.sql.SQLException { return new Policy(rs.getObject("id",UUID.class),rs.getString("route_key"),rs.getString("policy_name"),rs.getString("description"),rs.getString("scope_type"),rs.getInt("max_requests"),rs.getInt("window_seconds"),rs.getInt("isactive")==1,rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()); }
    private void audit(AuthenticatedUser user,Policy policy,String event,Policy previous,String reason,String requestId) {
        try { jdbc.update("INSERT INTO api_rate_limit_policy_events_dtl(operator_id,policy_id,actor_id,event_type,reason,previous_values,new_values,request_id) VALUES(?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)",user.organizationId(),policy.id(),user.externalUserId(),event,reason.trim(),previous==null?null:json(previous),json(policy),requestId); }
        catch(JsonProcessingException ex){throw new IllegalStateException("Could not record policy audit history.",ex);}
    }
    private String json(Policy p) throws JsonProcessingException { return json.writeValueAsString(p); }
    private String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
}
