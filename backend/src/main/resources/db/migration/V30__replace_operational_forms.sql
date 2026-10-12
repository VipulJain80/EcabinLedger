-- Transfer the four active DEFECT_REPORT forms supplied from the local environment.
-- Existing tenant forms/versions are retained for historical FK integrity, but archived.
-- All target IDs are resolved/generated in the destination database.
DO $$
DECLARE
  target_operator uuid;
  operator_count integer;
  area_count integer;
BEGIN
  SELECT count(*) INTO operator_count
    FROM operators_mst
   WHERE name='Skyways Service (DEMO)' AND isactive=1;
  IF operator_count <> 1 THEN
    RAISE EXCEPTION 'V30 requires exactly one active Skyways Service (DEMO) organization; found %', operator_count;
  END IF;
  SELECT id INTO target_operator FROM operators_mst
   WHERE name='Skyways Service (DEMO)' AND isactive=1;

  SELECT count(*) INTO area_count FROM cabin_areas_mst a
   WHERE a.operator_id=target_operator AND a.isactive=1
     AND a.code IN ('GALLEY','CABIN','LAVATORY','ENTRY_AREA');
  IF area_count <> 4 THEN
    RAISE EXCEPTION 'V30 requires exactly one active GALLEY, CABIN, LAVATORY, and ENTRY_AREA per organization; found % rows', area_count;
  END IF;
  IF EXISTS (
    SELECT 1 FROM cabin_areas_mst a
     WHERE a.operator_id=target_operator AND a.isactive=1
       AND a.code IN ('GALLEY','CABIN','LAVATORY','ENTRY_AREA')
     GROUP BY a.code HAVING count(*) <> 1
  ) THEN
    RAISE EXCEPTION 'V30 found ambiguous active area codes in the target organization';
  END IF;

  -- Keep old forms and versions reachable by submissions, defects, attachments and audit.
  UPDATE operational_forms_dtl
     SET form_key='ARCHIVED_V30_'||replace(id::text,'-',''),
         published_version_id=NULL,
         isactive=2,
         updated_at=now(),
         updated_by='flyway:V30'
   WHERE operator_id=target_operator;

  INSERT INTO operational_forms_dtl
    (id,operator_id,area_id,form_key,name,published_version_id,isactive,
     created_at,updated_at,created_by,updated_by,purpose,workflow_stage)
  SELECT gen_random_uuid(), target_operator, a.id, x.form_key, x.name, NULL, 1,
         x.created_at, x.updated_at, x.created_by, x.updated_by,
         'DEFECT_REPORT','INITIAL_REPORT'
    FROM (VALUES
      ('GALLEY_AREA','Galley Area','GALLEY',
       '2026-10-11 15:27:22.779649+05:30'::timestamptz,
       '2026-10-12 22:38:46.385749+05:30'::timestamptz,'abc@gmail.com','abc@gmail.com'),
      ('CABIN','Passenger Cabin','CABIN',
       '2026-10-10 12:57:38.386882+05:30'::timestamptz,
       '2026-10-11 14:24:25.239777+05:30'::timestamptz,'abc@gmail.com','abc@gmail.com'),
      ('DEMO_REPORT_LAVATORY','Lavatory defect report (DEMO)','LAVATORY',
       '2026-10-12 21:21:36.80891+05:30'::timestamptz,
       '2026-10-12 21:21:36.815243+05:30'::timestamptz,NULL,NULL),
      ('DEMO_REPORT_ENTRY_AREA','Entry Area defect report (DEMO)','ENTRY_AREA',
       '2026-10-10 18:34:35.393701+05:30'::timestamptz,
       '2026-10-12 22:06:30.095639+05:30'::timestamptz,NULL,'admin.test@ecabin.local')
    ) AS x(form_key,name,area_code,created_at,updated_at,created_by,updated_by)
    JOIN cabin_areas_mst a ON a.operator_id=target_operator AND a.code=x.area_code AND a.isactive=1;
END $$;
