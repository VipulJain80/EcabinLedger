CREATE TABLE navigation_menu_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators(id),
  menu_key varchar(80) NOT NULL,
  label varchar(120) NOT NULL,
  parent_id uuid,
  required_permissions varchar(240) NOT NULL,
  icon varchar(32) NOT NULL DEFAULT '',
  route_key varchar(80) NOT NULL,
  display_order integer NOT NULL DEFAULT 0 CHECK (display_order BETWEEN 0 AND 9999),
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(operator_id, id),
  CONSTRAINT navigation_parent_same_operator_fk FOREIGN KEY (operator_id,parent_id)
    REFERENCES navigation_menu_items(operator_id,id)
);
CREATE INDEX navigation_menu_active_order_idx ON navigation_menu_items(operator_id,isactive,display_order);
CREATE UNIQUE INDEX navigation_menu_sibling_key_uq ON navigation_menu_items(operator_id,COALESCE(parent_id,'00000000-0000-0000-0000-000000000000'::uuid),menu_key);

INSERT INTO navigation_menu_items(operator_id,menu_key,label,required_permissions,icon,route_key,display_order)
SELECT o.id,m.key,m.label,m.permissions,m.icon,m.route,m.position
FROM operators o CROSS JOIN (VALUES
 ('home','Home','VIEW','⌂','home',10),
 ('operations','Operations','VIEW','▦','operations',20),
 ('reports','Reports','VIEW','▤','reports',30),
 ('audit','Audit log','AUDIT','≡','audit',40),
 ('configuration','Administration','ADMINISTER_ORGANIZATION','⚙','configuration',50)
) AS m(key,label,permissions,icon,route,position)
ON CONFLICT DO NOTHING;

INSERT INTO navigation_menu_items(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,m.key,m.label,p.id,m.permissions,m.icon,m.route,m.position
FROM operators o
JOIN navigation_menu_items p ON p.operator_id=o.id AND p.menu_key='operations'
CROSS JOIN (VALUES
 ('defects','Defect register','VIEW','▦','defects',10),
 ('aircraft','Aircraft','VIEW','✈','fleet',20),
 ('inspections','Inspections','INSPECT','◷','operations',30),
 ('corrective-actions','Corrective actions','ASSIGN_ACTION|PERFORM_ACTION','⚒','operations',40),
 ('verification','Verification & approval','VERIFY|APPROVE|CLOSE','✓','quality',50)
) AS m(key,label,permissions,icon,route,position)
ON CONFLICT DO NOTHING;

INSERT INTO navigation_menu_items(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,m.key,m.label,p.id,'ADMINISTER_ORGANIZATION','',m.route,m.position
FROM operators o
JOIN navigation_menu_items p ON p.operator_id=o.id AND p.menu_key='configuration'
CROSS JOIN (VALUES
 ('organizations','Organization','organizations',10),
 ('fleets','Fleets','fleets',20),
 ('aircraft','Aircraft','aircraft',30),
 ('cabin-zones','Cabin zones','cabin-zones',40),
 ('defect-categories','Defect categories','defect-categories',50),
 ('components','Components','components',60),
 ('maintenance-teams','Maintenance teams','maintenance-teams',70),
 ('users','Users and roles','users',80),
 ('menu-items','Menus & submenus','menu-items',90)
) AS m(key,label,route,position)
ON CONFLICT DO NOTHING;
