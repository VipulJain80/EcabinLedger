-- Development/demo only. All aircraft_mst registrations and operational data below are synthetic.
-- Skyways Service is fictional demo branding. Never run in production.
INSERT INTO operators_mst(id, name) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Skyways Service (DEMO)')
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, isactive=1;

INSERT INTO operator_memberships_mst(operator_id, external_user_id, display_name, role, isactive) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-user', 'Alex Morgan (DEMO)', 'SUPERVISOR', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-inspector', 'Riya Shah (DEMO)', 'INSPECTOR', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-tech', 'Arun Das (DEMO)', 'MAINTENANCE_TECHNICIAN', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-quality', 'Meera Rao (DEMO)', 'QUALITY_COMPLIANCE', 1)
ON CONFLICT (operator_id, external_user_id) DO UPDATE
SET display_name=EXCLUDED.display_name, role=EXCLUDED.role, isactive=1;

-- Preserve the established abc@gmail.com local identity and membership on every seed run.
-- Insert it for a fresh workstation only; never overwrite its role, display name, or status.
INSERT INTO operator_memberships_mst(operator_id, external_user_id, display_name, role, isactive)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'abc@gmail.com', 'abc@gmail.com (LOCAL DEMO)', 'ADMIN', 1)
ON CONFLICT (operator_id, external_user_id) DO NOTHING;

-- Separate local-only test accounts. The reviewer role is tenant-scoped and intentionally
-- limited to VIEW + REVIEW; no local identity receives platform-wide SUPERADMIN authority.
INSERT INTO operator_roles_mst(operator_id, role_key, name, permissions, system_role, isactive)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'REVIEWER', 'Reviewer (LOCAL DEMO)', 'VIEW|REVIEW', false, 1)
ON CONFLICT (operator_id, role_key) DO NOTHING;

-- The extensible tenant role catalog supports a least-privilege local Auditor role.
INSERT INTO operator_roles_mst(operator_id, role_key, name, permissions, system_role, isactive)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'AUDITOR', 'Auditor (LOCAL DEMO)', 'VIEW|REPORT|AUDIT', false, 1)
ON CONFLICT (operator_id, role_key) DO NOTHING;

INSERT INTO operator_memberships_mst(operator_id, external_user_id, display_name, role, isactive) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'admin.test@ecabin.local', 'Admin Test (LOCAL DEMO)', 'ADMIN', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'auditor.test@ecabin.local', 'Auditor Test (LOCAL DEMO)', 'AUDITOR', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'reviewer.test@ecabin.local', 'Reviewer Test (LOCAL DEMO)', 'REVIEWER', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'maintenance.test@ecabin.local', 'Maintenance Test (LOCAL DEMO)', 'MAINTENANCE_TECHNICIAN', 1)
ON CONFLICT (operator_id, external_user_id) DO NOTHING;

-- Correct the earlier local-only sample role without touching abc@gmail.com or production users.
UPDATE operator_memberships_mst SET role='AUDITOR', isactive=1
WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='auditor.test@ecabin.local'
  AND display_name='Auditor Test (LOCAL DEMO)' AND role<>'AUDITOR';

INSERT INTO fleets_mst(id, operator_id, name) VALUES
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'General Fleet')
ON CONFLICT (operator_id, name) DO NOTHING;

INSERT INTO defect_categories_mst(operator_id, code, name)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', c.code, c.name FROM (VALUES
  ('CABIN','Cabin'),('GALLEY','Galley'),('LAVATORY','Lavatory'),('ATTENDANT_SEAT','Attendant seat'),
  ('IFE','In-flight entertainment'),('SEAT','Passenger seat'),('LIGHTING','Lighting'),
  ('OVERHEAD_BIN','Overhead bin'),('PSU','Passenger service unit'),('EMERGENCY_EQUIPMENT','Emergency equipment'),('OTHER','Other')
) AS c(code,name) ON CONFLICT(operator_id, code) DO NOTHING;

INSERT INTO cabin_components_mst(operator_id, code, name, area_id)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', c.code, c.name, a.id FROM (VALUES
  ('SEAT','Passenger seat','CABIN'),('IFE_DISPLAY','IFE display','CABIN'),('READING_LIGHT','Reading light','CABIN'),
  ('OVERHEAD_BIN_LATCH','Overhead bin latch','CABIN'),('PSU','Passenger service unit','CABIN'),
  ('GALLEY_CART_LATCH','Galley cart latch','GALLEY'),('LAVATORY_FAUCET','Lavatory faucet','LAVATORY'),('ATTENDANT_SEAT_BELT','Attendant seat belt','ATTENDANT_SEAT')
) AS c(code,name,area_code) JOIN cabin_areas_mst a ON a.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND a.code=c.area_code
ON CONFLICT(operator_id, code) DO NOTHING;

