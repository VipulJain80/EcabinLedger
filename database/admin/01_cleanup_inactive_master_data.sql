-- Administrative utility, intentionally outside Flyway.
-- Install 03_cleanup_execution_log.sql first. This file defines code only; it
-- does not call the procedure or alter application/master data.

BEGIN;

CREATE OR REPLACE FUNCTION ecabin_ledger.cleanup_require_policy(
  p_operation text,
  p_dry_run boolean,
  p_confirm boolean
) RETURNS ecabin_ledger.cleanup_database_policy
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ecabin_ledger
AS $function$
DECLARE
  v_policy ecabin_ledger.cleanup_database_policy%ROWTYPE;
BEGIN
  IF NOT pg_has_role(session_user, 'ecabin_cleanup_executor', 'member') THEN
    RAISE EXCEPTION 'Caller is not authorized for database cleanup.' USING ERRCODE = '42501';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user AND rolsuper) THEN
    RAISE EXCEPTION 'Cleanup procedures must be owned by a non-superuser database role.' USING ERRCODE = '42501';
  END IF;
  IF p_dry_run IS NULL OR p_confirm IS NULL OR p_operation IS NULL THEN
    RAISE EXCEPTION 'Cleanup flags and operation are required.' USING ERRCODE = '22023';
  END IF;
  SELECT * INTO v_policy
  FROM ecabin_ledger.cleanup_database_policy
  WHERE database_name = current_database()
  FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'No cleanup policy is registered for database %; fail-closed.', current_database()
      USING ERRCODE = '55000';
  END IF;
  IF p_operation NOT IN ('INACTIVE_MASTER_CLEANUP','TRANSACTION_PURGE') THEN
    RAISE EXCEPTION 'Unsupported cleanup operation.' USING ERRCODE = '22023';
  END IF;
  IF p_operation = 'TRANSACTION_PURGE' AND NOT p_dry_run AND p_confirm IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'Transaction purge requires p_confirm => TRUE.' USING ERRCODE = '42501';
  END IF;
  IF p_dry_run THEN
    RETURN v_policy;
  END IF;

  IF p_operation = 'INACTIVE_MASTER_CLEANUP' AND NOT v_policy.master_cleanup_enabled THEN
    RAISE EXCEPTION 'Inactive master cleanup is disabled by database policy.' USING ERRCODE = '42501';
  END IF;
  IF p_operation = 'TRANSACTION_PURGE' AND NOT v_policy.transaction_purge_enabled THEN
    RAISE EXCEPTION 'Transaction purge is disabled by database policy.' USING ERRCODE = '42501';
  END IF;
  IF v_policy.approval_reference IS NULL OR v_policy.approved_by IS NULL OR v_policy.approved_at IS NULL
     OR v_policy.approved_at > clock_timestamp()
     OR v_policy.approved_at < clock_timestamp() - interval '24 hours' THEN
    RAISE EXCEPTION 'An explicit cleanup approval from the last 24 hours is required.' USING ERRCODE = '42501';
  END IF;
  IF v_policy.environment = 'PRODUCTION' THEN
    IF NOT v_policy.production_purge_enabled THEN
      RAISE EXCEPTION 'Production cleanup is disabled; explicit production authorization is required.' USING ERRCODE = '42501';
    END IF;
    IF v_policy.backup_reference IS NULL OR v_policy.backup_approved_by IS NULL
       OR v_policy.backup_approved_at IS NULL
       OR v_policy.backup_approved_at < clock_timestamp() - interval '24 hours'
       OR v_policy.backup_approved_at > clock_timestamp() THEN
      RAISE EXCEPTION 'A verified backup approval from the last 24 hours is required for production cleanup.'
        USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN v_policy;
END
$function$;

REVOKE ALL ON FUNCTION ecabin_ledger.cleanup_require_policy(text, boolean, boolean) FROM PUBLIC;

