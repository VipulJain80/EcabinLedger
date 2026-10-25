-- Rename the standard Operations navigation entry while preserving tenant
-- custom labels and all existing permissions, routes, and ordering.
UPDATE navigation_menu_items_mst
SET label = 'Defect LOG Book',
    updated_at = now()
WHERE menu_key = 'defects'
  AND label = 'Defect register';

-- New organizations receive the same label when their Operations root is
-- created. Keep the existing tenant scope and permission requirements intact.
CREATE OR REPLACE FUNCTION seed_defect_register_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key = 'operations' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id, menu_key, label, parent_id,
        required_permissions, icon, route_key, display_order)
    VALUES(NEW.operator_id, 'defects', 'Defect LOG Book', NEW.id,
        'VIEW', '▦', 'defects', 10)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
