-- Keep the database attachment bound aligned with the supported 20 MiB per-file policy.
-- Older checks were created before attachments was renamed to attachments_dtl, so discover
-- and replace only the size_bytes check without touching scan-status or other constraints.
DO $$
DECLARE
  v_constraint record;
  v_table regclass := to_regclass(format('%I.attachments_dtl', current_schema()));
BEGIN
  IF v_table IS NULL THEN
    RAISE EXCEPTION 'Expected tenant-scoped attachments_dtl table is missing from schema %', current_schema();
  END IF;

  FOR v_constraint IN
    SELECT conname
    FROM pg_constraint
    WHERE conrelid = v_table
      AND contype = 'c'
      AND pg_get_constraintdef(oid) ILIKE '%size_bytes%'
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', v_table, v_constraint.conname);
  END LOOP;
END $$;

ALTER TABLE attachments_dtl
  ADD CONSTRAINT attachments_dtl_size_bytes_check
  CHECK (size_bytes BETWEEN 1 AND 20971520);
