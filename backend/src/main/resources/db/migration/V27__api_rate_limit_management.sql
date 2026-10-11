CREATE TABLE api_rate_limit_policies_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  route_key varchar(40) NOT NULL CHECK (route_key IN ('ORDINARY_READS','DEFECT_CREATE','WORKFLOW_MUTATIONS','EXPENSIVE_SEARCH','CSV_IMPORTS','CSV_EXPORTS','EVIDENCE_UPLOADS')),
  policy_name varchar(120) NOT NULL,
  description varchar(500) NOT NULL DEFAULT '',
  scope_type varchar(16) NOT NULL CHECK (scope_type IN ('PER_USER','TENANT')),
  max_requests integer NOT NULL CHECK (max_requests BETWEEN 1 AND 10000),
  window_seconds integer NOT NULL CHECK (window_seconds BETWEEN 1 AND 3600),
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200) NOT NULL, updated_by varchar(200) NOT NULL,
  CONSTRAINT api_rate_limit_policy_scope_unique UNIQUE(operator_id,route_key,scope_type)
);
CREATE INDEX api_rate_limit_policy_lookup_idx ON api_rate_limit_policies_mst(operator_id,route_key,isactive,scope_type);

CREATE TABLE api_rate_limit_usage_dtl (
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  policy_id uuid NOT NULL REFERENCES api_rate_limit_policies_mst(id),
  scope_hash char(64) NOT NULL,
  window_start timestamptz NOT NULL,
  request_count bigint NOT NULL DEFAULT 0 CHECK (request_count >= 0),
  rejected_count bigint NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(operator_id,policy_id,scope_hash,window_start)
);
CREATE INDEX api_rate_limit_usage_recent_idx ON api_rate_limit_usage_dtl(operator_id,window_start DESC);

-- Unauthenticated local token/bootstrap endpoint guard. Only a one-way IP digest is retained.
CREATE TABLE api_rate_limit_public_usage_dtl (
  route_key varchar(40) NOT NULL,
  scope_hash char(64) NOT NULL,
  window_start timestamptz NOT NULL,
  request_count bigint NOT NULL DEFAULT 0,
  rejected_count bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(route_key,scope_hash,window_start)
);

CREATE TABLE api_rate_limit_policy_events_dtl (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  policy_id uuid NOT NULL REFERENCES api_rate_limit_policies_mst(id),
  actor_id varchar(200) NOT NULL,
  event_type varchar(16) NOT NULL CHECK (event_type IN ('CREATED','UPDATED','ACTIVATED','DEACTIVATED')),
  reason varchar(1000) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 1000),
  previous_values jsonb,
  new_values jsonb NOT NULL,
  request_id varchar(80),
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX api_rate_limit_events_tenant_recent_idx ON api_rate_limit_policy_events_dtl(operator_id,occurred_at DESC);
CREATE FUNCTION prevent_api_rate_limit_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'API rate limit policy history is append-only'; END $$;
CREATE TRIGGER api_rate_limit_events_immutable BEFORE UPDATE OR DELETE ON api_rate_limit_policy_events_dtl
FOR EACH ROW EXECUTE FUNCTION prevent_api_rate_limit_event_mutation();

-- Starter limits are tenant rows, so administrators can tune them. The fixed server-side
-- safety ceiling in Java remains outside this table's authority.
CREATE FUNCTION seed_api_rate_limit_policies(p_operator uuid, p_actor varchar) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO api_rate_limit_policies_mst(operator_id,route_key,policy_name,description,scope_type,max_requests,window_seconds,created_by,updated_by)
  VALUES
    (p_operator,'ORDINARY_READS','Ordinary API reads','Default per-user read limit.','PER_USER',120,60,p_actor,p_actor),
    (p_operator,'DEFECT_CREATE','Defect creation','Default per-user defect creation limit.','PER_USER',30,60,p_actor,p_actor),
    (p_operator,'WORKFLOW_MUTATIONS','Workflow mutations','Default per-user workflow mutation limit.','PER_USER',30,60,p_actor,p_actor),
    (p_operator,'EXPENSIVE_SEARCH','Dashboard and searches','Default per-user dashboard and expensive search limit.','PER_USER',30,60,p_actor,p_actor),
    (p_operator,'CSV_IMPORTS','CSV imports','Default per-user import validation and confirmation limit.','PER_USER',10,60,p_actor,p_actor),
    (p_operator,'CSV_EXPORTS','CSV exports','Default per-user CSV export limit.','PER_USER',10,60,p_actor,p_actor),
    (p_operator,'EVIDENCE_UPLOADS','Evidence uploads','Default per-user evidence upload request limit.','PER_USER',10,60,p_actor,p_actor)
  ON CONFLICT(operator_id,route_key,scope_type) DO NOTHING;
END $$;
SELECT seed_api_rate_limit_policies(id,'system:migration') FROM operators_mst;
CREATE FUNCTION seed_api_rate_limits_for_operator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM seed_api_rate_limit_policies(NEW.id,'system:tenant-seed'); RETURN NEW; END $$;
CREATE TRIGGER operators_seed_api_rate_limits AFTER INSERT ON operators_mst FOR EACH ROW EXECUTE FUNCTION seed_api_rate_limits_for_operator();

INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'api-rate-limits','API Rate Limits',c.id,'ADMINISTER_ORGANIZATION','◴','api-rate-limits',140
FROM operators_mst o JOIN navigation_menu_items_mst c ON c.operator_id=o.id AND c.menu_key='configuration' AND c.parent_id IS NULL
ON CONFLICT DO NOTHING;
CREATE FUNCTION seed_api_rate_limits_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'api-rate-limits','API Rate Limits',NEW.id,'ADMINISTER_ORGANIZATION','◴','api-rate-limits',140)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER navigation_seed_api_rate_limits AFTER INSERT ON navigation_menu_items_mst FOR EACH ROW EXECUTE FUNCTION seed_api_rate_limits_navigation();
