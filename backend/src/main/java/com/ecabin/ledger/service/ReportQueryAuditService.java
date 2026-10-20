package com.ecabin.ledger.service;

import com.ecabin.ledger.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists a query outcome independently so a timed-out/read-only query does not erase its audit event. */
@Service
public class ReportQueryAuditService {
    private final JdbcTemplate jdbc;
    public ReportQueryAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthenticatedUser user, UUID reportId, UUID requestId, String eventType,
            String outcome, long durationMs, int returnedRows) {
        jdbc.update("""
            INSERT INTO report_query_events_mst(operator_id,report_id,actor_id,request_id,event_type,
                outcome,duration_ms,returned_row_count)
            VALUES(?,?,?,?,?,?,?,?)
            """, user.organizationId(), reportId, user.externalUserId(), requestId,
            eventType, outcome, Math.max(0, durationMs), Math.max(0, Math.min(100, returnedRows)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExport(AuthenticatedUser user, UUID reportId, UUID requestId, String outcome,
            long durationMs, long exportedRows) {
        jdbc.update("""
            INSERT INTO report_query_events_mst(operator_id,report_id,actor_id,request_id,event_type,
                outcome,duration_ms,returned_row_count,exported_row_count)
            VALUES(?,?,?,?, 'EXPORT',?,?,0,?)
            """, user.organizationId(), reportId, user.externalUserId(), requestId, outcome,
            Math.max(0, durationMs), Math.max(0, exportedRows));
    }
}
