-- Workflow enum values and inspection outcomes must fit without truncation.
ALTER TABLE defect_events ALTER COLUMN from_status TYPE varchar(32);
ALTER TABLE defect_events ALTER COLUMN to_status TYPE varchar(32);
ALTER TABLE defect_events ALTER COLUMN inspection_outcome TYPE varchar(24);
ALTER TABLE defect_events ALTER COLUMN note TYPE varchar(4000);
