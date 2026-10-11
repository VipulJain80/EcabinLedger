-- Link each new notification to the immutable business event that caused it.
-- Existing inbox rows remain readable and are intentionally left unlinked.
ALTER TABLE notifications_dtl ADD COLUMN source_event_id uuid;
ALTER TABLE notifications_dtl ADD CONSTRAINT notifications_source_event_fk
  FOREIGN KEY(source_event_id) REFERENCES defect_events_dtl(id);
CREATE UNIQUE INDEX notifications_event_recipient_idx
  ON notifications_dtl(operator_id, recipient_user_id, source_event_id)
  WHERE source_event_id IS NOT NULL;
