-- Keep mutable Form Master mappings separate from immutable published-version mappings.
-- Legacy forms and versions are backfilled from their existing single-area columns.
CREATE TABLE operational_form_areas_mst (
  operator_id uuid NOT NULL,
  form_id uuid NOT NULL,
  area_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (operator_id, form_id, area_id),
  FOREIGN KEY (operator_id, form_id)
    REFERENCES operational_forms_dtl(operator_id, id) ON DELETE CASCADE,
  FOREIGN KEY (operator_id, area_id)
    REFERENCES cabin_areas_mst(operator_id, id)
);

CREATE INDEX operational_form_areas_area_idx
  ON operational_form_areas_mst(operator_id, area_id, form_id);

INSERT INTO operational_form_areas_mst(operator_id, form_id, area_id)
SELECT operator_id, id, area_id FROM operational_forms_dtl
ON CONFLICT DO NOTHING;

CREATE TABLE operational_form_version_areas_dtl (
  operator_id uuid NOT NULL,
  form_id uuid NOT NULL,
  form_version_id uuid NOT NULL,
  area_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (operator_id, form_version_id, area_id),
  FOREIGN KEY (operator_id, form_id, form_version_id)
    REFERENCES operational_form_versions_dtl(operator_id, form_id, id) ON DELETE CASCADE,
  FOREIGN KEY (operator_id, area_id)
    REFERENCES cabin_areas_mst(operator_id, id)
);

CREATE INDEX operational_form_version_areas_resolution_idx
  ON operational_form_version_areas_dtl(operator_id, area_id, form_id, form_version_id);

INSERT INTO operational_form_version_areas_dtl(operator_id, form_id, form_version_id, area_id)
SELECT operator_id, form_id, id, area_id FROM operational_form_versions_dtl
ON CONFLICT DO NOTHING;

-- Published form applicability is historical data and cannot be changed after publication.
CREATE FUNCTION protect_published_form_area_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  version_status varchar(12);
  target_operator uuid;
  target_form uuid;
  target_version uuid;
BEGIN
  IF TG_OP='DELETE' OR TG_OP='UPDATE' THEN
    target_operator:=OLD.operator_id; target_form:=OLD.form_id; target_version:=OLD.form_version_id;
    SELECT status INTO version_status FROM operational_form_versions_dtl
     WHERE operator_id=target_operator AND form_id=target_form AND id=target_version;
    IF version_status='PUBLISHED' THEN
      RAISE EXCEPTION 'Published form version area mappings are immutable';
    END IF;
  END IF;
  IF TG_OP='INSERT' OR TG_OP='UPDATE' THEN
    target_operator:=NEW.operator_id; target_form:=NEW.form_id; target_version:=NEW.form_version_id;
    SELECT status INTO version_status FROM operational_form_versions_dtl
     WHERE operator_id=target_operator AND form_id=target_form AND id=target_version;
    IF version_status='PUBLISHED' THEN
      RAISE EXCEPTION 'Published form version area mappings are immutable';
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER operational_form_version_areas_immutable
  BEFORE INSERT OR UPDATE OR DELETE ON operational_form_version_areas_dtl
  FOR EACH ROW EXECUTE FUNCTION protect_published_form_area_snapshot();
