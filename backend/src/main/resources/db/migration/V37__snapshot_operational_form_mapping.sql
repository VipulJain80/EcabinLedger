-- A version owns its immutable form name and operational mapping. Remapping a form
-- creates a new draft snapshot and never rewrites the interpretation of old versions.
ALTER TABLE operational_form_versions_dtl
  ADD COLUMN form_name varchar(160),
  ADD COLUMN area_id uuid,
  ADD COLUMN purpose varchar(32),
  ADD COLUMN workflow_stage varchar(32);

-- The application-level immutability trigger correctly protects published versions.
-- This transactional, one-time backfill adds their missing mapping snapshot metadata;
-- disable only that trigger for the migration statement and immediately restore it.
ALTER TABLE operational_form_versions_dtl DISABLE TRIGGER operational_form_version_immutable;
UPDATE operational_form_versions_dtl v
   SET form_name=f.name, area_id=f.area_id, purpose=f.purpose, workflow_stage=f.workflow_stage
  FROM operational_forms_dtl f
 WHERE f.operator_id=v.operator_id AND f.id=v.form_id;
ALTER TABLE operational_form_versions_dtl ENABLE TRIGGER operational_form_version_immutable;

ALTER TABLE operational_form_versions_dtl
  ALTER COLUMN form_name SET NOT NULL,
  ALTER COLUMN area_id SET NOT NULL,
  ALTER COLUMN purpose SET NOT NULL,
  ALTER COLUMN workflow_stage SET NOT NULL,
  ADD CONSTRAINT operational_form_version_area_fk
    FOREIGN KEY(operator_id,area_id) REFERENCES cabin_areas_mst(operator_id,id),
  ADD CONSTRAINT operational_form_version_mapping_check CHECK (
    (purpose='DEFECT_REPORT' AND workflow_stage='INITIAL_REPORT') OR
    (purpose='REVIEW_DECISION' AND workflow_stage IN ('REPORTED','UNDER_REVIEW')) OR
    (purpose='INSPECTION' AND workflow_stage IN ('INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS')) OR
    (purpose='CORRECTIVE_ACTION' AND workflow_stage IN ('ACTION_ASSIGNED','IN_PROGRESS')) OR
    (purpose='VERIFICATION' AND workflow_stage='AWAITING_VERIFICATION') OR
    (purpose='CLOSURE_APPROVAL' AND workflow_stage='APPROVAL_REQUIRED') OR
    (purpose='REOPEN' AND workflow_stage='CLOSED')
  );

-- Keep legacy SQL seed/import statements compatible while ensuring every new version
-- receives a relational snapshot from its tenant-owned parent form. Application writes
-- supply these values explicitly after validating the mapping in OperationalFormService.
CREATE FUNCTION snapshot_operational_form_mapping() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  parent_form operational_forms_dtl%ROWTYPE;
BEGIN
  IF NEW.form_name IS NULL OR NEW.area_id IS NULL OR NEW.purpose IS NULL OR NEW.workflow_stage IS NULL THEN
    SELECT * INTO parent_form
      FROM operational_forms_dtl
     WHERE operator_id=NEW.operator_id AND id=NEW.form_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'Cannot snapshot mapping: parent operational form is missing for tenant %', NEW.operator_id;
    END IF;
    NEW.form_name := COALESCE(NEW.form_name,parent_form.name);
    NEW.area_id := COALESCE(NEW.area_id,parent_form.area_id);
    NEW.purpose := COALESCE(NEW.purpose,parent_form.purpose);
    NEW.workflow_stage := COALESCE(NEW.workflow_stage,parent_form.workflow_stage);
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER operational_form_version_mapping_snapshot
  BEFORE INSERT ON operational_form_versions_dtl
  FOR EACH ROW EXECUTE FUNCTION snapshot_operational_form_mapping();

CREATE INDEX operational_form_version_mapping_idx
  ON operational_form_versions_dtl(operator_id,area_id,purpose,workflow_stage,status);
