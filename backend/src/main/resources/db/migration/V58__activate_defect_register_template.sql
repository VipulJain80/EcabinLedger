-- Activate only the reviewed Defect Register v2 routine and its global template.
-- Tenant reports and role grants remain an explicit organization-admin action.
DO $$
DECLARE
  v_routine_id uuid;
  v_template_id uuid;
  v_function_oid oid;
BEGIN
  SELECT id INTO STRICT v_routine_id
  FROM report_routines_mst
  WHERE routine_key = 'DEFECT_REGISTER_V2'
    AND contract_version = 2
    AND routine_kind = 'REPORT'
    AND scope_policy = 'DEFECT_VISIBLE_SCOPE_V1'
    AND schema_name = current_schema()
    AND function_name = 'defect_register_report_v2';

  v_function_oid := to_regprocedure(format(
    '%I.%I(uuid,text,text,text,jsonb,integer,integer,jsonb)',
    current_schema(), 'defect_register_report_v2'));
  IF v_function_oid IS NULL OR NOT EXISTS (
    SELECT 1 FROM pg_proc WHERE oid = v_function_oid AND proretset AND NOT prosecdef
  ) THEN
    RAISE EXCEPTION 'Reviewed Defect Register v2 function is missing or has an unsupported execution mode';
  END IF;

  SELECT id INTO STRICT v_template_id
  FROM report_templates_mst
  WHERE template_code = 'DEFECT_REGISTER'
    AND template_version = 1
    AND routine_id = v_routine_id;

  IF (SELECT count(*) FROM report_template_filters_mst
      WHERE template_id = v_template_id AND template_version = 1) <> 4
     OR (SELECT count(*) FROM report_template_columns_mst
      WHERE template_id = v_template_id AND template_version = 1) <> 9
     OR NOT EXISTS (
       SELECT 1 FROM report_lookup_sources_mst l
       JOIN report_routines_mst r ON r.id = l.routine_id
       WHERE l.lookup_key = 'DEFECT_WORKFLOW_STATUS'
         AND l.is_active
         AND r.is_active
         AND r.contract_version = 2
         AND r.routine_kind = 'LOOKUP'
         AND r.scope_policy = 'DEFECT_VISIBLE_SCOPE_V1'
     ) THEN
    RAISE EXCEPTION 'Defect Register template filters, columns, or workflow-status lookup do not match the reviewed contract';
  END IF;
END $$;

UPDATE report_routines_mst
SET is_active = true,
    updated_at = now(),
    updated_by = 'system:migration'
WHERE routine_key = 'DEFECT_REGISTER_V2'
  AND contract_version = 2
  AND routine_kind = 'REPORT'
  AND scope_policy = 'DEFECT_VISIBLE_SCOPE_V1'
  AND schema_name = current_schema()
  AND function_name = 'defect_register_report_v2';

UPDATE report_templates_mst
SET is_active = true,
    updated_at = now(),
    updated_by = 'system:migration'
WHERE template_code = 'DEFECT_REGISTER'
  AND template_version = 1
  AND routine_id = (SELECT id FROM report_routines_mst WHERE routine_key = 'DEFECT_REGISTER_V2');

DO $$
BEGIN
  IF (SELECT count(*) FROM report_routines_mst WHERE routine_key = 'DEFECT_REGISTER_V2' AND is_active) <> 1
     OR (SELECT count(*) FROM report_templates_mst WHERE template_code = 'DEFECT_REGISTER' AND template_version = 1 AND is_active) <> 1 THEN
    RAISE EXCEPTION 'Defect Register routine and template were not activated';
  END IF;
END $$;
