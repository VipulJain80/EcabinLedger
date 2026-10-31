-- Administrative installation script. This is intentionally outside Flyway.
-- Run once as a DBA after reviewing DATABASE_CLEANUP_GUIDE.md.
-- This script creates administrative control/log objects only; it does not alter
-- application tables or delete business data.

BEGIN;

DO $roles$
DECLARE r record;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ecabin_cleanup_executor') THEN
    EXECUTE 'CREATE ROLE ecabin_cleanup_executor NOLOGIN';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ecabin_cleanup_policy_admin') THEN
    EXECUTE 'CREATE ROLE ecabin_cleanup_policy_admin NOLOGIN';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ecabin_cleanup_storage_operator') THEN
    EXECUTE 'CREATE ROLE ecabin_cleanup_storage_operator NOLOGIN';
  END IF;
  FOR r IN SELECT rolname,rolcanlogin,rolsuper,rolcreaterole,rolcreatedb,rolreplication,rolbypassrls
           FROM pg_roles
           WHERE rolname IN ('ecabin_cleanup_executor','ecabin_cleanup_policy_admin','ecabin_cleanup_storage_operator')
  LOOP
    IF r.rolcanlogin OR r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolbypassrls THEN
      RAISE EXCEPTION 'Existing cleanup role % has unsafe role attributes; manual DBA review required.',r.rolname;
    END IF;
  END LOOP;
END
$roles$;

CREATE TABLE ecabin_ledger.cleanup_database_policy (
  database_name text PRIMARY KEY,
  environment text NOT NULL CHECK (environment IN ('DEVELOPMENT','TEST','UAT','PRODUCTION')),
  master_cleanup_enabled boolean NOT NULL DEFAULT false,
  transaction_purge_enabled boolean NOT NULL DEFAULT false,
  form_configuration_purge_enabled boolean NOT NULL DEFAULT false,
  production_purge_enabled boolean NOT NULL DEFAULT false,
  approval_reference text,
  approved_by text,
  approved_at timestamptz,
  backup_reference text,
  backup_approved_by text,
  backup_approved_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK ((approval_reference IS NULL AND approved_by IS NULL AND approved_at IS NULL)
      OR (approval_reference IS NOT NULL AND btrim(approval_reference) <> ''
          AND approved_by IS NOT NULL AND btrim(approved_by) <> '' AND approved_at IS NOT NULL)),
  CHECK ((backup_reference IS NULL AND backup_approved_by IS NULL AND backup_approved_at IS NULL)
      OR (backup_reference IS NOT NULL AND btrim(backup_reference) <> ''
          AND backup_approved_by IS NOT NULL AND btrim(backup_approved_by) <> '' AND backup_approved_at IS NOT NULL))
);

CREATE TABLE ecabin_ledger.cleanup_master_registry (
  table_name text PRIMARY KEY,
  active_column text NOT NULL,
  inactive_value text NOT NULL,
  protected_column text,
  protected_values text[] NOT NULL DEFAULT ARRAY[]::text[],
  rationale text NOT NULL CHECK (btrim(rationale) <> '')
);

-- Explicitly registered current reference tables only. V23 drops the old
-- cabin_zones_mst table; it is deliberately not listed here.
INSERT INTO ecabin_ledger.cleanup_master_registry
  (table_name, active_column, inactive_value, protected_column, protected_values, rationale)
VALUES
  ('fleets_mst','isactive','2','name',ARRAY['General Fleet'],'Inactive fleets are eligible only when unreferenced; seeded General Fleet is protected.'),
  ('aircraft_mst','isactive','2',NULL,ARRAY[]::text[],'Inactive aircraft are eligible only when no retained row references them.'),
  ('maintenance_teams_mst','isactive','2',NULL,ARRAY[]::text[],'Inactive teams are eligible only when no retained row references them.'),
  ('cabin_areas_mst','isactive','2','code',ARRAY['ENTRY_AREA','GALLEY','LAVATORY','CABIN','ATTENDANT_SEAT'],'Provisioned base areas are system-required.'),
  ('cabin_components_mst','isactive','2','code',ARRAY['SEAT','IFE_DISPLAY','READING_LIGHT','OVERHEAD_BIN_LATCH','PSU','GALLEY_CART_LATCH','LAVATORY_FAUCET','ATTENDANT_SEAT_BELT','VINYL','OVEN','TAP','TRAY_TABLE'],'Starter components are protected; tenant-created inactive components may be removed only when unreferenced.'),
  ('defect_categories_mst','isactive','2','code',ARRAY['CABIN','GALLEY','LAVATORY','ATTENDANT_SEAT','IFE','SEAT','LIGHTING','OVERHEAD_BIN','PSU','EMERGENCY_EQUIPMENT','OTHER'],'Starter category codes are system-required.'),
  ('defect_conditions_mst','isactive','2',NULL,ARRAY[]::text[],'No seeded conditions were identified; references from retained rows block deletion.'),
  ('component_conditions_mst','isactive','2',NULL,ARRAY[]::text[],'Inactive component-condition mappings may be removed only when unreferenced.'),
  ('defect_severities_mst','isactive','2','severity_code',ARRAY['LOW','MEDIUM','HIGH','CRITICAL'],'Starter severity codes and the V75 per-tenant default configuration are preserved.')
