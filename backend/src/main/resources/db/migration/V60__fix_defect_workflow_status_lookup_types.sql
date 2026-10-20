-- The registered lookup contract returns TEXT for both columns. The status
-- master stores bounded VARCHAR values, so cast explicitly for PostgreSQL's
-- set-returning function row-type validation.
CREATE OR REPLACE FUNCTION defect_workflow_status_lookup_v2(
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
  RETURN QUERY
    SELECT s.status_code::text,
           COALESCE(NULLIF(s.alias_name,''),s.display_name)::text
    FROM workflow_status_master s
    WHERE s.module_code='DEFECT' AND s.is_active=true
    ORDER BY s.sort_order,s.status_code
    LIMIT p_max_results;
END $$;
