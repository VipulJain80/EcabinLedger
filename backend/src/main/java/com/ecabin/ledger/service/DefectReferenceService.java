package com.ecabin.ledger.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Allocates immutable, tenant-scoped human-readable defect references atomically in PostgreSQL. */
@Service
public class DefectReferenceService {
    private final JdbcTemplate jdbc;

    public DefectReferenceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID operatorId, UUID areaId) {
        String shortcode = jdbc.query(
            "SELECT shortcode FROM cabin_areas_mst WHERE operator_id=? AND id=? AND isactive=1 FOR SHARE",
            rs -> rs.next() ? rs.getString("shortcode") : null, operatorId, areaId);
        if (shortcode == null) throw new IllegalArgumentException("Choose an active area belonging to this organization.");

        // UTC is the documented business-date policy until the organization has an
        // explicit, authoritative business timezone setting.
        java.sql.Timestamp createdAt = jdbc.queryForObject("SELECT transaction_timestamp()", java.sql.Timestamp.class);
        if (createdAt == null) throw new IllegalStateException("Could not resolve the server creation timestamp.");
        LocalDate businessDate = createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        Long sequence = jdbc.queryForObject(
            "INSERT INTO defect_reference_counters_dtl(operator_id,business_date,area_shortcode,last_sequence) VALUES(?,?,?,1) " +
                "ON CONFLICT(operator_id,business_date,area_shortcode) DO UPDATE SET last_sequence=defect_reference_counters_dtl.last_sequence+1,updated_at=clock_timestamp() " +
                "RETURNING last_sequence",
            Long.class, operatorId, businessDate, shortcode);
        if (sequence == null || sequence < 1) throw new IllegalStateException("Could not allocate the defect reference sequence.");
        return format(businessDate, shortcode, sequence);
    }

    static String format(LocalDate date, String shortcode, long sequence) {
        if (date == null || shortcode == null || !shortcode.matches("[A-Z0-9]{2,10}") || sequence < 1)
            throw new IllegalArgumentException("A valid date, area shortcode and positive sequence are required.");
        return String.format(Locale.ROOT, "%s-%s-%02d", date.toString().replace("-", ""), shortcode, sequence);
    }
}
