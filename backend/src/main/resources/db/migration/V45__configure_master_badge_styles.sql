ALTER TABLE workflow_status_master
    ADD COLUMN background_color VARCHAR(7) NOT NULL DEFAULT '#64748B',
    ADD COLUMN text_color VARCHAR(7) NOT NULL DEFAULT '#FFFFFF',
    ADD COLUMN border_color VARCHAR(7) NOT NULL DEFAULT '#64748B',
    ADD COLUMN icon VARCHAR(32),
    ADD COLUMN display_style VARCHAR(12) NOT NULL DEFAULT 'SOLID';

ALTER TABLE defect_severities_mst
    ADD COLUMN background_color VARCHAR(7) NOT NULL DEFAULT '#E5E7EB',
    ADD COLUMN text_color VARCHAR(7) NOT NULL DEFAULT '#374151',
    ADD COLUMN border_color VARCHAR(7) NOT NULL DEFAULT '#D1D5DB',
    ADD COLUMN icon VARCHAR(32),
    ADD COLUMN display_style VARCHAR(12) NOT NULL DEFAULT 'SOLID';

ALTER TABLE workflow_status_master
    ADD CONSTRAINT workflow_status_badge_colors_ck CHECK (
        background_color ~ '^#[0-9A-Fa-f]{6}$' AND text_color ~ '^#[0-9A-Fa-f]{6}$' AND border_color ~ '^#[0-9A-Fa-f]{6}$'
    ),
    ADD CONSTRAINT workflow_status_display_style_ck CHECK (display_style IN ('SOLID','OUTLINE','SOFT'));

ALTER TABLE defect_severities_mst
    ADD CONSTRAINT defect_severity_badge_colors_ck CHECK (
        background_color ~ '^#[0-9A-Fa-f]{6}$' AND text_color ~ '^#[0-9A-Fa-f]{6}$' AND border_color ~ '^#[0-9A-Fa-f]{6}$'
    ),
    ADD CONSTRAINT defect_severity_display_style_ck CHECK (display_style IN ('SOLID','OUTLINE','SOFT'));

-- Store the initial visual configuration in each master. Components only render
-- values returned by these catalogs; they do not infer colors from status names.
UPDATE workflow_status_master SET
    background_color = CASE status_code
        WHEN 'UNDER_REVIEW' THEN '#7C3AED'
        WHEN 'INSPECTION_REQUIRED' THEN '#F59E0B'
        WHEN 'INSPECTION_IN_PROGRESS' THEN '#2563EB'
        WHEN 'INSPECTION_COMPLETE' THEN '#0F766E'
        WHEN 'ACTION_ASSIGNED' THEN '#1D4ED8'
        WHEN 'IN_PROGRESS' THEN '#2563EB'
        WHEN 'AWAITING_VERIFICATION' THEN '#F59E0B'
        WHEN 'VERIFIED' THEN '#15803D'
        WHEN 'APPROVAL_REQUIRED' THEN '#F59E0B'
        WHEN 'CLOSED' THEN '#6B7280'
        WHEN 'REJECTED' THEN '#B91C1C'
        WHEN 'CANCELLED' THEN '#475569'
        WHEN 'REOPENED' THEN '#0E7490'
        ELSE '#64748B'
    END,
    text_color = CASE WHEN status_code IN ('INSPECTION_REQUIRED','AWAITING_VERIFICATION') THEN '#1F2937' ELSE '#FFFFFF' END,
    border_color = CASE status_code
        WHEN 'UNDER_REVIEW' THEN '#6D28D9'
        WHEN 'INSPECTION_REQUIRED' THEN '#D97706'
        WHEN 'INSPECTION_IN_PROGRESS' THEN '#1D4ED8'
        WHEN 'INSPECTION_COMPLETE' THEN '#0F766E'
        WHEN 'ACTION_ASSIGNED' THEN '#1E40AF'
        WHEN 'IN_PROGRESS' THEN '#1D4ED8'
        WHEN 'AWAITING_VERIFICATION' THEN '#D97706'
        WHEN 'VERIFIED' THEN '#166534'
        WHEN 'APPROVAL_REQUIRED' THEN '#D97706'
        WHEN 'CLOSED' THEN '#4B5563'
        WHEN 'REJECTED' THEN '#991B1B'
        WHEN 'CANCELLED' THEN '#334155'
        WHEN 'REOPENED' THEN '#0F766E'
        ELSE '#475569'
    END,
    icon = CASE status_code
        WHEN 'REPORTED' THEN '•' WHEN 'UNDER_REVIEW' THEN '◷'
        WHEN 'INSPECTION_REQUIRED' THEN '!' WHEN 'INSPECTION_IN_PROGRESS' THEN '⌕'
        WHEN 'INSPECTION_COMPLETE' THEN '✓' WHEN 'ACTION_ASSIGNED' THEN '↗'
        WHEN 'IN_PROGRESS' THEN '↻' WHEN 'AWAITING_VERIFICATION' THEN '◷'
        WHEN 'VERIFIED' THEN '✓' WHEN 'APPROVAL_REQUIRED' THEN '!' WHEN 'CLOSED' THEN '✓'
        WHEN 'REJECTED' THEN '×' WHEN 'CANCELLED' THEN '−' WHEN 'REOPENED' THEN '↺'
        ELSE NULL
    END,
    display_style = 'SOLID';

