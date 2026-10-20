-- Malware scanning is not part of the application upload workflow. Files still
-- pass server-side type, content-signature, and size validation before storage.
-- Files quarantined only because a scanner was unavailable become usable after
-- deployment; files explicitly rejected by a prior scanner remain unavailable.
DO $$
DECLARE scan_constraint text;
BEGIN
  SELECT conname INTO scan_constraint
  FROM pg_constraint
  WHERE conrelid = 'attachments_dtl'::regclass
    AND contype = 'c'
    AND pg_get_constraintdef(oid) ILIKE '%scan_status%';

  IF scan_constraint IS NULL THEN
    RAISE EXCEPTION 'Could not find the attachments scan-status constraint.';
  END IF;

  EXECUTE format('ALTER TABLE attachments_dtl DROP CONSTRAINT %I', scan_constraint);
END $$;

UPDATE attachments_dtl
SET scan_status = 'NOT_SCANNED'
WHERE scan_status = 'QUARANTINED';

ALTER TABLE attachments_dtl
  ALTER COLUMN scan_status SET DEFAULT 'NOT_SCANNED',
  ADD CONSTRAINT attachments_scan_status_check
    CHECK (scan_status IN ('NOT_SCANNED', 'CLEAN', 'REJECTED'));
