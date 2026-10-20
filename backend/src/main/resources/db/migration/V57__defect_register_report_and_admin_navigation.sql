-- Keep Reports discoverable to organization administrators while keeping report
-- data authorization separate. The generic route filter treats these values as OR.
UPDATE navigation_menu_items_mst
SET required_permissions='REPORT_VIEW|ADMINISTER_ORGANIZATION', updated_at=now()
WHERE menu_key='reports' AND parent_id IS NULL
  AND required_permissions IS DISTINCT FROM 'REPORT_VIEW|ADMINISTER_ORGANIZATION';

CREATE OR REPLACE FUNCTION enforce_reports_navigation_permissions() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.route_key='reports' THEN
    NEW.required_permissions := 'REPORT_VIEW|ADMINISTER_ORGANIZATION';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER reports_navigation_permissions_guard
BEFORE INSERT OR UPDATE OF required_permissions,route_key ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION enforce_reports_navigation_permissions();

-- The routine mirrors DefectService.list and DefectController's scope flags.
-- It is intentionally registered inactive until the product owner reviews the
-- current broad VIEW and combined Reviewer/Maintenance semantics and PostgreSQL
-- parity tests run successfully in the deployment's disposable PostgreSQL setup.
CREATE FUNCTION defect_register_report_v2(
  p_tenant_id uuid,
  p_actor_subject text,
  p_tenant_role text,
  p_scope_policy text,
  p_filter_values jsonb,
  p_page_index integer,
  p_page_size integer,
  p_sort jsonb
) RETURNS TABLE(total_rows bigint, row_id text, row_data jsonb)
LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE
  role_permissions text[];
  v_from_date date;
  v_to_date date;
  v_from_at timestamptz;
  v_to_exclusive timestamptz;
  v_area text;
  v_status text;
  v_offset bigint;
  sort_index integer;
  sort_key text;
  sort_direction text;
  sort_keys text[] := ARRAY[]::text[];
