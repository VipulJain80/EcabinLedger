-- Email settings audit actions are longer than the original defect event type limit.
-- Widen the shared event column without changing existing event values or constraints.
ALTER TABLE defect_events_dtl
  ALTER COLUMN event_type TYPE varchar(64);
