-- Email delivery is separate from notifications_dtl. Both tenant and event switches
-- default to disabled; no messages are eligible until explicitly enabled.

-- V1 made defect_events_dtl.id globally unique. A matching tenant composite key is
-- needed for a tenant-safe source-event FK from the outbox.
ALTER TABLE defect_events_dtl
  ADD CONSTRAINT defect_events_operator_id_id_uq UNIQUE (operator_id, id);

CREATE TABLE email_notification_settings_mst (
  operator_id uuid PRIMARY KEY REFERENCES operators_mst(id) ON DELETE RESTRICT,
  is_enabled boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200) NOT NULL DEFAULT 'system:email-settings',
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(200) NOT NULL DEFAULT 'system:email-settings'
);

CREATE TABLE email_notification_event_settings_mst (
  operator_id uuid NOT NULL REFERENCES email_notification_settings_mst(operator_id) ON DELETE RESTRICT,
  event_key varchar(48) NOT NULL CHECK (event_key IN (
    'DEFECT_REPORTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED', 'ACTION_ASSIGNED',
    'ACTION_STARTED', 'ACTION_COMPLETED', 'INSPECTION_COMPLETED',
    'WORK_VERIFIED', 'VERIFICATION_REJECTED', 'ACTION_CLOSE_SUBMITTED',
    'CLOSURE_APPROVED', 'CLOSURE_REJECTED', 'DEFECT_CLOSED', 'DEFECT_REOPENED'
  )),
  is_enabled boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200) NOT NULL DEFAULT 'system:email-settings',
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(200) NOT NULL DEFAULT 'system:email-settings',
  PRIMARY KEY (operator_id, event_key)
);

CREATE TABLE email_outbox_dtl (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id) ON DELETE RESTRICT,
  source_event_id uuid NOT NULL,
  recipient_user_id varchar(200) NOT NULL,
  recipient_email varchar(320) NOT NULL,
  notification_type varchar(48) NOT NULL CHECK (notification_type IN (
    'DEFECT_REPORTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED', 'ACTION_ASSIGNED',
    'ACTION_STARTED', 'ACTION_COMPLETED', 'INSPECTION_COMPLETED',
    'WORK_VERIFIED', 'VERIFICATION_REJECTED', 'ACTION_CLOSE_SUBMITTED',
    'CLOSURE_APPROVED', 'CLOSURE_REJECTED', 'DEFECT_CLOSED', 'DEFECT_REOPENED'
  )),
  template_key varchar(80) NOT NULL,
  template_version integer NOT NULL CHECK (template_version > 0),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'SENT', 'DEAD', 'CANCELLED')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  max_attempts integer NOT NULL DEFAULT 8 CHECK (max_attempts BETWEEN 1 AND 20),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_owner varchar(160),
  lease_until timestamptz,
  last_error_code varchar(80),
  sent_at timestamptz,
  request_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200) NOT NULL DEFAULT 'system:email-outbox',
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(200) NOT NULL DEFAULT 'system:email-outbox',
  CONSTRAINT email_outbox_operator_id_uq UNIQUE (operator_id, id),
  CONSTRAINT email_outbox_source_event_fk FOREIGN KEY (operator_id, source_event_id)
    REFERENCES defect_events_dtl(operator_id, id) ON DELETE RESTRICT,
  CONSTRAINT email_outbox_idempotency_uq
    UNIQUE (operator_id, source_event_id, recipient_user_id, notification_type),
  CONSTRAINT email_outbox_attempt_bounds_ck CHECK (attempt_count <= max_attempts),
  CONSTRAINT email_outbox_lease_state_ck CHECK (
    (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
  ),
  CONSTRAINT email_outbox_sent_state_ck CHECK (
    (status = 'SENT' AND sent_at IS NOT NULL)
    OR (status <> 'SENT' AND sent_at IS NULL)
  )
);

CREATE INDEX email_outbox_due_idx
  ON email_outbox_dtl (next_attempt_at, created_at, id)
  WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX email_outbox_expired_lease_idx
  ON email_outbox_dtl (lease_until, id)
  WHERE status = 'PROCESSING';
CREATE INDEX email_outbox_tenant_status_idx
  ON email_outbox_dtl (operator_id, status, created_at DESC);
CREATE INDEX email_outbox_event_idx
  ON email_outbox_dtl (operator_id, source_event_id);

CREATE TABLE email_outbox_attempts_dtl (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL,
  outbox_id uuid NOT NULL,
  attempt_number integer NOT NULL CHECK (attempt_number > 0),
  started_at timestamptz NOT NULL,
  completed_at timestamptz,
  outcome varchar(24) NOT NULL CHECK (outcome IN (
    'STARTED', 'SENT', 'RETRY_SCHEDULED', 'PERMANENT_FAILURE', 'LEASE_EXPIRED'
  )),
  error_code varchar(80),
  smtp_response_category varchar(32),
  request_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200) NOT NULL DEFAULT 'system:email-worker',
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(200) NOT NULL DEFAULT 'system:email-worker',
  CONSTRAINT email_outbox_attempt_job_fk FOREIGN KEY (operator_id, outbox_id)
    REFERENCES email_outbox_dtl(operator_id, id) ON DELETE RESTRICT,
  CONSTRAINT email_outbox_attempt_number_uq UNIQUE (operator_id, outbox_id, attempt_number),
  CONSTRAINT email_outbox_attempt_time_ck CHECK (completed_at IS NULL OR completed_at >= started_at),
  CONSTRAINT email_outbox_attempt_completion_ck CHECK (
    (outcome = 'STARTED' AND completed_at IS NULL)
    OR (outcome <> 'STARTED' AND completed_at IS NOT NULL)
  )
);
CREATE INDEX email_outbox_attempt_history_idx
  ON email_outbox_attempts_dtl (operator_id, outbox_id, attempt_number DESC);

