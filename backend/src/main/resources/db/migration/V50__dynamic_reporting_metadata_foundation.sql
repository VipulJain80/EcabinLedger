-- Dynamic reporting metadata foundation only. This migration intentionally does not
-- register executable functions, enable tenant reports, or create report grants.

CREATE TABLE report_routines_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  routine_key varchar(80) NOT NULL UNIQUE,
  schema_name varchar(63) NOT NULL CHECK (schema_name ~ '^[a-z][a-z0-9_]{0,62}$'),
  function_name varchar(63) NOT NULL CHECK (function_name ~ '^[a-z][a-z0-9_]{0,62}$'),
  contract_version smallint NOT NULL CHECK (contract_version BETWEEN 1 AND 32767),
  is_active boolean NOT NULL DEFAULT false,
  timeout_ms integer NOT NULL DEFAULT 5000 CHECK (timeout_ms BETWEEN 100 AND 30000),
  max_page_size integer NOT NULL DEFAULT 100 CHECK (max_page_size BETWEEN 1 AND 500),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT report_routines_function_version_uq UNIQUE(schema_name, function_name, contract_version),
  CONSTRAINT report_routines_id_contract_uq UNIQUE(id, contract_version)
);
CREATE INDEX report_routines_active_idx ON report_routines_mst(is_active, routine_key) WHERE is_active = true;

CREATE TABLE report_templates_mst (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  template_code varchar(80) NOT NULL CHECK (template_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
  template_version integer NOT NULL CHECK (template_version > 0),
  name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
  description varchar(1000),
  routine_id uuid NOT NULL REFERENCES report_routines_mst(id) ON DELETE RESTRICT,
  is_active boolean NOT NULL DEFAULT false,
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 99999),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  PRIMARY KEY(id, template_version),
  CONSTRAINT report_templates_code_version_uq UNIQUE(template_code, template_version)
);
CREATE INDEX report_templates_active_order_idx
  ON report_templates_mst(is_active, sort_order, template_code, template_version DESC)
  WHERE is_active = true;

CREATE TABLE report_lookup_sources_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lookup_key varchar(80) NOT NULL UNIQUE CHECK (lookup_key ~ '^[A-Z][A-Z0-9_]{0,79}$'),
  routine_id uuid NOT NULL REFERENCES report_routines_mst(id) ON DELETE RESTRICT,
  contract_version smallint NOT NULL CHECK (contract_version BETWEEN 1 AND 32767),
  is_active boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT report_lookup_sources_routine_contract_fk FOREIGN KEY(routine_id, contract_version)
    REFERENCES report_routines_mst(id, contract_version) ON DELETE RESTRICT
);
CREATE INDEX report_lookup_sources_active_idx ON report_lookup_sources_mst(is_active, lookup_key) WHERE is_active = true;

