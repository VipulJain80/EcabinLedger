CREATE TABLE configuration_imports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  actor_id varchar(200) NOT NULL,
  resource_key varchar(48) NOT NULL,
  rows_json jsonb NOT NULL,
  errors_json jsonb NOT NULL,
  result_json jsonb,
  row_count integer NOT NULL CHECK (row_count BETWEEN 0 AND 5000),
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
CREATE INDEX configuration_imports_expiry_idx ON configuration_imports(expires_at);
CREATE INDEX configuration_imports_owner_idx ON configuration_imports(operator_id, actor_id, resource_key, status);
