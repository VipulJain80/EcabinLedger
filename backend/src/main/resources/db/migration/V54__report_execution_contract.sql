-- Report functions are deployment-reviewed capabilities. An active registry row is
-- executable only when it opts into contract v2 and the defect visibility policy.
-- No routine is activated or inserted by this migration.
ALTER TABLE report_routines_mst
  ADD COLUMN routine_kind varchar(12) NOT NULL DEFAULT 'REPORT'
    CHECK (routine_kind IN ('REPORT','LOOKUP')),
  ADD COLUMN scope_policy varchar(40) NOT NULL DEFAULT 'UNVERIFIED'
    CHECK (scope_policy IN ('UNVERIFIED','DEFECT_VISIBLE_SCOPE_V1'));

COMMENT ON COLUMN report_routines_mst.routine_kind IS
  'Deployment-managed callable type. REPORT functions return the v2 page envelope; LOOKUP functions return bounded value/label options.';
COMMENT ON COLUMN report_routines_mst.scope_policy IS
  'Only DEFECT_VISIBLE_SCOPE_V1 is executable. Its reviewed function must mirror DefectService reporter, reviewer-team, maintenance-team, and broad-view visibility.';
COMMENT ON COLUMN report_routines_mst.contract_version IS
  'Only version 2 is supported by the generic executor. It receives trusted tenant, actor, role and scope context plus bound JSON metadata.';

ALTER TABLE report_lookup_sources_mst
  ADD COLUMN max_results integer NOT NULL DEFAULT 100 CHECK (max_results BETWEEN 1 AND 100);

-- Date-range pairing is explicit metadata, so filter validation never guesses from labels.
ALTER TABLE report_filters_mst DROP CONSTRAINT report_filters_config_keys;
ALTER TABLE report_filters_mst ADD CONSTRAINT report_filters_config_keys CHECK
  (configuration - ARRAY['placeholder','min','max','maxLength','options','rangeGroup','rangeBound']::text[] = '{}'::jsonb);
ALTER TABLE report_template_filters_mst DROP CONSTRAINT report_template_filters_config_keys;
ALTER TABLE report_template_filters_mst ADD CONSTRAINT report_template_filters_config_keys CHECK
  (configuration - ARRAY['placeholder','min','max','maxLength','options','rangeGroup','rangeBound']::text[] = '{}'::jsonb);
ALTER TABLE report_filters_mst ADD CONSTRAINT report_filters_date_range_check CHECK
  ((NOT (configuration ? 'rangeGroup' OR configuration ? 'rangeBound')) OR
   (configuration ? 'rangeGroup' AND configuration ? 'rangeBound' AND filter_type='DATE' AND jsonb_typeof(configuration->'rangeGroup')='string'
     AND configuration->>'rangeGroup' ~ '^[a-z][a-zA-Z0-9_]{0,63}$'
     AND configuration->>'rangeBound' IN ('FROM','TO')));
ALTER TABLE report_template_filters_mst ADD CONSTRAINT report_template_filters_date_range_check CHECK
  ((NOT (configuration ? 'rangeGroup' OR configuration ? 'rangeBound')) OR
   (configuration ? 'rangeGroup' AND configuration ? 'rangeBound' AND filter_type='DATE' AND jsonb_typeof(configuration->'rangeGroup')='string'
     AND configuration->>'rangeGroup' ~ '^[a-z][a-zA-Z0-9_]{0,63}$'
     AND configuration->>'rangeBound' IN ('FROM','TO')));
CREATE UNIQUE INDEX report_filters_date_range_bound_uq ON report_filters_mst
  (operator_id,report_id,(configuration->>'rangeGroup'),(configuration->>'rangeBound'))
  WHERE configuration ? 'rangeGroup';
CREATE UNIQUE INDEX report_template_filters_date_range_bound_uq ON report_template_filters_mst
  (template_id,template_version,(configuration->>'rangeGroup'),(configuration->>'rangeBound'))
  WHERE configuration ? 'rangeGroup';

COMMENT ON TABLE report_query_events_mst IS
  'Append-only audit for query and lookup attempts. Filter values and report result data are intentionally excluded.';
