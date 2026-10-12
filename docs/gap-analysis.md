# Existing implementation audit and current state

> **Current master-data coverage audit — 2026-10-12.** This section supersedes any older master-screen or verification statements below where they conflict. The inventory was derived from the complete checked-in Flyway history (V1–V28). Local PostgreSQL was not reachable during this audit, so table presence, live constraints, and records were not runtime-verified.

## Master table inventory and UI coverage

There are **16 application-owned `_mst` tables** in the current migration history. V18 renamed legacy table names to the `_mst` convention; the table listed here is the current application name. `legacy_cabin_zones_archive` was removed by V23 and is not a current master. The two relationship tables are intentionally managed through their owning screens, rather than presented as duplicate standalone CRUD modules.

All organization-managed configuration endpoints require a validated principal, an active membership, and `ADMINISTER_ORGANIZATION`; tenant-owned queries and mutations receive the organization ID from that server-resolved principal. The common configuration controller provides GET list/table/export/template and POST save/import/activate/deactivate routes where a resource supports them. The table screen uses server-side search, paging (maximum 100), status filtering and sorting, plus XLSX export. CSV import is staged, validated, then committed through POST; it is not available for every resource. Supported configuration changes are written through the existing append-only configuration audit event path. Role/permission and menu visibility remains backend-enforced.