-- DEMO observation vocabulary only; this is not an approved maintenance procedure or engineering disposition.
INSERT INTO defect_conditions_mst(operator_id,code,name,description)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','SURFACE_DAMAGE','Surface damage (DEMO)','Illustrative demo condition label only; follow approved maintenance data for all operational decisions.')
ON CONFLICT(operator_id,code) DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,isactive=1;
INSERT INTO component_conditions_mst(operator_id,component_id,condition_id)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',c.id,d.id
FROM cabin_components_mst c CROSS JOIN defect_conditions_mst d
WHERE c.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND c.code IN ('TRAY_TABLE','VINYL')
  AND d.operator_id=c.operator_id AND d.code='SURFACE_DAMAGE'
ON CONFLICT(operator_id,component_id,condition_id) WHERE isactive=1 DO NOTHING;

-- This demonstration-only observation label is also available for the seeded Galley latch.
-- It is not an approved maintenance classification or procedure.
INSERT INTO component_conditions_mst(operator_id,component_id,condition_id)
SELECT c.operator_id,c.id,d.id
FROM cabin_components_mst c JOIN defect_conditions_mst d ON d.operator_id=c.operator_id AND d.code='SURFACE_DAMAGE' AND d.isactive=1
WHERE c.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND c.code='GALLEY_CART_LATCH' AND c.isactive=1
ON CONFLICT(operator_id,component_id,condition_id) WHERE isactive=1 DO NOTHING;

-- A local-only reporting template is seeded only for areas that already have an explicit active
-- component/condition mapping. It records observations and context; it is not a maintenance procedure.
INSERT INTO operational_forms_dtl(operator_id,area_id,form_key,name)
SELECT DISTINCT c.operator_id,a.id,'DEMO_REPORT_'||a.code,a.name||' defect report (DEMO)'
FROM cabin_areas_mst a
JOIN cabin_components_mst c ON c.operator_id=a.operator_id AND c.area_id=a.id AND c.isactive=1
JOIN component_conditions_mst cc ON cc.operator_id=c.operator_id AND cc.component_id=c.id AND cc.isactive=1
JOIN defect_conditions_mst dc ON dc.operator_id=cc.operator_id AND dc.id=cc.condition_id AND dc.isactive=1
WHERE a.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND a.isactive=1
  AND a.code<>'GALLEY'
ON CONFLICT(operator_id,form_key) DO NOTHING;

INSERT INTO operational_form_versions_dtl(operator_id,form_id,version_number,status,fields_json,created_by,published_by,published_at)
SELECT f.operator_id,f.id,COALESCE(max(v.version_number),0)+1,'PUBLISHED',
  '{"fields":[{"key":"category_ref","label":"Defect category","type":"DROPDOWN","required":true,"order":0,"optionSource":"DEFECT_CATEGORIES","binding":"DEFECT_CATEGORY"},{"key":"component_ref","label":"Component","type":"DROPDOWN","required":true,"order":1,"optionSource":"COMPONENTS","binding":"COMPONENT_ID"},{"key":"condition_ref","label":"Defect condition","type":"DROPDOWN","required":true,"order":2,"optionSource":"DEFECT_CONDITIONS","optionFilter":{"componentFieldKey":"component_ref"},"binding":"CONDITION_ID"},{"key":"zone","label":"Location","type":"TEXT","required":true,"order":3,"textRules":{"maxLength":80},"binding":"ZONE"},{"key":"title","label":"Defect title","type":"TEXT","required":true,"order":4,"textRules":{"maxLength":160},"binding":"TITLE"},{"key":"description","label":"Defect description","type":"TEXTAREA","required":true,"order":5,"textRules":{"maxLength":4000},"binding":"DESCRIPTION"},{"key":"severity_ref","label":"Severity","type":"DROPDOWN","required":true,"order":6,"optionSource":"DEFECT_SEVERITIES","binding":"SEVERITY"},{"key":"evidence","label":"Photo or document evidence","type":"IMAGE_UPLOAD","required":false,"order":7,"binding":"EVIDENCE_ATTACHMENTS","fileRules":{"extensions":["png","jpg","jpeg"],"mimeTypes":["image/png","image/jpeg"],"maxFileSizeBytes":5242880,"minCount":0,"maxCount":3}}]}'::jsonb,
  'local-demo-seed','local-demo-seed',now()