CREATE TABLE report_template_filters_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  template_id uuid NOT NULL,
  template_version integer NOT NULL,
  filter_key varchar(64) NOT NULL CHECK (filter_key ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
  label varchar(100) NOT NULL CHECK (length(btrim(label)) > 0),
  filter_type varchar(24) NOT NULL CHECK (filter_type IN
    ('TEXT','NUMBER','BOOLEAN','DATE','SINGLE_SELECT','MULTI_SELECT','LOOKUP_SINGLE','LOOKUP_MULTI')),
  parameter_key varchar(64) NOT NULL CHECK (parameter_key ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
  is_required boolean NOT NULL DEFAULT false,
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 99999),
  configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
  lookup_source_id uuid REFERENCES report_lookup_sources_mst(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT report_template_filters_template_fk FOREIGN KEY(template_id, template_version)
    REFERENCES report_templates_mst(id, template_version) ON DELETE RESTRICT,
  CONSTRAINT report_template_filters_key_uq UNIQUE(template_id, template_version, filter_key),
  CONSTRAINT report_template_filters_parameter_uq UNIQUE(template_id, template_version, parameter_key),
  CONSTRAINT report_template_filters_config_object CHECK (jsonb_typeof(configuration) = 'object'),
  CONSTRAINT report_template_filters_config_keys CHECK
    (configuration - ARRAY['placeholder','min','max','maxLength','options']::text[] = '{}'::jsonb),
  CONSTRAINT report_template_filters_config_types CHECK
    ((NOT (configuration ? 'placeholder') OR jsonb_typeof(configuration->'placeholder') = 'string') AND
     (NOT (configuration ? 'min') OR jsonb_typeof(configuration->'min') = 'number') AND
     (NOT (configuration ? 'max') OR jsonb_typeof(configuration->'max') = 'number') AND
     (NOT (configuration ? 'maxLength') OR (jsonb_typeof(configuration->'maxLength') = 'number' AND
       (configuration->>'maxLength')::numeric = trunc((configuration->>'maxLength')::numeric) AND
       (configuration->>'maxLength')::integer BETWEEN 1 AND 4000)) AND
     (NOT (configuration ? 'options') OR jsonb_typeof(configuration->'options') = 'array')),
  CONSTRAINT report_template_filters_lookup_check CHECK
    ((filter_type IN ('LOOKUP_SINGLE','LOOKUP_MULTI') AND lookup_source_id IS NOT NULL) OR
     (filter_type NOT IN ('LOOKUP_SINGLE','LOOKUP_MULTI') AND lookup_source_id IS NULL)),
  CONSTRAINT report_template_filters_options_check CHECK
    (filter_type NOT IN ('SINGLE_SELECT','MULTI_SELECT') OR
     (configuration ? 'options' AND jsonb_typeof(configuration->'options') = 'array')),
  CONSTRAINT report_template_filters_no_range_check CHECK
    (NOT (configuration ? 'min' AND configuration ? 'max') OR
     (configuration->>'min')::numeric <= (configuration->>'max')::numeric)
);
CREATE INDEX report_template_filters_order_idx
  ON report_template_filters_mst(template_id, template_version, sort_order, filter_key);

CREATE TABLE report_template_columns_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  template_id uuid NOT NULL,
  template_version integer NOT NULL,
  column_key varchar(64) NOT NULL CHECK (column_key ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
  label varchar(100) NOT NULL CHECK (length(btrim(label)) > 0),
  data_type varchar(16) NOT NULL CHECK (data_type IN ('TEXT','NUMBER','BOOLEAN','DATE','DATETIME','UUID')),
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 99999),
  is_visible boolean NOT NULL DEFAULT true,
  is_sortable boolean NOT NULL DEFAULT false,
  is_exportable boolean NOT NULL DEFAULT true,
  format_configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT report_template_columns_template_fk FOREIGN KEY(template_id, template_version)
    REFERENCES report_templates_mst(id, template_version) ON DELETE RESTRICT,
  CONSTRAINT report_template_columns_key_uq UNIQUE(template_id, template_version, column_key),
  CONSTRAINT report_template_columns_format_object CHECK (jsonb_typeof(format_configuration) = 'object'),
  CONSTRAINT report_template_columns_format_keys CHECK
    (format_configuration - ARRAY['dateFormat','decimalPlaces','currencyCode']::text[] = '{}'::jsonb),
  CONSTRAINT report_template_columns_format_types CHECK
    ((NOT (format_configuration ? 'dateFormat') OR jsonb_typeof(format_configuration->'dateFormat') = 'string') AND
     (NOT (format_configuration ? 'currencyCode') OR jsonb_typeof(format_configuration->'currencyCode') = 'string') AND
     (NOT (format_configuration ? 'decimalPlaces') OR (jsonb_typeof(format_configuration->'decimalPlaces') = 'number' AND
       (format_configuration->>'decimalPlaces')::numeric = trunc((format_configuration->>'decimalPlaces')::numeric) AND
       (format_configuration->>'decimalPlaces')::integer BETWEEN 0 AND 8)))
);
CREATE INDEX report_template_columns_order_idx
  ON report_template_columns_mst(template_id, template_version, sort_order, column_key);

