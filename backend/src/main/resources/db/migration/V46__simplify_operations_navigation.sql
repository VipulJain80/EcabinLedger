-- Keep the two standard Operations entry points available while preserving
-- the tenant's existing permission rules for each action.
UPDATE navigation_menu_items_mst AS item
SET isactive = 2,
    updated_at = now()
FROM navigation_menu_items_mst AS operations
WHERE item.operator_id = operations.operator_id
  AND item.parent_id = operations.id
  AND operations.menu_key = 'operations'
  AND operations.parent_id IS NULL
  AND item.menu_key NOT IN ('defects', 'report-defect')
  AND item.isactive = 1;

-- Restore the two standard entries if they were previously deactivated.
UPDATE navigation_menu_items_mst AS item
SET isactive = 1,
    display_order = CASE item.menu_key WHEN 'defects' THEN 10 ELSE 15 END,
    updated_at = now()
FROM navigation_menu_items_mst AS operations
WHERE item.operator_id = operations.operator_id
  AND item.parent_id = operations.id
  AND operations.menu_key = 'operations'
  AND operations.parent_id IS NULL
  AND item.menu_key IN ('defects', 'report-defect');

-- Ensure existing organizations have both entry points, without changing
-- permissions or replacing tenant-maintained labels and other metadata.
INSERT INTO navigation_menu_items_mst(operator_id, menu_key, label, parent_id,
    required_permissions, icon, route_key, display_order)
SELECT operations.operator_id, defaults.menu_key, defaults.label, operations.id,
       defaults.required_permissions, defaults.icon, defaults.route_key, defaults.display_order
FROM navigation_menu_items_mst AS operations
CROSS JOIN (VALUES
    ('defects', 'Defect register', 'VIEW', '▦', 'defects', 10),
    ('report-defect', 'Report Defect', 'REPORT', '＋', 'report-defect', 15)
) AS defaults(menu_key, label, required_permissions, icon, route_key, display_order)
WHERE operations.menu_key = 'operations'
  AND operations.parent_id IS NULL
ON CONFLICT DO NOTHING;

-- New Operations roots should receive the register entry as well. V28 already
-- seeds Report Defect for newly inserted Operations roots.
CREATE FUNCTION seed_defect_register_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key = 'operations' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id, menu_key, label, parent_id,
        required_permissions, icon, route_key, display_order)
    VALUES(NEW.operator_id, 'defects', 'Defect register', NEW.id,
        'VIEW', '▦', 'defects', 10)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER navigation_seed_defect_register
AFTER INSERT ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION seed_defect_register_navigation();
