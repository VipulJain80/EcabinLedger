-- Cabin areas are managed only through cabin_areas_mst. The former cabin-zones
-- table was retained temporarily by V22; remove either legacy name now that
-- defect locations and form mappings use the authoritative cabin-area master.
-- DROP TABLE uses RESTRICT (the default), so unexpected dependent objects fail
-- this migration instead of being removed implicitly.
DO $$
DECLARE
  legacy_name text;
  legacy_relation regclass;
BEGIN
  FOREACH legacy_name IN ARRAY ARRAY['cabin_zones_mst','legacy_cabin_zones_archive'] LOOP
    legacy_relation := to_regclass(format('%I.%I', current_schema(), legacy_name));
    IF legacy_relation IS NOT NULL THEN
      IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE contype='f' AND confrelid=legacy_relation AND conrelid<>legacy_relation
      ) THEN
        RAISE EXCEPTION 'Cannot remove %: another table still has a foreign-key dependency', legacy_name;
      END IF;
      EXECUTE format('DROP TABLE %I.%I', current_schema(), legacy_name);
    END IF;
  END LOOP;
END $$;
