-- Title and description are versioned form fields, not universal domain requirements.
-- Their configured requiredness is enforced by the published form validator.
ALTER TABLE defects_dtl
  ALTER COLUMN title DROP NOT NULL,
  ALTER COLUMN description DROP NOT NULL;

-- Keep historical snapshots immutable while allowing new submissions whose form omits
-- either optional field. Other snapshot invariants remain unchanged.
ALTER TABLE operational_form_submissions_dtl
  DROP CONSTRAINT operational_form_submission_snapshot_report_check;

ALTER TABLE operational_form_submissions_dtl
  ADD CONSTRAINT operational_form_submission_snapshot_report_check
  CHECK (purpose <> 'DEFECT_REPORT' OR (
    length(btrim(snapshot_defect_category)) BETWEEN 1 AND 32
    AND length(btrim(snapshot_zone)) BETWEEN 1 AND 80
    AND (snapshot_title IS NULL OR length(btrim(snapshot_title)) BETWEEN 1 AND 160)
    AND (snapshot_description IS NULL OR length(btrim(snapshot_description)) BETWEEN 1 AND 4000)
    AND snapshot_severity_id IS NOT NULL
  ));
