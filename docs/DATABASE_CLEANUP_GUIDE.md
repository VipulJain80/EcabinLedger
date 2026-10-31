# eCabin Ledger database cleanup utilities

## Scope and evidence

These utilities are manual DBA tools under `database/admin/`; they are not Flyway migrations and are never called by application startup. The inventory below is derived from the repository's migration source, including the source files currently present through V75. It is **not a live database inventory**: no database was connected to or inspected for this work. In particular, V74/V75 are present in the worktree and their actual application state is unknown.

V18 adds the `_mst`/`_dtl` naming convention. V22 archives the old cabin-zone master, and V23 drops that archive; therefore `cabin_zones_mst` is not part of the current schema inferred from the complete migration chain. Never run these scripts until the read-only verification script has been run against the intended database and its output reconciled with this guide.

## Files and installation order

1. `database/admin/03_cleanup_execution_log.sql` installs the policy, explicit master allowlist, run log, and external-storage manifest tables and the non-login administrative roles. This is administrative DDL, not a migration. It fails if same-named objects already exist; inspect them before any rerun.
2. `database/admin/01_cleanup_inactive_master_data.sql` defines the inactive-master procedure and the shared fail-closed authorization function.
3. `database/admin/02_purge_transaction_data.sql` defines the transaction-purge procedure.
4. `database/admin/04_cleanup_verification.sql` contains read-only verification queries.

An authorized DBA must review and install scripts 03, 01, then 02 in a controlled administrative session. The engineering task did **not** execute them. The routine owner must be a **non-superuser** role that owns every table whose append-only/immutability trigger it must suspend; otherwise the procedure fails closed or PostgreSQL rejects the trigger operation. Install the routines while acting as that approved owner, not as a superuser. If role provisioning requires a superuser, provision the non-login roles separately first, then run the administrative DDL as the non-superuser table owner. Assign membership in the non-login executor role only to a separately approved DBA account. Set `cleanup_database_policy` for the exact `current_database()` name and verified environment; no policy row is seeded and all destructive-operation flags default to false. The policy-admin role should be held separately from the executor role. Record approval and backup references only after independently verifying them.

After confirming the database identity and environment, an administrator can register a **disabled** policy row for dry-run use:

```sql
INSERT INTO ecabin_ledger.cleanup_database_policy(database_name,environment)
VALUES (current_database(),'UAT'); -- Replace UAT only with the verified environment.
```

This does not authorize actual cleanup. Enabling a real operation requires a separate reviewed policy update setting the relevant operation flag, approval reference/approver/time, and—on production—the production enable flag and recent backup reference/approver/time. Do not use sample or placeholder approvals. Keep policy administration separate from routine application credentials.

The two procedures are manual and fail closed unless the caller is a member of `ecabin_cleanup_executor`, a policy row exists for the current database, and the requested operation is enabled. A real operation also requires a recorded approval from the last 24 hours. Production operations additionally require the explicit production enable switch and a backup approval from the last 24 hours. The purge requires `p_confirm => TRUE` as well. These database checks supplement, rather than replace, organizational change approval and a verified restore test.

## Procedure behavior

### Inactive master cleanup

The procedure uses only rows registered in `cleanup_master_registry`. The initial allowlist is limited to the currently evidenced tenant reference masters:

- `fleets_mst`
- `aircraft_mst`
- `maintenance_teams_mst`
- `cabin_areas_mst`
- `cabin_components_mst`
- `defect_categories_mst`
- `defect_conditions_mst`
- `component_conditions_mst`
- `defect_severities_mst`

Those migrations use `isactive` with `1` meaning active and `2` meaning inactive. The registry stores the exact active-column name and inactive value, so reviewed schemas using `is_active`, a `status` value such as `INACTIVE`, or another actual convention can be represented without guessing. Do not register a table until its column semantics, tenant scope, and system seed rows have been checked against the target database. An unregistered `_mst` table is preserved.

System-required starter records are protected by code/name where the migrations establish them: General Fleet; the five seeded areas; starter components; starter defect categories; and LOW/MEDIUM/HIGH/CRITICAL severity codes. Other inactive rows are eligible only when no row in any table has an FK referencing them. The procedure discovers FK columns from PostgreSQL catalogs, including composite tenant FKs. It does not use `CASCADE`. A foreign-key reference or protected system code is reported as blocked. Active rows are never deleted.

