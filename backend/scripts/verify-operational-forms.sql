-- Read-only verification after the reviewed V30 payload has been applied.
-- Run with: psql ... -v schema=ecabin_ledger -v operator_id=<target-tenant-uuid> -f backend/scripts/verify-operational-forms.sql
\set ON_ERROR_STOP on
\if :{?schema}
\else
  \echo 'Set -v schema to the configured application schema.'
  \quit 2
\endif
\if :{?operator_id}
\else
  \echo 'Set -v operator_id to the verified target tenant UUID.'
  \quit 2
\endif

BEGIN TRANSACTION READ ONLY;
SELECT current_database() AS database_name, current_schema() AS session_schema;

-- Expected: four active transferred forms; older form rows remain archived for history.
SELECT count(*) FILTER (WHERE isactive=1) AS active_forms,
       count(DISTINCT form_key) FILTER (WHERE isactive=1) AS distinct_active_form_keys,
       count(*) FILTER (WHERE isactive=2 AND form_key LIKE 'ARCHIVED_V30_%') AS archived_forms
FROM :"schema".operational_forms_dtl
WHERE operator_id=:'operator_id'::uuid;

-- Expected active keys: CABIN, DEMO_REPORT_ENTRY_AREA, DEMO_REPORT_LAVATORY, GALLEY_AREA.
SELECT form_key, name, isactive
FROM :"schema".operational_forms_dtl
WHERE operator_id=:'operator_id'::uuid AND isactive=1
ORDER BY form_key;

-- Names/keys, target-resolved area, purpose/stage, publication pointer and version counts.
SELECT f.form_key, f.name, a.code AS area_code, f.purpose, f.workflow_stage,
       f.isactive, pv.version_number AS published_version,
       count(v.id) AS version_count,
       count(v.id) FILTER (WHERE v.status='PUBLISHED') AS published_versions,
       count(v.id) FILTER (WHERE v.status='DRAFT') AS drafts
FROM :"schema".operational_forms_dtl f
JOIN :"schema".cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id
LEFT JOIN :"schema".operational_form_versions_dtl pv
  ON pv.operator_id=f.operator_id AND pv.form_id=f.id AND pv.id=f.published_version_id
LEFT JOIN :"schema".operational_form_versions_dtl v
  ON v.operator_id=f.operator_id AND v.form_id=f.id
WHERE f.operator_id=:'operator_id'::uuid AND f.isactive=1
GROUP BY f.id, f.form_key, f.name, a.code, f.purpose, f.workflow_stage, f.isactive, pv.version_number
ORDER BY f.form_key;

-- Full field definitions, in version order; compare this output with the reviewed export.
SELECT f.form_key, v.version_number, v.status, v.fields_json->'fields' AS fields,
       v.published_by, v.published_at
FROM :"schema".operational_forms_dtl f
JOIN :"schema".operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
WHERE f.operator_id=:'operator_id'::uuid AND f.isactive=1
ORDER BY f.form_key, v.version_number;

-- Must return no rows: duplicate keys/versions, broken area ownership, invalid publication pointers.
SELECT 'duplicate_form_key' AS problem, form_key AS key, count(*) AS occurrences
FROM :"schema".operational_forms_dtl WHERE operator_id=:'operator_id'::uuid AND isactive=1
GROUP BY form_key HAVING count(*)>1
UNION ALL
SELECT 'duplicate_version_number', f.form_key||':'||v.version_number::text, count(*)
FROM :"schema".operational_forms_dtl f
JOIN :"schema".operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
WHERE f.operator_id=:'operator_id'::uuid AND f.isactive=1 GROUP BY f.form_key,v.version_number HAVING count(*)>1
UNION ALL
SELECT 'invalid_published_pointer', f.form_key, 1
FROM :"schema".operational_forms_dtl f
LEFT JOIN :"schema".operational_form_versions_dtl v
  ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id AND v.status='PUBLISHED'
WHERE f.operator_id=:'operator_id'::uuid AND f.isactive=1 AND f.published_version_id IS NOT NULL AND v.id IS NULL;

-- Historical references are reported to confirm archival preserved their source records.
SELECT
  (SELECT count(*) FROM :"schema".operational_form_submissions_dtl s
   JOIN :"schema".operational_forms_dtl f ON f.operator_id=s.operator_id AND f.id=s.form_id
   WHERE f.operator_id=:'operator_id'::uuid) AS submissions,
  (SELECT count(*) FROM :"schema".attachments_dtl x
   JOIN :"schema".operational_forms_dtl f ON f.operator_id=x.operator_id AND f.id=x.form_id
   WHERE f.operator_id=:'operator_id'::uuid) AS form_attachments,
  (SELECT count(*) FROM :"schema".attachments_dtl x
   JOIN :"schema".operational_forms_dtl f ON f.operator_id=x.operator_id AND f.id=x.form_id
   WHERE f.operator_id=:'operator_id'::uuid AND x.form_submission_id IS NOT NULL) AS submission_attachments;
ROLLBACK;
