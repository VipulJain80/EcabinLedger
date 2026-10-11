-- Renames preserve table data, object identity, foreign keys, indexes, grants, and trigger bindings.
-- Apply during a coordinated application deployment because runtime SQL must use the new names.
DO $$
DECLARE
  table_name text;
  expected text[] := ARRAY[
    'operators','fleets','aircraft','operator_memberships','operator_membership_teams',
    'maintenance_teams','cabin_zones','cabin_areas','defect_categories','cabin_components',
    'defect_conditions','component_conditions','operator_roles','navigation_menu_items',
    'defects','defect_events','inspections','corrective_actions','attachments','notifications',
    'configuration_imports','operational_forms','operational_form_versions',
    'operational_form_submissions','verifications','closure_approvals'
  ];
BEGIN
  FOREACH table_name IN ARRAY expected LOOP
    IF to_regclass(format('%I.%I', current_schema(), table_name)) IS NULL THEN
      RAISE EXCEPTION 'Cannot rename application table %.%: expected source table is missing', current_schema(), table_name;
    END IF;
    IF to_regclass(format('%I.%I', current_schema(), table_name || '_mst')) IS NOT NULL
       OR to_regclass(format('%I.%I', current_schema(), table_name || '_dtl')) IS NOT NULL THEN
      RAISE EXCEPTION 'Cannot rename application table %.%: destination already exists; investigate before applying V18', current_schema(), table_name;
    END IF;
  END LOOP;
END $$;

ALTER TABLE operators RENAME TO operators_mst;
ALTER TABLE fleets RENAME TO fleets_mst;
ALTER TABLE aircraft RENAME TO aircraft_mst;
ALTER TABLE operator_memberships RENAME TO operator_memberships_mst;
ALTER TABLE operator_membership_teams RENAME TO operator_membership_teams_mst;
ALTER TABLE maintenance_teams RENAME TO maintenance_teams_mst;
ALTER TABLE cabin_zones RENAME TO cabin_zones_mst;
ALTER TABLE cabin_areas RENAME TO cabin_areas_mst;
ALTER TABLE defect_categories RENAME TO defect_categories_mst;
ALTER TABLE cabin_components RENAME TO cabin_components_mst;
ALTER TABLE defect_conditions RENAME TO defect_conditions_mst;
ALTER TABLE component_conditions RENAME TO component_conditions_mst;
ALTER TABLE operator_roles RENAME TO operator_roles_mst;
ALTER TABLE navigation_menu_items RENAME TO navigation_menu_items_mst;

ALTER TABLE defects RENAME TO defects_dtl;
ALTER TABLE defect_events RENAME TO defect_events_dtl;
ALTER TABLE inspections RENAME TO inspections_dtl;
ALTER TABLE corrective_actions RENAME TO corrective_actions_dtl;
ALTER TABLE attachments RENAME TO attachments_dtl;
ALTER TABLE notifications RENAME TO notifications_dtl;
ALTER TABLE configuration_imports RENAME TO configuration_imports_dtl;
ALTER TABLE operational_forms RENAME TO operational_forms_dtl;
ALTER TABLE operational_form_versions RENAME TO operational_form_versions_dtl;
ALTER TABLE operational_form_submissions RENAME TO operational_form_submissions_dtl;
ALTER TABLE verifications RENAME TO verifications_dtl;
ALTER TABLE closure_approvals RENAME TO closure_approvals_dtl;

-- These trigger functions resolve relation names at execution time, so update them
-- after renaming. Trigger attachments remain bound to the same table/function OIDs.
CREATE OR REPLACE FUNCTION seed_operator_default_roles() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO operator_roles_mst(operator_id,role_key,name,permissions,system_role)
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

CREATE OR REPLACE FUNCTION seed_operator_cabin_areas() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO cabin_areas_mst(operator_id,code,name,description) VALUES
    (NEW.id,'ENTRY_AREA','Entry Area','Aircraft entry area'),
    (NEW.id,'GALLEY','Galley','Aircraft galley'),
    (NEW.id,'LAVATORY','Lavatory','Aircraft lavatory'),
    (NEW.id,'CABIN','Cabin','Passenger cabin'),
    (NEW.id,'ATTENDANT_SEAT','Attendant Seat','Cabin attendant seating area')
  ON CONFLICT(operator_id,code) DO NOTHING;
  INSERT INTO cabin_components_mst(operator_id,code,name,area_id,description)
  SELECT NEW.id,c.code,c.name,a.id,c.description
  FROM (VALUES
    ('VINYL','Vinyl','ENTRY_AREA','Entry area vinyl flooring'),
    ('OVEN','Oven','GALLEY','Galley oven'),
    ('TAP','Tap','LAVATORY','Lavatory tap'),
    ('TRAY_TABLE','Tray Table','CABIN','Passenger tray table')
  ) AS c(code,name,area_code,description)
  JOIN cabin_areas_mst a ON a.operator_id=NEW.id AND a.code=c.area_code
  ON CONFLICT(operator_id,code) DO NOTHING;
  RETURN NEW;
END $$;