Dry-run example (the default is dry-run):

```sql
CALL ecabin_ledger.cleanup_inactive_master_data(p_dry_run => TRUE);
```

Actual execution is disabled until DBA policy approval and backup gates are configured. Stop application writers, take and verify a backup, confirm the database identity, review the dry-run counts, then enable the exact database policy and call:

```sql
CALL ecabin_ledger.cleanup_inactive_master_data(p_dry_run => FALSE);
```

### Transaction purge

The purge is a full-database reset of the approved disposable development/test/UAT transaction dataset, not a tenant-selective operation. It targets only explicit case-linked rows, child-first, without `TRUNCATE`, `CASCADE`, or suffix-based table discovery. Its core approved set is:

1. `attachments_dtl`
2. `email_outbox_attempts_dtl` and case-event-linked `email_outbox_dtl`
3. Defect-linked `notifications_dtl`
4. `closure_approvals_dtl`, `verifications_dtl`, `inspections_dtl`, `corrective_actions_dtl`
5. Defect-linked `operational_form_submissions_dtl`
6. All case-scoped `defect_events_dtl` rows whose `defect_id` is the target case, plus legacy DEFECT-entity rows identified by their `entity_id`
7. `defects_dtl`

This includes both legacy held records (`defects_dtl.status='IN_PROGRESS'` with `corrective_actions_dtl.status='ON_HOLD'`) and new ON_HOLD records. They are disposable only as part of the explicitly approved whole-UAT transaction reset; no ON_HOLD-specific rewrite or status backfill occurs.

Organization configuration and master data are preserved. So are user memberships and permissions, Workflow Status Master, workflow versions/configuration, form definitions/versions/mappings, `defect_reference_counters_dtl`, `configuration_imports_dtl`, login events, API rate-limit counters/history, report definitions/query history, organization-level audit events, email settings, and all other rows not listed above.

Operational forms and their published versions are configuration, not transaction data. They are therefore preserved by default. A separate `form_configuration_purge_enabled` policy switch exists for an explicitly approved UAT teardown that intends to remove all forms, draft/published versions, and area mappings. It has no default enablement and should only be enabled with separate approval. Enabling it deletes *all* form configuration in the database, not just one tenant. Published-version and mapping immutability triggers are disabled only inside that same transaction and restored before success. Do not use this option where forms are needed for the clean production starting point.

Dry-run example (both defaults are safe):

```sql
CALL ecabin_ledger.purge_transaction_data(p_confirm => FALSE, p_dry_run => TRUE);
```

Actual execution requires all of: exact database policy approval; the transaction-purge enable switch; explicit confirmation; production enablement plus a recently approved backup when the environment is production; application/email-worker maintenance mode; successful dry-run review; and approval that **all** eligible transaction records in the database are disposable.

```sql
CALL ecabin_ledger.purge_transaction_data(p_confirm => TRUE, p_dry_run => FALSE);
```

Do not paste the actual call into a shared or production console until every gate has been independently reviewed. The procedure uses a transaction-scoped advisory lock and `SHARE ROW EXCLUSIVE ... NOWAIT` locks on its explicit data set. A lock conflict fails promptly. PostgreSQL statement/transaction rollback restores deleted rows and trigger states if any FK, trigger, SQL, or logging step fails. The procedure has no internal `COMMIT`; run the call as one transaction and explicitly `ROLLBACK` if the client reports an error.

## Evidence object storage

`attachments_dtl.object_key` refers to private local/Azure object storage. SQL cannot atomically delete those external objects with PostgreSQL rows. During an actual purge, each object key for a purged case is written to `cleanup_storage_manifest` before the attachment metadata is deleted. After the database transaction commits, an approved storage operator must delete each referenced object using the configured storage system, update the manifest to `DELETED` or `ERROR`, and verify that no `PENDING` or `ERROR` rows remain for the execution. Do not delete object storage before database commit. A failed database transaction rolls back both attachment deletion and manifest insertion.

