-- Soft-delete marker: 1 = active, 2 = soft-deleted. Audit events and Flyway history
-- deliberately remain immutable and are not soft-deleted.
ALTER TABLE operators ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE fleets ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));

DROP INDEX aircraft_fleet_idx;
ALTER TABLE aircraft ADD COLUMN isactive smallint;
UPDATE aircraft SET isactive = CASE WHEN active THEN 1 ELSE 2 END;
ALTER TABLE aircraft ALTER COLUMN isactive SET DEFAULT 1;
ALTER TABLE aircraft ALTER COLUMN isactive SET NOT NULL;
ALTER TABLE aircraft ADD CONSTRAINT aircraft_isactive_check CHECK (isactive IN (1, 2));
ALTER TABLE aircraft DROP COLUMN active;
CREATE INDEX aircraft_fleet_idx ON aircraft(operator_id, fleet_id, isactive, tail_number);

DROP INDEX memberships_external_user_idx;
ALTER TABLE operator_memberships ADD COLUMN isactive smallint;
UPDATE operator_memberships SET isactive = CASE WHEN active THEN 1 ELSE 2 END;
ALTER TABLE operator_memberships ALTER COLUMN isactive SET DEFAULT 1;
ALTER TABLE operator_memberships ALTER COLUMN isactive SET NOT NULL;
ALTER TABLE operator_memberships ADD CONSTRAINT memberships_isactive_check CHECK (isactive IN (1, 2));
ALTER TABLE operator_memberships DROP COLUMN active;
CREATE INDEX memberships_external_user_idx ON operator_memberships(external_user_id, isactive);

ALTER TABLE operator_membership_teams ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE defects ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE inspections ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE corrective_actions ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE verifications ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE closure_approvals ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE attachments ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));
ALTER TABLE notifications ADD COLUMN isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2));

CREATE INDEX defects_active_timeline_idx ON defects(operator_id, reported_at DESC, id DESC) WHERE isactive=1;
DROP INDEX defects_due_idx;
CREATE INDEX defects_due_idx ON defects(operator_id, due_at) WHERE isactive=1 AND due_at IS NOT NULL AND status NOT IN ('CLOSED','CANCELLED');
DROP INDEX corrective_actions_due_idx;
CREATE INDEX corrective_actions_due_idx ON corrective_actions(operator_id, due_at) WHERE isactive=1 AND due_at IS NOT NULL AND status NOT IN ('COMPLETED','CANCELLED');
