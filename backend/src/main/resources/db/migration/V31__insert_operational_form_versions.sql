-- Version payloads exported by the user. Generated IDs and tenant/form relations
-- are resolved in the target database; published versions are inserted, never edited.
DO $$
DECLARE
  target_operator uuid;
  form_count integer;
  expected_count integer;
BEGIN
  SELECT count(*) INTO form_count FROM operational_forms_dtl f
   JOIN operators_mst o ON o.id=f.operator_id
   WHERE o.name='Skyways Service (DEMO)' AND o.isactive=1 AND f.isactive=1
     AND f.form_key IN ('GALLEY_AREA','CABIN','DEMO_REPORT_LAVATORY','DEMO_REPORT_ENTRY_AREA');
  IF form_count <> 4 THEN
    RAISE EXCEPTION 'V31 requires the four active forms inserted by V30; found %', form_count;
  END IF;
  SELECT id INTO target_operator FROM operators_mst
   WHERE name='Skyways Service (DEMO)' AND isactive=1;
  SELECT count(*) INTO expected_count FROM operational_form_versions_dtl v
   WHERE v.operator_id=target_operator AND v.form_id IN
     (SELECT id FROM operational_forms_dtl WHERE operator_id=target_operator
       AND form_key IN ('GALLEY_AREA','CABIN','DEMO_REPORT_LAVATORY','DEMO_REPORT_ENTRY_AREA'));
  IF expected_count <> 0 THEN
    RAISE EXCEPTION 'V31 target forms already have % versions; refusing to duplicate or overwrite', expected_count;
  END IF;

  INSERT INTO operational_form_versions_dtl
    (id,operator_id,form_id,version_number,status,fields_json,created_by,created_at,
     published_by,published_at,updated_at,updated_by)
  SELECT gen_random_uuid(), target_operator, f.id, d.version_number, d.status,
         d.fields_json::jsonb, d.created_by, d.created_at, d.published_by,
         d.published_at, d.updated_at, d.updated_by
    FROM (VALUES
      ('GALLEY_AREA',1,'PUBLISHED',
       $galley1${"fields":[{"key":"galley_location","type":"TEXT","label":"Galley Number / Location","order":0,"binding":"ZONE","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":null,"maxLength":80,"minLength":null,"alphanumeric":null},"numericRules":null,"optionFilter":null,"optionSource":null},{"key":"component","type":"DROPDOWN","label":"Component","order":1,"binding":"COMPONENT_ID","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":null,"numericRules":null,"optionFilter":null,"optionSource":"COMPONENTS"},{"key":"condition","type":"DROPDOWN","label":"Defect Condition","order":2,"binding":"CONDITION_ID","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":null,"numericRules":null,"optionFilter":{"areaFieldKey":null,"componentFieldKey":"component"},"optionSource":"DEFECT_CONDITIONS"},{"key":"severity","type":"DROPDOWN","label":"Severity","order":3,"binding":"SEVERITY","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":null,"numericRules":null,"optionFilter":null,"optionSource":"GALLEY_SEVERITIES"},{"key":"remarks","type":"TEXTAREA","label":"Remarks","order":4,"binding":"REMARKS","visible":true,"helpText":null,"readOnly":false,"required":false,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":null,"maxLength":4000,"minLength":null,"alphanumeric":null},"numericRules":null,"optionFilter":null,"optionSource":null},{"key":"evidence","type":"IMAGE_UPLOAD","label":"Evidence Image Upload","order":5,"binding":"EVIDENCE_ATTACHMENTS","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":{"maxCount":3,"minCount":1,"mimeTypes":["image/jpeg","image/png","image/webp"],"extensions":["jpg","jpeg","png","webp"],"maxFileSizeBytes":5242880},"textRules":null,"numericRules":null,"optionFilter":null,"optionSource":null}]}$galley1$,
       'abc@gmail.com','2026-10-11 15:27:22.779649+05:30'::timestamptz,
       'abc@gmail.com','2026-10-11 15:27:22.865202+05:30'::timestamptz,
       '2026-10-11 15:27:22.865202+05:30'::timestamptz,'abc@gmail.com'),
      ('GALLEY_AREA',2,'PUBLISHED',
       $galley2${"fields":[{"key":"galley_location","type":"TEXT","label":"Galley Number / Location","order":0,"binding":"ZONE","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":null,"maxLength":80,"minLength":null,"alphanumeric":null},"numericRules":null,"optionFilter":null,"optionSource":null},{"key":"component","type":"DROPDOWN","label":"Component","order":1,"binding":"COMPONENT_ID","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":null,"numericRules":null,"optionFilter":null,"optionSource":"COMPONENTS"},{"key":"condition","type":"DROPDOWN","label":"Defect Condition","order":2,"binding":"CONDITION_ID","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":null,"numericRules":null,"optionFilter":{"areaFieldKey":null,"componentFieldKey":"component"},"optionSource":"DEFECT_CONDITIONS"},{"key":"severity","type":"DROPDOWN","label":"Severity","order":3,"binding":"SEVERITY","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":null,"numericRules":null,"optionFilter":null,"optionSource":"GALLEY_SEVERITIES"},{"key":"remarks","type":"TEXTAREA","label":"Remarks","order":4,"binding":"REMARKS","visible":true,"helpText":null,"readOnly":false,"required":false,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":null,"maxLength":4000,"minLength":null,"alphanumeric":null},"numericRules":null,"optionFilter":null,"optionSource":null},{"key":"evidence","type":"IMAGE_UPLOAD","label":"Evidence Image Upload","order":5,"binding":"EVIDENCE_ATTACHMENTS","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":{"maxCount":3,"minCount":1,"mimeTypes":["image/jpeg","image/png","image/webp"],"extensions":["jpg","jpeg","png","webp"],"maxFileSizeBytes":5242880},"textRules":null,"numericRules":null,"optionFilter":null,"optionSource":null},{"key":"aircraftId","type":"AIRCRAFT_SELECT","label":"Aircraft Registration","order":6,"binding":"AIRCRAFT_ID","visible":true,"helpText":null,"readOnly":false,"required":true,"dateRules":null,"fileRules":null,"textRules":null,"numericRules":null,"optionFilter":null,"optionSource":"AIRCRAFT"}]}$galley2$,
       'abc@gmail.com','2026-10-12 20:47:14.297102+05:30'::timestamptz,
       'abc@gmail.com','2026-10-12 22:38:46.385749+05:30'::timestamptz,
       '2026-10-12 22:38:46.385749+05:30'::timestamptz,'abc@gmail.com'),
      ('CABIN',1,'PUBLISHED',
       $cabin1${"fields":[{"key":"field_1","type":"TEXTAREA","label":"Remarks","order":0,"helpText":"Remarks","required":false,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":500,"minLength":2,"alphanumeric":true},"numericRules":null,"optionFilter":null,"optionSource":null}]}$cabin1$,
       'abc@gmail.com','2026-10-10 12:57:38.386882+05:30'::timestamptz,
       'abc@gmail.com','2026-10-11 13:02:17.935069+05:30'::timestamptz,
       '2026-10-11 13:02:17.935069+05:30'::timestamptz,'abc@gmail.com'),
      ('CABIN',2,'PUBLISHED',
       $cabin2${"fields":[{"key":"field_1","type":"TEXTAREA","label":"Remarks","order":0,"helpText":"Remarks","required":false,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":500,"minLength":2,"alphanumeric":true},"numericRules":null,"optionFilter":null,"optionSource":null}]}$cabin2$,
       'abc@gmail.com','2026-10-10 13:02:25.769735+05:30'::timestamptz,
       'abc@gmail.com','2026-10-11 13:29:50.043135+05:30'::timestamptz,
       '2026-10-11 13:29:50.043135+05:30'::timestamptz,'abc@gmail.com'),
      ('CABIN',3,'PUBLISHED',
       $cabin3${"fields":[{"key":"field_1","type":"TEXTAREA","label":"Remarks","order":0,"binding":null,"helpText":"Remarks","required":true,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":500,"minLength":2,"alphanumeric":true},"numericRules":null,"optionFilter":null,"optionSource":null}]}$cabin3$,
       'abc@gmail.com','2026-10-11 13:30:20.26008+05:30'::timestamptz,
       'abc@gmail.com','2026-10-11 14:16:24.180368+05:30'::timestamptz,
       '2026-10-11 14:16:24.180368+05:30'::timestamptz,'abc@gmail.com'),
      ('CABIN',4,'DRAFT',
       $cabin4${"fields":[{"key":"field_1","type":"TEXTAREA","label":"Remarks","order":0,"binding":null,"helpText":"Remarks","required":true,"dateRules":null,"fileRules":null,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":500,"minLength":2,"alphanumeric":true},"numericRules":null,"optionFilter":null,"optionSource":null}]}$cabin4$,
       'abc@gmail.com','2026-10-11 14:24:25.239777+05:30'::timestamptz,
       NULL,NULL,'2026-10-11 14:24:25.239777+05:30'::timestamptz,'abc@gmail.com'),
      ('DEMO_REPORT_LAVATORY',1,'PUBLISHED',
       $demo${"fields":[{"key":"observationContext","type":"TEXTAREA","label":"Observation context (DEMO)","order":0,"helpText":"Record what was observed. This demo field does not provide maintenance instructions.","required":true,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":2000,"minLength":5,"alphanumeric":false}},{"key":"reportingReference","type":"TEXT","label":"Supporting report reference (optional)","order":1,"helpText":"Optional local reference for supporting records.","required":false,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":120,"minLength":null,"alphanumeric":false}}]}$demo$,
       'local-demo-seed','2026-10-12 21:21:36.811549+05:30'::timestamptz,
       'local-demo-seed','2026-10-12 21:21:36.811549+05:30'::timestamptz,
       '2026-10-12 21:21:36.811549+05:30'::timestamptz,NULL),
      ('DEMO_REPORT_ENTRY_AREA',1,'PUBLISHED',
       $demo${"fields":[{"key":"observationContext","type":"TEXTAREA","label":"Observation context (DEMO)","order":0,"helpText":"Record what was observed. This demo field does not provide maintenance instructions.","required":true,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":2000,"minLength":5,"alphanumeric":false}},{"key":"reportingReference","type":"TEXT","label":"Supporting report reference (optional)","order":1,"helpText":"Optional local reference for supporting records.","required":false,"textRules":{"pattern":null,"alphaOnly":false,"maxLength":120,"minLength":null,"alphanumeric":false}}]}$demo$,
       'local-demo-seed','2026-10-10 18:34:35.397673+05:30'::timestamptz,
       'local-demo-seed','2026-10-10 18:34:35.397673+05:30'::timestamptz,
       '2026-10-10 18:34:35.397673+05:30'::timestamptz,NULL)
    ) AS d(form_key,version_number,status,fields_json,created_by,created_at,published_by,published_at,updated_at,updated_by)
    JOIN operational_forms_dtl f ON f.operator_id=target_operator AND f.form_key=d.form_key AND f.isactive=1;

  UPDATE operational_forms_dtl f
     SET published_version_id=v.id
    FROM operational_form_versions_dtl v
   WHERE f.operator_id=target_operator AND f.isactive=1
     AND f.form_key IN ('GALLEY_AREA','CABIN','DEMO_REPORT_LAVATORY','DEMO_REPORT_ENTRY_AREA')
     AND v.operator_id=f.operator_id AND v.form_id=f.id
     AND v.version_number=CASE f.form_key
       WHEN 'GALLEY_AREA' THEN 2 WHEN 'CABIN' THEN 3
       WHEN 'DEMO_REPORT_LAVATORY' THEN 1 WHEN 'DEMO_REPORT_ENTRY_AREA' THEN 1 END
     AND v.status='PUBLISHED';

  IF (SELECT count(*) FROM operational_forms_dtl f
       JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
       WHERE f.operator_id=target_operator AND f.isactive=1
         AND f.form_key IN ('GALLEY_AREA','CABIN','DEMO_REPORT_LAVATORY','DEMO_REPORT_ENTRY_AREA')) <> 8 THEN
    RAISE EXCEPTION 'V31 did not create the expected eight operational form versions';
  END IF;
  IF EXISTS (SELECT 1 FROM operational_forms_dtl f
      LEFT JOIN operational_form_versions_dtl v
        ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id AND v.status='PUBLISHED'
      WHERE f.operator_id=target_operator AND f.isactive=1
        AND f.form_key IN ('GALLEY_AREA','CABIN','DEMO_REPORT_LAVATORY','DEMO_REPORT_ENTRY_AREA')
        AND v.id IS NULL) THEN
    RAISE EXCEPTION 'V31 failed to assign a valid published version pointer to every active transferred form';
  END IF;
END $$;
