-- Keep organization email-delivery setting changes in the append-only
-- operational audit stream. This forward migration also covers databases where
-- V62 was applied before these triggers were included in its source file.
CREATE OR REPLACE FUNCTION record_email_notification_setting_change() RETURNS trigger LANGUAGE plpgsql AS $$
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

DROP TRIGGER IF EXISTS email_global_setting_audit ON email_notification_settings_mst;
CREATE TRIGGER email_global_setting_audit
  AFTER UPDATE OF is_enabled ON email_notification_settings_mst
  FOR EACH ROW EXECUTE FUNCTION record_email_notification_setting_change('GLOBAL');

DROP TRIGGER IF EXISTS email_event_setting_audit ON email_notification_event_settings_mst;
CREATE TRIGGER email_event_setting_audit
  AFTER UPDATE OF is_enabled ON email_notification_event_settings_mst
  FOR EACH ROW EXECUTE FUNCTION record_email_notification_setting_change('EVENT');
