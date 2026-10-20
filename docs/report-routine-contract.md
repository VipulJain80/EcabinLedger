# Dynamic report PostgreSQL function contract (v2)

Routine registration remains a reviewed deployment concern. The application does not accept routine names or SQL from clients and does not activate routines automatically. A routine is callable only when its registry row is active, has `contract_version = 2`, has `scope_policy = 'DEFECT_VISIBLE_SCOPE_V1'`, and has the matching `routine_kind`.

## Report provider

A registered report provider is invoked as:

```sql
SELECT total_rows, row_id, row_data
FROM "registered_schema"."registered_function"(
  tenant_uuid, actor_subject, tenant_role, scope_policy,
  filter_values_jsonb, page_index, page_size, sort_jsonb
)
```

The exact PostgreSQL input types are `uuid, text, text, text, jsonb, integer, integer, jsonb`. `filter_values_jsonb` uses approved routine parameter keys, and values are passed as one JDBC-bound JSONB parameter. `sort_jsonb` is a bounded array of `{ "key": ..., "direction": "ASC"|"DESC" }`; only metadata-approved sortable keys reach it. The function must use the stable source identity as a tie-breaker for every sort and for default ordering.

The function returns `RETURNS TABLE(total_rows bigint, row_id text, row_data jsonb)`. `row_id` is the immutable source identity serialized as text and must be nonempty and unique for a record across all pages. `row_data` must be a JSON object whose keys exactly equal the configured report column keys. A page with rows returns one output row per record and repeats the same matching `total_rows`. An empty result or a page beyond the last page returns exactly one sentinel row with `row_id = NULL` and `row_data = NULL`, while still returning the true total count. The executor rejects missing markers, inconsistent totals, unexpected keys, wrong value types, duplicate IDs, excessive rows, and oversized row JSON.

The function must be read-only and enforce tenant and row visibility itself using the supplied tenant/actor/role and trusted database membership/team state. `DEFECT_VISIBLE_SCOPE_V1` requires parity with current `DefectService` behavior: own reports for reporter-only users, reviewer-team/status visibility, assigned maintenance-team/action visibility, and broad-view behavior. Filters may narrow this visibility but must never broaden it. The database connection runs the call in a read-only transaction with the registered timeout.

## Lookup provider

A lookup provider is invoked as:

```sql
SELECT option_value, display_label
FROM "registered_schema"."registered_function"(
  tenant_uuid, actor_subject, tenant_role, scope_policy, max_results
)
```

Input types are `uuid, text, text, text, integer`; return type is `RETURNS TABLE(option_value text, display_label text)`. Providers must apply the same tenant and applicable row visibility restrictions. Values must be unique, type-compatible with `report_lookup_sources_mst.value_type`, nonempty and bounded; labels must be nonempty and bounded. At most the source's configured `max_results` (1–100) may be returned. The lookup key is resolved only through an active source referenced by an active lookup filter on a report visible to the caller.

V57 registers `defect_workflow_status_lookup_v2` with the v2 lookup signature and registers the `DEFECT_WORKFLOW_STATUS` source. V58 activates the reviewed `defect_register_report_v2` routine and the `DEFECT_REGISTER` global template after checking the function signature, execution mode, scope metadata, lookup registration, and template metadata. The source is not exposed to report users until it is referenced by a filter on an active tenant report that the user may view. V58 does not create tenant report definitions or role grants; an organization administrator must enable the template for the tenant and configure explicit role access. The PostgreSQL integration fixtures compare the report function with `DefectService.list` for reporter, reviewer, maintenance, combined, broad-view, and ordinary `VIEW` roles; these checks must run against disposable PostgreSQL before deployment approval.

## Excel export

`POST /api/v1/reports/{reportId}/export` accepts only the configured filter values and sortable column keys. It reuses the report query contract, requires all three application permissions (`REPORT_VIEW`, `REPORT_EXECUTE`, and `REPORT_EXPORT`) plus all three matching report grants, and writes only configured exportable columns in their configured order. The generated `.xlsx` is staged in a temporary file to keep memory bounded, then streamed to the client. Temporary files are deleted after transfer and are also marked for deletion when the application exits.

The default export bounds are 10,000 rows, 120,000 milliseconds total generation time, and 52,428,800 bytes (50 MiB). They can be adjusted with `REPORT_EXPORT_MAX_ROWS`, `REPORT_EXPORT_MAX_DURATION_MS`, and `REPORT_EXPORT_MAX_BYTES`; hard caps remain 100,000 rows, 15 minutes, and 512 MiB. An export that exceeds a bound fails instead of silently truncating. Report functions still enforce their own configured per-call timeout. The XLSX writer also rejects cell values longer than Excel's 32,767-character cell limit.
