-- Development/demo only. All aircraft registrations and operational data below are synthetic.
-- Skyways Service is fictional demo branding. Never run in production.
INSERT INTO operators(id, name) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Skyways Service (DEMO)')
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, isactive=1;

INSERT INTO operator_memberships(operator_id, external_user_id, display_name, role, isactive) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-user', 'Alex Morgan (DEMO)', 'SUPERVISOR', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'abc@gmail.com', 'abc@gmail.com (LOCAL DEMO)', 'ADMIN', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-inspector', 'Riya Shah (DEMO)', 'INSPECTOR', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-tech', 'Arun Das (DEMO)', 'MAINTENANCE_TECHNICIAN', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-quality', 'Meera Rao (DEMO)', 'QUALITY_COMPLIANCE', 1)
ON CONFLICT (operator_id, external_user_id) DO UPDATE
SET display_name=EXCLUDED.display_name, role=EXCLUDED.role, isactive=1;

INSERT INTO fleets(id, operator_id, name) VALUES
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'General Fleet')
ON CONFLICT (operator_id, name) DO NOTHING;

INSERT INTO defect_categories(operator_id, code, name)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', c.code, c.name FROM (VALUES
  ('CABIN','Cabin'),('GALLEY','Galley'),('LAVATORY','Lavatory'),('ATTENDANT_SEAT','Attendant seat'),
  ('IFE','In-flight entertainment'),('SEAT','Passenger seat'),('LIGHTING','Lighting'),
  ('OVERHEAD_BIN','Overhead bin'),('PSU','Passenger service unit'),('EMERGENCY_EQUIPMENT','Emergency equipment'),('OTHER','Other')
) AS c(code,name) ON CONFLICT(operator_id, code) DO NOTHING;

INSERT INTO cabin_zones(operator_id, code, name, area)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', z.code, z.name, z.area FROM (VALUES
  ('FWD_CABIN','FWD CABIN','CABIN'),('AFT_CABIN','AFT CABIN','CABIN'),('ROW','Row','CABIN'),('SEAT','Seat','CABIN'),
  ('L1','L1','CABIN'),('L2','L2','CABIN'),('R1','R1','CABIN'),('R2','R2','CABIN'),
  ('FWD_GALLEY','FWD GALLEY','GALLEY'),('AFT_GALLEY','AFT GALLEY','GALLEY'),
  ('FWD_LAV','FWD LAV','LAVATORY'),('AFT_LAV','AFT LAV','LAVATORY'),('ATTENDANT_SEAT','Attendant seat','ATTENDANT_SEAT')
) AS z(code,name,area) ON CONFLICT(operator_id, code) DO NOTHING;

INSERT INTO cabin_components(operator_id, code, name)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', c.code, c.name FROM (VALUES
  ('SEAT','Passenger seat'),('IFE_DISPLAY','IFE display'),('READING_LIGHT','Reading light'),
  ('OVERHEAD_BIN_LATCH','Overhead bin latch'),('PSU','Passenger service unit'),
  ('GALLEY_CART_LATCH','Galley cart latch'),('LAVATORY_FAUCET','Lavatory faucet'),('ATTENDANT_SEAT_BELT','Attendant seat belt')
) AS c(code,name) ON CONFLICT(operator_id, code) DO NOTHING;

INSERT INTO maintenance_teams(operator_id, name) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Cabin Maintenance (DEMO)'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Cabin Quality (DEMO)')
ON CONFLICT(operator_id, name) DO NOTHING;

INSERT INTO operator_membership_teams(operator_id, external_user_id, team_name, isactive) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-tech', 'Cabin Maintenance (DEMO)', 1),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-quality', 'Cabin Quality (DEMO)', 1)
ON CONFLICT (operator_id, external_user_id, team_name) DO UPDATE SET isactive=1;

INSERT INTO aircraft(id, operator_id, fleet_id, tail_number, aircraft_type, isactive) VALUES
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', (SELECT id FROM fleets WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='General Fleet'), 'DEMO-A320-01', 'A320-200', 1),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', (SELECT id FROM fleets WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='General Fleet'), 'DEMO-B737-01', 'B737-800', 1),
  ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', (SELECT id FROM fleets WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND name='General Fleet'), 'DEMO-A320-02', 'A320-200', 1)
ON CONFLICT (id) DO UPDATE
SET fleet_id=EXCLUDED.fleet_id, tail_number=EXCLUDED.tail_number, aircraft_type=EXCLUDED.aircraft_type, isactive=1;

