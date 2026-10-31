package com.ecabin.ledger.service;

import java.util.Map;
import java.util.Set;

/** Pure lifecycle rules, kept separate from persistence so invalid paths are easy to test. */
public final class DefectWorkflow {
    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
        Map.entry("REPORTED", Set.of("UNDER_REVIEW", "ACTION_ASSIGNED", "REWORK", "CANCELLED")),
        Map.entry("UNDER_REVIEW", Set.of("INSPECTION_REQUIRED", "ACTION_ASSIGNED", "REWORK", "CANCELLED")),
        Map.entry("REWORK", Set.of("UNDER_REVIEW")),
        Map.entry("INSPECTION_REQUIRED", Set.of("INSPECTION_IN_PROGRESS")),
        Map.entry("INSPECTION_IN_PROGRESS", Set.of("INSPECTION_COMPLETE", "INSPECTION_REQUIRED")),
        Map.entry("INSPECTION_COMPLETE", Set.of("ACTION_ASSIGNED", "CANCELLED")),
        Map.entry("ACTION_ASSIGNED", Set.of("IN_PROGRESS")),
        Map.entry("IN_PROGRESS", Set.of("ACTION_ASSIGNED", "ON_HOLD", "AWAITING_VERIFICATION", "APPROVAL_REQUIRED")),
        Map.entry("ON_HOLD", Set.of("IN_PROGRESS", "ACTION_ASSIGNED")),
        Map.entry("AWAITING_VERIFICATION", Set.of("VERIFIED", "IN_PROGRESS")),
        Map.entry("VERIFIED", Set.of("APPROVAL_REQUIRED")),
        Map.entry("APPROVAL_REQUIRED", Set.of("CLOSED", "IN_PROGRESS")),
        Map.entry("CLOSED", Set.of("REOPENED")),
        Map.entry("REOPENED", Set.of("INSPECTION_REQUIRED")),
        Map.entry("REJECTED", Set.of()), Map.entry("CANCELLED", Set.of()));

    private DefectWorkflow() {}

    public static boolean permits(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<String> validTargets(String from) { return TRANSITIONS.getOrDefault(from, Set.of()); }

    public static void requireTransition(String from, String to, String reason) {
        if (!permits(from, to)) throw new IllegalStateException("Cannot move a defect from " + from + " to " + to + ".");
        if (to.equals("REOPENED") && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("A reason is required to reopen a closed defect.");
    }

    public static void requireEvidence(String target, String evidenceReference) {
        if ((target.equals("VERIFIED") || target.equals("CLOSED")) && (evidenceReference == null || evidenceReference.isBlank()))
            throw new IllegalArgumentException("A verification or closure evidence reference is required.");
    }
}
