-- Existing operators are the organization/tenant boundary. Every child row carries operator_id.
CREATE TABLE fleets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  name varchar(120) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, name),
  UNIQUE(operator_id, id)
);
INSERT INTO fleets(operator_id, name) SELECT id, 'General Fleet' FROM operators ON CONFLICT DO NOTHING;
ALTER TABLE aircraft ADD COLUMN fleet_id uuid;
UPDATE aircraft a SET fleet_id=f.id FROM fleets f WHERE f.operator_id=a.operator_id AND f.name='General Fleet';
ALTER TABLE aircraft ALTER COLUMN fleet_id SET NOT NULL;
ALTER TABLE aircraft ADD CONSTRAINT aircraft_fleet_tenant_fk FOREIGN KEY(operator_id, fleet_id) REFERENCES fleets(operator_id, id);
CREATE INDEX aircraft_fleet_idx ON aircraft(operator_id, fleet_id, active, tail_number);

CREATE TABLE operator_memberships (
  operator_id uuid NOT NULL REFERENCES operators(id),
  external_user_id varchar(200) NOT NULL,
  display_name varchar(160),
  role varchar(32) NOT NULL CHECK (role IN ('ADMIN','SUPERVISOR','INSPECTOR','MAINTENANCE_TECHNICIAN','QUALITY_COMPLIANCE','VIEWER')),
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(operator_id, external_user_id)
);
CREATE INDEX memberships_external_user_idx ON operator_memberships(external_user_id, active);
CREATE TABLE operator_membership_teams (
  operator_id uuid NOT NULL,
  external_user_id varchar(200) NOT NULL,
  team_name varchar(120) NOT NULL,
  PRIMARY KEY(operator_id, external_user_id, team_name),
  FOREIGN KEY(operator_id, external_user_id) REFERENCES operator_memberships(operator_id, external_user_id) ON DELETE CASCADE
);
CREATE INDEX membership_teams_lookup_idx ON operator_membership_teams(operator_id, team_name, external_user_id);

ALTER TABLE defects DROP CONSTRAINT IF EXISTS defects_status_check;
ALTER TABLE defects ALTER COLUMN status TYPE varchar(32);
UPDATE defects SET status = CASE status
  WHEN 'REPORTED' THEN 'REPORTED'
  WHEN 'INSPECTING' THEN 'INSPECTION_IN_PROGRESS'
  WHEN 'IN_PROGRESS' THEN 'IN_PROGRESS'
  WHEN 'AWAITING_PARTS' THEN 'IN_PROGRESS'
  WHEN 'RESOLVED' THEN 'AWAITING_VERIFICATION'
  WHEN 'CLOSED' THEN 'CLOSED'
  ELSE status END;
ALTER TABLE defects ADD CONSTRAINT defects_status_check CHECK (status IN (
  'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS','INSPECTION_COMPLETE',
  'ACTION_ASSIGNED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED',
  'CLOSED','REJECTED','CANCELLED','REOPENED'));
ALTER TABLE defect_events DROP CONSTRAINT IF EXISTS defect_events_inspection_outcome_check;
ALTER TABLE defect_events ADD CONSTRAINT defect_events_inspection_outcome_check CHECK (inspection_outcome IS NULL OR inspection_outcome IN ('PASS','FAIL','CONDITIONAL','REQUIRES_FOLLOW_UP'));
ALTER TABLE defects ADD COLUMN category varchar(32) NOT NULL DEFAULT 'OTHER' CHECK (category IN (
  'CABIN','GALLEY','LAVATORY','ATTENDANT_SEAT','IFE','SEAT','LIGHTING','OVERHEAD_BIN','PSU','EMERGENCY_EQUIPMENT','OTHER'));
ALTER TABLE defects ADD COLUMN row_number smallint CHECK (row_number IS NULL OR row_number BETWEEN 1 AND 200);
ALTER TABLE defects ADD COLUMN seat_reference varchar(8);
ALTER TABLE defects ADD COLUMN component varchar(120);
ALTER TABLE defects ADD COLUMN assigned_team varchar(120);
ALTER TABLE defects ADD COLUMN assigned_user_id varchar(200);
ALTER TABLE defects ADD COLUMN due_at timestamptz;
ALTER TABLE defects ALTER COLUMN reported_by TYPE varchar(200);
ALTER TABLE defects ALTER COLUMN assigned_to TYPE varchar(200);
ALTER TABLE defect_events ALTER COLUMN actor_id TYPE varchar(200);
CREATE INDEX defects_due_idx ON defects(operator_id, due_at) WHERE due_at IS NOT NULL AND status NOT IN ('CLOSED','CANCELLED');
CREATE INDEX defects_assignee_idx ON defects(operator_id, assigned_user_id, status, reported_at DESC);
CREATE INDEX defects_category_idx ON defects(operator_id, category, severity, reported_at DESC);

