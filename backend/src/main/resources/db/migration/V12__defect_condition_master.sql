CREATE TABLE defect_conditions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  code varchar(48) NOT NULL,
  name varchar(120) NOT NULL,
  description varchar(500),
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id,id),
  UNIQUE(operator_id,code),
  CHECK (btrim(code) <> ''),
  CHECK (btrim(name) <> '')
);
CREATE INDEX defect_conditions_active_idx ON defect_conditions(operator_id,isactive,name);

CREATE TABLE component_conditions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  component_id uuid NOT NULL,
  condition_id uuid NOT NULL,
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id,id),
  FOREIGN KEY(operator_id,component_id) REFERENCES cabin_components(operator_id,id),
  FOREIGN KEY(operator_id,condition_id) REFERENCES defect_conditions(operator_id,id)
);
CREATE UNIQUE INDEX component_conditions_active_uq ON component_conditions(operator_id,component_id,condition_id) WHERE isactive=1;
CREATE INDEX component_conditions_component_idx ON component_conditions(operator_id,component_id,isactive,condition_id);
CREATE INDEX component_conditions_condition_idx ON component_conditions(operator_id,condition_id,isactive,component_id);

-- Historical defects stay valid and keep their original condition reference when a master is deactivated.
ALTER TABLE defects ADD COLUMN condition_id uuid;
ALTER TABLE defects ADD COLUMN condition_name_snapshot varchar(120);
ALTER TABLE defects ADD CONSTRAINT defects_condition_tenant_fk
  FOREIGN KEY(operator_id,condition_id) REFERENCES defect_conditions(operator_id,id);
CREATE INDEX defects_condition_idx ON defects(operator_id,condition_id) WHERE condition_id IS NOT NULL;

-- Add the new master to the existing permission-filtered Administration menu for provisioned tenants.
INSERT INTO navigation_menu_items(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'defect-conditions','Defect conditions',parent.id,'ADMINISTER_ORGANIZATION','', 'defect-conditions',100
FROM operators o
JOIN navigation_menu_items parent ON parent.operator_id=o.id AND parent.menu_key='configuration' AND parent.parent_id IS NULL
ON CONFLICT DO NOTHING;