FROM operational_forms_dtl f
LEFT JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.form_key LIKE 'DEMO_REPORT_%'
  AND f.isactive=1
  AND NOT EXISTS (SELECT 1 FROM operational_form_versions_dtl current_version WHERE current_version.operator_id=f.operator_id AND current_version.form_id=f.id AND current_version.status='PUBLISHED' AND current_version.fields_json @> '{"fields":[{"binding":"DEFECT_CATEGORY"}]}'::jsonb)
  AND NOT EXISTS (SELECT 1 FROM operational_form_versions_dtl draft WHERE draft.operator_id=f.operator_id AND draft.form_id=f.id AND draft.status='DRAFT')
GROUP BY f.operator_id,f.id
ON CONFLICT(operator_id,form_id,version_number) DO NOTHING;

UPDATE operational_forms_dtl f SET published_version_id=(
  SELECT v.id FROM operational_form_versions_dtl v
  WHERE v.operator_id=f.operator_id AND v.form_id=f.id AND v.status='PUBLISHED'
  ORDER BY v.version_number DESC LIMIT 1
),updated_at=now()
WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.form_key LIKE 'DEMO_REPORT_%'
  AND f.isactive=1
  AND NOT EXISTS (SELECT 1 FROM operational_form_versions_dtl current_version WHERE current_version.operator_id=f.operator_id AND current_version.form_id=f.id AND current_version.id=f.published_version_id AND current_version.fields_json @> '{"fields":[{"binding":"DEFECT_CATEGORY"}]}'::jsonb);

-- Bootstrap only a fresh synthetic demo tenant with no existing Galley defect-report form.
-- The version remains a draft until PostgresMigrationSmokeTest publishes it through the same
-- OperationalFormService validation used by Form Master. Existing administrator forms/drafts
-- are neither modified nor automatically published.
INSERT INTO operational_forms_dtl(operator_id,area_id,form_key,name,purpose,workflow_stage)
SELECT a.operator_id,a.id,'LOCAL_DEMO_GALLEY_AREA','Galley Area (DEMO)','DEFECT_REPORT','INITIAL_REPORT'
FROM cabin_areas_mst a
WHERE a.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND a.code='GALLEY' AND a.isactive=1
  AND NOT EXISTS (
    SELECT 1 FROM operational_forms_dtl existing
    WHERE existing.operator_id=a.operator_id AND existing.area_id=a.id AND existing.purpose='DEFECT_REPORT'
      AND existing.workflow_stage='INITIAL_REPORT'
  )
ON CONFLICT(operator_id,form_key) DO NOTHING;

INSERT INTO operational_form_versions_dtl(operator_id,form_id,version_number,status,fields_json,created_by)
SELECT f.operator_id,f.id,1,'DRAFT',
  '{"fields":[{"key":"aircraftId","label":"Aircraft Registration","type":"AIRCRAFT_SELECT","required":true,"order":0,"optionSource":"AIRCRAFT","binding":"AIRCRAFT_ID"},{"key":"location","label":"Galley Number / Location","type":"TEXT","required":true,"order":1,"textRules":{"maxLength":80},"binding":"ZONE"},{"key":"component_ref","label":"Component","type":"DROPDOWN","required":true,"order":2,"optionSource":"COMPONENTS","binding":"COMPONENT_ID"},{"key":"condition_ref","label":"Defect Condition","type":"DROPDOWN","required":true,"order":3,"optionSource":"DEFECT_CONDITIONS","optionFilter":{"componentFieldKey":"component_ref"},"binding":"CONDITION_ID"},{"key":"severity_ref","label":"Severity","type":"DROPDOWN","required":true,"order":4,"optionSource":"GALLEY_SEVERITIES","binding":"SEVERITY"},{"key":"remarks","label":"Remarks","type":"TEXTAREA","required":false,"order":5,"textRules":{"maxLength":4000},"binding":"REMARKS"},{"key":"evidence","label":"Evidence Image Upload","type":"IMAGE_UPLOAD","required":true,"order":6,"binding":"EVIDENCE_ATTACHMENTS","fileRules":{"extensions":["jpg","jpeg","png","webp"],"mimeTypes":["image/jpeg","image/png","image/webp"],"maxFileSizeBytes":5242880,"minCount":1,"maxCount":5}}]}'::jsonb,
  'local-demo-seed'
