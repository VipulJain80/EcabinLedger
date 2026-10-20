ALTER TABLE maintenance_sla_policies_mst
  ALTER COLUMN business_timezone SET DEFAULT 'Asia/Kolkata';

UPDATE maintenance_sla_policies_mst
SET business_timezone = 'Asia/Kolkata'
WHERE business_timezone <> 'Asia/Kolkata';

CREATE OR REPLACE FUNCTION seed_maintenance_sla_policies_for_operator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO maintenance_sla_policies_mst(operator_id,status_key,is_configured,date_required,allow_future,max_future_days,allow_past,max_past_days,business_timezone,updated_by)
  VALUES
    (NEW.id,'WORK_IN_PROGRESS',true,true,true,15,false,NULL,'Asia/Kolkata','system'),
    (NEW.id,'ON_HOLD',true,true,true,180,false,NULL,'Asia/Kolkata','system'),
    (NEW.id,'CLOSE_SUBMITTED',false,true,false,NULL,false,NULL,'Asia/Kolkata','system');
  RETURN NEW;
END $$;
