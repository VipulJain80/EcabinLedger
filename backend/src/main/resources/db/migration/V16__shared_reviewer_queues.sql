-- Team membership remains shared infrastructure; team type separates capabilities without duplicating memberships.
ALTER TABLE maintenance_teams ADD COLUMN team_type varchar(24) NOT NULL DEFAULT 'MAINTENANCE'
  CHECK (team_type IN ('MAINTENANCE','REVIEWER'));
ALTER TABLE defects ADD COLUMN reviewer_team_id uuid;
ALTER TABLE defects ADD CONSTRAINT defects_reviewer_team_tenant_fk
  FOREIGN KEY(operator_id, reviewer_team_id) REFERENCES maintenance_teams(operator_id, id);
ALTER TABLE operators ADD COLUMN default_reviewer_team_id uuid;
ALTER TABLE operators ADD CONSTRAINT operators_reviewer_team_tenant_fk
  FOREIGN KEY(id, default_reviewer_team_id) REFERENCES maintenance_teams(operator_id, id);

CREATE INDEX reviewer_teams_active_idx ON maintenance_teams(operator_id, team_type, isactive, name);
CREATE INDEX defects_reviewer_queue_idx ON defects(operator_id, reviewer_team_id, status, reported_at DESC, id)
  WHERE isactive=1;

-- Existing membership rows are intentionally retained as MAINTENANCE memberships. Reviewer
-- membership is granted explicitly by an administrator and never implies REVIEW permission.
