package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.WorkflowStage;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FormResolutionRulesTest {
    @Test
    void everyPurposeAcceptsOnlyItsSupportedWorkflowStages() {
        FormResolutionRules.requireMapping(FormPurpose.DEFECT_REPORT, WorkflowStage.INITIAL_REPORT);
        FormResolutionRules.requireMapping(FormPurpose.INSPECTION, WorkflowStage.INSPECTION_REQUIRED);
        FormResolutionRules.requireMapping(FormPurpose.INSPECTION, WorkflowStage.INSPECTION_IN_PROGRESS);
        FormResolutionRules.requireMapping(FormPurpose.CORRECTIVE_ACTION, WorkflowStage.ACTION_ASSIGNED);
        FormResolutionRules.requireMapping(FormPurpose.CORRECTIVE_ACTION, WorkflowStage.IN_PROGRESS);
        FormResolutionRules.requireMapping(FormPurpose.VERIFICATION, WorkflowStage.AWAITING_VERIFICATION);
        FormResolutionRules.requireMapping(FormPurpose.CLOSURE_APPROVAL, WorkflowStage.APPROVAL_REQUIRED);
        FormResolutionRules.requireMapping(FormPurpose.REOPEN, WorkflowStage.CLOSED);
    }

    @Test
    void defectReportResolvesThroughInitialContextThenOnlyAtReportedStatus() {
        assertEquals(WorkflowStage.INITIAL_REPORT, FormResolutionRules.stageForDefect(FormPurpose.DEFECT_REPORT, "REPORTED"));
        assertThrows(ResponseStatusException.class, () -> FormResolutionRules.stageForDefect(FormPurpose.DEFECT_REPORT, "UNDER_REVIEW"));
    }

    @Test
    void actionPurposeMustMatchCurrentServerSideStatus() {
        assertEquals(WorkflowStage.INSPECTION_IN_PROGRESS, FormResolutionRules.stageForDefect(FormPurpose.INSPECTION, "INSPECTION_IN_PROGRESS"));
        assertThrows(IllegalArgumentException.class, () -> FormResolutionRules.stageForDefect(FormPurpose.INSPECTION, "ACTION_ASSIGNED"));
        assertEquals(WorkflowStage.CLOSED, FormResolutionRules.stageForDefect(FormPurpose.REOPEN, "CLOSED"));
        assertThrows(IllegalArgumentException.class, () -> FormResolutionRules.stageForDefect(FormPurpose.REOPEN, "REPORTED"));
    }

    @Test
    void terminalStatusesWithoutConfiguredActionsCannotResolveForms() {
        assertThrows(ResponseStatusException.class, () -> FormResolutionRules.stageForDefect(FormPurpose.INSPECTION, "CANCELLED"));
        assertThrows(IllegalArgumentException.class, () -> FormResolutionRules.requireMapping(FormPurpose.VERIFICATION, WorkflowStage.CLOSED));
    }
}
