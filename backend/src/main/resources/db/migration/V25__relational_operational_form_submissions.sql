-- Form answers must be retained as fixed, typed relational report snapshots. Do not
-- use the definition JSON as an answer store: fields_json remains configuration only.
ALTER TABLE operational_form_submissions_dtl
  ADD COLUMN snapshot_defect_category varchar(32),
  ADD COLUMN snapshot_component_id uuid,
  ADD COLUMN snapshot_condition_id uuid,
  ADD COLUMN snapshot_zone varchar(80),
  ADD COLUMN snapshot_row_number smallint,
  ADD COLUMN snapshot_seat_reference varchar(8),
  ADD COLUMN snapshot_title varchar(160),
  ADD COLUMN snapshot_description varchar(4000),
  ADD COLUMN snapshot_severity_id uuid;

-- Fail closed rather than lose any legacy answer that has no explicit relational
-- binding. Files remain in attachments_dtl and section headings are not answers.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM operational_form_submissions_dtl s
    JOIN operational_form_versions_dtl v ON v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id
    CROSS JOIN LATERAL jsonb_each(s.answers_json) answer(key,value)
    LEFT JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') definition(value)
      ON definition.value->>'key'=answer.key
    WHERE answer.value NOT IN ('null'::jsonb, '""'::jsonb, '[]'::jsonb)
      AND NOT (
        (s.purpose='DEFECT_REPORT' AND definition.value->>'binding' IN
          ('DEFECT_CATEGORY','COMPONENT_ID','CONDITION_ID','ZONE','ROW_NUMBER','SEAT_REFERENCE','TITLE','DESCRIPTION','SEVERITY'))
        OR definition.value->>'type' IN ('FILE_UPLOAD','IMAGE_UPLOAD')
      )
  ) THEN
    RAISE EXCEPTION 'V25 cannot discard legacy unbound form answers. Export and review unmapped submissions, add an approved relational binding, then retry.';
  END IF;

  IF EXISTS (
    SELECT 1 FROM operational_form_submissions_dtl s
    JOIN operational_form_versions_dtl v ON v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') definition(value)
    WHERE s.purpose='DEFECT_REPORT' AND definition.value->>'binding' IS NOT NULL
    GROUP BY s.id,definition.value->>'binding' HAVING count(*)>1
  ) THEN
    RAISE EXCEPTION 'V25 found duplicate bindings in a historical published form. Resolve the duplicate form definition before retrying.';
  END IF;
END $$;

-- Disable only the submission immutability trigger while the forward migration
-- backfills the new snapshot columns. It is restored before the migration commits.
DROP TRIGGER operational_form_submission_immutable ON operational_form_submissions_dtl;

UPDATE operational_form_submissions_dtl s SET
  snapshot_defect_category=(
    SELECT c.code FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    JOIN defect_categories_mst c ON c.operator_id=s.operator_id AND c.id=(s.answers_json->>(f.value->>'key'))::uuid
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='DEFECT_CATEGORY'
  ),
  snapshot_component_id=(
    SELECT (s.answers_json->>(f.value->>'key'))::uuid FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='COMPONENT_ID'
  ),
  snapshot_condition_id=(
    SELECT (s.answers_json->>(f.value->>'key'))::uuid FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='CONDITION_ID'
  ),
  snapshot_zone=(
    SELECT s.answers_json->>(f.value->>'key') FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='ZONE'
  ),
  snapshot_row_number=(
    SELECT (s.answers_json->>(f.value->>'key'))::smallint FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='ROW_NUMBER'
  ),
  snapshot_seat_reference=(
    SELECT s.answers_json->>(f.value->>'key') FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='SEAT_REFERENCE'
  ),
  snapshot_title=(
    SELECT s.answers_json->>(f.value->>'key') FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='TITLE'
  ),
  snapshot_description=(
    SELECT s.answers_json->>(f.value->>'key') FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='DESCRIPTION'
  ),
  snapshot_severity_id=(
    SELECT (s.answers_json->>(f.value->>'key'))::uuid FROM operational_form_versions_dtl v
    CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') f
    WHERE v.operator_id=s.operator_id AND v.form_id=s.form_id AND v.id=s.form_version_id AND f.value->>'binding'='SEVERITY'
  )
WHERE s.purpose='DEFECT_REPORT';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM operational_form_submissions_dtl
    WHERE purpose='DEFECT_REPORT' AND (
      snapshot_defect_category IS NULL OR snapshot_component_id IS NULL OR snapshot_condition_id IS NULL
      OR snapshot_zone IS NULL OR snapshot_title IS NULL OR snapshot_description IS NULL OR snapshot_severity_id IS NULL
    )
  ) THEN
    RAISE EXCEPTION 'V25 found an incomplete historical defect-report snapshot. No answer data was removed; repair the published binding/data and retry.';
  END IF;
END $$;

ALTER TABLE operational_form_submissions_dtl
  ADD CONSTRAINT operational_form_submission_snapshot_row_check
    CHECK (snapshot_row_number IS NULL OR snapshot_row_number BETWEEN 1 AND 200),
  ADD CONSTRAINT operational_form_submission_snapshot_report_check
    CHECK (purpose <> 'DEFECT_REPORT' OR (
      length(btrim(snapshot_defect_category)) BETWEEN 1 AND 32 AND snapshot_component_id IS NOT NULL
      AND snapshot_condition_id IS NOT NULL AND length(btrim(snapshot_zone)) BETWEEN 1 AND 80
      AND length(btrim(snapshot_title)) BETWEEN 1 AND 160 AND length(btrim(snapshot_description)) BETWEEN 1 AND 4000
      AND snapshot_severity_id IS NOT NULL
    )),
  ADD CONSTRAINT operational_form_submission_snapshot_component_fk
    FOREIGN KEY(operator_id,snapshot_component_id) REFERENCES cabin_components_mst(operator_id,id),
  ADD CONSTRAINT operational_form_submission_snapshot_condition_fk
    FOREIGN KEY(operator_id,snapshot_condition_id) REFERENCES defect_conditions_mst(operator_id,id),
  ADD CONSTRAINT operational_form_submission_snapshot_severity_fk
    FOREIGN KEY(operator_id,snapshot_severity_id) REFERENCES defect_severities_mst(operator_id,id);

ALTER TABLE operational_form_submissions_dtl DROP COLUMN answers_json;

CREATE TRIGGER operational_form_submission_immutable BEFORE UPDATE OR DELETE ON operational_form_submissions_dtl
FOR EACH ROW EXECUTE FUNCTION protect_operational_form_submission();
