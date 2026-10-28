-- V25 removed answers_json after backfilling the fixed defect snapshot. Keep that
-- typed snapshot for the current domain model and add a nullable generic snapshot
-- for submissions made from administrator-defined versioned fields. Existing rows
-- deliberately remain NULL; immutable historical submissions are not rewritten.
ALTER TABLE operational_form_submissions_dtl
  ADD COLUMN submitted_answers_json jsonb;

ALTER TABLE operational_form_submissions_dtl
  ADD CONSTRAINT operational_form_submission_answers_object_check
  CHECK (submitted_answers_json IS NULL OR jsonb_typeof(submitted_answers_json) = 'object');
