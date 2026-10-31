-- Read-only verification queries for the schema documented in
-- DATABASE_CLEANUP_GUIDE.md. This file performs SELECTs only when an operator
-- chooses to run it; it is not executed automatically.

-- Database identity and installed utility state.
SELECT current_database() AS database_name, current_schema() AS current_schema,
       current_user AS current_user, session_user AS session_user;

SELECT database_name,environment,master_cleanup_enabled,transaction_purge_enabled,
       form_configuration_purge_enabled,production_purge_enabled,
       approval_reference,approved_by,approved_at,backup_reference,backup_approved_by,backup_approved_at
FROM ecabin_ledger.cleanup_database_policy
WHERE database_name=current_database();

-- The explicit inactive-master allowlist and its system-record protections.
SELECT table_name,active_column,inactive_value,protected_column,protected_values,rationale
FROM ecabin_ledger.cleanup_master_registry ORDER BY table_name;

-- All current base tables and suffix classifications. Review this against the
-- guide before authorizing either operation; suffix alone is not a purge rule.
SELECT table_name,
       CASE WHEN table_name LIKE '%\_mst' ESCAPE '\' THEN 'MASTER_SUFFIX'
            WHEN table_name LIKE '%\_dtl' ESCAPE '\' THEN 'DETAIL_SUFFIX'
            ELSE 'NO_SUFFIX' END AS suffix_class
FROM information_schema.tables
WHERE table_schema='ecabin_ledger' AND table_type='BASE TABLE'
ORDER BY table_name;

-- Foreign-key dependency inventory, including delete action. Any unanticipated
-- relation blocks confidence in the reviewed child-to-parent plan.
SELECT child_ns.nspname AS child_schema,child.relname AS child_table,con.conname AS constraint_name,
       parent_ns.nspname AS parent_schema,parent.relname AS parent_table,
       CASE con.confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
            WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END AS on_delete
FROM pg_constraint con
JOIN pg_class child ON child.oid=con.conrelid
JOIN pg_namespace child_ns ON child_ns.oid=child.relnamespace
JOIN pg_class parent ON parent.oid=con.confrelid
JOIN pg_namespace parent_ns ON parent_ns.oid=parent.relnamespace
WHERE con.contype='f' AND (child_ns.nspname='ecabin_ledger' OR parent_ns.nspname='ecabin_ledger')
ORDER BY parent_schema,parent_table,child_schema,child_table,con.conname;

-- Inactive candidates on registered masters, grouped by table. This reports
-- counts only; it does not determine which rows are system-required/FK-blocked.
DO $verify$
DECLARE r record; n bigint;
BEGIN
  FOR r IN SELECT * FROM ecabin_ledger.cleanup_master_registry ORDER BY table_name LOOP
    EXECUTE format('SELECT count(*) FROM ecabin_ledger.%I WHERE %I::text=$1',r.table_name,r.active_column)
      INTO n USING r.inactive_value;
    RAISE NOTICE 'registered_table=% inactive_candidate_count=%',r.table_name,n;
  END LOOP;
END
$verify$;

-- Prior successful executions and table-level counts.
SELECT r.execution_id,r.started_at,r.completed_at,r.database_name,r.operation_type,
       r.dry_run,r.initiated_by,r.outcome,r.approval_reference,r.backup_reference,
       l.table_name,l.total_records,l.inactive_records,l.eligible_records,
       l.blocked_records,l.deleted_records,l.affected_row_count
FROM ecabin_ledger.cleanup_execution_runs r
LEFT JOIN ecabin_ledger.cleanup_execution_log l USING (execution_id)
WHERE r.database_name=current_database()
ORDER BY r.started_at DESC,l.table_name;

-- External storage work must be completed before declaring the reset complete.
SELECT execution_id,status,count(*) AS object_count
FROM ecabin_ledger.cleanup_storage_manifest
GROUP BY execution_id,status ORDER BY execution_id,status;
SELECT execution_id,manifest_id,object_key,status,queued_at,result_note
FROM ecabin_ledger.cleanup_storage_manifest
WHERE status IN ('PENDING','ERROR') ORDER BY queued_at,manifest_id;
