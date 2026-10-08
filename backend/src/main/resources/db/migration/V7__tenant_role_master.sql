CREATE TABLE operator_roles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  role_key varchar(48) NOT NULL,
  name varchar(100) NOT NULL,
  permissions varchar(500) NOT NULL,
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  system_role boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, id),
  UNIQUE(operator_id, role_key)
);
CREATE INDEX operator_roles_active_idx ON operator_roles(operator_id,isactive,name);
ALTER TABLE operator_memberships DROP CONSTRAINT operator_memberships_role_check;
ALTER TABLE operator_memberships ALTER COLUMN role TYPE varchar(48);
INSERT INTO navigation_menu_items(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'roles','Roles',p.id,'ADMINISTER_ORGANIZATION','','roles',100
FROM operators o JOIN navigation_menu_items p ON p.operator_id=o.id AND p.menu_key='configuration'
ON CONFLICT DO NOTHING;

INSERT INTO operator_roles(operator_id,role_key,name,permissions,system_role)
SELECT o.id,r.role_key,r.name,r.permissions,true
FROM operators o CROSS JOIN (VALUES
 ('ADMIN','Administrator','VIEW|REPORT|REVIEW|INSPECT|ASSIGN_ACTION|PERFORM_ACTION|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT|ADMINISTER_ORGANIZATION'),
 ('SUPERVISOR','Supervisor','VIEW|REPORT|REVIEW|INSPECT|ASSIGN_ACTION|PERFORM_ACTION|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT'),
 ('INSPECTOR','Inspector','VIEW|REPORT|INSPECT'),
 ('MAINTENANCE_TECHNICIAN','Maintenance Technician','VIEW|PERFORM_ACTION'),
 ('QUALITY_COMPLIANCE','Quality Compliance','VIEW|VERIFY|APPROVE|CLOSE|REOPEN|AUDIT'),
 ('VIEWER','Viewer','VIEW')
) AS r(role_key,name,permissions)
ON CONFLICT(operator_id,role_key) DO NOTHING;

ALTER TABLE operator_memberships ADD CONSTRAINT operator_memberships_role_fk
  FOREIGN KEY(operator_id, role) REFERENCES operator_roles(operator_id, role_key);