The SQL procedure does not perform external blob deletion. A successful SQL purge therefore means database metadata was purged and object keys were queued; it does **not** mean physical evidence blobs were deleted. Storage deletion requires its own access-controlled, logged operation and must be reconciled before declaring the environment clean.

## Audit and execution log

`cleanup_execution_runs` records execution ID, database, operation, dry-run flag, actor, approval/backup references, and successful completion. `cleanup_execution_log` records per-table total/inactive/eligible/blocked/deleted/affected counts. `cleanup_storage_manifest` records external object deletion work. Retrieve results using the execution ID printed by `RAISE NOTICE`, or use the read-only queries in `04_cleanup_verification.sql`.

The procedure records successful calls in the same transaction as the work. If the call fails, PostgreSQL rolls back the run and table logs along with the purge, so a failed run cannot be durably logged from inside this ordinary atomic procedure. Preserve the client/server error output in the DBA change record; do not weaken atomic rollback to persist an in-transaction failure row.

## Table inventory from migrations

“Master” and “detail” below describe suffix convention only. Classification describes retention intent; a `_dtl` suffix is never sufficient reason to purge.

| Table | Classification | Why / treatment |
|---|---|---|
| `aircraft_mst` | Tenant reference master; cleanup allowlist | Aircraft lookup; inactive row removable only if no FK references it. |
| `fleets_mst` | Tenant reference master; cleanup allowlist | Fleet lookup; General Fleet seed protected and FK references block other rows. |
| `maintenance_teams_mst` | Tenant reference master; cleanup allowlist | Team lookup; in-use assignment/routing/membership references block deletion. |
| `cabin_areas_mst` | Tenant reference master; cleanup allowlist | Operational area lookup; five provisioned base areas protected. |
| `cabin_components_mst` | Tenant reference master; cleanup allowlist | Component lookup; seeded starter codes protected, FK references block deletion. |
| `defect_categories_mst` | Tenant reference master; cleanup allowlist | Category lookup; seeded codes protected. |
| `defect_conditions_mst` | Tenant reference master; cleanup allowlist | Condition lookup; FK references block deletion. |
| `component_conditions_mst` | Tenant reference master; cleanup allowlist | Component-condition mapping; referenced mappings block deletion. |
| `defect_severities_mst` | Tenant reference master; cleanup allowlist | Severity lookup; starter stable codes protected; V75 default must remain active. |
| `operators_mst` | Tenant/authentication root; excluded | Organization and FK root; never cleaned by these utilities. |
| `operator_memberships_mst` | Identity/security configuration; excluded | Active users and roles; login history depends on it. |
| `operator_membership_teams_mst` | Identity/security configuration; excluded | Team memberships; access control. |
| `operator_roles_mst` | Identity/security configuration; excluded | Organization roles and permissions. |
| `permissions_mst` | System authorization catalog; excluded | System permission definitions. |
| `navigation_menu_items_mst` | System/UI configuration; excluded | Organization navigation and authorization presentation. |
| `area_maintenance_team_mappings_mst` | Workflow routing configuration; excluded | Controls area-to-team assignment. |
| `defect_workflow_config_mst` | Workflow configuration; excluded | Selects active workflow version per organization. |
| `maintenance_sla_policies_mst` | Workflow/SLA configuration; excluded | Operational SLA rules. |
| `api_rate_limit_policies_mst` | Security configuration; excluded | Runtime request protection policies. |
| `email_notification_settings_mst` | Organization configuration; excluded | Global email enablement. |
| `email_notification_event_settings_mst` | Organization configuration; excluded | Per-event email switches. |
| `operational_form_areas_mst` | Form configuration; excluded by default | Mutable form-to-area mapping. Optional only under explicit form-config purge approval. |
| `report_routines_mst` | Reporting/system configuration; excluded | Approved report routine definitions. |
| `report_templates_mst` | Reporting configuration; excluded | Versioned report template definitions. |
| `report_lookup_sources_mst` | Reporting configuration; excluded | Allowlisted report lookup definitions. |
| `report_template_filters_mst` | Reporting configuration; excluded | Template filter definitions. |
| `report_template_columns_mst` | Reporting configuration; excluded | Template column definitions. |
| `reports_mst` | Reporting configuration; excluded | Tenant report definitions. |
| `report_filters_mst` | Reporting configuration; excluded | Tenant report filters. |
| `report_columns_mst` | Reporting configuration; excluded | Tenant report columns. |
| `report_role_access_mst` | Authorization/report configuration; excluded | Report role access rules. |
| `report_routine_parameters_mst` | Reporting configuration; excluded | Routine parameter contracts. |
| `report_query_events_mst` | Append-only audit/history; excluded | Report execution history. |
| `operational_forms_dtl` | Form configuration; excluded by default | Form definitions; only removed under separate policy approval. |
| `operational_form_versions_dtl` | Immutable form configuration/history; excluded by default | Draft/published versions; optional full form-config purge only. |
| `operational_form_version_areas_dtl` | Immutable form configuration/history; excluded by default | Published-version applicability snapshot. |
| `defect_workflow_versions_dtl` | Immutable workflow configuration/history; excluded | Workflow policy history; not case transaction data. |
| `defects_dtl` | Operational transaction; purge eligible | Case root; all dependent case rows are deleted first. |
| `defect_events_dtl` | Append-only case audit/history; scoped purge eligible | All events whose `defect_id` belongs to a purged case are removed regardless of event `entity_type` (including corrective-action/reviewer events); legacy DEFECT-entity rows use `entity_id`. Organization-level events with no case link are retained. |
| `inspections_dtl` | Operational transaction; purge eligible | Defect child rows. |
| `corrective_actions_dtl` | Operational transaction; purge eligible | Maintenance actions, including ON_HOLD history for purged cases. |
| `verifications_dtl` | Operational transaction; purge eligible | Defect child rows. |
| `closure_approvals_dtl` | Operational transaction; purge eligible | Defect child rows. |
| `attachments_dtl` | Evidence metadata; purge eligible | Case-linked metadata is removed; external objects are queued, not deleted by SQL. |
| `notifications_dtl` | Case notification transaction; scoped purge eligible | Only rows attached to purged defects are removed; unlinked rows are preserved. |
| `operational_form_submissions_dtl` | Immutable submission transaction/history; purge eligible | Only submissions linked to purged defects are removed; exact immutability trigger is suspended in-transaction. |
| `email_outbox_dtl` | Delivery transaction; scoped purge eligible | Only messages whose source is a purged defect event are removed. Global/org messages are preserved. |
| `email_outbox_attempts_dtl` | Delivery history; scoped purge eligible | Attempts for selected case-linked outbox rows are removed first. |
| `configuration_imports_dtl` | Administrative import/audit history; excluded | Import evidence/status retained. |
| `defect_reference_counters_dtl` | Identifier allocation state; excluded | Preserves monotonic references and prevents defect-reference reuse. |
| `api_rate_limit_usage_dtl` | Security operational state; excluded | Active request-limit windows; not case transaction data. |
| `api_rate_limit_public_usage_dtl` | Security operational state; excluded | Unauthenticated route throttling counters. |
| `api_rate_limit_policy_events_dtl` | Append-only security audit; excluded | Policy change history. |
| `user_login_events_dtl` | Append-only authentication audit; excluded | Login history; protected by update/delete and truncate triggers. |
| `workflow_status_master` | Global workflow configuration; excluded | Shared workflow status catalog; no `_mst`/`_dtl` suffix. |

