package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.ApiModels.MaintenanceSlaPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class MaintenanceSlaDateRules {
    record Range(LocalDate today,LocalDate minDate,LocalDate maxDate) {}
    private MaintenanceSlaDateRules() {}

    static Range range(MaintenanceSlaPolicy policy,Clock clock){
        if(!policy.configured())throw new ResponseStatusException(HttpStatus.CONFLICT,"Configure the Close Submitted SLA policy in Administration before using this status.");
        final ZoneId zone;
        try{zone=ZoneId.of(policy.businessTimezone());}catch(Exception ex){throw new IllegalStateException("The organization's configured business timezone is invalid.");}
        LocalDate today=LocalDate.now(clock.withZone(zone));
        LocalDate min=policy.allowPast()&&policy.maxPastDays()!=null?today.minusDays(policy.maxPastDays()):today;
        LocalDate max=policy.allowFuture()?today.plusDays(policy.maxFutureDays()):today;
        return new Range(today,min,max);
    }

    static LocalDate validate(MaintenanceSlaPolicy policy,LocalDate date,Clock clock){
        Range range=range(policy,clock);
        if(date==null){if(policy.dateRequired())throw new IllegalArgumentException("SLA Target Date is required.");return null;}
        if(date.isBefore(range.minDate())||date.isAfter(range.maxDate()))throw new IllegalArgumentException("SLA Target Date must be between "+range.minDate()+" and "+range.maxDate()+" ("+policy.businessTimezone()+").");
        return date;
    }
}
