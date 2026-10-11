-- Tenant-managed severity labels. Codes remain the stable compatibility value on defects.
ALTER TABLE operator_roles_mst ADD COLUMN description varchar(500);
UPDATE operator_roles_mst SET description=CASE role_key
  WHEN 'ADMIN' THEN 'Organization-wide administration and operational access. Scope remains limited to this organization; this is not platform Superadmin access.'
  WHEN 'SUPERVISOR' THEN 'Coordinates organization workflows across review, inspection, maintenance, verification, approval, closure, reopening, and audit. Does not administer organization configuration.'
  WHEN 'INSPECTOR' THEN 'Reports defects and performs assigned inspections. Does not receive maintenance, review, verification, approval, or closure authority.'
  WHEN 'MAINTENANCE_TECHNICIAN' THEN 'Performs eligible corrective actions assigned to active maintenance teams. Team membership and workflow state are independently required.'
  WHEN 'QUALITY_COMPLIANCE' THEN 'Performs verification, approval, closure, reopening, and audit operations allowed by the current workflow. Does not automatically receive review or maintenance-team access.'
  WHEN 'VIEWER' THEN 'Reads organization-visible records only. This role does not create or advance operational records.'
  ELSE 'Organization-defined role. Access is limited to the permissions listed in its role mapping and the backend tenant, team, ownership, and workflow checks.'
END WHERE description IS NULL OR btrim(description)='';

CREATE FUNCTION default_operator_role_description() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.description IS NULL OR btrim(NEW.description)='' THEN
    NEW.description:=CASE NEW.role_key
      WHEN 'ADMIN' THEN 'Organization-wide administration and operational access. Scope remains limited to this organization; this is not platform Superadmin access.'
      WHEN 'SUPERVISOR' THEN 'Coordinates organization workflows across review, inspection, maintenance, verification, approval, closure, reopening, and audit. Does not administer organization configuration.'
      WHEN 'INSPECTOR' THEN 'Reports defects and performs assigned inspections. Does not receive maintenance, review, verification, approval, or closure authority.'
      WHEN 'MAINTENANCE_TECHNICIAN' THEN 'Performs eligible corrective actions assigned to active maintenance teams. Team membership and workflow state are independently required.'
      WHEN 'QUALITY_COMPLIANCE' THEN 'Performs verification, approval, closure, reopening, and audit operations allowed by the current workflow. Does not automatically receive review or maintenance-team access.'
      WHEN 'VIEWER' THEN 'Reads organization-visible records only. This role does not create or advance operational records.'
      ELSE 'Organization-defined role. Access is limited to the permissions listed in its role mapping and the backend tenant, team, ownership, and workflow checks.'
    END;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER role_description_default_before_write BEFORE INSERT OR UPDATE OF role_key,description ON operator_roles_mst
FOR EACH ROW EXECUTE FUNCTION default_operator_role_description();

CREATE TABLE defect_severities_mst (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id uuid NOT NULL REFERENCES operators_mst(id),
  severity_code varchar(24) NOT NULL,
  severity_name varchar(100) NOT NULL,
  description varchar(500) NOT NULL,
  sort_order integer NOT NULL DEFAULT 100 CHECK (sort_order BETWEEN 0 AND 9999),
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by varchar(200),
  updated_by varchar(200),
  UNIQUE(operator_id,id),
  UNIQUE(operator_id,severity_code)
);
CREATE INDEX defect_severities_active_idx ON defect_severities_mst(operator_id,isactive,sort_order,severity_name);
CREATE TRIGGER audit_defect_severities BEFORE INSERT OR UPDATE ON defect_severities_mst FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();

