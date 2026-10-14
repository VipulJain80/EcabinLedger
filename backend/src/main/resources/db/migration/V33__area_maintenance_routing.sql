-- Explicit, tenant-scoped area routing and versioned workflow selection.
-- Existing organizations retain the existing Reviewer approval workflow.
CREATE TABLE area_maintenance_team_mappings_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  area_id uuid NOT NULL,
  maintenance_team_id uuid NOT NULL,
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_by varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(200) NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  change_reason varchar(1000) NOT NULL,
  UNIQUE(operator_id,id),
  FOREIGN KEY(operator_id,area_id) REFERENCES cabin_areas_mst(operator_id,id),
  FOREIGN KEY(operator_id,maintenance_team_id) REFERENCES maintenance_teams_mst(operator_id,id)
);
CREATE UNIQUE INDEX area_maintenance_team_active_area_uq
  ON area_maintenance_team_mappings_mst(operator_id,area_id) WHERE isactive=1;
CREATE INDEX area_maintenance_team_lookup_idx
  ON area_maintenance_team_mappings_mst(operator_id,isactive,area_id);

CREATE TABLE defect_workflow_versions_dtl (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  version_number integer NOT NULL CHECK (version_number > 0),
  assignment_mode varchar(32) NOT NULL CHECK (assignment_mode IN ('REVIEWER_APPROVAL','DIRECT_MAINTENANCE')),
  created_by varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  change_reason varchar(1000) NOT NULL,
  UNIQUE(operator_id,id),
  UNIQUE(operator_id,version_number)
);
CREATE TABLE defect_workflow_config_mst (
  operator_id uuid PRIMARY KEY REFERENCES operators_mst(id),
  active_version_id uuid NOT NULL,
  updated_by varchar(200) NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY(operator_id,active_version_id) REFERENCES defect_workflow_versions_dtl(operator_id,id)
);
ALTER TABLE defects_dtl ADD COLUMN workflow_version_id uuid;
ALTER TABLE defects_dtl ADD CONSTRAINT defects_workflow_version_tenant_fk
  FOREIGN KEY(operator_id,workflow_version_id) REFERENCES defect_workflow_versions_dtl(operator_id,id);
CREATE INDEX defects_workflow_version_idx ON defects_dtl(operator_id,workflow_version_id,status) WHERE isactive=1;
CREATE FUNCTION defect_workflow_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Defect workflow versions are immutable; create a new version.';
END $$;
CREATE TRIGGER defect_workflow_version_immutable BEFORE UPDATE OR DELETE ON defect_workflow_versions_dtl
FOR EACH ROW EXECUTE FUNCTION defect_workflow_version_immutable();

-- Seed the existing behavior for every organization. No tenant receives the new
-- direct-assignment behavior unless an administrator explicitly selects it.
INSERT INTO defect_workflow_versions_dtl(operator_id,version_number,assignment_mode,created_by,change_reason)
SELECT id,1,'REVIEWER_APPROVAL','system','Preserve existing Reviewer approval workflow.' FROM operators_mst;
INSERT INTO defect_workflow_config_mst(operator_id,active_version_id,updated_by)
SELECT v.operator_id,v.id,'system' FROM defect_workflow_versions_dtl v WHERE v.version_number=1;
UPDATE defects_dtl d SET workflow_version_id=c.active_version_id
FROM defect_workflow_config_mst c WHERE c.operator_id=d.operator_id;
CREATE FUNCTION snapshot_defect_workflow_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  SELECT active_version_id INTO NEW.workflow_version_id FROM defect_workflow_config_mst WHERE operator_id=NEW.operator_id FOR SHARE;
  IF NEW.workflow_version_id IS NULL THEN
    RAISE EXCEPTION 'No active defect workflow version is configured for organization %', NEW.operator_id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER defects_snapshot_workflow_version BEFORE INSERT ON defects_dtl
FOR EACH ROW EXECUTE FUNCTION snapshot_defect_workflow_version();

CREATE FUNCTION seed_defect_workflow_for_operator() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE workflow_id uuid;
BEGIN
  INSERT INTO defect_workflow_versions_dtl(operator_id,version_number,assignment_mode,created_by,change_reason)
  VALUES(NEW.id,1,'REVIEWER_APPROVAL','system','Preserve existing Reviewer approval workflow.') RETURNING id INTO workflow_id;
  INSERT INTO defect_workflow_config_mst(operator_id,active_version_id,updated_by)
  VALUES(NEW.id,workflow_id,'system');
  RETURN NEW;
END $$;
CREATE TRIGGER operators_seed_defect_workflow AFTER INSERT ON operators_mst
FOR EACH ROW EXECUTE FUNCTION seed_defect_workflow_for_operator();

-- Add the administration submenu for existing and newly provisioned organizations.
INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'area-team-mapping','Cabin Area–Team Mapping',config.id,'ADMINISTER_ORGANIZATION','⇢','area-team-mapping',85
FROM operators_mst o JOIN navigation_menu_items_mst config
  ON config.operator_id=o.id AND config.menu_key='configuration' AND config.parent_id IS NULL
ON CONFLICT DO NOTHING;
CREATE FUNCTION seed_area_team_mapping_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'area-team-mapping','Cabin Area–Team Mapping',NEW.id,'ADMINISTER_ORGANIZATION','⇢','area-team-mapping',85)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER navigation_seed_area_team_mapping AFTER INSERT ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION seed_area_team_mapping_navigation();