BEGIN
  IF p_scope_policy IS DISTINCT FROM 'DEFECT_VISIBLE_SCOPE_V1'
     OR p_tenant_id IS NULL OR p_actor_subject IS NULL OR btrim(p_actor_subject)=''
     OR p_tenant_role IS NULL OR btrim(p_tenant_role)=''
     OR p_filter_values IS NULL OR jsonb_typeof(p_filter_values)<>'object'
     OR p_sort IS NULL OR jsonb_typeof(p_sort)<>'array'
     OR p_page_index IS NULL OR p_page_index<0
     OR p_page_size IS NULL OR p_page_size<1 OR p_page_size>100 THEN
    RAISE EXCEPTION 'Invalid report execution context or bounds' USING ERRCODE='22023';
  END IF;
  v_offset := p_page_index::bigint * p_page_size::bigint;
  IF v_offset>100000 THEN RAISE EXCEPTION 'Report offset exceeds the supported limit' USING ERRCODE='22023'; END IF;
  IF p_filter_values - ARRAY['from_date','to_date','area','status']::text[] <> '{}'::jsonb THEN
    RAISE EXCEPTION 'Unexpected report filter parameter' USING ERRCODE='22023';
  END IF;

  SELECT string_to_array(r.permissions,'|') INTO role_permissions
  FROM operators_mst o
  JOIN operator_memberships_mst m ON m.operator_id=o.id AND m.external_user_id=p_actor_subject
    AND m.role=p_tenant_role AND m.isactive=1
  JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1
  WHERE o.id=p_tenant_id AND o.isactive=1;
  IF role_permissions IS NULL OR NOT ('VIEW'=ANY(role_permissions) OR 'DEFECT_VIEW_ALL'=ANY(role_permissions)) THEN
    RAISE EXCEPTION 'Defect read permission is required for this report scope' USING ERRCODE='42501';
  END IF;

  IF p_filter_values ? 'from_date' AND jsonb_typeof(p_filter_values->'from_date')<>'null' THEN
    IF jsonb_typeof(p_filter_values->'from_date')<>'string' OR p_filter_values->>'from_date' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
      RAISE EXCEPTION 'Invalid report start date' USING ERRCODE='22023';
    END IF;
    BEGIN v_from_date := (p_filter_values->>'from_date')::date;
    EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'Invalid report start date' USING ERRCODE='22023'; END;
    v_from_at := v_from_date::timestamp AT TIME ZONE 'Asia/Kolkata';
  END IF;
  IF p_filter_values ? 'to_date' AND jsonb_typeof(p_filter_values->'to_date')<>'null' THEN
    IF jsonb_typeof(p_filter_values->'to_date')<>'string' OR p_filter_values->>'to_date' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
      RAISE EXCEPTION 'Invalid report end date' USING ERRCODE='22023';
    END IF;
    BEGIN v_to_date := (p_filter_values->>'to_date')::date;
    EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'Invalid report end date' USING ERRCODE='22023'; END;
    v_to_exclusive := (v_to_date+1)::timestamp AT TIME ZONE 'Asia/Kolkata';
  END IF;
  IF v_from_date IS NOT NULL AND v_to_date IS NOT NULL AND v_from_date>v_to_date THEN
    RAISE EXCEPTION 'Report start date exceeds end date' USING ERRCODE='22023';
  END IF;
  IF p_filter_values ? 'area' AND jsonb_typeof(p_filter_values->'area')<>'null' THEN
    IF jsonb_typeof(p_filter_values->'area')<>'string'
       OR p_filter_values->>'area' NOT IN ('CABIN','GALLEY','LAVATORY','ATTENDANT_SEAT') THEN
      RAISE EXCEPTION 'Invalid report area' USING ERRCODE='22023';
    END IF;
    v_area := p_filter_values->>'area';
  END IF;
  IF p_filter_values ? 'status' AND jsonb_typeof(p_filter_values->'status')<>'null' THEN
    IF jsonb_typeof(p_filter_values->'status')<>'string' OR NOT EXISTS(
      SELECT 1 FROM workflow_status_master s
      WHERE s.module_code='DEFECT' AND s.status_code=p_filter_values->>'status' AND s.is_active=true) THEN
      RAISE EXCEPTION 'Invalid report status' USING ERRCODE='22023';
    END IF;
    v_status := p_filter_values->>'status';
  END IF;

  IF jsonb_array_length(p_sort)>3 THEN RAISE EXCEPTION 'Too many sort keys' USING ERRCODE='22023'; END IF;
  FOR sort_index IN 0..jsonb_array_length(p_sort)-1 LOOP
    IF jsonb_typeof(p_sort->sort_index)<>'object' OR jsonb_object_length(p_sort->sort_index)<>2
       OR NOT (p_sort->sort_index ? 'key') OR NOT (p_sort->sort_index ? 'direction') THEN
      RAISE EXCEPTION 'Invalid report sort entry' USING ERRCODE='22023';
    END IF;
    sort_key := p_sort->sort_index->>'key';
    sort_direction := p_sort->sort_index->>'direction';
    IF sort_key IS NULL OR sort_direction IS NULL
       OR sort_key NOT IN ('defectId','area','cabinZone','component','condition','severity','status','caseCreatedDate','caseClosedDate')
       OR sort_direction NOT IN ('ASC','DESC') OR sort_key=ANY(sort_keys) THEN
      RAISE EXCEPTION 'Unsupported report sort entry' USING ERRCODE='22023';
    END IF;
    sort_keys := array_append(sort_keys,sort_key);
  END LOOP;

  RETURN QUERY
  WITH base AS MATERIALIZED (
    SELECT d.id,d.reference,d.location,d.zone,d.component,d.condition_name_snapshot,
      COALESCE(sv.severity_name,d.severity) AS severity_label,
      d.status AS status_code,
      COALESCE(NULLIF(ws.alias_name,''),ws.display_name,d.status) AS status_label,
      d.reported_at,
      CASE WHEN d.status='CLOSED' THEN COALESCE((
        SELECT max(ev.occurred_at) FROM defect_events_dtl ev
        WHERE ev.operator_id=d.operator_id AND ev.defect_id=d.id
          AND (ev.to_status='CLOSED' OR ev.event_type='CLOSURE_APPROVED')
      ),d.updated_at) ELSE NULL END AS closed_at
    FROM defects_dtl d
    JOIN aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id
    LEFT JOIN defect_severities_mst sv ON sv.operator_id=d.operator_id AND sv.id=d.severity_id
    LEFT JOIN workflow_status_master ws ON ws.module_code='DEFECT' AND ws.status_code=d.status
    WHERE d.operator_id=p_tenant_id AND d.isactive=1 AND a.isactive=1
      AND (v_from_at IS NULL OR d.reported_at>=v_from_at)
      AND (v_to_exclusive IS NULL OR d.reported_at<v_to_exclusive)
      AND (v_area IS NULL OR d.location=v_area)
      AND (v_status IS NULL OR d.status=v_status)
      AND ('DEFECT_VIEW_ALL'=ANY(role_permissions) OR (
        (NOT (
          'REPORT'=ANY(role_permissions)
          AND NOT ('REVIEW'=ANY(role_permissions) OR 'INSPECT'=ANY(role_permissions)
            OR 'ASSIGN_ACTION'=ANY(role_permissions) OR 'PERFORM_ACTION'=ANY(role_permissions)
            OR 'VERIFY'=ANY(role_permissions) OR 'APPROVE'=ANY(role_permissions)
            OR 'CLOSE'=ANY(role_permissions) OR 'REOPEN'=ANY(role_permissions)
            OR 'ADMINISTER_ORGANIZATION'=ANY(role_permissions))
        ) OR d.reported_by=p_actor_subject)
        AND (NOT ('PERFORM_ACTION'=ANY(role_permissions) AND NOT ('REVIEW'=ANY(role_permissions))
          AND NOT ('ADMINISTER_ORGANIZATION'=ANY(role_permissions))) OR
          ((d.assigned_team IS NULL AND d.assigned_user_id=p_actor_subject) OR EXISTS(
            SELECT 1 FROM operator_membership_teams_mst omt
            JOIN operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1
            JOIN maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1
            WHERE omt.operator_id=p_tenant_id AND omt.external_user_id=p_actor_subject
              AND omt.team_name=d.assigned_team AND omt.isactive=1
          )))
        AND (NOT ('REVIEW'=ANY(role_permissions) AND NOT ('ADMINISTER_ORGANIZATION'=ANY(role_permissions))) OR
          (d.reviewer_team_id IS NOT NULL
            AND d.status IN ('REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED')
            AND EXISTS(
              SELECT 1 FROM maintenance_teams_mst rt
              JOIN operator_membership_teams_mst omt ON omt.operator_id=rt.operator_id AND omt.team_name=rt.name AND omt.isactive=1
              JOIN operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1
              JOIN operator_roles_mst rr ON rr.operator_id=om.operator_id AND rr.role_key=om.role AND rr.isactive=1
              WHERE rt.operator_id=p_tenant_id AND rt.id=d.reviewer_team_id AND rt.team_type='REVIEWER' AND rt.isactive=1
                AND omt.external_user_id=p_actor_subject AND ('|'||rr.permissions||'|') LIKE '%|REVIEW|%'
            )))
      ))
  ), totals AS (SELECT count(*)::bigint AS n FROM base), page_rows AS (
    SELECT b.* FROM base b
    ORDER BY
      CASE WHEN jsonb_array_length(p_sort)=0 THEN b.reported_at END DESC,
      CASE WHEN jsonb_array_length(p_sort)=0 THEN b.id END DESC,
      CASE WHEN p_sort->0->>'key'='defectId' AND p_sort->0->>'direction'='ASC' THEN b.reference END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='defectId' AND p_sort->0->>'direction'='DESC' THEN b.reference END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='area' AND p_sort->0->>'direction'='ASC' THEN b.location END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='area' AND p_sort->0->>'direction'='DESC' THEN b.location END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='cabinZone' AND p_sort->0->>'direction'='ASC' THEN b.zone END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='cabinZone' AND p_sort->0->>'direction'='DESC' THEN b.zone END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='component' AND p_sort->0->>'direction'='ASC' THEN b.component END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='component' AND p_sort->0->>'direction'='DESC' THEN b.component END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='condition' AND p_sort->0->>'direction'='ASC' THEN b.condition_name_snapshot END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='condition' AND p_sort->0->>'direction'='DESC' THEN b.condition_name_snapshot END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='severity' AND p_sort->0->>'direction'='ASC' THEN b.severity_label END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='severity' AND p_sort->0->>'direction'='DESC' THEN b.severity_label END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='status' AND p_sort->0->>'direction'='ASC' THEN b.status_label END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='status' AND p_sort->0->>'direction'='DESC' THEN b.status_label END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='caseCreatedDate' AND p_sort->0->>'direction'='ASC' THEN b.reported_at END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='caseCreatedDate' AND p_sort->0->>'direction'='DESC' THEN b.reported_at END DESC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='caseClosedDate' AND p_sort->0->>'direction'='ASC' THEN b.closed_at END ASC NULLS LAST,
      CASE WHEN p_sort->0->>'key'='caseClosedDate' AND p_sort->0->>'direction'='DESC' THEN b.closed_at END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='defectId' AND p_sort->1->>'direction'='ASC' THEN b.reference END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='defectId' AND p_sort->1->>'direction'='DESC' THEN b.reference END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='area' AND p_sort->1->>'direction'='ASC' THEN b.location END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='area' AND p_sort->1->>'direction'='DESC' THEN b.location END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='cabinZone' AND p_sort->1->>'direction'='ASC' THEN b.zone END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='cabinZone' AND p_sort->1->>'direction'='DESC' THEN b.zone END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='component' AND p_sort->1->>'direction'='ASC' THEN b.component END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='component' AND p_sort->1->>'direction'='DESC' THEN b.component END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='condition' AND p_sort->1->>'direction'='ASC' THEN b.condition_name_snapshot END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='condition' AND p_sort->1->>'direction'='DESC' THEN b.condition_name_snapshot END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='severity' AND p_sort->1->>'direction'='ASC' THEN b.severity_label END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='severity' AND p_sort->1->>'direction'='DESC' THEN b.severity_label END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='status' AND p_sort->1->>'direction'='ASC' THEN b.status_label END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='status' AND p_sort->1->>'direction'='DESC' THEN b.status_label END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='caseCreatedDate' AND p_sort->1->>'direction'='ASC' THEN b.reported_at END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='caseCreatedDate' AND p_sort->1->>'direction'='DESC' THEN b.reported_at END DESC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='caseClosedDate' AND p_sort->1->>'direction'='ASC' THEN b.closed_at END ASC NULLS LAST,
      CASE WHEN p_sort->1->>'key'='caseClosedDate' AND p_sort->1->>'direction'='DESC' THEN b.closed_at END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='defectId' AND p_sort->2->>'direction'='ASC' THEN b.reference END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='defectId' AND p_sort->2->>'direction'='DESC' THEN b.reference END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='area' AND p_sort->2->>'direction'='ASC' THEN b.location END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='area' AND p_sort->2->>'direction'='DESC' THEN b.location END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='cabinZone' AND p_sort->2->>'direction'='ASC' THEN b.zone END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='cabinZone' AND p_sort->2->>'direction'='DESC' THEN b.zone END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='component' AND p_sort->2->>'direction'='ASC' THEN b.component END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='component' AND p_sort->2->>'direction'='DESC' THEN b.component END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='condition' AND p_sort->2->>'direction'='ASC' THEN b.condition_name_snapshot END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='condition' AND p_sort->2->>'direction'='DESC' THEN b.condition_name_snapshot END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='severity' AND p_sort->2->>'direction'='ASC' THEN b.severity_label END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='severity' AND p_sort->2->>'direction'='DESC' THEN b.severity_label END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='status' AND p_sort->2->>'direction'='ASC' THEN b.status_label END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='status' AND p_sort->2->>'direction'='DESC' THEN b.status_label END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='caseCreatedDate' AND p_sort->2->>'direction'='ASC' THEN b.reported_at END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='caseCreatedDate' AND p_sort->2->>'direction'='DESC' THEN b.reported_at END DESC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='caseClosedDate' AND p_sort->2->>'direction'='ASC' THEN b.closed_at END ASC NULLS LAST,
      CASE WHEN p_sort->2->>'key'='caseClosedDate' AND p_sort->2->>'direction'='DESC' THEN b.closed_at END DESC NULLS LAST,
      b.id ASC
    OFFSET v_offset LIMIT p_page_size
  )
  SELECT t.n, p.id::text,
    CASE WHEN p.id IS NULL THEN NULL::jsonb ELSE jsonb_build_object(
      'defectId',p.reference,'area',p.location,'cabinZone',p.zone,'component',p.component,
      'condition',p.condition_name_snapshot,'severity',p.severity_label,'status',p.status_label,
      'caseCreatedDate',to_char(p.reported_at AT TIME ZONE 'Asia/Kolkata','YYYY-MM-DD"T"HH24:MI:SS.MS')||'+05:30',
      'caseClosedDate',CASE WHEN p.closed_at IS NULL THEN NULL ELSE
        to_char(p.closed_at AT TIME ZONE 'Asia/Kolkata','YYYY-MM-DD"T"HH24:MI:SS.MS')||'+05:30' END
    ) END
  FROM totals t LEFT JOIN page_rows p ON TRUE;
