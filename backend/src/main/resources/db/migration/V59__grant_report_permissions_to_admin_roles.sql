-- Organization administrators may access report data when the report itself
-- also has an explicit role-level grant. Do not create report-role grants here.
UPDATE operator_roles_mst r
SET permissions = concat_ws('|', r.permissions,
  CASE WHEN NOT ('REPORT_VIEW' = ANY(string_to_array(r.permissions, '|'))) THEN 'REPORT_VIEW' END,
  CASE WHEN NOT ('REPORT_EXECUTE' = ANY(string_to_array(r.permissions, '|'))) THEN 'REPORT_EXECUTE' END,
  CASE WHEN NOT ('REPORT_EXPORT' = ANY(string_to_array(r.permissions, '|'))) THEN 'REPORT_EXPORT' END),
  updated_at = now()
WHERE r.role_key = 'ADMIN'
  AND (
    NOT ('REPORT_VIEW' = ANY(string_to_array(r.permissions, '|')))
    OR NOT ('REPORT_EXECUTE' = ANY(string_to_array(r.permissions, '|')))
    OR NOT ('REPORT_EXPORT' = ANY(string_to_array(r.permissions, '|')))
  );

-- Keep future tenant-provisioned ADMIN roles consistent with existing ones.
CREATE OR REPLACE FUNCTION seed_operator_default_roles() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,system_role)
  VALUES
    (NEW.id,'ADMIN','Administrator','VIEW|REPORT|REVIEW|INSPECT|ASSIGN_ACTION|PERFORM_ACTION|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT|ADMINISTER_ORGANIZATION|REPORT_VIEW|REPORT_EXECUTE|REPORT_EXPORT',true),
    (NEW.id,'SUPERVISOR','Supervisor','VIEW|REPORT|REVIEW|INSPECT|ASSIGN_ACTION|PERFORM_ACTION|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT',true),
    (NEW.id,'INSPECTOR','Inspector','VIEW|REPORT|INSPECT',true),
    (NEW.id,'MAINTENANCE_TECHNICIAN','Maintenance Technician','VIEW|PERFORM_ACTION',true),
    (NEW.id,'QUALITY_COMPLIANCE','Quality Compliance','VIEW|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT',true),
    (NEW.id,'VIEWER','Viewer','VIEW',true)
  ON CONFLICT(operator_id,role_key) DO NOTHING;
  RETURN NEW;
END $$;
