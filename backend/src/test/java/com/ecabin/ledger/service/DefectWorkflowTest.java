package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DefectWorkflowTest {
    @Test
    void permitsOnlyDocumentedLifecycleEdges() {
        assertTrue(DefectWorkflow.permits("REPORTED", "UNDER_REVIEW"));
        assertTrue(DefectWorkflow.permits("REPORTED", "ACTION_ASSIGNED"), "Reviewer approval routes directly to Maintenance.");
        assertTrue(DefectWorkflow.permits("UNDER_REVIEW", "ACTION_ASSIGNED"), "A pending review can route directly to Maintenance.");
        assertTrue(DefectWorkflow.permits("UNDER_REVIEW", "REWORK"), "Reviewer rejection returns the report to its original Auditor.");
        assertTrue(DefectWorkflow.permits("REPORTED", "REWORK"));
        assertTrue(DefectWorkflow.permits("REWORK", "UNDER_REVIEW"), "The Auditor resubmits the same corrected case for review.");
        assertFalse(DefectWorkflow.permits("UNDER_REVIEW", "REJECTED"), "New rejections use the dedicated return-for-correction workflow.");
        assertFalse(DefectWorkflow.permits("REWORK", "ACTION_ASSIGNED"));
        assertFalse(DefectWorkflow.permits("UNDER_REVIEW", "REPORTED"));
        assertTrue(DefectWorkflow.permits("INSPECTION_COMPLETE", "ACTION_ASSIGNED"));
        assertTrue(DefectWorkflow.permits("AWAITING_VERIFICATION", "VERIFIED"));
        assertTrue(DefectWorkflow.permits("VERIFIED", "APPROVAL_REQUIRED"));
        assertTrue(DefectWorkflow.permits("IN_PROGRESS", "APPROVAL_REQUIRED"), "Maintenance Close submission routes directly to Closure Team review.");
        assertTrue(DefectWorkflow.permits("IN_PROGRESS", "ON_HOLD"), "Maintenance can place active work on hold.");
        assertTrue(DefectWorkflow.permits("ON_HOLD", "IN_PROGRESS"), "A held case can resume work.");
        assertTrue(DefectWorkflow.permits("ON_HOLD", "ACTION_ASSIGNED"), "Reassigning held work routes it back to the assigned state.");
        assertTrue(DefectWorkflow.permits("IN_PROGRESS", "AWAITING_VERIFICATION"), "Legacy verification workflows remain supported.");
        assertTrue(DefectWorkflow.permits("APPROVAL_REQUIRED", "CLOSED"));
        assertTrue(DefectWorkflow.permits("APPROVAL_REQUIRED", "IN_PROGRESS"), "A declined closure returns maintenance work to the active work state.");
        assertFalse(DefectWorkflow.permits("REPORTED", "CLOSED"));
        assertFalse(DefectWorkflow.permits("CLOSED", "IN_PROGRESS"));
    }

    @Test
    void closedDefectCanOnlyReopenWithReasonAndExplicitReopenedState() {
        assertThrows(IllegalStateException.class, () -> DefectWorkflow.requireTransition("CLOSED", "IN_PROGRESS", "repair"));
        assertThrows(IllegalArgumentException.class, () -> DefectWorkflow.requireTransition("CLOSED", "REOPENED", " "));
        assertDoesNotThrow(() -> DefectWorkflow.requireTransition("CLOSED", "REOPENED", "Repeat cabin inspection"));
    }

    @Test
    void verificationAndClosureRequireEvidenceReference() {
        assertThrows(IllegalArgumentException.class, () -> DefectWorkflow.requireEvidence("VERIFIED", null));
        assertThrows(IllegalArgumentException.class, () -> DefectWorkflow.requireEvidence("CLOSED", " "));
        assertDoesNotThrow(() -> DefectWorkflow.requireEvidence("CLOSED", "WO-12345"));
        assertDoesNotThrow(() -> DefectWorkflow.requireEvidence("IN_PROGRESS", null));
    }
}
