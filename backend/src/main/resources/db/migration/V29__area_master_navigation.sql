-- Replace the retired cabin-zones submenu with the active cabin-area master.
-- Navigation remains tenant-owned and visible only to organization administrators.
-- If an Area Master entry was already provisioned but inactive, restore it under the canonical Administration parent.
UPDATE navigation_menu_items_mst area_menu
SET label='Area Master',parent_id=config.id,required_permissions='ADMINISTER_ORGANIZATION',icon='',route_key='areas',display_order=40,isactive=1,updated_at=now()
FROM navigation_menu_items_mst config
WHERE config.operator_id=area_menu.operator_id AND config.menu_key='configuration' AND config.parent_id IS NULL
  AND area_menu.menu_key='areas' AND area_menu.parent_id=config.id;

INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'areas','Area Master',config.id,'ADMINISTER_ORGANIZATION','','areas',40
FROM operators_mst o
JOIN navigation_menu_items_mst config ON config.operator_id=o.id AND config.menu_key='configuration' AND config.parent_id IS NULL
ON CONFLICT DO NOTHING;

-- Keep the same navigation entry for organizations provisioned after this migration.
CREATE FUNCTION seed_area_master_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'areas','Area Master',NEW.id,'ADMINISTER_ORGANIZATION','','areas',40)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER navigation_seed_area_master AFTER INSERT ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION seed_area_master_navigation();