;

CREATE TABLE ecabin_ledger.cleanup_execution_runs (
  execution_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  database_name text NOT NULL,
  operation_type text NOT NULL CHECK (operation_type IN ('INACTIVE_MASTER_CLEANUP','TRANSACTION_PURGE')),
  dry_run boolean NOT NULL,
  initiated_by text NOT NULL,
  outcome text NOT NULL DEFAULT 'RUNNING' CHECK (outcome IN ('RUNNING','SUCCEEDED')),
  approval_reference text,
  backup_reference text
);

CREATE TABLE ecabin_ledger.cleanup_execution_log (
  execution_id uuid NOT NULL REFERENCES ecabin_ledger.cleanup_execution_runs(execution_id) ON DELETE RESTRICT,
  table_name text NOT NULL,
  total_records bigint NOT NULL DEFAULT 0 CHECK (total_records >= 0),
  inactive_records bigint NOT NULL DEFAULT 0 CHECK (inactive_records >= 0),
  eligible_records bigint NOT NULL DEFAULT 0 CHECK (eligible_records >= 0),
  blocked_records bigint NOT NULL DEFAULT 0 CHECK (blocked_records >= 0),
  deleted_records bigint NOT NULL DEFAULT 0 CHECK (deleted_records >= 0),
  affected_row_count bigint NOT NULL DEFAULT 0 CHECK (affected_row_count >= 0),
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (execution_id, table_name)
);

-- SQL cannot atomically remove Azure/local object blobs. Purge records their keys
-- here before deleting attachment metadata; a separate storage operator handles
-- the external deletion and records its result.
CREATE TABLE ecabin_ledger.cleanup_storage_manifest (
  manifest_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  execution_id uuid NOT NULL REFERENCES ecabin_ledger.cleanup_execution_runs(execution_id) ON DELETE RESTRICT,
  object_key text NOT NULL,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELETED','ERROR')),
  queued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  result_note text,
  UNIQUE (execution_id, object_key),
  CHECK ((status = 'PENDING' AND completed_at IS NULL)
      OR (status <> 'PENDING' AND completed_at IS NOT NULL))
);

REVOKE ALL ON ecabin_ledger.cleanup_database_policy,
  ecabin_ledger.cleanup_master_registry,
  ecabin_ledger.cleanup_execution_runs,
  ecabin_ledger.cleanup_execution_log,
  ecabin_ledger.cleanup_storage_manifest FROM PUBLIC;
GRANT USAGE ON SCHEMA ecabin_ledger TO ecabin_cleanup_executor,
  ecabin_cleanup_policy_admin,ecabin_cleanup_storage_operator;
GRANT SELECT, INSERT, UPDATE, DELETE ON ecabin_ledger.cleanup_database_policy,
  ecabin_ledger.cleanup_master_registry TO ecabin_cleanup_policy_admin;
GRANT SELECT ON ecabin_ledger.cleanup_execution_runs,
  ecabin_ledger.cleanup_execution_log TO ecabin_cleanup_executor, ecabin_cleanup_policy_admin;
GRANT SELECT ON ecabin_ledger.cleanup_storage_manifest TO ecabin_cleanup_executor,
  ecabin_cleanup_storage_operator,ecabin_cleanup_policy_admin;
GRANT UPDATE (status,completed_at,result_note) ON ecabin_ledger.cleanup_storage_manifest
  TO ecabin_cleanup_storage_operator;
GRANT UPDATE ON ecabin_ledger.cleanup_storage_manifest TO ecabin_cleanup_policy_admin;

COMMIT;

-- No policy row is inserted by this script: all execution remains disabled until
-- a DBA records the exact database name, environment, approval, and (for real
-- production cleanup) a recently approved backup in cleanup_database_policy.