END $$;

CREATE FUNCTION defect_workflow_status_lookup_v2(
  p_tenant_id uuid,
  p_actor_subject text,
  p_tenant_role text,
  p_scope_policy text,
  p_max_results integer
) RETURNS TABLE(option_value text, display_label text)
LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
BEGIN
  IF p_scope_policy IS DISTINCT FROM 'DEFECT_VISIBLE_SCOPE_V1' OR p_tenant_id IS NULL
     OR p_actor_subject IS NULL OR btrim(p_actor_subject)='' OR p_tenant_role IS NULL
     OR p_max_results IS NULL OR p_max_results<1 OR p_max_results>100 OR NOT EXISTS(
       SELECT 1 FROM operators_mst o
       JOIN operator_memberships_mst m ON m.operator_id=o.id AND m.external_user_id=p_actor_subject
         AND m.role=p_tenant_role AND m.isactive=1
       JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1
       WHERE o.id=p_tenant_id AND o.isactive=1) THEN
    RAISE EXCEPTION 'Invalid report lookup context or bounds' USING ERRCODE='22023';
  END IF;
  RETURN QUERY SELECT s.status_code,COALESCE(NULLIF(s.alias_name,''),s.display_name)
    FROM workflow_status_master s
    WHERE s.module_code='DEFECT' AND s.is_active=true
    ORDER BY s.sort_order,s.status_code
    LIMIT p_max_results;
