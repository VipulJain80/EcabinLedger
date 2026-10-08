ALTER TABLE aircraft ADD COLUMN msn_number varchar(16);
CREATE INDEX aircraft_msn_idx ON aircraft(operator_id,msn_number) WHERE msn_number IS NOT NULL;

UPDATE navigation_menu_items
SET label='Defect Summary', updated_at=now()
WHERE menu_key='home' AND route_key='home';
