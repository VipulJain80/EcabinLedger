-- The old location master is no longer an application resource. Preserve its records
-- under an archival name; no application query or write targets this table.
-- Defect location text is a snapshot and has no foreign key to this master.
DO $$ BEGIN
  IF to_regclass(format('%I.%I', current_schema(), 'cabin_zones_mst')) IS NULL THEN
    RAISE EXCEPTION 'Cannot archive legacy cabin locations: source table is missing';
  END IF;
  IF to_regclass(format('%I.%I', current_schema(), 'legacy_cabin_zones_archive')) IS NOT NULL THEN
    RAISE EXCEPTION 'Cannot archive legacy cabin locations: destination already exists';
  END IF;
END $$;

UPDATE navigation_menu_items_mst
SET isactive=2,updated_at=now()
WHERE menu_key='cabin-zones' OR route_key='cabin-zones';

ALTER TABLE cabin_zones_mst RENAME TO legacy_cabin_zones_archive;
