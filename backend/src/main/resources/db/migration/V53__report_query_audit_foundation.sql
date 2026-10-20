-- Append-only audit for authorized report query and lookup attempts. Query/filter values are never stored.
CREATE TABLE report_query_events_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL,
  report_id uuid NOT NULL,
  actor_id varchar(200) NOT NULL,
  request_id uuid,
  event_type varchar(16) NOT NULL CHECK (event_type IN ('QUERY','LOOKUP')),
  outcome varchar(32) NOT NULL CHECK (outcome IN
    ('SUCCESS','ACCESS_DENIED','INVALID_REQUEST','CONFIGURATION_BLOCKED','TIMEOUT','DATABASE_ERROR')),
  duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
  returned_row_count integer NOT NULL DEFAULT 0 CHECK (returned_row_count BETWEEN 0 AND 100),
  occurred_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT report_query_events_report_fk FOREIGN KEY(operator_id, report_id)
    REFERENCES reports_mst(operator_id, id) ON DELETE RESTRICT
);

CREATE INDEX report_query_events_tenant_time_idx
  ON report_query_events_mst(operator_id, occurred_at DESC, id DESC);
CREATE INDEX report_query_events_report_time_idx
  ON report_query_events_mst(operator_id, report_id, occurred_at DESC, id DESC);

CREATE FUNCTION reject_report_query_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'report query events are immutable';
END;
$$;
CREATE TRIGGER report_query_events_append_only BEFORE UPDATE OR DELETE ON report_query_events_mst
  FOR EACH ROW EXECUTE FUNCTION reject_report_query_event_mutation();
