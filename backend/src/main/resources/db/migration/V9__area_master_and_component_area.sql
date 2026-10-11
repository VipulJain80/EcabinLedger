-- cabin_zones remain aircraft locations (FWD CABIN, L1, etc.); they are not areas.
CREATE TABLE cabin_areas (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  code varchar(48) NOT NULL,
  name varchar(100) NOT NULL,
  description varchar(500),
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, id),
  UNIQUE(operator_id, code)
);
CREATE INDEX cabin_areas_active_idx ON cabin_areas(operator_id, isactive, name);

INSERT INTO cabin_areas(operator_id, code, name, description)
SELECT o.id, a.code, a.name, a.description
FROM operators o CROSS JOIN (VALUES
  ('ENTRY_AREA','Entry Area','Aircraft entry area'),
  ('GALLEY','Galley','Aircraft galley'),
  ('LAVATORY','Lavatory','Aircraft lavatory'),
  ('CABIN','Cabin','Passenger cabin'),
  ('ATTENDANT_SEAT','Attendant Seat','Cabin attendant seating area')
) AS a(code,name,description)
ON CONFLICT(operator_id, code) DO NOTHING;

ALTER TABLE cabin_components ADD COLUMN area_id uuid;
ALTER TABLE cabin_components ADD COLUMN description varchar(500);
-- Deterministic mappings for the original demo catalog. Do not assign unknown records arbitrarily.
UPDATE cabin_components c SET area_id = a.id
FROM cabin_areas a
WHERE a.operator_id=c.operator_id AND a.code = CASE c.code
  WHEN 'SEAT' THEN 'CABIN'
  WHEN 'IFE_DISPLAY' THEN 'CABIN'
  WHEN 'READING_LIGHT' THEN 'CABIN'
  WHEN 'OVERHEAD_BIN_LATCH' THEN 'CABIN'
  WHEN 'PSU' THEN 'CABIN'
  WHEN 'GALLEY_CART_LATCH' THEN 'GALLEY'
  WHEN 'LAVATORY_FAUCET' THEN 'LAVATORY'
  WHEN 'ATTENDANT_SEAT_BELT' THEN 'ATTENDANT_SEAT'
END;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM cabin_components WHERE area_id IS NULL) THEN
    RAISE EXCEPTION 'Existing cabin_components need an explicit area mapping before V9 can be applied.';
  END IF;
END $$;
ALTER TABLE cabin_components ALTER COLUMN area_id SET NOT NULL;
ALTER TABLE cabin_components ADD CONSTRAINT cabin_components_area_fk
  FOREIGN KEY(operator_id, area_id) REFERENCES cabin_areas(operator_id, id);
CREATE INDEX cabin_components_area_active_idx ON cabin_components(operator_id, area_id, isactive, name);

INSERT INTO cabin_components(operator_id,code,name,area_id,description)
SELECT o.id, c.code, c.name, a.id, c.description
FROM operators o
CROSS JOIN (VALUES
  ('VINYL','Vinyl','ENTRY_AREA','Entry area vinyl flooring'),
  ('OVEN','Oven','GALLEY','Galley oven'),
  ('TAP','Tap','LAVATORY','Lavatory tap'),
  ('TRAY_TABLE','Tray Table','CABIN','Passenger tray table')
) AS c(code,name,area_code,description)
JOIN cabin_areas a ON a.operator_id=o.id AND a.code=c.area_code
ON CONFLICT(operator_id,code) DO NOTHING;

CREATE FUNCTION seed_operator_cabin_areas() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO cabin_areas(operator_id,code,name,description) VALUES
    (NEW.id,'ENTRY_AREA','Entry Area','Aircraft entry area'),
    (NEW.id,'GALLEY','Galley','Aircraft galley'),
    (NEW.id,'LAVATORY','Lavatory','Aircraft lavatory'),
    (NEW.id,'CABIN','Cabin','Passenger cabin'),
    (NEW.id,'ATTENDANT_SEAT','Attendant Seat','Cabin attendant seating area')
  ON CONFLICT(operator_id,code) DO NOTHING;
  INSERT INTO cabin_components(operator_id,code,name,area_id,description)
  SELECT NEW.id,c.code,c.name,a.id,c.description
  FROM (VALUES
    ('VINYL','Vinyl','ENTRY_AREA','Entry area vinyl flooring'),
    ('OVEN','Oven','GALLEY','Galley oven'),
    ('TAP','Tap','LAVATORY','Lavatory tap'),
    ('TRAY_TABLE','Tray Table','CABIN','Passenger tray table')
  ) AS c(code,name,area_code,description)
  JOIN cabin_areas a ON a.operator_id=NEW.id AND a.code=c.area_code
  ON CONFLICT(operator_id,code) DO NOTHING;
  RETURN NEW;
END $$;
CREATE TRIGGER operators_seed_cabin_areas AFTER INSERT ON operators
FOR EACH ROW EXECUTE FUNCTION seed_operator_cabin_areas();

ALTER TABLE defects ADD COLUMN area_id uuid;
ALTER TABLE defects ADD COLUMN component_id uuid;
UPDATE defects d SET area_id=a.id FROM cabin_areas a
WHERE a.operator_id=d.operator_id AND a.code=d.location AND d.area_id IS NULL;
ALTER TABLE defects ADD CONSTRAINT defects_area_fk FOREIGN KEY(operator_id, area_id) REFERENCES cabin_areas(operator_id, id);
ALTER TABLE defects ADD CONSTRAINT defects_component_fk FOREIGN KEY(operator_id, component_id) REFERENCES cabin_components(operator_id, id);