-- Existing tenants receive disabled settings. Future tenants get the same catalog
-- through the provisioning trigger. No role grants or email jobs are seeded.
INSERT INTO email_notification_settings_mst (operator_id)
SELECT id FROM operators_mst;

INSERT INTO email_notification_event_settings_mst (operator_id, event_key)
SELECT s.operator_id, e.event_key
FROM email_notification_settings_mst s
CROSS JOIN (VALUES
  ('DEFECT_REPORTED'), ('REVIEW_APPROVED'), ('REVIEW_REJECTED'), ('ACTION_ASSIGNED'),
  ('ACTION_STARTED'), ('ACTION_COMPLETED'), ('INSPECTION_COMPLETED'),
  ('WORK_VERIFIED'), ('VERIFICATION_REJECTED'), ('ACTION_CLOSE_SUBMITTED'),
  ('CLOSURE_APPROVED'), ('CLOSURE_REJECTED'), ('DEFECT_CLOSED'), ('DEFECT_REOPENED')
) AS e(event_key);

CREATE FUNCTION seed_email_settings_for_operator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO email_notification_settings_mst (operator_id, created_by, updated_by)
  VALUES (NEW.id, 'system:tenant-provisioning', 'system:tenant-provisioning');
  INSERT INTO email_notification_event_settings_mst (operator_id, event_key, created_by, updated_by)
  SELECT NEW.id, e.event_key, 'system:tenant-provisioning', 'system:tenant-provisioning'
  FROM (VALUES
    ('DEFECT_REPORTED'), ('REVIEW_APPROVED'), ('REVIEW_REJECTED'), ('ACTION_ASSIGNED'),
    ('ACTION_STARTED'), ('ACTION_COMPLETED'), ('INSPECTION_COMPLETED'),
    ('WORK_VERIFIED'), ('VERIFICATION_REJECTED'), ('ACTION_CLOSE_SUBMITTED'),
    ('CLOSURE_APPROVED'), ('CLOSURE_REJECTED'), ('DEFECT_CLOSED'), ('DEFECT_REOPENED')
  ) AS e(event_key);
  RETURN NEW;
END;
$$;
CREATE TRIGGER operators_seed_email_settings
  AFTER INSERT ON operators_mst
  FOR EACH ROW EXECUTE FUNCTION seed_email_settings_for_operator();

CREATE TRIGGER audit_email_notification_settings
  BEFORE INSERT OR UPDATE ON email_notification_settings_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_email_notification_event_settings
  BEFORE INSERT OR UPDATE ON email_notification_event_settings_mst
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_email_outbox
  BEFORE INSERT OR UPDATE ON email_outbox_dtl
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_email_outbox_attempts
  BEFORE INSERT OR UPDATE ON email_outbox_attempts_dtl
  FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();

CREATE FUNCTION record_email_notification_setting_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor text := nullif(current_setting('ecabin.actor_id', true), '');
        correlation text := nullif(current_setting('ecabin.request_id', true), '');
        setting_kind text := TG_ARGV[0];
BEGIN
  IF OLD.is_enabled IS NOT DISTINCT FROM NEW.is_enabled THEN RETURN NEW; END IF;
  IF actor IS NULL THEN
    RAISE EXCEPTION 'Set transaction-local ecabin.actor_id before changing email notification settings';
  END IF;
  INSERT INTO defect_events_dtl(operator_id, event_type, note, actor_id, entity_type,
                                entity_id, previous_state, new_state, request_id)
  VALUES (NEW.operator_id,
          CASE setting_kind WHEN 'GLOBAL' THEN 'EMAIL_NOTIFICATION_SETTINGS_CHANGED'
                            ELSE 'EMAIL_NOTIFICATION_EVENT_SETTING_CHANGED' END,
          'Email notification delivery setting changed.', actor, 'ORGANIZATION', NEW.operator_id,
          jsonb_build_object('eventKey', CASE WHEN setting_kind='EVENT' THEN OLD.event_key END,
                             'isEnabled', OLD.is_enabled),
          jsonb_build_object('eventKey', CASE WHEN setting_kind='EVENT' THEN NEW.event_key END,
                             'isEnabled', NEW.is_enabled),
          CASE WHEN correlation ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
               THEN correlation::uuid ELSE NULL END);
  RETURN NEW;
END;
$$;
CREATE TRIGGER email_global_setting_audit
  AFTER UPDATE OF is_enabled ON email_notification_settings_mst
  FOR EACH ROW EXECUTE FUNCTION record_email_notification_setting_change('GLOBAL');
CREATE TRIGGER email_event_setting_audit
  AFTER UPDATE OF is_enabled ON email_notification_event_settings_mst
  FOR EACH ROW EXECUTE FUNCTION record_email_notification_setting_change('EVENT');
