-- Report visibility is controlled by the dedicated application permission.
-- Existing role permissions are deliberately not changed.
UPDATE navigation_menu_items_mst
SET required_permissions='REPORT_VIEW'
WHERE menu_key='reports' AND parent_id IS NULL
  AND required_permissions IS DISTINCT FROM 'REPORT_VIEW';

-- Keep export outcomes in the same append-only audit stream as report queries.
ALTER TABLE report_query_events_mst
  DROP CONSTRAINT report_query_events_mst_event_type_check,
  DROP CONSTRAINT report_query_events_mst_outcome_check;

ALTER TABLE report_query_events_mst
  ADD CONSTRAINT report_query_events_event_type_ck CHECK (event_type IN ('QUERY','LOOKUP','EXPORT')),
  ADD CONSTRAINT report_query_events_outcome_ck CHECK (outcome IN
    ('SUCCESS','ACCESS_DENIED','INVALID_REQUEST','CONFIGURATION_BLOCKED','TIMEOUT','DATABASE_ERROR','EXPORT_LIMIT_EXCEEDED')),
  ADD COLUMN exported_row_count bigint NOT NULL DEFAULT 0 CHECK (exported_row_count >= 0);
