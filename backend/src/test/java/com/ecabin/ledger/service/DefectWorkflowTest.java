package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DefectWorkflowTest {
    @Test
    void permitsOnlyDocumentedLifecycleEdges() {
        assertTrue(DefectWorkflow.permits("REPORTED", "UNDER_REVIEW"));
        assertTrue(DefectWorkflow.permits("UNDER_REVIEW", "REPORTED"), "A rejected report returns to the reporter for correction.");
        assertTrue(DefectWorkflow.permits("INSPECTION_COMPLETE", "ACTION_ASSIGNED"));
        assertTrue(DefectWorkflow.permits("AWAITING_VERIFICATION", "VERIFIED"));
        assertTrue(DefectWorkflow.permits("VERIFIED", "APPROVAL_REQUIRED"));
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
