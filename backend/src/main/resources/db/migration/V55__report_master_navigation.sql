-- Report configuration belongs under Administration and is visible only to
-- users with the organization-administration permission. Report data grants
-- remain independently configured in report_role_access_mst.
INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT config.operator_id,'report-master','Report Master',config.id,'ADMINISTER_ORGANIZATION','▤','report-master',160
FROM navigation_menu_items_mst config
WHERE config.menu_key='configuration' AND config.parent_id IS NULL
ON CONFLICT DO NOTHING;

CREATE FUNCTION seed_report_master_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'report-master','Report Master',NEW.id,'ADMINISTER_ORGANIZATION','▤','report-master',160)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER navigation_seed_report_master AFTER INSERT ON navigation_menu_items_mst
  FOR EACH ROW EXECUTE FUNCTION seed_report_master_navigation();