CREATE TABLE inspections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  defect_id uuid NOT NULL,
  inspector_user_id varchar(200) NOT NULL,
  status varchar(20) NOT NULL CHECK (status IN ('ASSIGNED','IN_PROGRESS','COMPLETED','CANCELLED')),
  result varchar(24) CHECK (result IS NULL OR result IN ('PASS','FAIL','CONDITIONAL','REQUIRES_FOLLOW_UP')),
  assigned_at timestamptz NOT NULL DEFAULT now(),
  inspected_at timestamptz,
  findings varchar(4000),
  notes varchar(2000),
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(operator_id, defect_id) REFERENCES defects(operator_id, id),
  UNIQUE(operator_id, id)
);
CREATE INDEX inspections_assignee_idx ON inspections(operator_id, inspector_user_id, status, assigned_at DESC);
CREATE INDEX inspections_defect_history_idx ON inspections(operator_id, defect_id, assigned_at DESC);

CREATE TABLE corrective_actions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  defect_id uuid NOT NULL,
  created_by_user_id varchar(200) NOT NULL,
  assigned_user_id varchar(200),
  assigned_team varchar(120),
  description varchar(2000) NOT NULL,
  priority varchar(16) NOT NULL CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  due_at timestamptz,
  status varchar(20) NOT NULL CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','COMPLETED','CANCELLED')),
  completed_at timestamptz,
  completed_by_user_id varchar(200),
  completion_notes varchar(2000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(operator_id, defect_id) REFERENCES defects(operator_id, id),
  UNIQUE(operator_id, id)
);
CREATE INDEX corrective_actions_due_idx ON corrective_actions(operator_id, due_at) WHERE due_at IS NOT NULL AND status NOT IN ('COMPLETED','CANCELLED');
CREATE INDEX corrective_actions_defect_idx ON corrective_actions(operator_id, defect_id, created_at DESC);
CREATE INDEX corrective_actions_assignee_idx ON corrective_actions(operator_id, assigned_user_id, status, due_at);

CREATE TABLE verifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  defect_id uuid NOT NULL,
  verifier_user_id varchar(200) NOT NULL,
  result varchar(16) NOT NULL CHECK (result IN ('PASS','FAIL')),
  notes varchar(2000) NOT NULL,
  verified_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(operator_id, defect_id) REFERENCES defects(operator_id, id),
  UNIQUE(operator_id, id)
);
CREATE INDEX verifications_defect_idx ON verifications(operator_id, defect_id, verified_at DESC);

CREATE TABLE closure_approvals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  defect_id uuid NOT NULL,
  approver_user_id varchar(200) NOT NULL,
  decision varchar(16) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
  reason varchar(2000) NOT NULL,
  approved_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(operator_id, defect_id) REFERENCES defects(operator_id, id),
  UNIQUE(operator_id, id)
);

CREATE TABLE attachments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  defect_id uuid NOT NULL,
  object_key varchar(512) NOT NULL UNIQUE,
  original_filename varchar(255) NOT NULL,
  detected_mime_type varchar(120) NOT NULL,
  size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 15728640),
  sha256 char(64) NOT NULL,
  uploaded_by varchar(200) NOT NULL,
  uploaded_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(operator_id, defect_id) REFERENCES defects(operator_id, id),
  UNIQUE(operator_id, id)
);
CREATE INDEX attachments_defect_idx ON attachments(operator_id, defect_id, uploaded_at DESC);

CREATE TABLE notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  recipient_user_id varchar(200) NOT NULL,
  defect_id uuid,
  notification_type varchar(40) NOT NULL,
  message varchar(500) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz,
  FOREIGN KEY(operator_id, defect_id) REFERENCES defects(operator_id, id)
);
CREATE INDEX notifications_inbox_idx ON notifications(operator_id, recipient_user_id, created_at DESC) WHERE read_at IS NULL;

ALTER TABLE defect_events ALTER COLUMN defect_id DROP NOT NULL;
ALTER TABLE defect_events ADD COLUMN entity_type varchar(40) NOT NULL DEFAULT 'DEFECT';
ALTER TABLE defect_events ADD COLUMN entity_id uuid;
ALTER TABLE defect_events ADD COLUMN previous_state jsonb;
ALTER TABLE defect_events ADD COLUMN new_state jsonb;
ALTER TABLE defect_events ADD COLUMN request_id uuid;
ALTER TABLE defect_events DISABLE TRIGGER defect_events_append_only;
UPDATE defect_events SET entity_id=defect_id;
ALTER TABLE defect_events ENABLE TRIGGER defect_events_append_only;
ALTER TABLE defect_events ADD CONSTRAINT defect_event_entity_check CHECK (entity_id IS NOT NULL);
CREATE INDEX defect_events_entity_timeline_idx ON defect_events(operator_id, entity_type, entity_id, occurred_at DESC, id DESC);
