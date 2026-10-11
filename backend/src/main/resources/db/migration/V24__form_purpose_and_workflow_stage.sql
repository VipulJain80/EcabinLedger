-- Existing operational forms are exclusively used by the initial defect-report flow.
-- Preserve that behavior explicitly; administrators can configure later-stage purposes
-- and statuses through Form Master without rewriting any published field definitions.
ALTER TABLE operational_forms_dtl
  ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'DEFECT_REPORT',
  ADD COLUMN workflow_stage varchar(32) NOT NULL DEFAULT 'INITIAL_REPORT';

ALTER TABLE operational_forms_dtl
  ADD CONSTRAINT operational_forms_purpose_check CHECK (purpose IN (
    'DEFECT_REPORT','INSPECTION','CORRECTIVE_ACTION','VERIFICATION','CLOSURE_APPROVAL','REOPEN'
  )),
  ADD CONSTRAINT operational_forms_workflow_stage_check CHECK (workflow_stage IN (
    'INITIAL_REPORT','REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS',
    'INSPECTION_COMPLETE','ACTION_ASSIGNED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED',
    'APPROVAL_REQUIRED','CLOSED','REOPENED'
  )),
  ADD CONSTRAINT operational_forms_purpose_stage_check CHECK (
    (purpose='DEFECT_REPORT' AND workflow_stage='INITIAL_REPORT') OR
    (purpose='INSPECTION' AND workflow_stage IN ('INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS')) OR
    (purpose='CORRECTIVE_ACTION' AND workflow_stage IN ('ACTION_ASSIGNED','IN_PROGRESS')) OR
    (purpose='VERIFICATION' AND workflow_stage='AWAITING_VERIFICATION') OR
    (purpose='CLOSURE_APPROVAL' AND workflow_stage='APPROVAL_REQUIRED') OR
    (purpose='REOPEN' AND workflow_stage='CLOSED')
  );

CREATE INDEX operational_forms_resolution_idx
  ON operational_forms_dtl(operator_id,area_id,purpose,workflow_stage,isactive,name);

ALTER TABLE operational_form_submissions_dtl
  ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'DEFECT_REPORT',
  ADD COLUMN workflow_stage varchar(32) NOT NULL DEFAULT 'INITIAL_REPORT';

ALTER TABLE operational_form_submissions_dtl
  ADD CONSTRAINT operational_form_submissions_purpose_check CHECK (purpose IN (
    'DEFECT_REPORT','INSPECTION','CORRECTIVE_ACTION','VERIFICATION','CLOSURE_APPROVAL','REOPEN'
  )),
  ADD CONSTRAINT operational_form_submissions_workflow_stage_check CHECK (workflow_stage IN (
    'INITIAL_REPORT','REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS',
    'INSPECTION_COMPLETE','ACTION_ASSIGNED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED',
    'APPROVAL_REQUIRED','CLOSED','REOPENED'
  )),
  ADD CONSTRAINT operational_form_submissions_purpose_stage_check CHECK (
    (purpose='DEFECT_REPORT' AND workflow_stage='INITIAL_REPORT') OR
    (purpose='INSPECTION' AND workflow_stage IN ('INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS')) OR
    (purpose='CORRECTIVE_ACTION' AND workflow_stage IN ('ACTION_ASSIGNED','IN_PROGRESS')) OR
    (purpose='VERIFICATION' AND workflow_stage='AWAITING_VERIFICATION') OR
    (purpose='CLOSURE_APPROVAL' AND workflow_stage='APPROVAL_REQUIRED') OR
    (purpose='REOPEN' AND workflow_stage='CLOSED')
  );