END $$;

INSERT INTO report_routines_mst(id,routine_key,schema_name,function_name,contract_version,is_active,
    timeout_ms,max_page_size,routine_kind,scope_policy,created_by,updated_by)
VALUES
  ('57000000-0000-4000-8000-000000000001','DEFECT_REGISTER_V2',current_schema(),'defect_register_report_v2',2,false,10000,100,'REPORT','DEFECT_VISIBLE_SCOPE_V1','system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000002','DEFECT_WORKFLOW_STATUS_LOOKUP_V2',current_schema(),'defect_workflow_status_lookup_v2',2,true,5000,100,'LOOKUP','DEFECT_VISIBLE_SCOPE_V1','system:migration','system:migration');

INSERT INTO report_routine_parameters_mst(routine_id,contract_version,parameter_key,parameter_type,is_required,is_filterable,sort_order,created_by,updated_by)
SELECT '57000000-0000-4000-8000-000000000001',2,p.parameter_key,p.parameter_type,false,true,p.sort_order,'system:migration','system:migration'
FROM (VALUES ('from_date','DATE',10),('to_date','DATE',20),('area','TEXT',30),('status','TEXT',40)) p(parameter_key,parameter_type,sort_order);

INSERT INTO report_lookup_sources_mst(id,lookup_key,routine_id,contract_version,is_active,value_type,max_results,created_by,updated_by)
VALUES ('57000000-0000-4000-8000-000000000003','DEFECT_WORKFLOW_STATUS','57000000-0000-4000-8000-000000000002',2,true,'TEXT',100,'system:migration','system:migration');

