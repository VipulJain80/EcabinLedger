-- Administrative utility, intentionally outside Flyway.
-- Install 03_cleanup_execution_log.sql first. This file defines code only; it
-- does not call the procedure or alter application data.

BEGIN;

CREATE OR REPLACE FUNCTION ecabin_ledger.cleanup_log_purge_table(
  p_execution_id uuid, p_table_name text, p_planned bigint, p_deleted bigint
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ecabin_ledger
AS $function$
BEGIN
  INSERT INTO ecabin_ledger.cleanup_execution_log
    (execution_id,table_name,total_records,eligible_records,deleted_records,affected_row_count)
  VALUES (p_execution_id,p_table_name,p_planned,p_planned,p_deleted,p_deleted);
END
$function$;
REVOKE ALL ON FUNCTION ecabin_ledger.cleanup_log_purge_table(uuid,text,bigint,bigint) FROM PUBLIC;

CREATE OR REPLACE PROCEDURE ecabin_ledger.purge_transaction_data(
  IN p_confirm boolean DEFAULT FALSE,
  IN p_dry_run boolean DEFAULT TRUE
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ecabin_ledger
AS $procedure$
DECLARE
  v_policy ecabin_ledger.cleanup_database_policy%ROWTYPE;
  v_run_id uuid := gen_random_uuid();
  v_count bigint;
  v_deleted bigint;
BEGIN
  v_policy := ecabin_ledger.cleanup_require_policy('TRANSACTION_PURGE',p_dry_run,p_confirm);
  PERFORM pg_advisory_xact_lock(784492130041::bigint);

  IF NOT p_dry_run THEN
    -- Acquire the data-set locks before taking the target snapshot so a defect
    -- committed immediately before lock acquisition is included in the purge.
    LOCK TABLE ecabin_ledger.attachments_dtl,
      ecabin_ledger.email_outbox_attempts_dtl, ecabin_ledger.email_outbox_dtl,
      ecabin_ledger.notifications_dtl, ecabin_ledger.closure_approvals_dtl,
      ecabin_ledger.verifications_dtl, ecabin_ledger.inspections_dtl,
      ecabin_ledger.corrective_actions_dtl, ecabin_ledger.operational_form_submissions_dtl,
      ecabin_ledger.defect_events_dtl, ecabin_ledger.defects_dtl
      IN SHARE ROW EXCLUSIVE MODE NOWAIT;

    IF v_policy.form_configuration_purge_enabled THEN
      LOCK TABLE ecabin_ledger.operational_form_areas_mst,
        ecabin_ledger.operational_form_version_areas_dtl,
        ecabin_ledger.operational_form_versions_dtl,ecabin_ledger.operational_forms_dtl
        IN SHARE ROW EXCLUSIVE MODE NOWAIT;
    END IF;
  END IF;

  INSERT INTO ecabin_ledger.cleanup_execution_runs
    (execution_id,database_name,operation_type,dry_run,initiated_by,outcome,approval_reference,backup_reference)
  VALUES (v_run_id,current_database(),'TRANSACTION_PURGE',p_dry_run,session_user,'RUNNING',
          v_policy.approval_reference,v_policy.backup_reference);

  -- Defects define the approved operational-data purge boundary. This is a
  -- complete database reset for the explicitly approved non-production dataset;
  -- it is not a tenant-selective API. The policy/environment gate is mandatory.
  CREATE TEMP TABLE cleanup_target_defects ON COMMIT DROP AS
    SELECT operator_id,id FROM ecabin_ledger.defects_dtl;
  CREATE UNIQUE INDEX ON cleanup_target_defects(operator_id,id);

  IF NOT p_dry_run THEN
    -- Capture external blob keys before removing attachment metadata. Blob
    -- deletion is an explicit post-commit operator task, not an SQL side effect.
    INSERT INTO ecabin_ledger.cleanup_storage_manifest(execution_id,object_key)
    SELECT v_run_id,a.object_key
    FROM ecabin_ledger.attachments_dtl a
    JOIN cleanup_target_defects d ON d.operator_id=a.operator_id AND d.id=a.defect_id
    ON CONFLICT (execution_id,object_key) DO NOTHING;

    IF NOT EXISTS (
      SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
      WHERE c.oid='ecabin_ledger.defect_events_dtl'::regclass
        AND t.tgname='defect_events_append_only' AND NOT t.tgisinternal
    ) THEN
      RAISE EXCEPTION 'Expected defect_events_append_only trigger was not found; manual review required.';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
      WHERE c.oid='ecabin_ledger.operational_form_submissions_dtl'::regclass
        AND t.tgname='operational_form_submission_immutable' AND NOT t.tgisinternal
    ) THEN
      RAISE EXCEPTION 'Expected operational_form_submission_immutable trigger was not found; manual review required.';
    END IF;

    -- These exact guards are disabled only in the same transaction as the purge;
    -- any exception rolls back both data changes and trigger state.
    ALTER TABLE ecabin_ledger.defect_events_dtl DISABLE TRIGGER defect_events_append_only;
    ALTER TABLE ecabin_ledger.operational_form_submissions_dtl DISABLE TRIGGER operational_form_submission_immutable;
  END IF;

  -- Count/delete each explicit operational table in child-to-parent order.
  SELECT count(*) INTO v_count FROM ecabin_ledger.attachments_dtl a
    JOIN cleanup_target_defects d ON d.operator_id=a.operator_id AND d.id=a.defect_id;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.attachments_dtl a USING cleanup_target_defects d
      WHERE d.operator_id=a.operator_id AND d.id=a.defect_id;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'attachments_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.email_outbox_attempts_dtl a
    JOIN ecabin_ledger.email_outbox_dtl o ON o.operator_id=a.operator_id AND o.id=a.outbox_id
    JOIN ecabin_ledger.defect_events_dtl e ON e.operator_id=o.operator_id AND e.id=o.source_event_id
    JOIN cleanup_target_defects d ON d.operator_id=e.operator_id
      AND (d.id=e.defect_id OR (e.entity_type='DEFECT' AND d.id=e.entity_id));
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.email_outbox_attempts_dtl a USING ecabin_ledger.email_outbox_dtl o,
      ecabin_ledger.defect_events_dtl e,cleanup_target_defects d
    WHERE o.operator_id=a.operator_id AND o.id=a.outbox_id
      AND e.operator_id=o.operator_id AND e.id=o.source_event_id
      AND d.operator_id=e.operator_id
      AND (d.id=e.defect_id OR (e.entity_type='DEFECT' AND d.id=e.entity_id));
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'email_outbox_attempts_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.email_outbox_dtl o
    JOIN ecabin_ledger.defect_events_dtl e ON e.operator_id=o.operator_id AND e.id=o.source_event_id
    JOIN cleanup_target_defects d ON d.operator_id=e.operator_id
      AND (d.id=e.defect_id OR (e.entity_type='DEFECT' AND d.id=e.entity_id));
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.email_outbox_dtl o USING ecabin_ledger.defect_events_dtl e,cleanup_target_defects d
    WHERE e.operator_id=o.operator_id AND e.id=o.source_event_id
      AND d.operator_id=e.operator_id
      AND (d.id=e.defect_id OR (e.entity_type='DEFECT' AND d.id=e.entity_id));
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'email_outbox_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.notifications_dtl n
    JOIN cleanup_target_defects d ON d.operator_id=n.operator_id AND d.id=n.defect_id;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.notifications_dtl n USING cleanup_target_defects d
      WHERE d.operator_id=n.operator_id AND d.id=n.defect_id;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'notifications_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.closure_approvals_dtl x
    JOIN cleanup_target_defects d ON d.operator_id=x.operator_id AND d.id=x.defect_id;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.closure_approvals_dtl x USING cleanup_target_defects d
      WHERE d.operator_id=x.operator_id AND d.id=x.defect_id;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'closure_approvals_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.verifications_dtl x
    JOIN cleanup_target_defects d ON d.operator_id=x.operator_id AND d.id=x.defect_id;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.verifications_dtl x USING cleanup_target_defects d
      WHERE d.operator_id=x.operator_id AND d.id=x.defect_id;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'verifications_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.inspections_dtl x
    JOIN cleanup_target_defects d ON d.operator_id=x.operator_id AND d.id=x.defect_id;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.inspections_dtl x USING cleanup_target_defects d
      WHERE d.operator_id=x.operator_id AND d.id=x.defect_id;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'inspections_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.corrective_actions_dtl x
    JOIN cleanup_target_defects d ON d.operator_id=x.operator_id AND d.id=x.defect_id;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.corrective_actions_dtl x USING cleanup_target_defects d
      WHERE d.operator_id=x.operator_id AND d.id=x.defect_id;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'corrective_actions_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.operational_form_submissions_dtl x
    JOIN cleanup_target_defects d ON d.operator_id=x.operator_id AND d.id=x.defect_id;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.operational_form_submissions_dtl x USING cleanup_target_defects d
      WHERE d.operator_id=x.operator_id AND d.id=x.defect_id;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'operational_form_submissions_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.defect_events_dtl e
    JOIN cleanup_target_defects d ON d.operator_id=e.operator_id
      AND (d.id=e.defect_id OR (e.entity_type='DEFECT' AND d.id=e.entity_id));
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.defect_events_dtl e USING cleanup_target_defects d
      WHERE d.operator_id=e.operator_id
        AND (d.id=e.defect_id OR (e.entity_type='DEFECT' AND d.id=e.entity_id));
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'defect_events_dtl',v_count,v_deleted);

  SELECT count(*) INTO v_count FROM ecabin_ledger.defects_dtl;
  v_deleted := 0;
  IF NOT p_dry_run THEN
    DELETE FROM ecabin_ledger.defects_dtl;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
  END IF;
  PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'defects_dtl',v_count,v_deleted);

  IF NOT p_dry_run THEN
    ALTER TABLE ecabin_ledger.defect_events_dtl ENABLE TRIGGER defect_events_append_only;
    ALTER TABLE ecabin_ledger.operational_form_submissions_dtl ENABLE TRIGGER operational_form_submission_immutable;
  END IF;

  -- Form definitions are organization configuration and are preserved by default.
  -- Their purge requires a separate DBA policy switch and is counted independently.
  IF v_policy.form_configuration_purge_enabled THEN
    SELECT count(*) INTO v_count FROM ecabin_ledger.operational_form_version_areas_dtl;
    v_deleted := 0;
    IF NOT p_dry_run THEN
      LOCK TABLE ecabin_ledger.operational_form_areas_mst,ecabin_ledger.operational_form_version_areas_dtl,
        ecabin_ledger.operational_form_versions_dtl,ecabin_ledger.operational_forms_dtl
        IN SHARE ROW EXCLUSIVE MODE NOWAIT;
      IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='ecabin_ledger.operational_form_versions_dtl'::regclass
                     AND tgname='operational_form_version_immutable' AND NOT tgisinternal)
         OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='ecabin_ledger.operational_form_version_areas_dtl'::regclass
                        AND tgname='operational_form_version_areas_immutable' AND NOT tgisinternal) THEN
        RAISE EXCEPTION 'Expected form immutability triggers were not found; manual review required.';
      END IF;
      UPDATE ecabin_ledger.operational_forms_dtl SET published_version_id=NULL
        WHERE published_version_id IS NOT NULL;
      ALTER TABLE ecabin_ledger.operational_form_version_areas_dtl DISABLE TRIGGER operational_form_version_areas_immutable;
      ALTER TABLE ecabin_ledger.operational_form_versions_dtl DISABLE TRIGGER operational_form_version_immutable;
      DELETE FROM ecabin_ledger.operational_form_version_areas_dtl;
      GET DIAGNOSTICS v_deleted=ROW_COUNT;
    END IF;
    PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'operational_form_version_areas_dtl',v_count,v_deleted);

    SELECT count(*) INTO v_count FROM ecabin_ledger.operational_form_areas_mst;
    v_deleted := 0;
    IF NOT p_dry_run THEN
      DELETE FROM ecabin_ledger.operational_form_areas_mst;
      GET DIAGNOSTICS v_deleted=ROW_COUNT;
    END IF;
    PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'operational_form_areas_mst',v_count,v_deleted);

    SELECT count(*) INTO v_count FROM ecabin_ledger.operational_form_versions_dtl;
    v_deleted := 0;
    IF NOT p_dry_run THEN
      DELETE FROM ecabin_ledger.operational_form_versions_dtl;
      GET DIAGNOSTICS v_deleted=ROW_COUNT;
    END IF;
    PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'operational_form_versions_dtl',v_count,v_deleted);

    SELECT count(*) INTO v_count FROM ecabin_ledger.operational_forms_dtl;
    v_deleted := 0;
    IF NOT p_dry_run THEN
      DELETE FROM ecabin_ledger.operational_forms_dtl;
      GET DIAGNOSTICS v_deleted=ROW_COUNT;
      ALTER TABLE ecabin_ledger.operational_form_versions_dtl ENABLE TRIGGER operational_form_version_immutable;
      ALTER TABLE ecabin_ledger.operational_form_version_areas_dtl ENABLE TRIGGER operational_form_version_areas_immutable;
    END IF;
    PERFORM ecabin_ledger.cleanup_log_purge_table(v_run_id,'operational_forms_dtl',v_count,v_deleted);
  ELSE
    RAISE NOTICE 'Operational form definitions, versions, and area mappings were preserved by policy.';
  END IF;

  -- This is a broad UAT reset. Any unexpected FK dependency aborts the CALL;
  -- no CASCADE is used and PostgreSQL rolls back all prior changes in the CALL.
  UPDATE ecabin_ledger.cleanup_execution_runs
  SET outcome='SUCCEEDED',completed_at=clock_timestamp()
  WHERE execution_id=v_run_id;
  RAISE NOTICE 'Transaction purge % completed; dry_run=%; storage objects queued=%',
    v_run_id,p_dry_run,(SELECT count(*) FROM ecabin_ledger.cleanup_storage_manifest WHERE execution_id=v_run_id);
END
$procedure$;

REVOKE ALL ON PROCEDURE ecabin_ledger.purge_transaction_data(boolean,boolean) FROM PUBLIC;
GRANT EXECUTE ON PROCEDURE ecabin_ledger.purge_transaction_data(boolean,boolean) TO ecabin_cleanup_executor;
GRANT EXECUTE ON FUNCTION ecabin_ledger.cleanup_log_purge_table(uuid,text,bigint,bigint) TO ecabin_cleanup_executor;

-- Required examples (never executed while installing this file):
-- CALL ecabin_ledger.purge_transaction_data(p_confirm => FALSE, p_dry_run => TRUE);
-- CALL ecabin_ledger.purge_transaction_data(p_confirm => TRUE, p_dry_run => FALSE);

COMMIT;