The table inventory is migration-derived. Run the catalog query in `04_cleanup_verification.sql` to identify differences in a target database. Any extra table, missing table, renamed constraint, or unexpected FK is a stop condition for manual review.

## FK dependency map from migration definitions

This is the principal operational dependency graph found in the migration source; `04_cleanup_verification.sql` is the authoritative way to check the actual target database.

| Parent | Referencing rows / relationship | Delete implication |
|---|---|---|
| `operators_mst` | Tenant-owned fleet, aircraft, memberships, roles, reference masters, configuration and transaction rows | Tenant roots/config are excluded; no tenant deletion is performed. |
| `fleets_mst` | `aircraft_mst(operator_id,fleet_id)` | Inactive fleet is blocked while any aircraft references it. |
| `cabin_areas_mst` | Components, defects, area-team mappings, forms and form-area snapshots | Inactive area is blocked while any live/config/history row references it. |
| `maintenance_teams_mst` | Area routing, corrective actions, closure routing, membership/team configuration | Inactive team is blocked while any live/config/history row references it. |
| `cabin_components_mst` | `component_conditions_mst` and `defects_dtl` | Inactive component is blocked while either mapping or defect snapshot references it. |
| `defect_conditions_mst` | `component_conditions_mst` and `defects_dtl` | Inactive condition is blocked while either mapping or defect snapshot references it. |
| `defect_categories_mst` / `defect_severities_mst` | Tenant-scoped defect references and snapshot references | Inactive referenced value is blocked; standard severity codes are also protected. |
| `defects_dtl` | Attachments, notifications, actions, inspections, verifications, closure decisions, form submissions, events | Purge children first; unexpected dependent rows make the parent delete fail and roll the transaction back. |
| `defect_events_dtl` | `email_outbox_dtl(operator_id,source_event_id)` with `ON DELETE RESTRICT` | Delete attempts then only case-event-linked outbox rows before deleting those case events. |
| `email_outbox_dtl` | `email_outbox_attempts_dtl(operator_id,outbox_id)` with `ON DELETE RESTRICT` | Delete attempt history before the selected outbox row. |
| `operational_forms_dtl` | Form areas and form versions; published pointer references the selected `(operator_id,form_id,version_id)` | Preserve by default; optional full form purge detaches published pointers and removes explicit child mappings/versions first. |
| `operational_form_versions_dtl` | Submissions, attachments, and version-area snapshots | Preserve by default; an unexpected reference blocks the opt-in form purge. |
| `operator_memberships_mst` | `operator_membership_teams_mst(operator_id,external_user_id)` uses `ON DELETE CASCADE` | Both tables are security configuration and excluded; the utility never relies on cascade. |
| Report definitions/templates/routines | Composite tenant and version FKs, several explicitly `ON DELETE RESTRICT` | Reporting configuration and query history are excluded. |

