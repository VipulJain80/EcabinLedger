-- Add the explicit hold workflow state without rewriting existing status metadata or records.
-- Replace the verified V69 check and preserve the complete V61 report contract and authorization.
DO $$
DECLARE
  v_definition text;
  v_actual_statuses text[];
  v_expected_statuses text[] := ARRAY[
    'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS','INSPECTION_COMPLETE',
    'ACTION_ASSIGNED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED',
    'CLOSED','REJECTED','REWORK','CANCELLED','REOPENED'
  ];
BEGIN
  SELECT pg_get_constraintdef(c.oid) INTO v_definition
  FROM pg_constraint c
  WHERE c.conrelid = to_regclass(format('%I.defects_dtl', current_schema()))
    AND c.conname = 'defects_status_check'
    AND c.contype = 'c';
  IF v_definition IS NULL OR v_definition NOT ILIKE '%status%' THEN
    RAISE EXCEPTION 'V74 cannot safely replace defects_status_check: expected V69 constraint is missing; inspect manually.';
  END IF;
  SELECT COALESCE(array_agg(captures[1] ORDER BY captures[1]), ARRAY[]::text[]) INTO v_actual_statuses
  FROM regexp_matches(v_definition, '''([^'']+)''', 'g') AS matches(captures);
  SELECT array_agg(status_value ORDER BY status_value) INTO v_expected_statuses
  FROM unnest(v_expected_statuses) AS statuses(status_value);
  IF v_actual_statuses IS DISTINCT FROM v_expected_statuses THEN
    RAISE EXCEPTION 'V74 found unexpected defects_status_check definition: %. Manual review is required.', v_definition;
  END IF;
END $$;

DO $$
DECLARE
  v_display_name text;
  v_alias_name text;
  v_description text;
  v_is_active boolean;
  v_sort_order integer;
  v_is_initial boolean;
  v_is_terminal boolean;
BEGIN
  SELECT display_name,alias_name,description,is_active,sort_order,is_initial,is_terminal
    INTO v_display_name,v_alias_name,v_description,v_is_active,v_sort_order,v_is_initial,v_is_terminal
  FROM workflow_status_master
  WHERE module_code='DEFECT' AND status_code='ON_HOLD'
  FOR UPDATE;
  IF FOUND THEN
    IF v_display_name IS DISTINCT FROM 'On Hold'
       OR v_alias_name IS DISTINCT FROM 'On Hold'
       OR v_description IS DISTINCT FROM 'Maintenance work is temporarily paused.'
       OR v_is_active IS DISTINCT FROM TRUE
       OR v_sort_order IS DISTINCT FROM 8
       OR v_is_initial IS DISTINCT FROM FALSE
       OR v_is_terminal IS DISTINCT FROM FALSE THEN
      RAISE EXCEPTION 'DEFECT/ON_HOLD already exists with inactive or conflicting metadata; manual review is required.';
    END IF;
  ELSE
    INSERT INTO workflow_status_master
      (module_code,status_code,display_name,alias_name,description,is_active,sort_order,is_initial,is_terminal)
    VALUES
      ('DEFECT','ON_HOLD','On Hold','On Hold','Maintenance work is temporarily paused.',TRUE,8,FALSE,FALSE);
  END IF;
END $$;

ALTER TABLE defects_dtl DROP CONSTRAINT defects_status_check;
ALTER TABLE defects_dtl ADD CONSTRAINT defects_status_check CHECK (status IN (
  'REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','INSPECTION_IN_PROGRESS','INSPECTION_COMPLETE',
  'ACTION_ASSIGNED','IN_PROGRESS','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED',
  'CLOSED','REJECTED','REWORK','CANCELLED','REOPENED','ON_HOLD'
));

