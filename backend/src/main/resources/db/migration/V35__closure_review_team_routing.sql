-- Closure review routing is separate from the initial Reviewer route.
ALTER TABLE maintenance_teams_mst DROP CONSTRAINT IF EXISTS maintenance_teams_team_type_check;
ALTER TABLE maintenance_teams_mst ADD CONSTRAINT maintenance_teams_team_type_check
  CHECK (team_type IN ('MAINTENANCE','REVIEWER','CLOSURE'));

ALTER TABLE operators_mst ADD COLUMN default_closure_team_id uuid;
ALTER TABLE operators_mst ADD CONSTRAINT operators_closure_team_tenant_fk
  FOREIGN KEY (id, default_closure_team_id) REFERENCES maintenance_teams_mst(operator_id, id);
CREATE INDEX operators_closure_team_idx ON operators_mst(id, default_closure_team_id)
  WHERE default_closure_team_id IS NOT NULL;

-- No closure team is selected automatically: administrators must explicitly configure it.