The remaining same-schema and cross-schema FK edges are discovered dynamically for master cleanup, and the actual catalog inventory is emitted by the verification script. Purge does not auto-discover new transaction tables for deletion: a newly discovered FK can cause a safe rollback and must be reviewed before the explicit allowlist is changed.

## Foreign-key and trigger handling

- The cleanup procedure discovers incoming FK references for each registered master, including tenant composite keys. Referenced inactive records are blocked; no cascade is attempted.
- The purge has an explicit child-to-parent order. It does not use `CASCADE` or `TRUNCATE` and relies on FK violations to abort if an unreviewed dependency exists.
- Migrations establish restrictive/no-action case references. Form mapping relationships include some `ON DELETE CASCADE`; the procedure explicitly deletes named rows and does not depend on those cascades.
- `defect_events_dtl` has an append-only trigger; `operational_form_submissions_dtl`, published form versions, and published version-area mappings have immutability triggers. The purge disables only named triggers, only after checking them, and only inside the same transaction; it restores them before success. Unexpected trigger names or FK edges require DBA review.
- `user_login_events_dtl`, API rate-limit policy events, report-query events, and workflow-version history remain untouched.
- The ON_HOLD feature is not dropped or rewritten. It remains in status constraints and Workflow Status Master; only disposable case/action rows within the approved purge boundary are removed.

## Rollback, backups, and completion

Within PostgreSQL, a failed call is transactionally rolled back. A committed purge cannot be undone by this utility: restore from the approved backup or point-in-time recovery. External object deletions happen after SQL commit and cannot be rolled back through PostgreSQL; retain the backup and manifest until object cleanup and verification finish.

Before actual use:

1. Run `04_cleanup_verification.sql` against the exact database and confirm identity, environment, migration state, table inventory, FKs, and trigger names.
2. Stop application and worker writes; ensure no maintenance window conflict.
3. Take a backup and verify a restore, with an approval reference.
4. Configure the exact database policy as DBA, with independent approval and (for production) current backup metadata.
5. Run and review the dry run; retain execution ID/counts.
6. Only then make a separate authorized actual call.
7. Verify table counts, retained configuration/master data, status configuration, run logs, and storage manifest.
8. Delete external blobs through the storage operator process and verify no pending/error manifest entries.

Never treat a successful SQL call as proof that external storage is clean. Never install these scripts into the Flyway location or invoke them from application startup.
