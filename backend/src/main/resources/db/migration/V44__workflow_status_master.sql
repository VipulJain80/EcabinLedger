CREATE TABLE workflow_status_master (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_code VARCHAR(50) NOT NULL,
    status_code VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    alias_name VARCHAR(100),
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL,
    is_initial BOOLEAN NOT NULL DEFAULT FALSE,
    is_terminal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT workflow_status_module_code_uq UNIQUE (module_code, status_code),
    CONSTRAINT workflow_status_module_code_ck CHECK (module_code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT workflow_status_code_ck CHECK (status_code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT workflow_status_sort_order_ck CHECK (sort_order >= 0)
);

CREATE INDEX workflow_status_module_active_order_idx
    ON workflow_status_master (module_code, is_active, sort_order, display_name);

-- Seed only missing codes so rerunning the seed never replaces maintained labels,
-- aliases, UUIDs, descriptions, or activity state.
INSERT INTO workflow_status_master
    (module_code, status_code, display_name, alias_name, description, is_active, sort_order, is_initial, is_terminal)
VALUES
    ('DEFECT','REPORTED','Reported','New','A defect has been submitted and is awaiting review.',TRUE,1,TRUE,FALSE),
    ('DEFECT','UNDER_REVIEW','Under Review','Review Pending','A Reviewer is assessing the submitted defect.',TRUE,2,FALSE,FALSE),
    ('DEFECT','INSPECTION_REQUIRED','Inspection Required','Requires Inspection','An inspection is required before corrective work.',TRUE,3,FALSE,FALSE),
    ('DEFECT','INSPECTION_IN_PROGRESS','Inspection In Progress','Inspecting','An inspection is currently in progress.',TRUE,4,FALSE,FALSE),
    ('DEFECT','INSPECTION_COMPLETE','Inspection Complete','Inspection Done','The inspection has been completed.',TRUE,5,FALSE,FALSE),
    ('DEFECT','ACTION_ASSIGNED','Action Assigned','Assigned','Corrective work has been assigned to Maintenance.',TRUE,6,FALSE,FALSE),
    ('DEFECT','IN_PROGRESS','In Progress','Processing','Corrective work is in progress.',TRUE,7,FALSE,FALSE),
    ('DEFECT','AWAITING_VERIFICATION','Awaiting Verification','Pending Verification','Corrective work is awaiting verification.',TRUE,8,FALSE,FALSE),
    ('DEFECT','VERIFIED','Verified',NULL,'Corrective work has passed verification.',TRUE,9,FALSE,FALSE),
    ('DEFECT','APPROVAL_REQUIRED','Approval Required','Pending Approval','The closure request is awaiting Closure Team review.',TRUE,10,FALSE,FALSE),
    ('DEFECT','CLOSED','Closed','Completed','The defect has been closed; the workflow permits reopening.',TRUE,11,FALSE,FALSE),
    ('DEFECT','REJECTED','Rejected','Declined','The defect review was rejected.',TRUE,12,FALSE,TRUE),
    ('DEFECT','CANCELLED','Cancelled','Withdrawn','The defect was cancelled.',TRUE,13,FALSE,TRUE),
    ('DEFECT','REOPENED','Reopened',NULL,'A previously closed defect has been reopened.',TRUE,14,FALSE,FALSE)
ON CONFLICT (module_code, status_code) DO NOTHING;

-- Metadata controls which codes may be newly assigned. The Java workflow graph,
-- role checks and business operations remain the authority for legal transitions.
CREATE FUNCTION enforce_active_defect_status_master() RETURNS trigger AS $$
DECLARE
    status_is_active BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.workflow_status_master WHERE module_code = ''DEFECT'' AND status_code = $1 AND is_active = TRUE)', TG_TABLE_SCHEMA)
            INTO status_is_active USING NEW.status;
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.workflow_status_master WHERE module_code = ''DEFECT'' AND status_code = $1 AND is_active = TRUE)', TG_TABLE_SCHEMA)
            INTO status_is_active USING NEW.status;
    ELSE
        RETURN NEW;
    END IF;
    IF NOT COALESCE(status_is_active, FALSE) THEN
        RAISE EXCEPTION 'Defect status % is not active in Workflow Status Master.', NEW.status
            USING ERRCODE = '23514', CONSTRAINT = 'defects_status_master_active_ck';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER defects_status_master_active
    BEFORE INSERT OR UPDATE ON defects_dtl
    FOR EACH ROW EXECUTE FUNCTION enforce_active_defect_status_master();