| Table | Purpose, ownership and schema/migration constraints | Existing API/service and UI/navigation | Screen status and supported operations / guardrails |
|---|---|---|---|
| `operators_mst` | Tenant root and organization profile; global identity boundary. V1, soft-delete/audit metadata V4/V14; referenced by tenant tables. | `GET/POST /api/v1/configuration/organizations[/table]`, `ConfigurationController` → `ConfigurationService`; Administration → Organization. | **Complete, intentionally restricted.** View, paginated search, export, edit own organization name. No create, import, activate, or deactivate; provisioning/onboarding owns these. The UI previously showed import/template actions despite backend rejection; fixed in this change. |
| `fleets_mst` | Tenant-owned aircraft grouping. V2, renamed V18; unique `(operator_id,name)` and composite tenant identity used by aircraft FK. | `/configuration/fleets`, configuration service; Administration → Fleets. | **Complete.** Create/edit/deactivate/reactivate, search/status/page, export and staged CSV import. Active aircraft prevent fleet deactivation. |
| `aircraft_mst` | Tenant-owned aircraft identity/registration, MSN, type, fleet. V1/V2/V8/V18; unique tenant registration and tenant-composite fleet FK; `isactive` 1/2. | `/configuration/aircraft`; Administration → Aircraft. | **Complete.** Create/edit/status, type filter, server paging/search, export/import. An aircraft with any defect history cannot be deactivated. |
| `operator_memberships_mst` | Tenant membership and application role by enterprise subject; not an identity-provider user directory. V2/V4/V7/V14/V18; PK `(operator_id,external_user_id)`, active role FK to `operator_roles_mst`. | `/configuration/users`; Administration → Users and roles. | **Complete.** Provision/update membership, role, display name and status; team assignments are shown with the user. Identity subject is immutable on edit. No password or local credential administration. Self-deactivation, last active ADMIN and open work/inspection constraints are enforced. Import is available for supported create-only membership flow; role is tenant-owned and validated. |
| `operator_membership_teams_mst` | Tenant membership-to-team relationship (soft-deactivated history). V2/V4/V14/V18; tenant FK to membership; current team validity checked against active `maintenance_teams_mst`. | Read/write as part of `/configuration/users` save and team membership selection in `ConfigurationService`; surfaced in Users and roles and team member eligibility. | **Complete as an embedded relationship, no duplicate screen.** Add/remove active team memberships; not independently imported/exported or status-filtered. Membership grants no application permission; active role and workflow scope remain separately enforced. |
| `maintenance_teams_mst` | Tenant Reviewer and Maintenance teams. V5/V14/V16/V18; unique tenant/name, `team_type` check added V16, active flags. | `/configuration/maintenance-teams`; Administration → Teams. Reviewer routing has a dedicated editor/API. | **Complete.** CRUD/status, search/page, export/import, team type and eligible-reviewer count. Rename/deactivation is blocked while memberships, assignments, default route, or live routed defects depend on it. |
| `cabin_areas_mst` | Authoritative tenant cabin-area master. V9/V14/V18; unique tenant/code and composite tenant identity; seeded at organization onboarding. | `/configuration/areas`; Administration → Area Master. Used by forms, components and defects. | **Complete.** CRUD/status, search/page, export/import. Deactivation blocked while active components or open defects depend on the area. No `cabin_zones_mst` fallback. |
| `defect_categories_mst` | Tenant defect taxonomy. V5/V14/V18; unique tenant/code and active flag. | `/configuration/defect-categories`; Administration → Defect categories. | **Complete.** CRUD/status, search/page, export/import. Tenant-scoped and audited. |
| `cabin_components_mst` | Tenant cabin equipment reference assigned to one area. V5/V9/V14/V18; unique tenant/code and composite FK to tenant-owned area. | `/configuration/components`; Administration → Components. | **Complete.** CRUD/status, area filter/reference selection, search/page, export/import. Area must be active; component area cannot change after defect history references it. |
| `defect_conditions_mst` | Tenant defect-condition vocabulary. V12/V14/V18; unique tenant/code, active flag. | `/configuration/defect-conditions`; Administration → Defect conditions. | **Complete.** CRUD/status, search/page, export/import. The condition editor also maintains applicable component relationships; condition text is taxonomy, not maintenance procedure. |
| `component_conditions_mst` | Tenant join mapping that restricts a condition to eligible components. V12/V14/V18; composite FKs and active relationship history. | No separate endpoint/screen; read/write through Conditions (`componentIds`) and `ConfigurationService.synchronizeConditionComponents`; eligibility also checked during defect/reference-data use. | **Complete as part of Conditions, intentionally not standalone.** Multi-select manages the relationship atomically; reference selections must be active and tenant-owned. A separate CRUD screen would duplicate the parent workflow. |
| `operator_roles_mst` | Tenant role catalog and permission-code mapping. V7/V18/V20; unique tenant/role key; built-in `ADMIN` protected; `SUPERADMIN` reserved and rejected. | `/configuration/roles` (mutations) and `/configuration/role-permissions` (read-only view); Administration → Roles and Role-Permission Mapping. | **Complete.** Create/edit tenant-defined roles and activate/deactivate where permitted; role list is searchable, paged, exportable/importable where supported. Permission mapping screen is read-only; no mutation of built-in ADMIN or grant of `ADMINISTER_ORGANIZATION` through custom roles. No SUPERADMIN. |
| `permissions_mst` | Global system-owned catalog documenting the Java permission enum; V20, globally unique code, system status. Not tenant-owned. | `GET /api/v1/configuration/permissions[/table]`; Administration → Permission Master. | **Complete, intentionally read-only.** Browse/search/status/page/export. Backend rejects save/deactivate/activate; permissions are code-owned and never editable from tenant UI. |
| `navigation_menu_items_mst` | Tenant menu and submenu configuration with required permission and route key. V6/V14/V18, plus menu seed extensions V20/V27/V28; tenant composite self-FK, sibling-key uniqueness, bounded order. | `/configuration/menu-items`; Administration → Menus & submenus; effective navigation via `GET /api/v1/navigation` filtered by backend grants. | **Complete.** CRUD/status, search/page, export/import. Route key and permission values are allow-listed; parent must be tenant-owned and valid. Hiding a UI item does not replace backend route authorization. |
| `defect_severities_mst` | Tenant severity labels, not certified airworthiness thresholds. V20, tenant/code uniqueness, order check 0–9999, active flag and defect tenant FK. V26 adds demonstration vocabulary. | `/configuration/severities`; Administration → Severity Master. | **Complete.** CRUD/status, search/page, export/import. Stable codes cannot be edited after creation; descriptions explicitly avoid representing aviation thresholds. |
| `api_rate_limit_policies_mst` | Tenant-owned runtime request-rate policies. V27, unique `(operator_id,route_key,scope_type)`, bounded route/scope/count/window constraints. | `GET/POST /api/v1/rate-limits/*`; `ApiRateLimitController`/`ApiRateLimitService`; Administration → API Rate Limits from backend navigation. | **Complete, protected configuration.** List/search/page, create/edit/activate/deactivate, effective policy and usage/rejection metrics, append-only policy history. Admin-only and tenant-scoped; fixed infrastructure ceilings cannot be changed here. No generic master import/export. |

### Navigation and operational configuration adjacent to masters

