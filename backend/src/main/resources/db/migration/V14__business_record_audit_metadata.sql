-- Nullable creator/modifier identities preserve unknown historical actors. Existing timestamp columns
-- are reused; new timestamps have defaults only for future writes and are not backfilled.
ALTER TABLE operators ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE fleets ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE aircraft ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE operator_memberships ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE operator_membership_teams ADD COLUMN created_at timestamptz, ADD COLUMN updated_at timestamptz,
  ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE operator_membership_teams ALTER COLUMN created_at SET DEFAULT now(), ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE defects ADD COLUMN updated_by varchar(200);
ALTER TABLE inspections ADD COLUMN updated_at timestamptz, ADD COLUMN updated_by varchar(200);
ALTER TABLE inspections ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE corrective_actions ADD COLUMN updated_by varchar(200);
ALTER TABLE maintenance_teams ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE cabin_zones ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE defect_categories ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE cabin_components ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE cabin_areas ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE defect_conditions ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE component_conditions ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE navigation_menu_items ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE operator_roles ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);

ALTER TABLE attachments ADD COLUMN updated_at timestamptz, ADD COLUMN updated_by varchar(200);
ALTER TABLE attachments ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE notifications ADD COLUMN created_by varchar(200), ADD COLUMN updated_at timestamptz, ADD COLUMN updated_by varchar(200);
ALTER TABLE notifications ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE configuration_imports ADD COLUMN updated_at timestamptz, ADD COLUMN updated_by varchar(200);
ALTER TABLE configuration_imports ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE operational_forms ADD COLUMN created_by varchar(200), ADD COLUMN updated_by varchar(200);
ALTER TABLE operational_form_versions ADD COLUMN updated_at timestamptz, ADD COLUMN updated_by varchar(200);
ALTER TABLE operational_form_versions ALTER COLUMN updated_at SET DEFAULT now();

-- Transaction-local principal identity is set by write services. Values from HTTP payloads are never read.
CREATE FUNCTION maintain_business_audit_metadata() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE payload jsonb := to_jsonb(NEW); prior jsonb := to_jsonb(OLD); actor text := nullif(current_setting('ecabin.actor_id', true), ''); stamp timestamptz := now();
BEGIN
  IF TG_OP='INSERT' THEN
    IF payload ? 'created_at' AND payload->'created_at'='null'::jsonb THEN payload:=jsonb_set(payload,'{created_at}',to_jsonb(stamp)); END IF;
    IF payload ? 'created_by' AND actor IS NOT NULL THEN payload:=jsonb_set(payload,'{created_by}',to_jsonb(actor)); END IF;
    IF payload ? 'updated_at' THEN payload:=jsonb_set(payload,'{updated_at}',to_jsonb(stamp)); END IF;
    IF payload ? 'updated_by' AND actor IS NOT NULL THEN payload:=jsonb_set(payload,'{updated_by}',to_jsonb(actor)); END IF;
  ELSE
    -- Creator metadata is immutable even if a future SQL writer accidentally includes it in UPDATE.
    IF payload ? 'created_at' THEN payload:=jsonb_set(payload,'{created_at}',coalesce(prior->'created_at','null'::jsonb)); END IF;
    IF payload ? 'created_by' THEN payload:=jsonb_set(payload,'{created_by}',coalesce(prior->'created_by','null'::jsonb)); END IF;
    IF payload ? 'updated_at' THEN payload:=jsonb_set(payload,'{updated_at}',to_jsonb(stamp)); END IF;
    IF payload ? 'updated_by' AND actor IS NOT NULL THEN payload:=jsonb_set(payload,'{updated_by}',to_jsonb(actor)); END IF;
  END IF;
  RETURN jsonb_populate_record(NEW,payload);
END;
$$;

CREATE TRIGGER audit_operators BEFORE INSERT OR UPDATE ON operators FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_fleets BEFORE INSERT OR UPDATE ON fleets FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_aircraft BEFORE INSERT OR UPDATE ON aircraft FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_operator_memberships BEFORE INSERT OR UPDATE ON operator_memberships FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_operator_membership_teams BEFORE INSERT OR UPDATE ON operator_membership_teams FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_defects BEFORE INSERT OR UPDATE ON defects FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_inspections BEFORE INSERT OR UPDATE ON inspections FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_corrective_actions BEFORE INSERT OR UPDATE ON corrective_actions FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_maintenance_teams BEFORE INSERT OR UPDATE ON maintenance_teams FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_cabin_zones BEFORE INSERT OR UPDATE ON cabin_zones FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_defect_categories BEFORE INSERT OR UPDATE ON defect_categories FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_cabin_components BEFORE INSERT OR UPDATE ON cabin_components FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_cabin_areas BEFORE INSERT OR UPDATE ON cabin_areas FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_defect_conditions BEFORE INSERT OR UPDATE ON defect_conditions FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_component_conditions BEFORE INSERT OR UPDATE ON component_conditions FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_navigation_menu_items BEFORE INSERT OR UPDATE ON navigation_menu_items FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_operator_roles BEFORE INSERT OR UPDATE ON operator_roles FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_attachments BEFORE INSERT OR UPDATE ON attachments FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_notifications BEFORE INSERT OR UPDATE ON notifications FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_configuration_imports BEFORE INSERT OR UPDATE ON configuration_imports FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_operational_forms BEFORE INSERT OR UPDATE ON operational_forms FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();
CREATE TRIGGER audit_operational_form_versions BEFORE INSERT OR UPDATE ON operational_form_versions FOR EACH ROW EXECUTE FUNCTION maintain_business_audit_metadata();

-- These immutable history records keep their established event actor/time fields. defect_events,
-- form submissions, verifications and closure approvals are never modified to add redundant metadata.
