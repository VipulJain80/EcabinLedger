package com.ecabin.ledger.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Resolves closure-review routing and membership from tenant-owned server data. */
@Service
public class ClosureReviewAccessService {
    private final JdbcTemplate jdbc;

    public ClosureReviewAccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID requireEligibleReviewer(UUID tenantId, String actorId) {
        UUID teamId = jdbc.query("SELECT t.id FROM operators_mst o JOIN maintenance_teams_mst t ON t.operator_id=o.id AND t.id=o.default_closure_team_id AND t.team_type IN ('CLOSURE','REVIEWER') AND t.isactive=1 WHERE o.id=? AND o.isactive=1 AND EXISTS (SELECT 1 FROM operator_memberships_mst m JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 JOIN operator_membership_teams_mst mt ON mt.operator_id=m.operator_id AND mt.external_user_id=m.external_user_id AND mt.team_name=t.name AND mt.isactive=1 WHERE m.operator_id=o.id AND m.external_user_id=? AND m.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|APPROVE|%')", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId, actorId);
        if (teamId == null) throw new AccessDeniedException("You need the APPROVE permission and active membership in the configured closure-review team to act on this defect.");
        return teamId;
    }

    public boolean isEligibleReviewer(UUID tenantId, String actorId) {
        try { requireEligibleReviewer(tenantId, actorId); return true; }
        catch (AccessDeniedException denied) { return false; }
    }

    public boolean mayViewClosureCase(UUID tenantId,String actorId,UUID defectId,String status){
        if(!isEligibleReviewer(tenantId,actorId))return false;
        if("APPROVAL_REQUIRED".equals(status))return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM defect_events_dtl WHERE operator_id=? AND defect_id=? AND event_type IN ('CLOSURE_APPROVED','CLOSURE_REJECTED'))",Boolean.class,tenantId,defectId));
    }
}
