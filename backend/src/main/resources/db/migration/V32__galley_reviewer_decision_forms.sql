-- Reviewer decisions are workflow forms, not operational submissions. Keep
-- operational_form_submissions_dtl restricted to the existing typed purposes.
ALTER TABLE operational_forms_dtl
  DROP CONSTRAINT operational_forms_purpose_check,
  DROP CONSTRAINT operational_forms_purpose_stage_check;

ALTER TABLE operational_forms_dtl
  ADD CONSTRAINT operational_forms_purpose_check CHECK (purpose IN (
    'DEFECT_REPORT','REVIEW_DECISION','INSPECTION','CORRECTIVE_ACTION','VERIFICATION','CLOSURE_APPROVAL','REOPEN'
  )),
  ADD CONSTRAINT operational_forms_purpose_stage_check CHECK (
    (purpose='DEFECT_REPORT' AND workflow_stage='INITIAL_REPORT') OR
    (purpose='REVIEW_DECISION' AND workflow_stage IN ('REPORTED','UNDER_REVIEW')) OR
    (purpose='INSPECTION' AND workflow_stage IN ('INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS')) OR
    (purpose='CORRECTIVE_ACTION' AND workflow_stage IN ('ACTION_ASSIGNED','IN_PROGRESS')) OR
    (purpose='VERIFICATION' AND workflow_stage='AWAITING_VERIFICATION') OR
    (purpose='CLOSURE_APPROVAL' AND workflow_stage='APPROVAL_REQUIRED') OR
    (purpose='REOPEN' AND workflow_stage='CLOSED')
  );
