CREATE TABLE operators (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE aircraft (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  tail_number varchar(16) NOT NULL,
  aircraft_type varchar(80) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, tail_number),
  UNIQUE(operator_id, id)
);
CREATE TABLE defects (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  aircraft_id uuid NOT NULL,
  reference varchar(24) NOT NULL,
  location varchar(24) NOT NULL CHECK (location IN ('CABIN','GALLEY','LAVATORY','ATTENDANT_SEAT')),
  zone varchar(80) NOT NULL,
  title varchar(160) NOT NULL,
  description varchar(4000) NOT NULL,
  severity varchar(16) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  status varchar(20) NOT NULL CHECK (status IN ('REPORTED','INSPECTING','IN_PROGRESS','AWAITING_PARTS','RESOLVED','CLOSED')),
  reported_by varchar(160) NOT NULL,
  assigned_to varchar(160),
  reported_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  FOREIGN KEY(operator_id, aircraft_id) REFERENCES aircraft(operator_id, id),
  UNIQUE(operator_id, reference),
  UNIQUE(operator_id, id)
);
CREATE INDEX defects_tenant_status_idx ON defects(operator_id, status, reported_at DESC, id DESC);
CREATE INDEX defects_aircraft_history_idx ON defects(operator_id, aircraft_id, reported_at DESC);
CREATE TABLE defect_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  defect_id uuid NOT NULL,
  event_type varchar(32) NOT NULL,
  from_status varchar(20),
  to_status varchar(20),
  note varchar(2000) NOT NULL,
  evidence_reference varchar(240),
  inspection_outcome varchar(16) CHECK (inspection_outcome IS NULL OR inspection_outcome IN ('PASS','FAIL')),
  actor_id varchar(160) NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  FOREIGN KEY(operator_id, defect_id) REFERENCES defects(operator_id, id)
);
CREATE INDEX defect_events_timeline_idx ON defect_events(operator_id, defect_id, occurred_at DESC, id DESC);

-- Audit entries are append-only. Runtime database role should receive SELECT/INSERT only for this table.
CREATE FUNCTION reject_defect_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'defect events are immutable';
END;
$$;
CREATE TRIGGER defect_events_append_only BEFORE UPDATE OR DELETE ON defect_events
FOR EACH ROW EXECUTE FUNCTION reject_defect_event_mutation();

-- Production tenants and aircraft are provisioned through an operator onboarding workflow.