CREATE OR REPLACE PROCEDURE ecabin_ledger.cleanup_inactive_master_data(
  IN p_dry_run boolean DEFAULT TRUE
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ecabin_ledger
AS $procedure$
DECLARE
  v_policy ecabin_ledger.cleanup_database_policy%ROWTYPE;
  v_run_id uuid := gen_random_uuid();
  v_entry record;
  v_lock record;
  v_rel regclass;
  v_protected_predicate text;
  v_reference_predicate text;
  v_sql text;
  v_total bigint;
  v_inactive bigint;
  v_blocked bigint;
  v_eligible bigint;
  v_deleted bigint := 0;
BEGIN
  v_policy := ecabin_ledger.cleanup_require_policy('INACTIVE_MASTER_CLEANUP', p_dry_run, FALSE);

  -- Prevent overlapping administrative cleanup operations. The lock releases
  -- automatically at transaction end, including rollback.
  PERFORM pg_advisory_xact_lock(784492130041::bigint);

  IF NOT p_dry_run THEN
    LOCK TABLE ecabin_ledger.cleanup_master_registry IN SHARE MODE NOWAIT;
    -- Freeze writes to each registered master and every FK-dependent table before
    -- eligibility checks. NOWAIT fails safely instead of waiting behind the app.
    FOR v_lock IN
      SELECT DISTINCT n.nspname, c.relname
      FROM ecabin_ledger.cleanup_master_registry r
      JOIN pg_class parent ON parent.oid = to_regclass(format('%I.%I','ecabin_ledger',r.table_name))
      JOIN pg_namespace pn ON pn.oid = parent.relnamespace
      JOIN pg_constraint fk ON fk.confrelid = parent.oid AND fk.contype = 'f'
      JOIN pg_class c ON c.oid = fk.conrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      UNION
      SELECT 'ecabin_ledger'::name, r.table_name::name
      FROM ecabin_ledger.cleanup_master_registry r
    LOOP
      EXECUTE format('LOCK TABLE %I.%I IN SHARE ROW EXCLUSIVE MODE NOWAIT', v_lock.nspname, v_lock.relname);
    END LOOP;
  END IF;

  INSERT INTO ecabin_ledger.cleanup_execution_runs
    (execution_id,database_name,operation_type,dry_run,initiated_by,outcome,approval_reference,backup_reference)
  VALUES (v_run_id,current_database(),'INACTIVE_MASTER_CLEANUP',p_dry_run,session_user,'RUNNING',
          v_policy.approval_reference,v_policy.backup_reference);

  FOR v_entry IN
    SELECT * FROM ecabin_ledger.cleanup_master_registry ORDER BY table_name
  LOOP
    v_rel := to_regclass(format('%I.%I','ecabin_ledger',v_entry.table_name));
    IF v_rel IS NULL THEN
      RAISE EXCEPTION 'Registered master table ecabin_ledger.% is missing; review the allowlist.',v_entry.table_name;
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM pg_attribute a
      WHERE a.attrelid=v_rel AND a.attname=v_entry.active_column AND a.attnum>0 AND NOT a.attisdropped
    ) THEN
      RAISE EXCEPTION 'Registered active column %.% is missing; review the allowlist.',v_entry.table_name,v_entry.active_column;
    END IF;
    IF v_entry.protected_column IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM pg_attribute a
      WHERE a.attrelid=v_rel AND a.attname=v_entry.protected_column AND a.attnum>0 AND NOT a.attisdropped
    ) THEN
      RAISE EXCEPTION 'Registered protected column %.% is missing; review the allowlist.',v_entry.table_name,v_entry.protected_column;
    END IF;

    v_protected_predicate := CASE WHEN v_entry.protected_column IS NULL THEN 'FALSE'
      ELSE format('m.%I::text = ANY($2)',v_entry.protected_column) END;

    SELECT string_agg(
      format('EXISTS (SELECT 1 FROM %I.%I AS c WHERE %s)', child_ns.nspname, child.relname, fk_match.match_sql),
      ' OR '
    ) INTO v_reference_predicate
    FROM (
      SELECT fk.oid, fk.conrelid, string_agg(
        format('c.%I = m.%I', child_attr.attname, parent_attr.attname), ' AND ' ORDER BY child_key.ordinality
      ) AS match_sql
      FROM pg_constraint fk
      JOIN LATERAL unnest(fk.conkey) WITH ORDINALITY AS child_key(attnum,ordinality) ON TRUE
      JOIN LATERAL unnest(fk.confkey) WITH ORDINALITY AS parent_key(attnum,ordinality)
        ON parent_key.ordinality=child_key.ordinality
      JOIN pg_attribute child_attr ON child_attr.attrelid=fk.conrelid AND child_attr.attnum=child_key.attnum
      JOIN pg_attribute parent_attr ON parent_attr.attrelid=fk.confrelid AND parent_attr.attnum=parent_key.attnum
      WHERE fk.contype='f' AND fk.confrelid=v_rel
      GROUP BY fk.oid,fk.conrelid
    ) fk_match
    JOIN pg_class child ON child.oid=fk_match.conrelid
    JOIN pg_namespace child_ns ON child_ns.oid=child.relnamespace;
    v_reference_predicate := COALESCE(v_reference_predicate,'FALSE');

    v_sql := format(
      'SELECT count(*), count(*) FILTER (WHERE m.%I::text=$1), '
      'count(*) FILTER (WHERE m.%I::text=$1 AND ((%s) OR (%s))), '
      'count(*) FILTER (WHERE m.%I::text=$1 AND NOT ((%s) OR (%s))) '
      'FROM ecabin_ledger.%I AS m',
      v_entry.active_column,v_entry.active_column,v_protected_predicate,v_reference_predicate,
      v_entry.active_column,v_protected_predicate,v_reference_predicate,v_entry.table_name
    );
    EXECUTE v_sql INTO v_total,v_inactive,v_blocked,v_eligible USING v_entry.inactive_value,v_entry.protected_values;

    v_deleted := 0;
    IF NOT p_dry_run THEN
      v_sql := format(
        'WITH doomed AS (SELECT m.ctid FROM ecabin_ledger.%I AS m '
        'WHERE m.%I::text=$1 AND NOT ((%s) OR (%s)) FOR UPDATE) '
        'DELETE FROM ecabin_ledger.%I AS m USING doomed d WHERE m.ctid=d.ctid',
        v_entry.table_name,v_entry.active_column,v_protected_predicate,v_reference_predicate,v_entry.table_name
      );
      EXECUTE v_sql USING v_entry.inactive_value,v_entry.protected_values;
      GET DIAGNOSTICS v_deleted = ROW_COUNT;
    END IF;

    INSERT INTO ecabin_ledger.cleanup_execution_log
      (execution_id,table_name,total_records,inactive_records,eligible_records,blocked_records,deleted_records,affected_row_count)
    VALUES (v_run_id,v_entry.table_name,v_total,v_inactive,v_eligible,v_blocked,v_deleted,v_deleted);
    RAISE NOTICE 'execution_id=% table=% total=% inactive=% eligible=% blocked=% deleted=%',
      v_run_id,v_entry.table_name,v_total,v_inactive,v_eligible,v_blocked,v_deleted;
  END LOOP;

  UPDATE ecabin_ledger.cleanup_execution_runs
  SET outcome='SUCCEEDED',completed_at=clock_timestamp()
  WHERE execution_id=v_run_id;
  RAISE NOTICE 'Inactive master cleanup % completed; dry_run=%',v_run_id,p_dry_run;
END
$procedure$;

REVOKE ALL ON PROCEDURE ecabin_ledger.cleanup_inactive_master_data(boolean) FROM PUBLIC;
GRANT EXECUTE ON PROCEDURE ecabin_ledger.cleanup_inactive_master_data(boolean) TO ecabin_cleanup_executor;
GRANT EXECUTE ON FUNCTION ecabin_ledger.cleanup_require_policy(text,boolean,boolean) TO ecabin_cleanup_executor;

COMMIT;