-- Starter labels are product defaults only, not approved aviation risk thresholds.
INSERT INTO defect_severities_mst(operator_id,severity_code,severity_name,description,sort_order)
SELECT o.id,v.code,v.name,v.description,v.sort_order
FROM operators_mst o
CROSS JOIN (VALUES
  ('LOW','Low','Starter display label only. Not an airworthiness classification or maintenance threshold.',10),
  ('MEDIUM','Medium','Starter display label only. Not an airworthiness classification or maintenance threshold.',20),
  ('HIGH','High','Starter display label only. Not an airworthiness classification or maintenance threshold.',30),
  ('CRITICAL','Critical','Starter display label only. Not an airworthiness classification or maintenance threshold.',40)
) AS v(code,name,description,sort_order)
ON CONFLICT(operator_id,severity_code) DO NOTHING;

-- New tenant seed is additive and cannot overwrite administrator-managed values.
CREATE FUNCTION seed_operator_default_severities() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO defect_severities_mst(operator_id,severity_code,severity_name,description,sort_order)
  VALUES
    (NEW.id,'LOW','Low','Starter display label only. Not an airworthiness classification or maintenance threshold.',10),
    (NEW.id,'MEDIUM','Medium','Starter display label only. Not an airworthiness classification or maintenance threshold.',20),
    (NEW.id,'HIGH','High','Starter display label only. Not an airworthiness classification or maintenance threshold.',30),
    (NEW.id,'CRITICAL','Critical','Starter display label only. Not an airworthiness classification or maintenance threshold.',40)
  ON CONFLICT(operator_id,severity_code) DO NOTHING;
  RETURN NEW;
END $$;
CREATE TRIGGER operators_seed_default_severities AFTER INSERT ON operators_mst
FOR EACH ROW EXECUTE FUNCTION seed_operator_default_severities();

-- Keep old severity text snapshots valid while linking known records to their tenant master row.
DO $$
DECLARE constraint_name text;
BEGIN
  FOR constraint_name IN
    SELECT conname FROM pg_constraint
    WHERE conrelid='defects_dtl'::regclass AND contype='c'
      AND pg_get_constraintdef(oid) ILIKE '%severity%'
  LOOP
    EXECUTE format('ALTER TABLE defects_dtl DROP CONSTRAINT %I', constraint_name);
  END LOOP;
END $$;
ALTER TABLE defects_dtl ALTER COLUMN severity TYPE varchar(24);
ALTER TABLE defects_dtl ADD CONSTRAINT defects_severity_code_nonblank CHECK (length(btrim(severity)) BETWEEN 1 AND 24);
ALTER TABLE defects_dtl ADD COLUMN severity_id uuid;
UPDATE defects_dtl d SET severity_id=s.id
FROM defect_severities_mst s
WHERE s.operator_id=d.operator_id AND s.severity_code=d.severity;
ALTER TABLE defects_dtl ADD CONSTRAINT defects_severity_tenant_fk
  FOREIGN KEY(operator_id,severity_id) REFERENCES defect_severities_mst(operator_id,id);
CREATE INDEX defects_severity_tenant_idx ON defects_dtl(operator_id,severity_id,reported_at DESC) WHERE severity_id IS NOT NULL;