FROM operational_forms_dtl f
WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.form_key='LOCAL_DEMO_GALLEY_AREA'
  AND f.purpose='DEFECT_REPORT' AND f.workflow_stage='INITIAL_REPORT' AND f.isactive=1
  AND NOT EXISTS (SELECT 1 FROM operational_form_versions_dtl v WHERE v.operator_id=f.operator_id AND v.form_id=f.id)
ON CONFLICT(operator_id,form_id,version_number) DO NOTHING;

-- If the fixed local demo tenant already has exactly one administrator-created Galley report
-- form, preserve its published version and add the missing system aircraft selector only in a
-- seed-owned draft. The smoke test publishes that draft through OperationalFormService.
WITH galley_form AS (
  SELECT f.operator_id,f.id AS form_id,v.fields_json,
    (SELECT COALESCE(max(existing.version_number),0)+1 FROM operational_form_versions_dtl existing WHERE existing.operator_id=f.operator_id AND existing.form_id=f.id) AS next_version
  FROM operational_forms_dtl f
  JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id AND a.code='GALLEY' AND a.isactive=1
  JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id AND v.status='PUBLISHED'
  WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.purpose='DEFECT_REPORT' AND f.workflow_stage='INITIAL_REPORT' AND f.isactive=1
    AND f.form_key<>'LOCAL_DEMO_GALLEY_AREA'
    AND NOT EXISTS (SELECT 1 FROM operational_form_versions_dtl draft WHERE draft.operator_id=f.operator_id AND draft.form_id=f.id AND draft.status='DRAFT')
    AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.fields_json->'fields') existing(field) WHERE existing.field->>'binding'='AIRCRAFT_ID')
    AND (SELECT count(*) FROM operational_forms_dtl other WHERE other.operator_id=f.operator_id AND other.area_id=f.area_id AND other.purpose='DEFECT_REPORT' AND other.workflow_stage='INITIAL_REPORT' AND other.isactive=1)=1
)
INSERT INTO operational_form_versions_dtl(operator_id,form_id,version_number,status,fields_json,created_by)
SELECT operator_id,form_id,next_version,'DRAFT',
  jsonb_build_object('fields',
    jsonb_build_array(jsonb_build_object('key','aircraftId','label','Aircraft Registration','type','AIRCRAFT_SELECT','required',true,'order',0,'optionSource','AIRCRAFT','binding','AIRCRAFT_ID')) ||
    COALESCE((SELECT jsonb_agg(jsonb_set(existing.field,'{order}',to_jsonb(COALESCE((existing.field->>'order')::integer,existing.ordinality::integer-1)+1)) ORDER BY existing.ordinality)
      FROM jsonb_array_elements(galley_form.fields_json->'fields') WITH ORDINALITY existing(field,ordinality)), '[]'::jsonb)),
  'local-demo-seed'
FROM galley_form
ON CONFLICT(operator_id,form_id,version_number) DO NOTHING;

INSERT INTO maintenance_teams_mst(operator_id, name, team_type, isactive) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Cabin Maintenance', 'MAINTENANCE', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Cabin Quality (DEMO)', 'MAINTENANCE', 1)
ON CONFLICT(operator_id, name) DO NOTHING;

INSERT INTO maintenance_teams_mst(operator_id, name, team_type, isactive)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Cabin Review', 'REVIEWER', 1)
ON CONFLICT (operator_id, name) DO NOTHING;

UPDATE maintenance_teams_mst SET team_type='MAINTENANCE',isactive=1
WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='Cabin Maintenance';
UPDATE maintenance_teams_mst SET team_type='REVIEWER',isactive=1
WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='Cabin Review';

INSERT INTO operator_membership_teams_mst(operator_id, external_user_id, team_name, isactive) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-tech', 'Cabin Maintenance (DEMO)', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-quality', 'Cabin Quality (DEMO)', 1)
ON CONFLICT (operator_id, external_user_id, team_name) DO UPDATE SET isactive=1;

INSERT INTO operator_membership_teams_mst(operator_id, external_user_id, team_name, isactive) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'reviewer.test@ecabin.local', 'Cabin Review', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'maintenance.test@ecabin.local', 'Cabin Maintenance', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-tech', 'Cabin Maintenance', 1)
ON CONFLICT (operator_id, external_user_id, team_name) DO NOTHING;