`operational_forms_dtl` and `operational_form_versions_dtl` are not `_mst` tables, but Form Master is a required Administration resource. It has a dedicated version-aware UI and `/api/v1/configuration/forms` APIs rather than generic CRUD; draft editing, immutable published versions, area/purpose/stage mapping, publication validation, preview and status actions are preserved. Defect forms, versioned submissions, team assignment, and workflow records remain domain modules, not master screens. Reviewer routing is also a dedicated protected configuration editor and has a backend POST operation with a reason.

The common configuration UI consumes backend-provided menu entries and renders a section selector only for definitions returned by that API. API Rate Limits is a separate page rendered from the effective navigation key; it is not a duplicate generic table. All menu entries remain filtered by backend permissions, and direct protected API calls remain permission-checked. Existing organization onboarding provisions its own Organization and menu; this audit adds no new permission grants or navigation entries.

### Gaps found and implemented

- **Fixed:** Organization profile previously appeared to have CSV Bulk Upload and Download Sample CSV controls although organization creation/import is owned by onboarding and backend import validation/commit already rejected it. The Organizations screen now suppresses those unsupported controls, and the backend no longer offers an organization import template. Other resource import flows are unchanged.
- **Fixed:** The menu editor omitted `report-defect` and `api-rate-limits` from its frontend route choices and backend route allow-list, even though migrations provision those routes. Both existing sections can now be edited without allowing arbitrary destinations; the backend still checks the same fixed allow-list.
- **No missing standalone `_mst` UI found:** all remaining tables are exposed through either a supported master screen, a read-only catalog, or an explicitly protected dedicated configuration view. The two join tables are correctly managed through their parent domains.
- **Backend safety already present and retained:** tenant resolution and `ADMINISTER_ORGANIZATION` enforcement in `ConfigurationController`; resource-specific read-only checks, dependency-aware deactivation, tenant-bound references, mutation audit, and append-only protections. No new permissions were granted.

### Verification boundary

The migration-derived inventory is complete for V1–V28 in this checkout. The local PostgreSQL port was not reachable during this audit; therefore runtime schema/table inventory, migrations against local DB, actual records, and live API/UI data could not be verified. No database contents were changed. `TenantIsolationPostgresTest` is Testcontainers-based and must be excluded from this task's test command because Docker is unavailable and prohibited. The opt-in `PostgresMigrationSmokeTest` is not run unless a safe local DB target is explicitly configured; its default is disabled unless `ECABIN_DB_VERIFY=true`.

## Earlier implementation audit (historical)

The following sections preserve findings from an earlier implementation audit. Statements about the repository's inventory, available tools, tests, or verification are historical; use the dated master coverage audit at the top of this document for the current master-table/UI status.

## Authentication evidence in this repository

