-- Galley Area uses the four requested choices through the existing tenant severity master.
-- The master stays the source of truth; this migration is additive and never rewrites rows.
INSERT INTO defect_severities_mst(operator_id,severity_code,severity_name,description,sort_order)
SELECT o.id,v.code,v.name,'Galley Area reporting option. Configure organization-specific operational meaning before production use.',v.sort_order
FROM operators_mst o
CROSS JOIN (VALUES
  ('MAJOR','Major',20),
  ('MINOR','Minor',30),
  ('OBSERVATION','Observation',40)
) AS v(code,name,sort_order)
ON CONFLICT(operator_id,severity_code) DO NOTHING;

-- Keep future tenant defaults additive and preserve any existing administrator-managed rows.
CREATE OR REPLACE FUNCTION seed_operator_default_severities() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO defect_severities_mst(operator_id,severity_code,severity_name,description,sort_order)
  VALUES
    (NEW.id,'LOW','Low','Starter display label only. Not an airworthiness classification or maintenance threshold.',10),
    (NEW.id,'MEDIUM','Medium','Starter display label only. Not an airworthiness classification or maintenance threshold.',20),
    (NEW.id,'HIGH','High','Starter display label only. Not an airworthiness classification or maintenance threshold.',30),
    (NEW.id,'CRITICAL','Critical','Starter display label only. Not an airworthiness classification or maintenance threshold.',40),
    (NEW.id,'MAJOR','Major','Galley Area reporting option. Configure organization-specific operational meaning before production use.',50),
    (NEW.id,'MINOR','Minor','Galley Area reporting option. Configure organization-specific operational meaning before production use.',60),
    (NEW.id,'OBSERVATION','Observation','Galley Area reporting option. Configure organization-specific operational meaning before production use.',70)
  ON CONFLICT(operator_id,severity_code) DO NOTHING;
  RETURN NEW;
END $$;