-- The Java Permission enum and operator_roles_mst.permissions remain the authorization
-- authorities. This global read-only catalog documents that fixed supported set.
CREATE TABLE permissions_mst (
  permission_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  permission_code varchar(48) NOT NULL UNIQUE,
  permission_name varchar(100) NOT NULL,
  description varchar(1200) NOT NULL,
  module varchar(80) NOT NULL,
  is_system_permission boolean NOT NULL DEFAULT true,
  isactive smallint NOT NULL DEFAULT 1 CHECK (isactive IN (1,2)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT permissions_mst_system_active CHECK (is_system_permission OR isactive IN (1,2))
);
CREATE INDEX permissions_mst_active_module_idx ON permissions_mst(isactive,module,permission_code);

INSERT INTO permissions_mst(permission_code,permission_name,description,module,is_system_permission)
VALUES
('VIEW','View operational records','Allows reading organization-visible aircraft, fleet, defect and operational reference data. It does not grant mutation rights; report-only, reviewer and maintenance data remains limited by the backend ownership and active team scope.','Defects',true),
('REPORT','Report defects','Allows creating a defect report and submitting its configured report form. It does not grant review, inspection, maintenance, verification, approval, closure, or unrestricted access to other reporters’ defects.','Defects',true),
('REVIEW','Review defects','Allows eligible review decisions and reviewer routing operations. The actor must also have an active membership in the currently routed eligible Reviewer team; this does not grant inspection, maintenance, verification, approval, closure, or organization administration.','Review',true),
('INSPECT','Perform inspections','Allows assigned inspection workflow operations and recording inspection results. Inspection assignment, tenant, and current defect state are checked by the service; this does not grant corrective-action performance, independent verification, approval, or closure.','Inspections',true),
('ASSIGN_ACTION','Assign corrective actions','Allows creating or reassigning corrective actions at valid workflow stages. Team, assignee membership, tenant, and workflow state are checked separately; this does not grant permission to perform or complete the work.','Maintenance',true),
('PERFORM_ACTION','Perform corrective actions','Allows starting and completing eligible corrective actions. Access is limited to active team-assigned work and ownership rules; team membership alone grants no permission and this does not grant review, verification, approval, closure, or administration.','Maintenance',true),
('VERIFY','Verify completed work','Allows recording independent verification outcomes at the required workflow stage. The service enforces workflow and separation-of-duties rules; this does not automatically approve or close a defect.','Verification',true),
('APPROVE','Approve closure readiness','Allows recording an approval decision where the workflow requires approval. Current status and separation-of-duties checks remain enforced; this does not itself close the defect or grant organization administration.','Approval',true),
('CLOSE','Close defects','Allows closing a defect only when required inspection, corrective action, verification, and approval conditions are met. It does not bypass those workflow gates or grant reopening rights.','Defects',true),
('REOPEN','Reopen closed defects','Allows a supported reopen transition with the required reason and audit event. Tenant scope, current state, and workflow checks remain enforced; this does not grant general edit or administration rights.','Defects',true),
('AUDIT','View audit history','Allows reading audit records for resources visible under the user’s applicable organization and workflow scope. It does not grant mutation or tenant-wide access to records otherwise restricted by team/ownership rules.','Audit',true),
('ADMINISTER_ORGANIZATION','Administer organization configuration','Allows organization-scoped configuration APIs, including supported master data, memberships, roles, and menus. It is not platform-wide Superadmin authority and cannot create or assign the reserved SUPERADMIN identity.','Administration',true)
ON CONFLICT(permission_code) DO NOTHING;

-- Expose the three administration views using the existing permission-filtered menu.
INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,v.menu_key,v.label,config.id,'ADMINISTER_ORGANIZATION','',v.route_key,v.display_order
FROM operators_mst o
JOIN navigation_menu_items_mst config ON config.operator_id=o.id AND config.menu_key='configuration'
CROSS JOIN (VALUES
  ('severities','Severity Master','severities',110),
  ('permissions','Permission Master','permissions',120),
  ('role-permissions','Role-Permission Mapping','role-permissions',130)
) AS v(menu_key,label,route_key,display_order)
ON CONFLICT DO NOTHING;

-- If a tenant is provisioned after this migration and its normal navigation seed
-- creates the Administration root, attach these same views to that root.
CREATE FUNCTION seed_operator_severity_permission_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.menu_key='configuration' AND NEW.parent_id IS NULL THEN
    INSERT INTO navigation_menu_items_mst(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
    VALUES
      (NEW.operator_id,'severities','Severity Master',NEW.id,'ADMINISTER_ORGANIZATION','','severities',110),
      (NEW.operator_id,'permissions','Permission Master',NEW.id,'ADMINISTER_ORGANIZATION','','permissions',120),
      (NEW.operator_id,'role-permissions','Role-Permission Mapping',NEW.id,'ADMINISTER_ORGANIZATION','','role-permissions',130)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER navigation_seed_severity_permissions AFTER INSERT ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION seed_operator_severity_permission_navigation();
