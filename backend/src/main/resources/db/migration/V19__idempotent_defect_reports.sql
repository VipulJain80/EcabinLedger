-- A client may safely retry a report after a network timeout without creating a second defect.
ALTER TABLE defects_dtl ADD COLUMN report_idempotency_key uuid;
ALTER TABLE defects_dtl ADD COLUMN report_payload_hash varchar(64);
ALTER TABLE defects_dtl ADD CONSTRAINT defects_report_idempotency_pair_check
  CHECK ((report_idempotency_key IS NULL AND report_payload_hash IS NULL)
      OR (report_idempotency_key IS NOT NULL AND report_payload_hash IS NOT NULL));
CREATE UNIQUE INDEX defects_report_idempotency_idx
  ON defects_dtl(operator_id, report_idempotency_key)
  WHERE report_idempotency_key IS NOT NULL;
