ALTER TABLE operator_memberships_mst
  ADD COLUMN last_login_at timestamptz;

CREATE INDEX operator_membership_last_login_idx
  ON operator_memberships_mst(operator_id,last_login_at DESC,external_user_id);

CREATE TABLE user_login_events_dtl (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL,
  external_user_id varchar(200) NOT NULL,
  event_type varchar(8) NOT NULL CHECK (event_type IN ('LOGIN','LOGOUT')),
  occurred_at timestamptz NOT NULL DEFAULT now(),
  request_id varchar(80),
  authentication_source varchar(40),
  CONSTRAINT user_login_events_tenant_id_unique UNIQUE (operator_id,id),
  CONSTRAINT user_login_events_membership_fk FOREIGN KEY (operator_id,external_user_id)
    REFERENCES operator_memberships_mst(operator_id,external_user_id)
);

CREATE INDEX user_login_events_tenant_time_idx
  ON user_login_events_dtl(operator_id,occurred_at DESC,id DESC);
CREATE INDEX user_login_events_membership_time_idx
  ON user_login_events_dtl(operator_id,external_user_id,occurred_at DESC,id DESC);

CREATE FUNCTION reject_user_login_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'user login events are immutable';
END;
$$;
CREATE TRIGGER user_login_events_append_only
  BEFORE UPDATE OR DELETE ON user_login_events_dtl
  FOR EACH ROW EXECUTE FUNCTION reject_user_login_event_mutation();
CREATE TRIGGER user_login_events_no_truncate
  BEFORE TRUNCATE ON user_login_events_dtl
  FOR EACH STATEMENT EXECUTE FUNCTION reject_user_login_event_mutation();

INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'user-login-monitoring','User Login Monitoring',c.id,'ADMINISTER_ORGANIZATION','◷','user-login-monitoring',150
FROM operators_mst o
JOIN navigation_menu_items_mst c ON c.operator_id=o.id AND c.menu_key='configuration' AND c.parent_id IS NULL
ON CONFLICT DO NOTHING;

CREATE FUNCTION seed_user_login_monitoring_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'user-login-monitoring','User Login Monitoring',NEW.id,'ADMINISTER_ORGANIZATION','◷','user-login-monitoring',150)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER navigation_seed_user_login_monitoring AFTER INSERT ON navigation_menu_items_mst
  FOR EACH ROW EXECUTE FUNCTION seed_user_login_monitoring_navigation();
