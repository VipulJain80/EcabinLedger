package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ecabin.ledger.api.ApiModels.MaintenanceSlaPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MaintenanceSlaDateRulesTest {
    private final Clock today=Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"),ZoneOffset.UTC);
    @Test void wipIncludesTodayAndFifteenDaysButNotDaySixteen(){
        var policy=new MaintenanceSlaPolicy("WORK_IN_PROGRESS",true,true,true,15,false,null,"UTC");
        assertEquals(LocalDate.parse("2026-10-14"),MaintenanceSlaDateRules.validate(policy,LocalDate.parse("2026-10-14"),today));
        assertEquals(LocalDate.parse("2026-10-29"),MaintenanceSlaDateRules.validate(policy,LocalDate.parse("2026-10-29"),today));
        assertThrows(IllegalArgumentException.class,()->MaintenanceSlaDateRules.validate(policy,LocalDate.parse("2026-10-30"),today));
        assertThrows(IllegalArgumentException.class,()->MaintenanceSlaDateRules.validate(policy,LocalDate.parse("2026-10-13"),today));
    }
    @Test void onHoldAllowsOneHundredEightyDaysAndOptionalPastPolicyIsBounded(){
        var onHold=new MaintenanceSlaPolicy("ON_HOLD",true,true,true,180,false,null,"UTC");
        assertEquals(LocalDate.parse("2027-04-12"),MaintenanceSlaDateRules.validate(onHold,LocalDate.parse("2027-04-12"),today));
        assertThrows(IllegalArgumentException.class,()->MaintenanceSlaDateRules.validate(onHold,LocalDate.parse("2027-04-13"),today));
        var past=new MaintenanceSlaPolicy("WORK_IN_PROGRESS",true,true,false,null,true,5,"UTC");
        assertEquals(LocalDate.parse("2026-10-09"),MaintenanceSlaDateRules.validate(past,LocalDate.parse("2026-10-09"),today));
        assertThrows(IllegalArgumentException.class,()->MaintenanceSlaDateRules.validate(past,LocalDate.parse("2026-10-08"),today));
    }
    @Test void businessTimezoneControlsDateAtMidnightAndCloseSubmittedRequiresSavedPolicy(){
        Clock boundary=Clock.fixed(Instant.parse("2026-10-14T23:30:00Z"),ZoneOffset.UTC);
        var kolkata=new MaintenanceSlaPolicy("WORK_IN_PROGRESS",true,true,false,null,false,null,"Asia/Kolkata");
        assertEquals(LocalDate.parse("2026-10-15"),MaintenanceSlaDateRules.range(kolkata,boundary).today());
        var unconfigured=new MaintenanceSlaPolicy("CLOSE_SUBMITTED",false,true,false,null,false,null,"UTC");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->MaintenanceSlaDateRules.range(unconfigured,today));
    }
    @Test void todayOnlyPolicyAcceptsTodayAndOptionalPolicyAcceptsAnEmptyTarget(){
        var todayOnly=new MaintenanceSlaPolicy("ON_HOLD",true,false,false,null,false,null,"UTC");
        assertEquals(LocalDate.parse("2026-10-14"),MaintenanceSlaDateRules.validate(todayOnly,LocalDate.parse("2026-10-14"),today));
        assertThrows(IllegalArgumentException.class,()->MaintenanceSlaDateRules.validate(todayOnly,LocalDate.parse("2026-10-15"),today));
        var optional=new MaintenanceSlaPolicy("CLOSE_SUBMITTED",true,false,false,null,false,null,"UTC");
        assertNull(MaintenanceSlaDateRules.validate(optional,null,today));
    }
}
