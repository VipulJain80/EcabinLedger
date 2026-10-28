-- Component and condition links are nullable in defects_dtl. Keep the immutable report snapshot
-- aligned with that domain contract while retaining all text bounds and required severity checks.
ALTER TABLE operational_form_submissions_dtl
  DROP CONSTRAINT operational_form_submission_snapshot_report_check;

ALTER TABLE operational_form_submissions_dtl
  ADD CONSTRAINT operational_form_submission_snapshot_report_check
  CHECK (purpose <> 'DEFECT_REPORT' OR (
    length(btrim(snapshot_defect_category)) BETWEEN 1 AND 32
    AND length(btrim(snapshot_zone)) BETWEEN 1 AND 80
    AND length(btrim(snapshot_title)) BETWEEN 1 AND 160
    AND length(btrim(snapshot_description)) BETWEEN 1 AND 4000
    AND snapshot_severity_id IS NOT NULL
  ));