INSERT INTO defects(id, operator_id, aircraft_id, reference, location, category, zone, row_number, seat_reference, component,
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

INSERT INTO inspections(id, operator_id, defect_id, inspector_user_id, status, result, assigned_at, inspected_at, findings, notes) VALUES
  ('33333333-3333-4333-8333-333333333302', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111102', 'demo-inspector', 'IN_PROGRESS', NULL, now()-interval '30 minutes', NULL, NULL, NULL),
  ('33333333-3333-4333-8333-333333333303', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111103', 'demo-inspector', 'COMPLETED', 'PASS', now()-interval '1 day', now()-interval '1 day', 'Leak source isolated to faucet cartridge.', 'Repair authorization recorded.'),
  ('33333333-3333-4333-8333-333333333304', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-inspector', 'COMPLETED', 'PASS', now()-interval '6 days', now()-interval '6 days', 'Indicator cover replaced and legible.', 'No additional findings.')
ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, result=EXCLUDED.result, inspected_at=EXCLUDED.inspected_at,
  findings=EXCLUDED.findings, notes=EXCLUDED.notes, isactive=1;

INSERT INTO corrective_actions(id, operator_id, defect_id, created_by_user_id, assigned_user_id, description, priority,
  due_at, status, completed_at, completed_by_user_id, completion_notes) VALUES
  ('44444444-4444-4444-8444-444444444403', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111103', 'demo-user', 'demo-tech', 'Replace faucet cartridge and complete leak check.', 'MEDIUM', now()-interval '2 hours', 'COMPLETED', now()-interval '2 hours', 'demo-tech', 'Cartridge replaced; tap cycled and checked.'),
  ('44444444-4444-4444-8444-444444444404', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-user', 'demo-tech', 'Replace indicator cover and confirm readability.', 'LOW', now()-interval '5 days', 'COMPLETED', now()-interval '4 days', 'demo-tech', 'Replacement installed and checked against cabin lighting.')
ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, due_at=EXCLUDED.due_at, completed_at=EXCLUDED.completed_at,
  completed_by_user_id=EXCLUDED.completed_by_user_id, completion_notes=EXCLUDED.completion_notes, isactive=1;

INSERT INTO verifications(id, operator_id, defect_id, verifier_user_id, result, notes, verified_at) VALUES
  ('55555555-5555-4555-8555-555555555504', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-quality', 'PASS', 'Independent visual and operational check passed.', now()-interval '2 days')
ON CONFLICT (id) DO UPDATE SET verifier_user_id=EXCLUDED.verifier_user_id, result=EXCLUDED.result,
  notes=EXCLUDED.notes, isactive=1;

INSERT INTO closure_approvals(id, operator_id, defect_id, approver_user_id, decision, reason, approved_at) VALUES
  ('66666666-6666-4666-8666-666666666604', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111104', 'demo-user', 'APPROVED', 'Demo closure reviewed after independent verification.', now()-interval '1 day')
ON CONFLICT (id) DO UPDATE SET approver_user_id=EXCLUDED.approver_user_id, decision=EXCLUDED.decision,
  reason=EXCLUDED.reason, approved_at=EXCLUDED.approved_at, isactive=1;

WITH demo_events(defect_id, to_status, note) AS (VALUES
  ('11111111-1111-4111-8111-111111111101'::uuid, 'REPORTED', 'DEMO: Seat recline finding reported.'),
  ('11111111-1111-4111-8111-111111111102'::uuid, 'INSPECTION_IN_PROGRESS', 'DEMO: Galley latch assigned for cabin inspection.'),
  ('11111111-1111-4111-8111-111111111103'::uuid, 'AWAITING_VERIFICATION', 'DEMO: Corrective work completed; independent verification required.'),
  ('11111111-1111-4111-8111-111111111104'::uuid, 'CLOSED', 'DEMO: Verified and approved for closure.')
)
INSERT INTO defect_events(operator_id, defect_id, event_type, to_status, note, actor_id, entity_type, entity_id, new_state)
SELECT 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', demo_events.defect_id, 'DEMO_SEEDED', demo_events.to_status,
       demo_events.note, 'demo-user', 'DEFECT', demo_events.defect_id, jsonb_build_object('status', demo_events.to_status, 'demo', true)
FROM demo_events
WHERE NOT EXISTS (
  SELECT 1 FROM defect_events existing
  WHERE existing.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    AND existing.entity_id=demo_events.defect_id AND existing.event_type='DEMO_SEEDED'
);