CREATE TABLE reports_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id) ON DELETE RESTRICT,
  report_code varchar(80) NOT NULL CHECK (report_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
  definition_version integer NOT NULL CHECK (definition_version > 0),
  name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
  description varchar(1000),
  routine_id uuid NOT NULL REFERENCES report_routines_mst(id) ON DELETE RESTRICT,
  source_template_id uuid,
  source_template_version integer,
  is_active boolean NOT NULL DEFAULT false,
  is_current boolean NOT NULL DEFAULT false,
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 99999),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT reports_tenant_id_uq UNIQUE(operator_id, id),
  CONSTRAINT reports_tenant_code_version_uq UNIQUE(operator_id, report_code, definition_version),
  CONSTRAINT reports_source_template_fk FOREIGN KEY(source_template_id, source_template_version)
    REFERENCES report_templates_mst(id, template_version) ON DELETE RESTRICT,
  CONSTRAINT reports_source_template_pair_ck CHECK
    ((source_template_id IS NULL AND source_template_version IS NULL) OR
     (source_template_id IS NOT NULL AND source_template_version IS NOT NULL))
);
CREATE UNIQUE INDEX reports_one_current_version_uq ON reports_mst(operator_id, report_code) WHERE is_current = true;
CREATE INDEX reports_available_idx ON reports_mst(operator_id, is_active, is_current, sort_order, report_code);

CREATE TABLE report_filters_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL,
  report_id uuid NOT NULL,
  filter_key varchar(64) NOT NULL CHECK (filter_key ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
  label varchar(100) NOT NULL CHECK (length(btrim(label)) > 0),
  filter_type varchar(24) NOT NULL CHECK (filter_type IN
    ('TEXT','NUMBER','BOOLEAN','DATE','SINGLE_SELECT','MULTI_SELECT','LOOKUP_SINGLE','LOOKUP_MULTI')),
  parameter_key varchar(64) NOT NULL CHECK (parameter_key ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
  is_required boolean NOT NULL DEFAULT false,
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 99999),
  configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
  lookup_source_id uuid REFERENCES report_lookup_sources_mst(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT report_filters_tenant_report_fk FOREIGN KEY(operator_id, report_id)
    REFERENCES reports_mst(operator_id, id) ON DELETE RESTRICT,
  CONSTRAINT report_filters_key_uq UNIQUE(operator_id, report_id, filter_key),
  CONSTRAINT report_filters_parameter_uq UNIQUE(operator_id, report_id, parameter_key),
  CONSTRAINT report_filters_config_object CHECK (jsonb_typeof(configuration) = 'object'),
  CONSTRAINT report_filters_config_keys CHECK
    (configuration - ARRAY['placeholder','min','max','maxLength','options']::text[] = '{}'::jsonb),
  CONSTRAINT report_filters_config_types CHECK
    ((NOT (configuration ? 'placeholder') OR jsonb_typeof(configuration->'placeholder') = 'string') AND
     (NOT (configuration ? 'min') OR jsonb_typeof(configuration->'min') = 'number') AND
     (NOT (configuration ? 'max') OR jsonb_typeof(configuration->'max') = 'number') AND
     (NOT (configuration ? 'maxLength') OR (jsonb_typeof(configuration->'maxLength') = 'number' AND
       (configuration->>'maxLength')::numeric = trunc((configuration->>'maxLength')::numeric) AND
       (configuration->>'maxLength')::integer BETWEEN 1 AND 4000)) AND
     (NOT (configuration ? 'options') OR jsonb_typeof(configuration->'options') = 'array')),
  CONSTRAINT report_filters_lookup_check CHECK
    ((filter_type IN ('LOOKUP_SINGLE','LOOKUP_MULTI') AND lookup_source_id IS NOT NULL) OR
     (filter_type NOT IN ('LOOKUP_SINGLE','LOOKUP_MULTI') AND lookup_source_id IS NULL)),
  CONSTRAINT report_filters_options_check CHECK
    (filter_type NOT IN ('SINGLE_SELECT','MULTI_SELECT') OR
     (configuration ? 'options' AND jsonb_typeof(configuration->'options') = 'array')),
  CONSTRAINT report_filters_no_range_check CHECK
    (NOT (configuration ? 'min' AND configuration ? 'max') OR
     (configuration->>'min')::numeric <= (configuration->>'max')::numeric)
);
CREATE INDEX report_filters_order_idx ON report_filters_mst(operator_id, report_id, sort_order, filter_key);

CREATE TABLE report_columns_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL,
  report_id uuid NOT NULL,
  column_key varchar(64) NOT NULL CHECK (column_key ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
  label varchar(100) NOT NULL CHECK (length(btrim(label)) > 0),
  data_type varchar(16) NOT NULL CHECK (data_type IN ('TEXT','NUMBER','BOOLEAN','DATE','DATETIME','UUID')),
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 99999),
  is_visible boolean NOT NULL DEFAULT true,
  is_sortable boolean NOT NULL DEFAULT false,
  is_exportable boolean NOT NULL DEFAULT true,
  format_configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  CONSTRAINT report_columns_tenant_report_fk FOREIGN KEY(operator_id, report_id)
    REFERENCES reports_mst(operator_id, id) ON DELETE RESTRICT,
  CONSTRAINT report_columns_key_uq UNIQUE(operator_id, report_id, column_key),
  CONSTRAINT report_columns_format_object CHECK (jsonb_typeof(format_configuration) = 'object'),
  CONSTRAINT report_columns_format_keys CHECK
    (format_configuration - ARRAY['dateFormat','decimalPlaces','currencyCode']::text[] = '{}'::jsonb),
  CONSTRAINT report_columns_format_types CHECK
    ((NOT (format_configuration ? 'dateFormat') OR jsonb_typeof(format_configuration->'dateFormat') = 'string') AND
     (NOT (format_configuration ? 'currencyCode') OR jsonb_typeof(format_configuration->'currencyCode') = 'string') AND
     (NOT (format_configuration ? 'decimalPlaces') OR (jsonb_typeof(format_configuration->'decimalPlaces') = 'number' AND
       (format_configuration->>'decimalPlaces')::numeric = trunc((format_configuration->>'decimalPlaces')::numeric) AND
       (format_configuration->>'decimalPlaces')::integer BETWEEN 0 AND 8)))
);
CREATE INDEX report_columns_order_idx ON report_columns_mst(operator_id, report_id, sort_order, column_key);

