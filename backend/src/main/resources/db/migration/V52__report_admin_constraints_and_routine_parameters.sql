-- A tenant may enable a particular approved global template version only once.
-- Keep inactive copies reserved too, so deactivation cannot enable accidental duplication.
CREATE UNIQUE INDEX reports_tenant_template_version_uq
  ON reports_mst(operator_id, source_template_id, source_template_version)
  WHERE source_template_id IS NOT NULL;

-- Reviewed routine registrations describe the only filter parameters that administrators may map.
CREATE TABLE report_routine_parameters_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  routine_id uuid NOT NULL,
  contract_version smallint NOT NULL,
  parameter_key varchar(64) NOT NULL CHECK (parameter_key ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
  parameter_type varchar(16) NOT NULL CHECK (parameter_type IN
    ('TEXT','NUMBER','BOOLEAN','DATE','UUID','TEXT_ARRAY','NUMBER_ARRAY','UUID_ARRAY')),
  is_required boolean NOT NULL DEFAULT false,
  is_filterable boolean NOT NULL DEFAULT true,
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 99999),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT report_routine_parameters_routine_fk FOREIGN KEY(routine_id,contract_version)
    REFERENCES report_routines_mst(id,contract_version) ON DELETE RESTRICT,
  CONSTRAINT report_routine_parameters_key_uq UNIQUE(routine_id,contract_version,parameter_key)
);
CREATE INDEX report_routine_parameters_order_idx
  ON report_routine_parameters_mst(routine_id,contract_version,sort_order,parameter_key);

CREATE TRIGGER audit_report_routine_parameters BEFORE INSERT OR UPDATE ON report_routine_parameters_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();

ALTER TABLE report_lookup_sources_mst
  ADD COLUMN value_type varchar(8) NOT NULL DEFAULT 'TEXT'
  CHECK (value_type IN ('TEXT','NUMBER','UUID'));
