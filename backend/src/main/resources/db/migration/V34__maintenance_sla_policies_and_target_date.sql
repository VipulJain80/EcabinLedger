-- due_at is the existing corrective-action deadline. Keep using this field rather
-- than storing a second, potentially divergent SLA date.
ALTER TABLE corrective_actions_dtl DROP CONSTRAINT corrective_actions_status_check;
ALTER TABLE corrective_actions_dtl ADD CONSTRAINT corrective_actions_status_check
  CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','ON_HOLD','COMPLETED','CANCELLED'));

CREATE TABLE maintenance_sla_policies_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  status_key varchar(32) NOT NULL CHECK (status_key IN ('WORK_IN_PROGRESS','ON_HOLD','CLOSE_SUBMITTED')),
  is_configured boolean NOT NULL DEFAULT false,
  date_required boolean NOT NULL DEFAULT true,
  allow_future boolean NOT NULL DEFAULT true,
  max_future_days integer,
  allow_past boolean NOT NULL DEFAULT false,
  max_past_days integer,
  business_timezone varchar(80) NOT NULL DEFAULT 'UTC',
  updated_by varchar(200) NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id,status_key),
  UNIQUE(operator_id,id),
  CHECK ((allow_future AND max_future_days BETWEEN 0 AND 3650) OR (NOT allow_future AND max_future_days IS NULL)),
  CHECK ((allow_past AND max_past_days BETWEEN 0 AND 3650) OR (NOT allow_past AND max_past_days IS NULL)),
  CHECK (is_configured OR status_key = 'CLOSE_SUBMITTED')
);
CREATE INDEX maintenance_sla_policy_lookup_idx ON maintenance_sla_policies_mst(operator_id,status_key);

INSERT INTO maintenance_sla_policies_mst(operator_id,status_key,is_configured,date_required,allow_future,max_future_days,allow_past,max_past_days,business_timezone,updated_by)
SELECT o.id,p.status_key,p.is_configured,true,p.allow_future,p.days,false,NULL,'UTC','system'
FROM operators_mst o
CROSS JOIN (VALUES
  ('WORK_IN_PROGRESS',true,true,15),
  ('ON_HOLD',true,true,180),
  ('CLOSE_SUBMITTED',false,false,NULL)
) AS p(status_key,is_configured,allow_future,days);

CREATE FUNCTION seed_maintenance_sla_policies_for_operator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO maintenance_sla_policies_mst(operator_id,status_key,is_configured,date_required,allow_future,max_future_days,allow_past,max_past_days,business_timezone,updated_by)
  VALUES
    (NEW.id,'WORK_IN_PROGRESS',true,true,true,15,false,NULL,'UTC','system'),
    (NEW.id,'ON_HOLD',true,true,true,180,false,NULL,'UTC','system'),
    (NEW.id,'CLOSE_SUBMITTED',false,true,false,NULL,false,NULL,'UTC','system');
  RETURN NEW;
END $$;
CREATE TRIGGER operators_seed_maintenance_sla_policies AFTER INSERT ON operators_mst
FOR EACH ROW EXECUTE FUNCTION seed_maintenance_sla_policies_for_operator();

INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT n.operator_id,'maintenance-sla-policies','Maintenance SLA Policies',n.id,'ADMINISTER_ORGANIZATION','◷','maintenance-sla-policies',110
FROM navigation_menu_items_mst n WHERE n.menu_key='configuration' AND n.parent_id IS NULL
ON CONFLICT DO NOTHING;

CREATE FUNCTION seed_maintenance_sla_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'maintenance-sla-policies','Maintenance SLA Policies',NEW.id,'ADMINISTER_ORGANIZATION','◷','maintenance-sla-policies',110)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER navigation_seed_maintenance_sla AFTER INSERT ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION seed_maintenance_sla_navigation();