UPDATE defect_severities_mst SET
    background_color = CASE severity_code
        WHEN 'CRITICAL' THEN '#B91C1C' WHEN 'HIGH' THEN '#C2410C'
        WHEN 'MEDIUM' THEN '#FDE68A' WHEN 'LOW' THEN '#E5E7EB'
        WHEN 'MINOR' THEN '#F3F4F6' ELSE '#E5E7EB'
    END,
    text_color = CASE severity_code
        WHEN 'CRITICAL' THEN '#FFFFFF' WHEN 'HIGH' THEN '#FFFFFF'
        WHEN 'MEDIUM' THEN '#713F12' ELSE '#374151'
    END,
    border_color = CASE severity_code
        WHEN 'CRITICAL' THEN '#991B1B' WHEN 'HIGH' THEN '#9A3412'
        WHEN 'MEDIUM' THEN '#D97706' WHEN 'LOW' THEN '#9CA3AF'
        WHEN 'MINOR' THEN '#D1D5DB' ELSE '#9CA3AF'
    END,
    icon = CASE severity_code
        WHEN 'CRITICAL' THEN '▲' WHEN 'HIGH' THEN '🔥' WHEN 'MEDIUM' THEN '⚠'
        WHEN 'LOW' THEN '−' ELSE NULL
    END,
    display_style = 'SOLID';

-- Keep future tenant starter severities aligned with the current stored defaults.
CREATE OR REPLACE FUNCTION seed_operator_default_severities() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO defect_severities_mst(operator_id,severity_code,severity_name,description,sort_order,background_color,text_color,border_color,icon,display_style)
  VALUES
    (NEW.id,'LOW','Low','Starter display label only. Not an airworthiness classification or maintenance threshold.',10,'#E5E7EB','#374151','#9CA3AF','−','SOLID'),
    (NEW.id,'MEDIUM','Medium','Starter display label only. Not an airworthiness classification or maintenance threshold.',20,'#FDE68A','#713F12','#D97706','⚠','SOLID'),
    (NEW.id,'HIGH','High','Starter display label only. Not an airworthiness classification or maintenance threshold.',30,'#C2410C','#FFFFFF','#9A3412','🔥','SOLID'),
    (NEW.id,'CRITICAL','Critical','Starter display label only. Not an airworthiness classification or maintenance threshold.',40,'#B91C1C','#FFFFFF','#991B1B','▲','SOLID')
  ON CONFLICT(operator_id,severity_code) DO NOTHING;
  RETURN NEW;
END $$;
