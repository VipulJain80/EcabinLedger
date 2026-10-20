package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaintenanceSlaPolicyService {
    private static final String BUSINESS_TIMEZONE = "Asia/Kolkata";
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Autowired
    public MaintenanceSlaPolicyService(JdbcTemplate jdbc) { this(jdbc,Clock.systemUTC()); }
    MaintenanceSlaPolicyService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}

    public List<MaintenanceSlaPolicy> list(UUID tenant) {
        return jdbc.query("SELECT status_key,is_configured,date_required,allow_future,max_future_days,allow_past,max_past_days,business_timezone FROM maintenance_sla_policies_mst WHERE operator_id=? ORDER BY CASE status_key WHEN 'WORK_IN_PROGRESS' THEN 1 WHEN 'ON_HOLD' THEN 2 ELSE 3 END",
            (rs,n)->new MaintenanceSlaPolicy(rs.getString(1),rs.getBoolean(2),rs.getBoolean(3),rs.getBoolean(4),(Integer)rs.getObject(5),rs.getBoolean(6),(Integer)rs.getObject(7),rs.getString(8)),tenant);
    }

    public MaintenanceSlaPolicy requireConfigured(UUID tenant,String status) {
        return jdbc.query("SELECT status_key,is_configured,date_required,allow_future,max_future_days,allow_past,max_past_days,business_timezone FROM maintenance_sla_policies_mst WHERE operator_id=? AND status_key=?",
            (rs,n)->new MaintenanceSlaPolicy(rs.getString(1),rs.getBoolean(2),rs.getBoolean(3),rs.getBoolean(4),(Integer)rs.getObject(5),rs.getBoolean(6),(Integer)rs.getObject(7),rs.getString(8)),tenant,status).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Maintenance SLA policy is not configured for "+status+"."));
    }

    @Transactional
    public MaintenanceSlaPolicy update(UUID tenant,String actor,MaintenanceSlaPolicyUpdate input) {
        String status=normalizeStatus(input.statusKey());
        validate(input);
        jdbc.queryForObject("SELECT set_config('ecabin.actor_id', ?, true)",String.class,actor);
        Map<String,Object> before=jdbc.query("SELECT status_key,is_configured,date_required,allow_future,max_future_days,allow_past,max_past_days,business_timezone FROM maintenance_sla_policies_mst WHERE operator_id=? AND status_key=? FOR UPDATE",new ColumnMapRowMapper(),tenant,status).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Maintenance SLA policy was not found."));
        int changed=jdbc.update("UPDATE maintenance_sla_policies_mst SET is_configured=true,date_required=?,allow_future=?,max_future_days=?,allow_past=?,max_past_days=?,business_timezone=?,updated_by=?,updated_at=now() WHERE operator_id=? AND status_key=?",
            input.dateRequired(),input.allowFuture(),input.allowFuture()?input.maxFutureDays():null,input.allowPast(),input.allowPast()?input.maxPastDays():null,input.businessTimezone().trim(),actor,tenant,status);
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Maintenance SLA policy changed. Reload and try again.");
        MaintenanceSlaPolicy after=requireConfigured(tenant,status);
        jdbc.update("INSERT INTO defect_events_dtl(operator_id,event_type,note,actor_id,entity_type,entity_id,previous_state,new_state,request_id) VALUES(?, 'MAINTENANCE_SLA_POLICY_UPDATED', ?, ?, 'MAINTENANCE_SLA_POLICY', ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)",tenant,input.reason().trim(),actor,UUID.nameUUIDFromBytes((tenant+":"+status).getBytes(java.nio.charset.StandardCharsets.UTF_8)),new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(before).toString(),new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(after).toString(),MDC.get("request_id"));
        return after;
    }

    public LocalDate validateDate(UUID tenant,String status,LocalDate date) {
        MaintenanceSlaPolicy policy=requireConfigured(tenant,status);
        return MaintenanceSlaDateRules.validate(policy,date,clock);
    }

    public MaintenanceSlaEffective effective(UUID tenant,String status) {
        MaintenanceSlaPolicy policy=requireConfigured(tenant,status);
        MaintenanceSlaDateRules.Range range=MaintenanceSlaDateRules.range(policy,clock);
        return new MaintenanceSlaEffective(policy,range.today(),range.minDate(),range.maxDate());
    }

    private void validate(MaintenanceSlaPolicyUpdate p){
        normalizeStatus(p.statusKey());
        if(p.reason()==null||p.reason().isBlank()||p.reason().length()>1000)throw new IllegalArgumentException("Provide a change reason up to 1,000 characters.");
        if(!BUSINESS_TIMEZONE.equals(p.businessTimezone()==null?null:p.businessTimezone().trim()))throw new IllegalArgumentException("The business timezone must be India Standard Time (Asia/Kolkata).");
        if(p.allowFuture())bound(p.maxFutureDays(),"Maximum future days");else if(p.maxFutureDays()!=null)throw new IllegalArgumentException("Clear maximum future days when future dates are disabled.");
        if(p.allowPast())bound(p.maxPastDays(),"Maximum past days");else if(p.maxPastDays()!=null)throw new IllegalArgumentException("Clear maximum past days when past dates are disabled.");
    }
    private void bound(Integer days,String label){if(days==null||days<0||days>3650)throw new IllegalArgumentException(label+" must be between 0 and 3,650.");}
    private String normalizeStatus(String value){if(value==null||!List.of("WORK_IN_PROGRESS","ON_HOLD","CLOSE_SUBMITTED").contains(value))throw new IllegalArgumentException("Choose a supported maintenance status.");return value;}
}
