-- Rejected defects remain the same case and return to the original reporter.
-- REJECTED stays in the catalog for historical rows; new reviewer decisions use REWORK.
ALTER TABLE defects_dtl DROP CONSTRAINT IF EXISTS defects_status_check;
ALTER TABLE defects_dtl ADD CONSTRAINT defects_status_check CHECK (status IN (
  'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS','INSPECTION_COMPLETE',
  'ACTION_ASSIGNED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED',
  'CLOSED','REJECTED','REWORK','CANCELLED','REOPENED'
));

INSERT INTO workflow_status_master
  (module_code,status_code,display_name,alias_name,description,is_active,sort_order,is_initial,is_terminal)
VALUES
  ('DEFECT','REWORK','Rework','Returned for Correction',
   'A Reviewer returned the defect to its original reporter for correction and resubmission.',TRUE,15,FALSE,FALSE)
ON CONFLICT (module_code,status_code) DO UPDATE
SET display_name=EXCLUDED.display_name,
    alias_name=EXCLUDED.alias_name,
    description=EXCLUDED.description,
    is_active=TRUE,
    sort_order=EXCLUDED.sort_order,
    is_initial=FALSE,
    is_terminal=FALSE,
    updated_at=clock_timestamp();
