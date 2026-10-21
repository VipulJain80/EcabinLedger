-- Put the email-delivery controls inside the existing Administration menu.
-- Setting changes remain tenant scoped and require ADMINISTER_ORGANIZATION.
INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT config.operator_id,'email-notifications','Email Notifications',config.id,
       'ADMINISTER_ORGANIZATION','✉','email-notifications',170
FROM navigation_menu_items_mst config
WHERE config.menu_key='configuration' AND config.parent_id IS NULL
ON CONFLICT DO NOTHING;

CREATE FUNCTION seed_email_notification_settings_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES(NEW.operator_id,'email-notifications','Email Notifications',NEW.id,
           'ADMINISTER_ORGANIZATION','✉','email-notifications',170)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER navigation_seed_email_notification_settings
AFTER INSERT ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION seed_email_notification_settings_navigation();
