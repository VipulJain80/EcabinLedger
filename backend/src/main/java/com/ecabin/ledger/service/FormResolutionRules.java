package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.FormPurpose;
import com.ecabin.ledger.api.FormModels.WorkflowStage;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Pure mapping and status rules shared by Form Master writes and operational resolution. */
final class FormResolutionRules {
    private FormResolutionRules() {}

    static void requireMapping(FormPurpose purpose, WorkflowStage stage) {
        if (purpose == null || stage == null) throw new IllegalArgumentException("Choose a form purpose and applicable workflow stage.");
        boolean valid = switch (purpose) {
            case DEFECT_REPORT -> stage == WorkflowStage.INITIAL_REPORT;
            case REVIEW_DECISION -> stage == WorkflowStage.REPORTED || stage == WorkflowStage.UNDER_REVIEW;
            case INSPECTION -> stage == WorkflowStage.INSPECTION_REQUIRED || stage == WorkflowStage.INSPECTION_IN_PROGRESS;
            case CORRECTIVE_ACTION -> stage == WorkflowStage.ACTION_ASSIGNED || stage == WorkflowStage.IN_PROGRESS;
            case VERIFICATION -> stage == WorkflowStage.AWAITING_VERIFICATION;
            case CLOSURE_APPROVAL -> stage == WorkflowStage.APPROVAL_REQUIRED;
            case REOPEN -> stage == WorkflowStage.CLOSED;
        };
        if (!valid) throw new IllegalArgumentException("The selected workflow stage is not valid for this form purpose.");
    }

    static WorkflowStage stageForDefect(FormPurpose purpose, String status) {
        if (purpose == FormPurpose.DEFECT_REPORT) {
            if (!Set.of("REPORTED", "REWORK").contains(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The defect report form is no longer applicable to this workflow stage.");
            return WorkflowStage.INITIAL_REPORT;
        }
        if (purpose == FormPurpose.REVIEW_DECISION && !Set.of("REPORTED", "UNDER_REVIEW").contains(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The Reviewer decision form is not applicable to this workflow status.");
        if (purpose == FormPurpose.CORRECTIVE_ACTION && Set.of("ACTION_ASSIGNED","IN_PROGRESS").contains(status)) return WorkflowStage.IN_PROGRESS;
        WorkflowStage stage;
        try { stage = WorkflowStage.valueOf(status); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "No operational form is available for this workflow status."); }
        requireMapping(purpose, stage);
        return stage;
    }
}
