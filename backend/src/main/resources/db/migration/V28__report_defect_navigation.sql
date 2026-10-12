-- Dedicated area-first entry point for users who already have REPORT permission.
INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'report-defect','Report Defect',operations.id,'REPORT','＋','report-defect',15
FROM operators_mst o
JOIN navigation_menu_items_mst operations ON operations.operator_id=o.id AND operations.menu_key='operations' AND operations.parent_id IS NULL
ON CONFLICT DO NOTHING;

CREATE FUNCTION seed_report_defect_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='operations' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'report-defect','Report Defect',NEW.id,'REPORT','＋','report-defect',15)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER navigation_seed_report_defect AFTER INSERT ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION seed_report_defect_navigation();
