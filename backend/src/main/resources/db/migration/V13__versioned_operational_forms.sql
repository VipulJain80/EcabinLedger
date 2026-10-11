CREATE TABLE operational_forms (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  area_id uuid NOT NULL,
  form_key varchar(80) NOT NULL,
  name varchar(160) NOT NULL,
  published_version_id uuid,
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id,id),
  UNIQUE(operator_id,form_key),
  FOREIGN KEY(operator_id,area_id) REFERENCES cabin_areas(operator_id,id),
  CHECK (btrim(form_key) <> ''), CHECK (btrim(name) <> '')
);
CREATE INDEX operational_forms_area_idx ON operational_forms(operator_id,area_id,isactive,name);

CREATE TABLE operational_form_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL,
  form_id uuid NOT NULL,
  version_number integer NOT NULL CHECK (version_number > 0),
  status varchar(12) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
  fields_json jsonb NOT NULL DEFAULT '{"fields":[]}'::jsonb,
  created_by varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_by varchar(200),
  published_at timestamptz,
  UNIQUE(operator_id,id),
  UNIQUE(operator_id,form_id,id),
  UNIQUE(operator_id,form_id,version_number),
  FOREIGN KEY(operator_id,form_id) REFERENCES operational_forms(operator_id,id),
  CHECK (jsonb_typeof(fields_json)='object' AND jsonb_typeof(fields_json->'fields')='array')
);
CREATE UNIQUE INDEX operational_form_one_draft_idx ON operational_form_versions(operator_id,form_id) WHERE status='DRAFT';
ALTER TABLE operational_forms ADD CONSTRAINT operational_forms_published_version_fk
  FOREIGN KEY(operator_id,id,published_version_id) REFERENCES operational_form_versions(operator_id,form_id,id);
CREATE INDEX operational_form_versions_lookup_idx ON operational_form_versions(operator_id,form_id,version_number DESC);

CREATE FUNCTION protect_published_form_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='PUBLISHED' THEN
    RAISE EXCEPTION 'Published form versions are immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER operational_form_version_immutable BEFORE UPDATE OR DELETE ON operational_form_versions
FOR EACH ROW EXECUTE FUNCTION protect_published_form_version();

CREATE TABLE operational_form_submissions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL,
  defect_id uuid NOT NULL,
  form_id uuid NOT NULL,
  form_version_id uuid NOT NULL,
  submitted_by varchar(200) NOT NULL,
  answers_json jsonb NOT NULL CHECK (jsonb_typeof(answers_json)='object'),
  submitted_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id,id),
  FOREIGN KEY(operator_id,defect_id) REFERENCES defects(operator_id,id),
  FOREIGN KEY(operator_id,form_id,form_version_id) REFERENCES operational_form_versions(operator_id,form_id,id)
);
CREATE INDEX operational_form_submissions_defect_idx ON operational_form_submissions(operator_id,defect_id,submitted_at DESC);
CREATE INDEX operational_form_submissions_version_idx ON operational_form_submissions(operator_id,form_id,form_version_id);

CREATE FUNCTION protect_operational_form_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Operational form submissions are immutable';
  RETURN NULL;
END;
$$;
CREATE TRIGGER operational_form_submission_immutable BEFORE UPDATE OR DELETE ON operational_form_submissions
FOR EACH ROW EXECUTE FUNCTION protect_operational_form_submission();

ALTER TABLE attachments ADD COLUMN form_submission_id uuid;
ALTER TABLE attachments ADD COLUMN form_id uuid;
ALTER TABLE attachments ADD COLUMN form_version_id uuid;
ALTER TABLE attachments ADD COLUMN field_key varchar(80);
ALTER TABLE attachments ADD COLUMN scan_status varchar(16) NOT NULL DEFAULT 'QUARANTINED'
  CHECK (scan_status IN ('QUARANTINED','CLEAN','REJECTED'));
ALTER TABLE attachments ADD CONSTRAINT attachments_form_submission_fk
  FOREIGN KEY(operator_id,form_submission_id) REFERENCES operational_form_submissions(operator_id,id);
ALTER TABLE attachments ADD CONSTRAINT attachments_form_version_fk
  FOREIGN KEY(operator_id,form_version_id) REFERENCES operational_form_versions(operator_id,id);
ALTER TABLE attachments ADD CONSTRAINT attachments_form_fk
  FOREIGN KEY(operator_id,form_id) REFERENCES operational_forms(operator_id,id);
CREATE INDEX attachments_submission_idx ON attachments(operator_id,form_submission_id,field_key) WHERE form_submission_id IS NOT NULL;
CREATE INDEX attachments_form_upload_lookup_idx ON attachments(operator_id,defect_id,form_id,form_version_id,field_key,scan_status) WHERE isactive=1;

INSERT INTO navigation_menu_items(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'forms','Form Master',p.id,'ADMINISTER_ORGANIZATION','▧','forms',100
FROM operators o JOIN navigation_menu_items p ON p.operator_id=o.id AND p.menu_key='configuration' AND p.parent_id IS NULL
ON CONFLICT DO NOTHING;
