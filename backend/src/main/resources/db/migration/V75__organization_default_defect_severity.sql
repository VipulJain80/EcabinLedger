-- Existing organizations receive no designated default. New defect reports fall back to the
-- first active severity in the established API order until an administrator selects one.
ALTER TABLE defect_severities_mst
  ADD COLUMN is_default boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT defect_severities_default_requires_active_ck
    CHECK (NOT is_default OR isactive = 1);

-- The partial unique index enforces at most one default per organization, including concurrent writes.
CREATE UNIQUE INDEX defect_severities_one_default_per_operator_uidx
  ON defect_severities_mst(operator_id)
  WHERE is_default;
