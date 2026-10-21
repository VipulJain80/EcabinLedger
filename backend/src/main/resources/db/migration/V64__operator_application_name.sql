ALTER TABLE operators_mst
    ADD COLUMN application_name varchar(160) NOT NULL DEFAULT 'eCabin Ledger';

ALTER TABLE operators_mst
    ADD CONSTRAINT operators_application_name_nonblank_ck
    CHECK (btrim(application_name) <> '');

COMMENT ON COLUMN operators_mst.application_name IS
    'Tenant-specific application name displayed in the authenticated application shell.';