- Spring Security is stateless and uses Spring's default Bearer-token resolver (`Authorization: Bearer …`).
- `SecurityConfig` constructs a `NimbusJwtDecoder` from `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, applies issuer/time validation, and adds an `OAUTH2_AUDIENCE` check.
- The original `DefectController` read `operator_id` and `sub` from the validated JWT and checked `scope`/`scp`-derived `SCOPE_defects.read` and `SCOPE_defects.write`. It has been changed to resolve `operator_id` + `sub` against active `operator_memberships`; the application role is from that DB record and backend permission checks gate each operation.
- Production browser integration expects `window.eCabinAuth.getAccessToken()`; no enterprise gateway adapter, gateway headers, Microsoft integration, role/group claim mapping, user directory, or user provisioning exists in this repository. A separate loopback-only `local-auth` profile issues short-lived tokens for the seeded `abc@gmail.com` demo membership and is not evidence of the real gateway contract.
- No signing algorithm is pinned in application configuration; Spring Nimbus validates the token signature using issuer metadata/JWKS. Issuer/JWKS and forwarded-header behavior therefore remain unknown until the gateway contract is supplied.

No claim about Microsoft token type, exact signing algorithm, issuer, audience, key-rotation behavior or gateway headers can be made from this repository. Spring Resource Server's current default expects a JWT bearer token using issuer discovery/JWKS. The actual enterprise gateway owner must validate that this is their supported interface.

## Gap severity

### Critical

- Production gateway integration is not present; browser token provider is an undefined global. Real issuer/audience/key/header/claims and Microsoft-gateway flow cannot be safely inferred. The local development token issuer is bound to loopback and must never be deployed.
- The original state machine allowed direct `REPORTED → INSPECTING` and `IN_PROGRESS → RESOLVED → CLOSED`; it had no independent verification/approval or reopen flow.
- Operator claim directly selected the tenant. No server-side membership/user record or application RBAC existed; any trusted token with the claim and scope could cross into that tenant.
- Evidence was only an arbitrary text reference. No upload/download controls, content validation, malware scanning, or storage authorization existed.
- Gateway access-token integration is still an unresolved production launch blocker; the frontend bridge is not implemented here. The attachment table is metadata only, with no object-store adapter or authorized file API.

### High

- Only one defect table existed; inspections, corrective actions, verification, approval, notifications and fleet hierarchy were missing.
- Defect taxonomy omitted seat, IFE, lighting, overhead-bin, PSU and emergency-equipment categories.
- No tenant-isolation, authorization, lifecycle, migration or integration tests; Maven and JDK were absent.
- Health/readiness existed but Prometheus registry, structured request logging, request correlation and consistent security errors were absent.
- No complete authorization/security/API integration suite, browser end-to-end test, in-app notification UI/delivery, full search/filter surface, or role provisioning workflow exists yet.

### Medium

- Search was client-side over one page; no aircraft/category/date/assignee/overdue filters.
- Production container installs web dependencies without a lockfile; runtime and migration database credentials were not separated.
- Dashboard displayed only four counts; SLA/aging/overdue views and in-app notifications were absent.
- API documentation, deployment-specific profiles and restore/rollback guidance were incomplete.

### Low

- Accessibility details, responsive navigation destinations, audit-event filtering and UI confirmations need refinement.

## Existing coverage

Next App Router, responsive defect list/create/detail UI, REST DTOs, PostgreSQL/Flyway, operator-scoped SQL, cursor pagination, optimistic version column, inspection outcome/evidence-reference fields, append-only audit trigger, Spring JWT resource-server validation, scope checks, Dockerfiles and a local Postgres compose service existed. Those pieces are extended rather than replaced.

## Implemented during hardening

- V2–V4 database migrations add fleet hierarchy, organization membership/roles/team grants, workflow records, attachment metadata, notifications, indexes, correctly sized audit fields, and `isactive` soft-delete flags on mutable business tables.
- Backend principal resolution and explicit role-to-permission map; tenant context is sourced from the validated claim and active server-side membership.
- Dedicated inspection, corrective action, verification and approval endpoints; explicit state transition rules, transactional row locks and actor/evidence audit events.
- Request ID filter, structured log configuration, Actuator health/Prometheus hooks, Springdoc, Maven Wrapper, workflow/RBAC unit tests and Docker-dependent tenant isolation test.
- UI identity comes from `/me`; reporting/workflow controls are hidden according to role as a usability hint.
- V5 adds tenant-scoped configuration masters for cabin zones, defect categories, components and maintenance teams, plus audited GET/POST administration for organization profile, fleets, aircraft and user memberships. Configuration is ADMIN-only, menu metadata comes from the API, and deactivation/reactivation uses reasoned `isactive` changes.
- Development seed now provides a DEMO-labelled Skyways Service tenant, three synthetic aircraft, four sample defects across lifecycle states, memberships, inspections, actions, verification, approval, and seed audit events. The opt-in seed test applied it twice and verified idempotency.

## Verification gaps

- JDK 17 `./mvnw clean test` and `./mvnw clean package`, run from `backend/`, passed against PostgreSQL 18.6 in the `ecabin_ledger` schema after consolidating the Maven project there; 8 tests passed and the Docker-based Testcontainers test was skipped because no Docker daemon/socket was available.
- Flyway applied and validated V1–V4 in the new schema. The application integration test completed a defect lifecycle, verified tenant-scoped reads/events/updates, and confirmed soft-deleted defects are hidden; its inserted test records were rolled back.
- The opt-in seed test added persistent demo records to `ecabin_ledger`; the workflow integration test continues to roll back its generated records.
- Next 16.3.3 production build and ESLint passed using the installed Node runtime and the package-local CLI entrypoints. The environment does not expose `npm`; commands were run as equivalent direct script binaries.
- Full cross-tenant coverage for aircraft, attachments, inspections, corrective actions and audit administration, live gateway authentication, and browser E2E remain unverified. Flyway warns that PostgreSQL 18 is newer than the version it has officially tested against (17), although these migrations and the exercised workflow passed on 18.6.