-- Use the active demo Reviewer team as the local default route; preserve other organizations' settings.
UPDATE operators_mst SET default_reviewer_team_id=(SELECT id FROM maintenance_teams_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='Cabin Review' AND team_type='REVIEWER' AND isactive=1)
WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND EXISTS(SELECT 1 FROM operator_membership_teams_mst mt JOIN operator_memberships_mst m ON m.operator_id=mt.operator_id AND m.external_user_id=mt.external_user_id AND m.isactive=1 JOIN operator_roles_mst r ON r.operator_id=m.operator_id AND r.role_key=m.role AND r.isactive=1 WHERE mt.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND mt.team_name='Cabin Review' AND mt.isactive=1 AND ('|'||r.permissions||'|') LIKE '%|REVIEW|%');

INSERT INTO aircraft_mst(id, operator_id, fleet_id, tail_number, aircraft_type, msn_number, isactive) VALUES
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', (SELECT id FROM fleets_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='General Fleet'), 'DEMO-A320-01', 'A320-200', 'DEMO-32001', 1),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', (SELECT id FROM fleets_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='General Fleet'), 'DEMO-B737-01', 'B737-800', 'DEMO-73701', 1),
  ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', (SELECT id FROM fleets_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='General Fleet'), 'DEMO-A320-02', 'A320-200', 'DEMO-32002', 1)
ON CONFLICT (id) DO UPDATE
SET fleet_id=EXCLUDED.fleet_id, tail_number=EXCLUDED.tail_number, aircraft_type=EXCLUDED.aircraft_type, msn_number=EXCLUDED.msn_number, isactive=1;

INSERT INTO defects_dtl(id, operator_id, aircraft_id, reference, location, category, zone, row_number, seat_reference, component,
  title, description, severity, status, reported_by, assigned_to, assigned_user_id, due_at, reported_at, updated_at, isactive) VALUES
  ('11111111-1111-4111-8111-111111111101', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'EL-DEMO-0001', 'CABIN', 'SEAT', 'FWD CABIN', 14, '14A', 'Recline lever', 'Seat recline lever binds', 'Seat recline lever intermittently sticks during operation.', 'MEDIUM', 'REPORTED', 'demo-user', NULL, NULL, NULL, now()-interval '2 hours', now()-interval '2 hours', 1),
  ('11111111-1111-4111-8111-111111111102', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'EL-DEMO-0002', 'GALLEY', 'GALLEY', 'FWD GALLEY', NULL, NULL, 'Cart latch 2A', 'Galley cart latch does not secure', 'Forward galley cart latch requires inspection before the next service cycle.', 'HIGH', 'INSPECTION_IN_PROGRESS', 'demo-user', 'demo-inspector', 'demo-inspector', now()+interval '12 hours', now()-interval '5 hours', now()-interval '30 minutes', 1),
  ('11111111-1111-4111-8111-111111111103', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'EL-DEMO-0003', 'LAVATORY', 'LAVATORY', 'AFT LAV', NULL, NULL, 'Faucet assembly', 'Aft lavatory tap intermittently leaks', 'Tap drips after shutoff; corrective work is recorded and awaiting independent verification.', 'MEDIUM', 'AWAITING_VERIFICATION', 'demo-user', 'demo-tech', 'demo-tech', now()-interval '1 hour', now()-interval '2 days', now()-interval '2 hours', 1),
  ('11111111-1111-4111-8111-111111111104', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'EL-DEMO-0004', 'CABIN', 'EMERGENCY_EQUIPMENT', 'ROW 20', 20, '20C', 'Equipment seal indicator', 'Equipment seal indicator unclear', 'Indicator cover was replaced, independently checked and approved for closure.', 'LOW', 'CLOSED', 'demo-user', 'demo-tech', 'demo-tech', now()-interval '1 day', now()-interval '7 days', now()-interval '3 hours', 1)
ON CONFLICT (operator_id, reference) DO UPDATE SET
  aircraft_id=EXCLUDED.aircraft_id, location=EXCLUDED.location, category=EXCLUDED.category, zone=EXCLUDED.zone,
  row_number=EXCLUDED.row_number, seat_reference=EXCLUDED.seat_reference, component=EXCLUDED.component,
  title=EXCLUDED.title, description=EXCLUDED.description, severity=EXCLUDED.severity, status=EXCLUDED.status,
  reported_by=EXCLUDED.reported_by, assigned_to=EXCLUDED.assigned_to, assigned_user_id=EXCLUDED.assigned_user_id,
  due_at=EXCLUDED.due_at, updated_at=EXCLUDED.updated_at, isactive=1;