CREATE TABLE report_role_access_mst (
  operator_id uuid NOT NULL,
  report_id uuid NOT NULL,
  role_key varchar(48) NOT NULL,
  can_view boolean NOT NULL DEFAULT false,
  can_execute boolean NOT NULL DEFAULT false,
  can_export boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  PRIMARY KEY(operator_id, report_id, role_key),
  CONSTRAINT report_role_access_report_fk FOREIGN KEY(operator_id, report_id)
    REFERENCES reports_mst(operator_id, id) ON DELETE RESTRICT,
  CONSTRAINT report_role_access_role_fk FOREIGN KEY(operator_id, role_key)
    REFERENCES operator_roles_mst(operator_id, role_key) ON DELETE RESTRICT,
  CONSTRAINT report_role_access_execute_requires_view_ck CHECK (NOT can_execute OR can_view),
  CONSTRAINT report_role_access_export_requires_execute_ck CHECK (NOT can_export OR (can_execute AND can_view))
);
CREATE INDEX report_role_access_lookup_idx
  ON report_role_access_mst(operator_id, role_key, report_id) WHERE can_view = true;

-- Keep the established actor/timestamp behavior for future tenant metadata writes.
CREATE TRIGGER audit_report_routines BEFORE INSERT OR UPDATE ON report_routines_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_report_templates BEFORE INSERT OR UPDATE ON report_templates_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_report_lookup_sources BEFORE INSERT OR UPDATE ON report_lookup_sources_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_report_template_filters BEFORE INSERT OR UPDATE ON report_template_filters_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_report_template_columns BEFORE INSERT OR UPDATE ON report_template_columns_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_reports BEFORE INSERT OR UPDATE ON reports_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_report_filters BEFORE INSERT OR UPDATE ON report_filters_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_report_columns BEFORE INSERT OR UPDATE ON report_columns_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_report_role_access BEFORE INSERT OR UPDATE ON report_role_access_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();

-- Catalog registration documents supported capabilities but does not assign them to roles.
INSERT INTO permissions_mst(permission_code, permission_name, description, module, is_system_permission)
VALUES
  ('REPORT_VIEW','View reports','Allows viewing report definitions and metadata when the tenant role also has an explicit report view grant. Does not allow report execution or export.','Reports',true),
  ('REPORT_EXECUTE','Execute reports','Allows executing an explicitly granted report when the tenant role also has that report execution grant. Does not allow export.','Reports',true),
  ('REPORT_EXPORT','Export reports','Allows exporting results from an explicitly granted report when the tenant role also has report view and execution grants.','Reports',true)
ON CONFLICT (permission_code) DO NOTHING;
