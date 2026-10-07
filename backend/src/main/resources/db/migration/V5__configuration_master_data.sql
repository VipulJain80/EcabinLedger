ALTER TABLE operators ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE fleets ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE aircraft ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE defects DROP CONSTRAINT defects_category_check;

CREATE TABLE maintenance_teams (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  name varchar(120) NOT NULL,
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, id),
  UNIQUE(operator_id, name)
);
CREATE INDEX maintenance_teams_active_idx ON maintenance_teams(operator_id, isactive, name);

INSERT INTO maintenance_teams(operator_id, name)
SELECT DISTINCT operator_id, team_name FROM operator_membership_teams
WHERE btrim(team_name) <> '' ON CONFLICT(operator_id, name) DO NOTHING;
INSERT INTO maintenance_teams(operator_id, name)
SELECT DISTINCT operator_id, assigned_team FROM corrective_actions
WHERE assigned_team IS NOT NULL AND btrim(assigned_team) <> '' ON CONFLICT(operator_id, name) DO NOTHING;

CREATE TABLE cabin_zones (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  code varchar(48) NOT NULL,
  name varchar(100) NOT NULL,
  area varchar(24) NOT NULL CHECK (area IN ('CABIN','GALLEY','LAVATORY','ATTENDANT_SEAT')),
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, id),
  UNIQUE(operator_id, code)
);
CREATE INDEX cabin_zones_active_idx ON cabin_zones(operator_id, isactive, area, name);

CREATE TABLE defect_categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  code varchar(32) NOT NULL,
  name varchar(100) NOT NULL,
  description varchar(500),
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, id),
  UNIQUE(operator_id, code)
);
CREATE INDEX defect_categories_active_idx ON defect_categories(operator_id, isactive, name);

CREATE TABLE cabin_components (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  code varchar(48) NOT NULL,
  name varchar(120) NOT NULL,
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1, 2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, id),
  UNIQUE(operator_id, code)
);
CREATE INDEX cabin_components_active_idx ON cabin_components(operator_id, isactive, name);

INSERT INTO defect_categories(operator_id, code, name)
SELECT o.id, c.code, c.name FROM operators o CROSS JOIN (VALUES
  ('CABIN','Cabin'),('GALLEY','Galley'),('LAVATORY','Lavatory'),('ATTENDANT_SEAT','Attendant seat'),
  ('IFE','In-flight entertainment'),('SEAT','Passenger seat'),('LIGHTING','Lighting'),
  ('OVERHEAD_BIN','Overhead bin'),('PSU','Passenger service unit'),('EMERGENCY_EQUIPMENT','Emergency equipment'),('OTHER','Other')
) AS c(code,name) ON CONFLICT(operator_id, code) DO NOTHING;

INSERT INTO cabin_zones(operator_id, code, name, area)
SELECT o.id, z.code, z.name, z.area FROM operators o CROSS JOIN (VALUES
  ('FWD_CABIN','FWD CABIN','CABIN'),('AFT_CABIN','AFT CABIN','CABIN'),('ROW','Row','CABIN'),('SEAT','Seat','CABIN'),
  ('L1','L1','CABIN'),('L2','L2','CABIN'),('R1','R1','CABIN'),('R2','R2','CABIN'),
  ('FWD_GALLEY','FWD GALLEY','GALLEY'),('AFT_GALLEY','AFT GALLEY','GALLEY'),
  ('FWD_LAV','FWD LAV','LAVATORY'),('AFT_LAV','AFT LAV','LAVATORY'),('ATTENDANT_SEAT','Attendant seat','ATTENDANT_SEAT')
) AS z(code,name,area) ON CONFLICT(operator_id, code) DO NOTHING;

INSERT INTO cabin_components(operator_id, code, name)
SELECT o.id, c.code, c.name FROM operators o CROSS JOIN (VALUES
  ('SEAT','Passenger seat'),('IFE_DISPLAY','IFE display'),('READING_LIGHT','Reading light'),
  ('OVERHEAD_BIN_LATCH','Overhead bin latch'),('PSU','Passenger service unit'),
  ('GALLEY_CART_LATCH','Galley cart latch'),('LAVATORY_FAUCET','Lavatory faucet'),('ATTENDANT_SEAT_BELT','Attendant seat belt')
) AS c(code,name) ON CONFLICT(operator_id, code) DO NOTHING;
