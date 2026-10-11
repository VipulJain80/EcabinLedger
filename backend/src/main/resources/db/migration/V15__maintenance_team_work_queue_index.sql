-- Supports tenant-scoped shared work queues without scanning unrelated defects.
CREATE INDEX defects_team_work_queue_idx
  ON defects(operator_id, assigned_team, status, reported_at DESC, id)
  WHERE isactive=1 AND assigned_team IS NOT NULL;
