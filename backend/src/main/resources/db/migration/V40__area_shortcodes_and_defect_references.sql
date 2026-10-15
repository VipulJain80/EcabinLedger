-- Assign stable, tenant-owned human-readable area shortcodes and prepare the
-- existing defect reference column for date/shortcode/sequence identifiers.
ALTER TABLE cabin_areas_mst ADD COLUMN shortcode varchar(10);

-- These mappings are explicit for every area code provisioned by V9/V18.
-- Unknown area codes stop the migration so an administrator can review them.
UPDATE cabin_areas_mst
SET shortcode = CASE code
  WHEN 'CABIN' THEN 'CBN'
  WHEN 'GALLEY' THEN 'GLY'
  WHEN 'LAVATORY' THEN 'LAV'
  WHEN 'ENTRY_AREA' THEN 'ENT'
  WHEN 'ATTENDANT_SEAT' THEN 'ATS'
  ELSE NULL
END;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM cabin_areas_mst WHERE shortcode IS NULL) THEN
    RAISE EXCEPTION 'V40 requires an explicit shortcode mapping for every existing cabin_areas_mst.code; resolve unmapped area codes and retry.';
  END IF;
  IF EXISTS (SELECT 1 FROM cabin_areas_mst WHERE shortcode !~ '^[A-Z0-9]{2,10}$') THEN
    RAISE EXCEPTION 'V40 found an invalid backfilled area shortcode; shortcodes must be uppercase A-Z/0-9 and 2-10 characters.';
  END IF;
  IF EXISTS (SELECT 1 FROM cabin_areas_mst GROUP BY operator_id, shortcode HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'V40 found duplicate area shortcodes within an organization; assign unique reviewed shortcodes and retry.';
  END IF;
END $$;

ALTER TABLE cabin_areas_mst ALTER COLUMN shortcode SET NOT NULL;
ALTER TABLE cabin_areas_mst ADD CONSTRAINT cabin_areas_shortcode_format_check
  CHECK (shortcode ~ '^[A-Z0-9]{2,10}$');
ALTER TABLE cabin_areas_mst ADD CONSTRAINT cabin_areas_operator_shortcode_key
  UNIQUE (operator_id, shortcode);

-- Existing references (including legacy EL-* values) are preserved unchanged.
ALTER TABLE defects_dtl ALTER COLUMN reference TYPE varchar(48);

CREATE TABLE defect_reference_counters_dtl (
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  business_date date NOT NULL,
  area_shortcode varchar(10) NOT NULL CHECK (area_shortcode ~ '^[A-Z0-9]{2,10}$'),
  last_sequence bigint NOT NULL CHECK (last_sequence > 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (operator_id, business_date, area_shortcode)
);
CREATE INDEX defect_reference_counters_updated_idx
  ON defect_reference_counters_dtl(updated_at);

-- Keep tenant onboarding aligned with the same verified area-code mapping.
CREATE OR REPLACE FUNCTION seed_operator_cabin_areas() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO cabin_areas_mst(operator_id,code,name,description,shortcode) VALUES
    (NEW.id,'ENTRY_AREA','Entry Area','Aircraft entry area','ENT'),
    (NEW.id,'GALLEY','Galley','Aircraft galley','GLY'),
    (NEW.id,'LAVATORY','Lavatory','Aircraft lavatory','LAV'),
    (NEW.id,'CABIN','Cabin','Passenger cabin','CBN'),
    (NEW.id,'ATTENDANT_SEAT','Attendant Seat','Cabin attendant seating area','ATS')
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
