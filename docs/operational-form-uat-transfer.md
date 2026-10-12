# Operational Form UAT transfer

The source rows supplied by the user contain five form records, of which four are active. V30/V31 transfer the four active form keys: `GALLEY_AREA`, `CABIN`, `DEMO_REPORT_LAVATORY`, and `DEMO_REPORT_ENTRY_AREA`. The inactive `DEMO_REPORT_CABIN` record is intentionally excluded. The source IDs are not reused; the migrations resolve the target tenant and area codes and generate fresh form/version IDs.

## Migrations

- `V30__replace_operational_forms.sql` archives existing target-tenant form rows by setting them inactive, clearing their current published pointers, and assigning an `ARCHIVED_V30_...` key. It does not delete old forms or version rows, so historical submissions, defects, attachments, and audit references remain intact. It inserts four active form records with `DEFECT_REPORT` / `INITIAL_REPORT` mapping.
- `V31__insert_operational_form_versions.sql` inserts eight versions: Galley v1/v2, Passenger Cabin v1/v2/v3/v4, Lavatory v1, and Entry Area v1. It assigns each form's published pointer to Galley v2, Cabin v3, Lavatory v1, and Entry Area v1. Cabin v4 remains a draft.

Both migrations fail if the active organization named `Skyways Service (DEMO)` or required area records are missing/ambiguous. V31 also fails if the V30 forms already have versions. Existing records are not physically deleted. Before UAT deployment, verify that migration version 30 has not already been applied there. Never edit an applied Flyway migration; if V30 was applied, use a new forward-only migration instead.

The source data is based on the user-provided query exports; it has not been independently re-read from PostgreSQL in this environment. Review version field JSON against the source before applying. No migration execution or UAT verification is claimed.

## Apply and verify

Run the normal backend startup/migration process only after reviewing the target and confirming authorization. Then run the read-only verification script with target schema and tenant UUID:

```sh
cd backend
psql "$DATABASE_URL" -v schema=ecabin_ledger -v operator_id='<target-tenant-uuid>' -f scripts/verify-operational-forms.sql
```

Expected result: four active forms with the keys above; archived prior form rows may also remain. The verification output shows target area codes, versions, publication pointers, field JSON, duplicate keys/versions, and historical submission/attachment counts. Confirm API discovery in the target application after the migrations have run. Do not infer successful discovery merely from row existence.

The standalone source exporter remains available for future reviews at `backend/scripts/export-operational-forms.py`; it requires `psql`, configured `PG*` environment variables, `DB_SCHEMA`, and `ECABIN_FORM_EXPORT_OPERATOR_ID`.
