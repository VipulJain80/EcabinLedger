CREATE FUNCTION seed_operator_default_roles() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO operator_roles(operator_id,role_key,name,permissions,system_role)
  VALUES
    (NEW.id,'ADMIN','Administrator','VIEW|REPORT|REVIEW|INSPECT|ASSIGN_ACTION|PERFORM_ACTION|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT|ADMINISTER_ORGANIZATION',true),
    (NEW.id,'SUPERVISOR','Supervisor','VIEW|REPORT|REVIEW|INSPECT|ASSIGN_ACTION|PERFORM_ACTION|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT',true),
    (NEW.id,'INSPECTOR','Inspector','VIEW|REPORT|INSPECT',true),
    (NEW.id,'MAINTENANCE_TECHNICIAN','Maintenance Technician','VIEW|PERFORM_ACTION',true),
    (NEW.id,'QUALITY_COMPLIANCE','Quality Compliance','VIEW|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT',true),
    (NEW.id,'VIEWER','Viewer','VIEW',true)
  ON CONFLICT(operator_id,role_key) DO NOTHING;
  RETURN NEW;
END $$;
CREATE TRIGGER operators_seed_default_roles AFTER INSERT ON operators
FOR EACH ROW EXECUTE FUNCTION seed_operator_default_roles();
