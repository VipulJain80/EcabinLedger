package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DefectWorkflowTest {
    @Test
    void permitsOnlyDocumentedLifecycleEdges() {
        assertTrue(DefectWorkflow.permits("REPORTED", "UNDER_REVIEW"));
        assertTrue(DefectWorkflow.permits("REPORTED", "ACTION_ASSIGNED"), "Reviewer approval routes directly to Maintenance.");
        assertTrue(DefectWorkflow.permits("UNDER_REVIEW", "ACTION_ASSIGNED"), "A pending review can route directly to Maintenance.");
        assertTrue(DefectWorkflow.permits("UNDER_REVIEW", "REJECTED"), "Reviewer rejection is terminal for this report.");
        assertFalse(DefectWorkflow.permits("UNDER_REVIEW", "REPORTED"), "Reviewer rejection does not return the report to the Auditor.");
        assertTrue(DefectWorkflow.permits("INSPECTION_COMPLETE", "ACTION_ASSIGNED"));
        assertTrue(DefectWorkflow.permits("AWAITING_VERIFICATION", "VERIFIED"));
        assertTrue(DefectWorkflow.permits("VERIFIED", "APPROVAL_REQUIRED"));
        assertTrue(DefectWorkflow.permits("IN_PROGRESS", "APPROVAL_REQUIRED"), "Maintenance Close submission routes directly to Closure Team review.");
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