INSERT INTO report_templates_mst(id,template_code,template_version,name,description,routine_id,is_active,sort_order,created_by,updated_by)
VALUES ('57000000-0000-4000-8000-000000000004','DEFECT_REGISTER',1,'Defect Register',
  'View reported defects by date range, area, and workflow status.',
  '57000000-0000-4000-8000-000000000001',false,10,'system:migration','system:migration');

INSERT INTO report_template_filters_mst(template_id,template_version,filter_key,label,filter_type,parameter_key,is_required,sort_order,configuration,lookup_source_id,created_by,updated_by)
VALUES
  ('57000000-0000-4000-8000-000000000004',1,'fromDate','From Date','DATE','from_date',false,10,'{"rangeGroup":"reportedAt","rangeBound":"FROM"}'::jsonb,NULL,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'toDate','To Date','DATE','to_date',false,20,'{"rangeGroup":"reportedAt","rangeBound":"TO"}'::jsonb,NULL,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'area','Area','SINGLE_SELECT','area',false,30,
    '{"options":[{"value":"CABIN","label":"Cabin"},{"value":"GALLEY","label":"Galley"},{"value":"LAVATORY","label":"Lavatory"},{"value":"ATTENDANT_SEAT","label":"Attendant Seat"}]}'::jsonb,NULL,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'status','Status','LOOKUP_SINGLE','status',false,40,'{}'::jsonb,
    '57000000-0000-4000-8000-000000000003','system:migration','system:migration');

INSERT INTO report_template_columns_mst(template_id,template_version,column_key,label,data_type,sort_order,is_visible,is_sortable,is_exportable,format_configuration,created_by,updated_by)
VALUES
  ('57000000-0000-4000-8000-000000000004',1,'defectId','Defect ID','TEXT',1,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'area','Area','TEXT',2,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'cabinZone','Cabin zone','TEXT',3,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'component','Component','TEXT',4,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'condition','Condition','TEXT',5,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'severity','Severity','TEXT',6,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'status','Status','TEXT',7,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'caseCreatedDate','Case Created Date','DATETIME',8,true,true,true,'{}'::jsonb,'system:migration','system:migration'),
  ('57000000-0000-4000-8000-000000000004',1,'caseClosedDate','Case Closed Date','DATETIME',9,true,true,true,'{}'::jsonb,'system:migration','system:migration');