-- Pin the reviewed Defect Register routine to the canonical application schema.
-- Preserve the v2 contract and change only relation name resolution.
CREATE OR REPLACE FUNCTION ecabin_ledger.defect_register_report_v2(
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
  FROM ecabin_ledger.operators_mst o
  JOIN ecabin_ledger.operator_memberships_mst m ON m.operator_id=o.id AND m.external_user_id=p_actor_subject
    AND m.role=p_tenant_role AND m.isactive=1
  JOIN ecabin_ledger.operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1
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
      SELECT 1 FROM ecabin_ledger.workflow_status_master s
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
      CASE WHEN current_action.status='ON_HOLD' AND d.status IN ('IN_PROGRESS','ON_HOLD') THEN 'ON_HOLD' ELSE d.status END AS status_code,
      COALESCE(NULLIF(ws.alias_name,''),ws.display_name,CASE WHEN current_action.status='ON_HOLD' AND d.status IN ('IN_PROGRESS','ON_HOLD') THEN 'ON_HOLD' ELSE d.status END) AS status_label,
      d.reported_at,
      CASE WHEN d.status='CLOSED' THEN COALESCE((
        SELECT max(ev.occurred_at) FROM ecabin_ledger.defect_events_dtl ev
        WHERE ev.operator_id=d.operator_id AND ev.defect_id=d.id
          AND (ev.to_status='CLOSED' OR ev.event_type='CLOSURE_APPROVED')
      ),d.updated_at) ELSE NULL END AS closed_at
    FROM ecabin_ledger.defects_dtl d
    JOIN ecabin_ledger.aircraft_mst a ON a.id=d.aircraft_id AND a.operator_id=d.operator_id
    LEFT JOIN ecabin_ledger.defect_severities_mst sv ON sv.operator_id=d.operator_id AND sv.id=d.severity_id
    LEFT JOIN LATERAL (SELECT ca.status,ca.assigned_team,ca.assigned_user_id FROM ecabin_ledger.corrective_actions_dtl ca WHERE ca.operator_id=d.operator_id AND ca.defect_id=d.id AND ca.isactive=1 ORDER BY ca.created_at DESC,ca.id DESC LIMIT 1) current_action ON TRUE
    LEFT JOIN ecabin_ledger.workflow_status_master ws ON ws.module_code='DEFECT' AND ws.status_code=CASE WHEN current_action.status='ON_HOLD' AND d.status IN ('IN_PROGRESS','ON_HOLD') THEN 'ON_HOLD' ELSE d.status END
    WHERE d.operator_id=p_tenant_id AND d.isactive=1 AND a.isactive=1
      AND (v_from_at IS NULL OR d.reported_at>=v_from_at)
      AND (v_to_exclusive IS NULL OR d.reported_at<v_to_exclusive)
      AND (v_area IS NULL OR d.location=v_area)
      AND (v_status IS NULL OR (CASE WHEN current_action.status='ON_HOLD' AND d.status IN ('IN_PROGRESS','ON_HOLD') THEN 'ON_HOLD' ELSE d.status END)=v_status)
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
          ((current_action.assigned_team IS NULL AND current_action.assigned_user_id=p_actor_subject) OR EXISTS(
            SELECT 1 FROM ecabin_ledger.operator_membership_teams_mst omt
            JOIN ecabin_ledger.operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1
            JOIN ecabin_ledger.maintenance_teams_mst mt ON mt.operator_id=omt.operator_id AND mt.name=omt.team_name AND mt.isactive=1
            WHERE omt.operator_id=p_tenant_id AND omt.external_user_id=p_actor_subject
              AND omt.team_name=current_action.assigned_team AND omt.isactive=1
          )))
        AND (NOT ('REVIEW'=ANY(role_permissions) AND NOT ('ADMINISTER_ORGANIZATION'=ANY(role_permissions))) OR
          (d.reviewer_team_id IS NOT NULL
            AND d.status IN ('REPORTED','UNDER_REVIEW','INSPECTION_REQUIRED','IN_PROGRESS','ON_HOLD','AWAITING_VERIFICATION','VERIFIED','APPROVAL_REQUIRED','REOPENED','REJECTED')
            AND EXISTS(
              SELECT 1 FROM ecabin_ledger.maintenance_teams_mst rt
              JOIN ecabin_ledger.operator_membership_teams_mst omt ON omt.operator_id=rt.operator_id AND omt.team_name=rt.name AND omt.isactive=1
              JOIN ecabin_ledger.operator_memberships_mst om ON om.operator_id=omt.operator_id AND om.external_user_id=omt.external_user_id AND om.isactive=1
              JOIN ecabin_ledger.operator_roles_mst rr ON rr.operator_id=om.operator_id AND rr.role_key=om.role AND rr.isactive=1
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