UPDATE defects_dtl d SET area_id=a.id FROM cabin_areas_mst a
WHERE a.operator_id=d.operator_id AND a.code=d.location AND d.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND d.area_id IS NULL;

INSERT INTO inspections_dtl(id, operator_id, defect_id, inspector_user_id, status, result, assigned_at, inspected_at, findings, notes) VALUES
  ('33333333-3333-4333-8333-333333333302', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111102', 'demo-inspector', 'IN_PROGRESS', NULL, now()-interval '30 minutes', NULL, NULL, NULL),
  ('33333333-3333-4333-8333-333333333303', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111103', 'demo-inspector', 'COMPLETED', 'PASS', now()-interval '1 day', now()-interval '1 day', 'Leak source isolated to faucet cartridge.', 'Repair authorization recorded.'),
  ('33333333-3333-4333-8333-333333333304', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-inspector', 'COMPLETED', 'PASS', now()-interval '6 days', now()-interval '6 days', 'Indicator cover replaced and legible.', 'No additional findings.')
ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, result=EXCLUDED.result, inspected_at=EXCLUDED.inspected_at,
  findings=EXCLUDED.findings, notes=EXCLUDED.notes, isactive=1;

INSERT INTO corrective_actions_dtl(id, operator_id, defect_id, created_by_user_id, assigned_user_id, description, priority,
  due_at, status, completed_at, completed_by_user_id, completion_notes) VALUES
  ('44444444-4444-4444-8444-444444444403', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111103', 'demo-user', 'demo-tech', 'Replace faucet cartridge and complete leak check.', 'MEDIUM', now()-interval '2 hours', 'COMPLETED', now()-interval '2 hours', 'demo-tech', 'Cartridge replaced; tap cycled and checked.'),
  ('44444444-4444-4444-8444-444444444404', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-user', 'demo-tech', 'Replace indicator cover and confirm readability.', 'LOW', now()-interval '5 days', 'COMPLETED', now()-interval '4 days', 'demo-tech', 'Replacement installed and checked against cabin lighting.')
ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, due_at=EXCLUDED.due_at, completed_at=EXCLUDED.completed_at,
  completed_by_user_id=EXCLUDED.completed_by_user_id, completion_notes=EXCLUDED.completion_notes, isactive=1;

INSERT INTO verifications_dtl(id, operator_id, defect_id, verifier_user_id, result, notes, verified_at) VALUES
  ('55555555-5555-4555-8555-555555555504', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-quality', 'PASS', 'Independent visual and operational check passed.', now()-interval '2 days')
ON CONFLICT (id) DO UPDATE SET verifier_user_id=EXCLUDED.verifier_user_id, result=EXCLUDED.result,
  notes=EXCLUDED.notes, isactive=1;

INSERT INTO closure_approvals_dtl(id, operator_id, defect_id, approver_user_id, decision, reason, approved_at) VALUES
  ('66666666-6666-4666-8666-666666666604', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-user', 'APPROVED', 'Demo closure reviewed after independent verification.', now()-interval '1 day')
ON CONFLICT (id) DO UPDATE SET approver_user_id=EXCLUDED.approver_user_id, decision=EXCLUDED.decision,
  reason=EXCLUDED.reason, approved_at=EXCLUDED.approved_at, isactive=1;

WITH demo_events(defect_id, to_status, note) AS (VALUES
  ('11111111-1111-4111-8111-111111111101'::uuid, 'REPORTED', 'DEMO: Seat recline finding reported.'),
  ('11111111-1111-4111-8111-111111111102'::uuid, 'INSPECTION_IN_PROGRESS', 'DEMO: Galley latch assigned for cabin inspection.'),
  ('11111111-1111-4111-8111-111111111103'::uuid, 'AWAITING_VERIFICATION', 'DEMO: Corrective work completed; independent verification required.'),
  ('11111111-1111-4111-8111-111111111104'::uuid, 'CLOSED', 'DEMO: Verified and approved for closure.')
)
INSERT INTO defect_events_dtl(operator_id, defect_id, event_type, to_status, note, actor_id, entity_type, entity_id, new_state)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', demo_events.defect_id, 'DEMO_SEEDED', demo_events.to_status,
       demo_events.note, 'demo-user', 'DEFECT', demo_events.defect_id, jsonb_build_object('status', demo_events.to_status, 'demo', true)
FROM demo_events
WHERE NOT EXISTS (
  SELECT 1 FROM defect_events_dtl existing
  WHERE existing.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    AND existing.entity_id=demo_events.defect_id AND existing.event_type='DEMO_SEEDED'
);
