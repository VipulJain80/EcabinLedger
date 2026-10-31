package com.ecabin.ledger.service;

import com.ecabin.ledger.api.ApiModels.WorkflowStatusPage;
import com.ecabin.ledger.api.ApiModels.WorkflowStatusView;
import com.ecabin.ledger.api.ApiModels.WorkflowStatusUpdateRequest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class WorkflowStatusService {
    private static final RowMapper<WorkflowStatusView> MAPPER = (rs, row) -> new WorkflowStatusView(
        rs.getObject("id", UUID.class), rs.getString("module_code"), rs.getString("status_code"),
        rs.getString("display_name"), rs.getString("alias_name"), rs.getString("description"),
        rs.getBoolean("is_active"), rs.getInt("sort_order"), rs.getBoolean("is_initial"),
        rs.getBoolean("is_terminal"), rs.getString("background_color"), rs.getString("text_color"),
        rs.getString("border_color"), rs.getString("icon"), rs.getString("display_style"));

    private final JdbcTemplate jdbc;
    private TenantReferenceCache referenceCache;

    public WorkflowStatusService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setReferenceCache(TenantReferenceCache referenceCache) { this.referenceCache = referenceCache; }

    public WorkflowStatusPage list(String moduleCode, boolean activeOnly, int page, int size, String search, String sortBy, String sortDirection) {
        return list(moduleCode,activeOnly,page,size,search,sortBy,sortDirection,false);
    }

    public WorkflowStatusPage refreshList(String moduleCode, boolean activeOnly, int page, int size, String search, String sortBy, String sortDirection) {
        return list(moduleCode,activeOnly,page,size,search,sortBy,sortDirection,true);
    }

    private WorkflowStatusPage list(String moduleCode, boolean activeOnly, int page, int size, String search, String sortBy, String sortDirection, boolean refresh) {
        String key=String.join("|",String.valueOf(moduleCode),String.valueOf(activeOnly),String.valueOf(page),String.valueOf(size),String.valueOf(search),String.valueOf(sortBy),String.valueOf(sortDirection));
        if(referenceCache==null)return listUncached(moduleCode,activeOnly,page,size,search,sortBy,sortDirection);
        return refresh?referenceCache.refresh(TenantReferenceCache.Resource.WORKFLOW_STATUSES,null,key,()->listUncached(moduleCode,activeOnly,page,size,search,sortBy,sortDirection))
            :referenceCache.get(TenantReferenceCache.Resource.WORKFLOW_STATUSES,null,key,()->listUncached(moduleCode,activeOnly,page,size,search,sortBy,sortDirection));
    }

    private WorkflowStatusPage listUncached(String moduleCode, boolean activeOnly, int page, int size, String search, String sortBy, String sortDirection) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Choose a valid page and a page size from 1 to 200.");
        String module = moduleCode == null || moduleCode.isBlank() ? null : moduleCode.trim().toUpperCase(Locale.ROOT);
        if (module != null && !module.matches("[A-Z][A-Z0-9_]{0,49}")) throw new IllegalArgumentException("Module code is invalid.");
        String query = search == null || search.isBlank() ? null : search.trim();
        if (query != null && query.length() > 100) throw new IllegalArgumentException("Search text cannot exceed 100 characters.");
        String orderColumn = switch (sortBy == null ? "sortOrder" : sortBy) {
            case "statusCode" -> "status_code";
            case "displayName" -> "display_name";
            case "aliasName" -> "alias_name";
            case "isActive" -> "is_active";
            case "sortOrder" -> "sort_order";
            default -> throw new IllegalArgumentException("Unsupported workflow status sort field.");
        };
        String direction = "DESC".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        String where = " WHERE (?::varchar IS NULL OR module_code=?) AND (?=FALSE OR is_active=TRUE) AND (?::varchar IS NULL OR status_code ILIKE '%'||?||'%' OR display_name ILIKE '%'||?||'%' OR COALESCE(alias_name,'') ILIKE '%'||?||'%')";
        Long count = jdbc.queryForObject("SELECT count(*) FROM workflow_status_master" + where, Long.class,
            module, module, activeOnly, query, query, query, query);
        long total = count == null ? 0 : count;
        List<WorkflowStatusView> items = jdbc.query("SELECT id,module_code,status_code,display_name,alias_name,description,is_active,sort_order,is_initial,is_terminal,background_color,text_color,border_color,icon,display_style FROM workflow_status_master" + where + " ORDER BY module_code," + orderColumn + " " + direction + ",status_code LIMIT ? OFFSET ?",
            MAPPER, module, module, activeOnly, query, query, query, query, size, (long) page * size);
        return new WorkflowStatusPage(items, page, size, total, Math.max(1, (int) Math.ceil((double) total / size)));
    }

    public WorkflowStatusView get(UUID id) {
        WorkflowStatusView found = jdbc.query("SELECT id,module_code,status_code,display_name,alias_name,description,is_active,sort_order,is_initial,is_terminal,background_color,text_color,border_color,icon,display_style FROM workflow_status_master WHERE id=?",
            rs -> rs.next() ? MAPPER.mapRow(rs, 0) : null, id);
        if (found == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow status was not found.");
        return found;
    }

    @Transactional
    public WorkflowStatusView update(UUID id, WorkflowStatusUpdateRequest request, UUID operatorId, String actorId) {
        if (request.displayName() == null && request.aliasName() == null && request.description() == null
                && request.sortOrder() == null && request.isActive() == null) {
            throw new IllegalArgumentException("Provide at least one status metadata field to update.");
        }
        if (request.displayName() != null && request.displayName().isBlank()) throw new IllegalArgumentException("Display name is required.");
        if (request.displayStyle() != null && !List.of("SOLID", "OUTLINE", "SOFT").contains(request.displayStyle())) throw new IllegalArgumentException("Display style must be SOLID, OUTLINE, or SOFT.");
        if (request.icon() != null && request.icon().codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Badge icon contains unsupported control characters.");
        WorkflowStatusView existing = get(id);
        BadgeColorRules.requireAccessible(
            request.backgroundColor() == null ? existing.backgroundColor() : request.backgroundColor(),
            request.textColor() == null ? existing.textColor() : request.textColor(),
            request.borderColor() == null ? existing.borderColor() : request.borderColor(),
            request.displayStyle() == null ? existing.displayStyle() : request.displayStyle());
        jdbc.update("UPDATE workflow_status_master SET display_name=COALESCE(?,display_name), alias_name=CASE WHEN ? THEN NULLIF(?, '') ELSE alias_name END, description=CASE WHEN ? THEN NULLIF(?, '') ELSE description END, sort_order=COALESCE(?,sort_order), is_active=COALESCE(?,is_active), background_color=COALESCE(?,background_color), text_color=COALESCE(?,text_color), border_color=COALESCE(?,border_color), icon=CASE WHEN ? THEN NULLIF(?, '') ELSE icon END, display_style=COALESCE(?,display_style), updated_at=clock_timestamp() WHERE id=?",
            request.displayName() == null ? null : request.displayName().trim(), request.aliasName() != null, request.aliasName() == null ? null : request.aliasName().trim(), request.description() != null,
            request.description() == null ? null : request.description().trim(), request.sortOrder(), request.isActive(), request.backgroundColor(), request.textColor(), request.borderColor(), request.icon() != null, request.icon() == null ? null : request.icon().trim(), request.displayStyle(), id);
        WorkflowStatusView updated = get(existing.id());
        String reason = request.reason().trim();
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,defect_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state) VALUES(?,NULL,'WORKFLOW_STATUS_UPDATED',?,?, 'WORKFLOW_STATUS', ?,jsonb_build_object('displayName',?::text,'aliasName',?::text,'description',?::text,'sortOrder',?::integer,'isActive',?::boolean,'backgroundColor',?::text,'textColor',?::text,'borderColor',?::text,'icon',?::text,'displayStyle',?::text),jsonb_build_object('displayName',?::text,'aliasName',?::text,'description',?::text,'sortOrder',?::integer,'isActive',?::boolean,'backgroundColor',?::text,'textColor',?::text,'borderColor',?::text,'icon',?::text,'displayStyle',?::text))",
            operatorId, "Workflow status " + existing.statusCode() + " updated. Reason: " + reason, actorId, id,
            existing.displayName(), existing.aliasName(), existing.description(), existing.sortOrder(), existing.isActive(), existing.backgroundColor(), existing.textColor(), existing.borderColor(), existing.icon(), existing.displayStyle(),
            updated.displayName(), updated.aliasName(), updated.description(), updated.sortOrder(), updated.isActive(), updated.backgroundColor(), updated.textColor(), updated.borderColor(), updated.icon(), updated.displayStyle());
        if(referenceCache!=null)referenceCache.invalidateGlobalAfterCommit(TenantReferenceCache.Resource.WORKFLOW_STATUSES);
        return updated;
    }

    /** Used by workflow services when they are given a new status, never to authorize a transition. */
    public static void requireActive(JdbcTemplate jdbc, String statusCode) {
        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow_status_master WHERE module_code='DEFECT' AND status_code=? AND is_active=TRUE)", Boolean.class, statusCode) != Boolean.TRUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The requested defect status is inactive or is not configured.");
        }
    }
}
