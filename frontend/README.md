# eCabin Ledger

eCabin Ledger is an aviation cabin defect operations platform for cabin, galley, lavatory, passenger-seat, attendant-seat and equipment findings. This repository is an existing MVP being hardened; it is not yet a certified maintenance or airworthiness system.

## Architecture

```text
Microsoft SSO
  ↓ (owned by enterprise)
Existing enterprise authentication gateway
  ↓ (token contract must be supplied by gateway owner)
Next.js 16.3.3 web UI ── bearer token ──→ Spring Boot 3.5 / JDK 17 API
                                              ↓ validate token + resolve membership
                                      tenant-scoped services + RBAC
                                              ↓
                               PostgreSQL / Flyway / append-only events
```

The API is a modular monolith with boundaries for authentication, organization configuration, defect workflow, inspections, corrective actions, verification/approval, audit, attachments and notifications. Configuration menus are supplied by the backend and each table is organization-scoped. Configuration reads use GET; create/update, activate and soft-delete operations use POST only. Master data edits are restricted to ADMIN and written to append-only audit events. Keep the relational database authoritative. Scale first with stateless API replicas, bounded DB pools, query/index tuning, and background work for notifications and reports. Likely bottlenecks are tenant-wide dashboard aggregation, audit growth, attachment transfer, and database connections. Roadmap: (1) modular monolith + relational DB, (2) cache/background workers, (3) read replicas/search, (4) extract measured high-load modules, (5) multi-region only when operational needs justify it.

### API rate-limit management

Organization administrators can manage limits under **Administration → API Rate Limits**. The navigation item is permission filtered and the API independently requires the existing `ADMINISTER_ORGANIZATION` grant; no permission is automatically added to roles. Management is tenant scoped. This repository does not have an authenticated platform administrator identity, so global policies and emergency ceilings are not exposed to tenant administrators.

Spring applies one central filter after JWT authentication and before endpoint authorization. The filter resolves the validated JWT to the active membership, classifies the method and path against a backend-owned category allow-list, then reads current policy rows and increments a shared PostgreSQL fixed-window counter with one atomic `INSERT ... ON CONFLICT ... RETURNING`. A policy change is used by the next request without a process restart or policy cache invalidation. Precedence is active tenant override, then active per-user policy, then a conservative fixed server default if the per-user row was deactivated. Starter rows are 120 requests/minute for ordinary reads, 30/minute for defect creation, workflow mutations and expensive searches, and 10/minute for CSV imports, exports and evidence-upload requests. The category catalog defines hard maximums; tenant changes cannot override them. Fixed windows have no burst allowance.

`GET /api/v1/rate-limits/catalog`, `/policies`, `/policies/{id}`, `/effective`, `/usage`, and `/history` provide authorized configuration and collected observations. `POST /api/v1/rate-limits/policies`, `/policies/{id}`, `/policies/{id}/activate`, and `/policies/{id}/deactivate` perform changes; every change requires a reason and appends an immutable actor/time/policy audit event. Usage counters keep a one-way SHA-256 scope digest, not a token or raw user/IP. Collected totals cover the prior 24 hours; old counter buckets are removed after 30 days. An unauthenticated local token endpoint uses a separate fixed server ceiling (`API_RATE_LIMIT_UNAUTHENTICATED_PER_MINUTE`, default 10/minute) and the direct remote IP address; forwarded IP headers are not trusted. Other unauthenticated application routes remain subject to Spring's existing authentication rules.

The PostgreSQL counter is atomic across API replicas and policy changes are immediately visible, but it adds a database write to each authenticated `/api/v1` request. This is a deliberate minimal deployment choice because the current stack has no shared Redis service. Monitor database write latency and pool pressure; if measured load requires it, move the atomic counter to a shared Redis-compatible store while preserving PostgreSQL as the policy and audit source. If policy/counter storage is unavailable, enforcement fails closed with HTTP 503; exceeded limits return the standard API error envelope with HTTP 429 and `Retry-After`. Request-count limiting supplements request-body, upload-size, concurrency, query-timeout and connection-pool protections.

## Technology and structure

- Frontend: Next.js 16.3.3 App Router, React 19, TypeScript.
- API: Spring Boot 3.5, Java 17, Spring Security Resource Server, JDBC.
- Database: PostgreSQL with Flyway migrations.
- API docs: Springdoc at `/api-docs` and `/swagger-ui`.
- Health/metrics: Spring Actuator health/readiness and Prometheus endpoint.

```text
frontend/                          Next.js app, configuration, dependencies and Dockerfile
backend/pom.xml                    Spring Boot Maven project
backend/mvnw, backend/.mvn/        Maven Wrapper and configuration
backend/src/main/java/com/ecabin/ledger/
  api/                             controllers, request/response DTOs, errors
  config/                          security, request IDs
  security/                        validated identity, membership and permissions
  service/                         defect lifecycle, workflows and configuration
backend/src/main/resources/db/     seed data and Flyway schema migrations
backend/src/test/java/             workflow, RBAC and PostgreSQL isolation tests
backend/Dockerfile                 API container build
docs/gap-analysis.md               repository audit and prioritized gaps
compose.yaml                       local PostgreSQL only
```

## Data model

`operators_mst` is the tenant root. `fleets_mst` own `aircraft_mst`; `aircraft_mst`, `defects_dtl`, workflow records, `attachments_dtl`, and `notifications_dtl` carry tenant ownership. Configuration masters include `operators_mst` (tenant roots), `fleets_mst`, `aircraft_mst`, `operator_memberships_mst`, `operator_membership_teams_mst`, `maintenance_teams_mst`, `cabin_areas_mst`, `defect_categories_mst`, `cabin_components_mst`, `defect_conditions_mst`, `component_conditions_mst`, `operator_roles_mst`, `navigation_menu_items_mst`, `defect_severities_mst`, the global read-only `permissions_mst` catalog, and tenant-scoped `api_rate_limit_policies_mst`. See [docs/gap-analysis.md](docs/gap-analysis.md) for the full master UI and API inventory. `cabin_areas_mst` is the single authoritative source of operational cabin areas. Cabin location details such as FWD CABIN, L1, row and seat are captured on defect records as text snapshots, not resolved through a second location master. Each `cabin_components_mst` row has a required tenant-composite foreign key to one active area when created or reactivated. Active `defect_conditions_mst` are assigned to one or more active tenant-owned components through `component_conditions_mst`; new defects must select a condition mapped to the selected component and area. Existing defects retain nullable condition references and a name snapshot for historical compatibility. V9 maps only the known original component codes; migration aborts if another unmapped component is found rather than assigning it arbitrarily. `isactive` is a checked `SMALLINT`: `1` means active and `2` means soft-deleted. Existing `aircraft_mst`/membership booleans were migrated to this field, and normal operational queries ignore rows with `isactive=2`. `operator_membership_teams_mst` limits team-assigned corrective actions to active members explicitly assigned to that team. `defect_events_dtl` is append-only and deliberately has no soft-delete flag; Flyway history is also excluded. Use a separate migration DB role and runtime DB role in production; the migration trigger alone is not a substitute for DB privilege separation.

## Database table naming and migration recovery

Application tables use one business-purpose suffix. Master/reference/configuration tables use `_mst`; workflow, operational, audit, submission, and staging records use `_dtl`. `flyway_schema_history` is framework-managed and intentionally unchanged. Cabin areas are the single operational area source.

| Classification | Tables |
|---|---|
| Master (`_mst`) | `operators_mst`, `fleets_mst`, `aircraft_mst`, `operator_memberships_mst`, `operator_membership_teams_mst`, `maintenance_teams_mst`, `cabin_areas_mst`, `defect_categories_mst`, `cabin_components_mst`, `defect_conditions_mst`, `component_conditions_mst`, `operator_roles_mst`, `navigation_menu_items_mst`, `defect_severities_mst`, `permissions_mst`, `api_rate_limit_policies_mst` |
| Transaction/detail (`_dtl`) | `defects_dtl`, `defect_events_dtl`, `inspections_dtl`, `corrective_actions_dtl`, `attachments_dtl`, `notifications_dtl`, `configuration_imports_dtl`, `operational_forms_dtl`, `operational_form_versions_dtl`, `operational_form_submissions_dtl`, `verifications_dtl`, `closure_approvals_dtl`, `api_rate_limit_usage_dtl`, `api_rate_limit_public_usage_dtl`, `api_rate_limit_policy_events_dtl` |

V18 is forward-only and uses PostgreSQL `ALTER TABLE ... RENAME`, which preserves rows and object identity; PostgreSQL keeps foreign-key dependencies and trigger attachments bound to their relations. The migration fails closed if an expected source table is missing or a destination name already exists. It updates the two onboarding trigger functions that contain table names. Existing audit append-only and form-decision immutability triggers remain attached. Constraint and index names retain their existing labels because PostgreSQL object references remain valid and those object names are not part of the table naming rule.

V19 adds an optional tenant-scoped idempotency key and payload hash to defect reports. The partial unique index prevents duplicate reports when a client retries after a lost response; requests that reuse a key with a different actor or payload are rejected. The added columns are nullable, so existing reports are unchanged.

Deploy with a coordinated rollout: stop or drain old application instances, take and verify a PostgreSQL backup/PITR point, apply Flyway migrations with a migration-only credential, deploy the matching application version, then run health, tenant-isolation, workflow, and audit checks before reopening traffic. V18 requires a coordinated deployment because it changes application table names; V19 is backward-compatible because its columns are nullable. Set `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD` separately from runtime `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` in production; local defaults continue to use the existing datasource credentials. PostgreSQL rolls back a failed transactional migration; investigate and repair any blocking schema condition before retrying. After a successful production rollout, do not rename tables backward as an application rollback. Restore into an isolated environment from the verified backup/PITR point or deploy a reviewed forward corrective migration, then validate it before routing production traffic.

## Authentication, authorization and tenancy

Production has no local username/password or independent login provider: the enterprise gateway performs Microsoft SSO. The production backend expects a JWT bearer token, uses Spring's issuer discovery/JWKS decoder from `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, validates issuer/time and the `OAUTH2_AUDIENCE` audience, then resolves `operator_id` plus `sub` against an active `operator_memberships_mst` row. The application role comes from that server-side membership, not a request field. Inactive or unknown membership is denied; provisioning never grants ADMIN automatically. A separate `local-auth` profile exists only for loopback workstation demos and must never be enabled in a deployed environment.

This is the repository's current contract, not a confirmed contract for the enterprise gateway. Before deployment, the gateway owner must confirm token format, issuer, audience, JWKS/signing algorithm and rotation, required claims, identity and organization claim mapping, role/group mapping, token forwarding, and the supported browser token adapter. Do not accept gateway identity headers or claims until documented and verified. Production browser integration expects `window.eCabinAuth.getAccessToken()`; the repository does not implement the enterprise gateway SDK/adapter. See the audit for detail.

Roles are `ADMIN`, `SUPERVISOR`, `INSPECTOR`, `MAINTENANCE_TECHNICIAN`, `QUALITY_COMPLIANCE`, and `VIEWER`. The current permission catalog is `VIEW`, `REPORT`, `REVIEW`, `INSPECT`, `ASSIGN_ACTION`, `PERFORM_ACTION`, `VERIFY`, `APPROVE`, `CLOSE`, `REOPEN`, `AUDIT`, and `ADMINISTER_ORGANIZATION`; services and workflows check these capabilities rather than treating a display role name as authority. Spring Security now classifies current `/api/v1` route/method combinations centrally, resolves active membership from the validated JWT, and denies unmatched API routes by default. Controller/service checks still enforce operation-specific permissions and tenant-scoped SQL.

`SUPERADMIN` is reserved from tenant role creation and membership assignment. This repository does not yet implement a platform identity scope, global administration APIs, or an approved SUPERADMIN bootstrap/recovery process. Do not create a tenant role or directly edit production membership rows to simulate one. Before enabling platform administration, define an enterprise-authenticated platform principal, separate global permissions from tenant permissions, approve an auditable out-of-band provisioning and recovery procedure, and add explicit tests. Ordinary `ADMIN` remains organization-scoped.

Reviewer queue access is restricted in the backend to active `REVIEWER` team membership plus an active organization role that independently grants `REVIEW`. Reviewer-scoped lists, dashboard/fleet counts, fleet exports, defect details, and workflow operations apply that current routing scope. Organization administrators can see defects needing a route, including defects reported while no eligible route was configured. Other roles with broad `VIEW` grants retain organization-wide read access as defined by their current role; least-privilege role design remains the organization's responsibility.

## Defect workflow and audit

The intended state sequence is `REPORTED → UNDER_REVIEW → INSPECTION_REQUIRED → INSPECTION_IN_PROGRESS → INSPECTION_COMPLETE → ACTION_ASSIGNED → IN_PROGRESS → AWAITING_VERIFICATION → VERIFIED → APPROVAL_REQUIRED → CLOSED`. Reopening is explicit (`CLOSED → REOPENED`) and requires a reason. Inspection, action, verification and approval are separate API operations. Workflow events record actor, timestamp, state and evidence reference. Closed records cannot move through a normal transition.

The aircraft browsing path starts on **Defect Summary**. Selecting an MSN or registration opens `/aircraft/{aircraftId}/defects`; the backend resolves the aircraft within the authenticated organization and returns a server-paginated defect list. Team-scoped maintenance users see only current team work, and report-only users see only defects they reported. Defect Summary counts use the same permission scope. New reporting is area-first through the **Report Defect** navigation item (visible only when the authenticated user's effective permissions include `REPORT`). It loads active `cabin_areas_mst` entries, resolves exact published `DEFECT_REPORT` / `INITIAL_REPORT` versions, and requires explicit form selection if multiple forms match. The form's `AIRCRAFT_SELECT` field searches `GET /api/v1/aircraft/options` by registration or MSN; the backend returns only active aircraft in the authenticated organization. No aircraft is inherited from Defect Summary, and aircraft identity is not stored in form answers. The selected database ID is validated against the tenant and becomes the existing `defects_dtl.aircraft_id` relationship. The area remains system-controlled context. The aircraft-specific **Log Defect** entry point remains available and also requires explicit aircraft selection in the report form. Report creation remains `REPORTED` with an append-only event and an idempotency key; the exact published form submission and evidence are then attached through existing APIs. Because creation, upload and form submission are separate API operations, a failed later step can leave a reported defect for recovery; the page retains the created defect and uploaded evidence IDs to retry completion without creating a second defect.

Corrective actions remain the team-assignment source of truth, reusing `defects_dtl.assigned_team`, `corrective_actions_dtl.assigned_team`, `operator_membership_teams_mst`, and active `maintenance_teams_mst`. `GET /api/v1/maintenance-work-queue` uses server-side search, filtering, sorting, and pagination; it includes a defect once even when the member belongs to multiple active teams. Maintenance principals with `PERFORM_ACTION` but without organization-wide review/admin capabilities are scoped to their active team assignments for defect lists, details, dashboard summaries, and attachment access. Team assignment requires an active organization team; if an individual assignee is also selected, that person must be an active member of the selected team. Team work can be started/completed by any active member when no individual is selected, while explicit individual ownership remains enforced. A reviewer can reassign active work through the corrective-action API; the prior action is retained as cancelled and an append-only event records previous/new team and assignee. Assignment notifications are written once per eligible recipient; an inbox UI and external delivery worker are not yet implemented. Individual claim/ownership beyond the existing optional assignee is a future extension.

Reviewer teams reuse `maintenance_teams_mst` with `team_type='REVIEWER'` and the existing soft-deactivated `operator_membership_teams_mst` rows. Organization administrators manage team type and user team memberships under **Administration → Teams** and set the default under **Administration → Reviewer routing**. A membership never grants permissions: the user must also have `REVIEW` in their organization role. On defect creation, the server snapshots the eligible organization default team into the defect's single `reviewer_team_id`; the shared queue does not create per-user assignments. If the route is missing or no longer has an active member with `REVIEW`, the defect is still saved, an immutable `REVIEW_ROUTING_FAILED` event is recorded, and the defect is visible to administrators for follow-up. Maintenance completion preserves the route so the returned defect is available to the same Reviewer team. Explicit rerouting records before/after team IDs in the audit trail and changes current queue visibility.

Versioned operational forms are configured under **Form Master** and mapped directly to an active `cabin_areas_mst` record, a form purpose, and a workflow stage. `operational_forms_dtl` stores this tenant-scoped resolution key; `operational_form_versions_dtl.fields_json` stores only the immutable form design (labels, field types, order, validation, reference source, and approved binding). Form Master reads the server-owned allow-list from `GET /api/v1/configuration/form-field-bindings`; each catalog record names a stable binding, verified domain property and destination table/column. The destination metadata is descriptive: Java services use explicit typed mappings, never SQL derived from a form definition. Only bindings implemented for the applicable purpose can be published. The current writable business bindings cover initial defect reporting and map to existing columns in `defects_dtl`; evidence uses private object storage and `attachments_dtl`. Aircraft and cabin-area context remain server-controlled. Reference options are resolved from active tenant data, and condition choices must match the selected component and area. Unbound business fields, unsupported types/sources, duplicate bindings, hidden required fields, and required read-only fields block publication.

Purposes in the schema are `DEFECT_REPORT`, `INSPECTION`, `CORRECTIVE_ACTION`, `VERIFICATION`, `CLOSURE_APPROVAL`, and `REOPEN`. The corresponding workflow actions still use their dedicated authorized APIs and relational workflow tables; status, assignment, review decision, verification result, approval decision, and closure are not generic writable form fields. A published design with no approved writable binding for its purpose cannot be published. V24 backfills prior report-only definitions and submission purpose/stage metadata. V25 converts historical supported report answers into fixed typed columns on the existing immutable `operational_form_submissions_dtl`, preserves exact form/version/purpose/stage links, and drops `answers_json`. It fails closed if it finds nonempty historical answers with no approved mapping so a reviewed data-preservation plan is required; it never silently discards unbound data. New report submissions validate answers against the exact published definition and the already-created tenant-owned defect, then persist an immutable typed snapshot. The field definition remains JSON configuration, but no form answer values are persisted as JSON/JSONB or in an EAV table. Attachment IDs remain relational links in `attachments_dtl`. Multiple active forms can share a resolution key; the UI requires explicit selection. Form mappings never grant permissions. Backend lookup, submission, and evidence operations independently check tenant, current status, permission, exact published version, and defect visibility. Form selections reset when aircraft, defect, purpose, area, or version changes. Submissions append a defect event and are immutable.

For `DEFECT_REPORT`, the published field definition is also the only source for user-entered report fields and their order. Administrators bind configured fields to backend properties in Form Master: aircraft (`AIRCRAFT_SELECT` / `AIRCRAFT_ID`, stable key `aircraftId` and required for initial reporting), defect category (`DEFECT_CATEGORIES`), component (`COMPONENTS`), condition (`DEFECT_CONDITIONS`, dependent on that component), severity (`DEFECT_SEVERITIES`), location/zone, title, and description. Aircraft is a system-controlled selection, not an answer binding: the selected UUID is validated against the active tenant's Aircraft Master and stored only in `defects_dtl.aircraft_id`. The registration and MSN shown in the UI are read-only labels. These required bindings must be present and compatible before a report can be created. **Location** is free text and can hold one physical reference such as `Galley 2`, `Lavatory 1`, `Seat 14A`, or `Attendant Seat 3`; this lets an area-specific form use a single location field. Optional structured row and seat-reference bindings remain available for forms that specifically need separate reporting or filtering. At submission, Spring resolves answers from the exact active published version and validates reference IDs against active tenant-owned reference data; parallel property values sent by the browser are ignored. The cabin area is system-controlled context. Existing published versions are not rewritten; an administrator must create a new draft version, add the Aircraft Registration selector when the form is an initial defect report, and publish it through Form Master. Existing defects and historical form submissions retain their original version references.

Evidence attachments use private Azure Blob Storage or a private local filesystem provider behind the same `BlobStorage` interface. `ATTACHMENT_STORAGE_PROVIDER=azure` explicitly selects Azure/Azurite; Azure upload failures never fall back to disk. The `local-auth` workstation profile selects `local` by default and stores files under `/Users/vipuljain/Desktop/EcabinLedger/uploadimages`; override with `ATTACHMENT_LOCAL_PATH`. The local store creates and checks the configured writable directory, uses opaque keys, prevents path traversal (including symlink escapes) and overwrite, and is never exposed as a web/static directory. In Docker, build with `docker build -t ecabin-ledger-api ./backend`, mount persistent host storage using `-v /Users/vipuljain/Desktop/EcabinLedger/uploadimages:/app/uploadimages`, and set `ATTACHMENT_STORAGE_PROVIDER=local` plus `ATTACHMENT_LOCAL_PATH=/app/uploadimages`; a host path is not available inside the container automatically. Ensure the mounted host directory is writable by container UID 10001, or run a workstation container with `--user "$(id -u):$(id -g)"` so it can write to the developer-owned mount. Local storage is not durable production storage. The API validates extension and image signature, limits each image to 5 MiB independently (an exactly 5 MiB image is accepted), enforces each published field's other size/count restrictions, generates opaque object keys, scopes metadata and downloads to the authenticated tenant, and stores neither bearer tokens nor image bytes in PostgreSQL. Multipart requests can be up to 64 MiB to support multiple images; the 5 MiB limit is enforced per image in the service. Files remain quarantined and unavailable for download/submission until ClamAV marks them `CLEAN`; scanner absence or failure never bypasses scanning. Configure `CLAMAV_HOST`, `CLAMAV_PORT`, and `CLAMAV_TIMEOUT_MS`; ClamAV is required in production and for successful local evidence submission. Downloads proxy bytes through the authorized API rather than exposing filesystem paths or issuing browser-visible signed URLs.

The Galley demo form is seeded only for the fixed synthetic Skyways Service tenant. The seed resolves that tenant's active `GALLEY` row and existing Galley component, then adds only the explicitly labelled `Surface damage (DEMO)` component-condition relationship if absent. It creates a seven-field **Galley Area (DEMO)** draft, including the required Aircraft Registration selector plus six business/evidence fields, only when no Galley `DEFECT_REPORT` / `INITIAL_REPORT` form already exists. If exactly one existing Galley report form has a published version but no draft and no aircraft selector, the seed preserves that immutable publication and adds a seed-owned next draft with the selector. It does not edit administrator-created drafts or alter published versions. `PostgresMigrationSmokeTest` publishes only an untouched seed-owned draft through `OperationalFormService`, so the current binding and publication validation runs before the version becomes available. It then resolves the form through the same service path used by `GET /api/v1/areas/{areaId}/forms`, checks the tenant-scoped aircraft, component and condition choices, and verifies registration/MSN data comes from Aircraft Master. A manually created Galley form is preserved; if one exists but is unpublished or lacks the required aircraft binding, finish it in Form Master using a new draft and publish it through the existing workflow. The demo condition and severity labels are illustrative data, not maintenance procedures, certified classifications, or airworthiness thresholds.

The application business tables inspected for audit metadata include `operators_mst`, `fleets_mst`, `aircraft_mst`, `operator_memberships_mst`, `operator_membership_teams_mst`, `defects_dtl`, `inspections_dtl`, `corrective_actions_dtl`, `maintenance_teams_mst`, `defect_categories_mst`, `cabin_components_mst`, `cabin_areas_mst`, `defect_conditions_mst`, `component_conditions_mst`, `navigation_menu_items_mst`, `operator_roles_mst`, `attachments_dtl`, `notifications_dtl`, `configuration_imports_dtl`, `operational_forms_dtl`, and `operational_form_versions_dtl`. Established actor/time equivalents are reused: for example `reported_by/reported_at`, `inspector_user_id/assigned_at`, `created_by_user_id/created_at`, form-version `created_by/created_at` and `published_by/published_at`, membership and notification timestamps. V14 adds nullable `created_by`/`updated_by` or missing `updated_at` only where mutable business records lacked equivalents; it does not fabricate historical actor/timestamp values. Transaction-local `ecabin.actor_id` is sourced from the validated server principal and the database trigger stamps future writes and preserves creator fields on updates. Client DTOs do not contain editable audit metadata. No audit indexes were added because the current access paths do not filter/sort by these fields.

Immutable or append-only records deliberately excluded from V14 are `defect_events_dtl` (append-only audit history), `operational_form_submissions_dtl` (immutable typed submission snapshot), `verifications_dtl` and `closure_approvals_dtl` (immutable decisions with their own actor/time fields), and Flyway schema history. `configuration_imports_dtl` is mutable staging and has metadata; immutable decisions/events retain their existing business actor and timestamp fields rather than receiving redundant mutable columns.

## API

Base path: `/api/v1`. All operational routes require a validated bearer token and active organization membership.

| Method | Route | Purpose |
|---|---|---|
| GET | `/me` | Resolved organization, display name, application role and granted permissions |
| GET | `/navigation` | Menu and nested submenu items filtered by the authenticated user's permissions |
| GET | `/dashboard/summary` | Tenant-scoped summary counts |
| GET | `/reference-data` | Active defect categories, cabin areas, components and severities for operational forms |
| GET | `/reference-data/components/{componentId}/conditions` | Active defect conditions assigned to this tenant-owned component |
| GET | `/defects` | Server-paged tenant search by defect ID, registration, category, component, assigned person, aircraft type, date range, status, location, and current user's reports |
| GET | `/review-queue` | Server-paged shared defects routed to the authenticated Reviewer team(s); administrators also see items requiring routing |
| GET | `/reviewer-teams` | Active Reviewer teams with active members granted `REVIEW` |
| GET | `/configuration/reviewer-routing/default` | Current organization Reviewer routing configuration (ADMIN) |
| POST | `/configuration/reviewer-routing/default` | Validate and update the organization default Reviewer team (ADMIN; reason required) |
| POST | `/defects/{id}/review-routing` | Reroute a defect to another eligible Reviewer team (REVIEW; reason required) |
| POST | `/defects` | Report defect |
| GET | `/defects/{id}` | Tenant-scoped defect detail |
| POST | `/defects/{id}/transitions` | Apply allowed general transition |
| GET | `/defects/{id}/events` | Tenant-scoped audit timeline |
| GET | `/configuration/menu` | ADMIN-only master-data submenu metadata |
| GET | `/configuration/{resource}` | ADMIN-only tenant-scoped master records; optional `includeInactive=true` |
| GET | `/configuration/{resource}/import-template` | Download the backend-defined sample CSV |
| POST | `/configuration/{resource}/imports/validate` | Multipart CSV validation and 15-minute tenant/user-bound preview; no master writes |
| POST | `/configuration/{resource}/imports` | Confirm a validated import; create-only, one transaction, idempotent receipt |
| POST | `/configuration/{resource}` | Create or update a master-data record |
| POST | `/configuration/{resource}/{id}/activate` | Reactivate a soft-deleted record with a reason |
| POST | `/configuration/{resource}/{id}/deactivate` | Soft-delete a record with a reason |
| POST | `/defects/{id}/inspections` | Record inspection outcome |
| POST | `/defects/{id}/actions` | Assign corrective action |
| POST | `/defects/{id}/actions/{actionId}/start` | Start action |
| POST | `/defects/{id}/actions/{actionId}/complete` | Complete corrective work |
| POST | `/defects/{id}/verification` | Independently verify work |
| POST | `/defects/{id}/approval` | Approve/reject closure |
| GET | `/configuration/forms/table` and `/configuration/forms/export` | Tenant-scoped, server-paged form master and streamed filtered export |
| POST | `/configuration/forms` and `/configuration/forms/{id}/versions/{versionId}` | Create a form and save a draft version |
| POST | `/configuration/forms/{id}/mapping` | Update tenant-scoped area, purpose, and workflow-stage mapping with an audit reason |
| POST | `/configuration/forms/{id}/publish` | Publish an immutable version |
| GET | `/areas/{areaId}/forms?purpose=…&stage=…` | Resolve published forms for the configured area, purpose, and stage |
| GET | `/defects/{id}/operational-forms?purpose=…` | Resolve forms from tenant, visible defect, server-side status, and purpose-specific permission |
| GET | `/forms/{formId}/versions/{versionId}/fields/{fieldKey}/options` | Load tenant-authorized dropdown options for the exact published version |
| POST | `/defects/{id}/form-submissions` | Validate and submit answers against a published version and matching purpose/status |
| POST | `/defects/{id}/forms/{formId}/versions/{versionId}/fields/{fieldKey}/attachments` | Upload private, signature-checked, scanned evidence |
| GET | `/attachments/{id}/download` and POST `/attachments/{id}/remove` | Tenant-authorized clean-file download and audited pre-submission removal |

Configuration resources include `organizations`, `fleets`, `aircraft`, `areas`, `defect-categories`, `defect-conditions`, `components`, `maintenance-teams`, `users`, `menu-items`, `roles`, `severities`, `permissions`, and `role-permissions`. The latter two are protected read-only catalog/mapping views; all mutable configuration uses existing administration authorization. Mutations resolve tenant identity from the validated principal and create an audit event. User configuration grants an application membership to an existing enterprise identity; it does not create an identity-provider account. Deletion is a POST-based `isactive=2` change; organization records cannot be deleted, aircraft with defect history cannot be deactivated, an area cannot be deactivated while it has active components or open defects, and the last active administrator cannot be deactivated. OpenAPI is available at `/api-docs`.

## Area, Component, Condition and CSV master maintenance

Area records live only in `cabin_areas_mst`. V22 temporarily renamed the former cabin-location table so its relationships could be checked; V23 removes both legacy names without cascading across dependencies. Existing defect location strings remain historical snapshots. Initial tenant areas are `ENTRY_AREA`, `GALLEY`, `LAVATORY`, `CABIN`, and `ATTENDANT_SEAT`. Components require a valid `area_id`; the database enforces tenant consistency with `(operator_id, area_id)`. New organizations receive the initial Area and Component records and default application roles from database onboarding triggers. Existing components are mapped only from known codes in V9, and the migration fails closed if unmapped component data exists. Condition labels are tenant-configured, can apply to several components, and are fetched for defect entry only after the user selects an area and component. The illustrative demo label is explicitly marked as demo vocabulary, not approved maintenance data.

Generic editable configuration master pages expose **Bulk Upload** and **Download Sample CSV** where the backend supports imports; **Add New** is shown only for resources that permit creation. Organization provisioning is onboarding-owned, so the Organization profile intentionally has neither import controls nor create/deactivate actions. Search, applicable filters, page size, column visibility and export use the shared `DataTable` toolbar. The Excel export takes the visible TanStack leaf-column IDs at click time, rejects utility/unsupported columns server-side, preserves their displayed order and streams every tenant-scoped row matching the active filters and sort order (not only the visible page). It emits only whitelisted corresponding values, not arbitrary API properties. Dedicated protected screens such as Form Master, Reviewer routing and API Rate Limits use their domain-specific controls.

Templates and accepted headers come from the backend `ConfigurationImportService`. CSVs must be UTF-8 with the exact downloaded header, `.csv` extension, at most 5 MB and at most 5,000 data rows. CSV quoting, escaped double quotes, commas inside quoted values, quoted line breaks and blank lines are handled. Files are previewed before commit; row errors identify the CSV row, field and reason. Invalid rows are excluded from the staged valid set, and the user confirms the valid set explicitly. The accepted rows are committed together in one DB transaction; if any persistence or audit write fails, that transaction rolls back. Import mode is create-only: duplicates in the file and records already present (including soft-deleted unique keys) are rejected rather than overwritten. Successful record mutations use the ordinary configuration save path and its append-only audit event.

Validated rows and rejected-row preview data are staged in `configuration_imports_dtl`, bound to the authenticated operator, user and resource, and expire after 15 minutes. A scheduled cleanup removes expired previews. Confirm requests need only the staging UUID; the server does not accept client-supplied row content or validation results. A completed import stores a receipt so retrying the same confirm request returns the same counts without repeating writes. The UI can download rejected rows and their reasons as a formula-injection-safe CSV.

Templates: Areas use `area_code,area_name,description`; Components use `component_code,component_name,area_code,description`; Defect Conditions use `condition_code,condition_name,description,applicable_component_codes` (component codes separated by `|`); Severity Master uses `severity_code,severity_name,description,sort_order`; Aircraft resolve `fleet_name`; components resolve `area_code`; menu items resolve `parent_key`. Other templates are generated for Fleets, Defect Categories, Maintenance Teams, Users / memberships, Menus & submenus and Roles. Organizations have no import template or import endpoint because organization creation belongs to operator onboarding. Users imports can only grant a validated active application role to an existing enterprise identity; no password or identity-provider account can be imported. User import is create-only and the existing ADMIN-only configuration policy, role checks and membership foreign keys remain enforced.

Example preview response: `{"importId":"…","resource":"areas","validRows":3,"rejectedRows":1,"expiresAt":"…","rows":[{"rowNumber":2,"values":{"area_code":"CABIN"},"errors":[]}]}`. Confirmation returns `created`, `updated`, `skipped`, and `rejected` counts; update/skip counts are zero in create-only mode. Routes are ADMIN-authorized under `/api/v1/configuration/{resource}` and documented through Springdoc.

## Local setup

Requirements: Node.js compatible with Next 16, JDK 17, PostgreSQL, and network access to Maven Central for first dependency resolution. Docker Compose is an optional isolated PostgreSQL alternative.

1. Copy `.env.example` to `.env` and set the local PostgreSQL password. The example targets database `postgres` and schema `ecabin_ledger`, enables loopback-only `local-auth`, and selects workstation local evidence storage. Never use these development settings in staging or production. If using Compose's PostgreSQL instead, point the URL/user/password at its `ecabin` database and `ecabin` user.
2. Ensure that PostgreSQL is running. From `backend/`, run `./mvnw clean test`. This is the ordinary unit-test command; opt-in PostgreSQL checks are skipped unless their environment flags are enabled.
3. Initialize migrations and demo records explicitly from `backend/`. Flyway applies migrations during this command; `PostgresMigrationSmokeTest` itself verifies the configured database/schema, and only executes the demo seed when `ECABIN_SEED_DEMO=true`:

   ```sh
   set -a
   source ../.env
   set +a
   ECABIN_DB_VERIFY=true ECABIN_SEED_DEMO=true ./mvnw -Dtest=PostgresMigrationSmokeTest test
   ```

   The seed is repeatable and limited to the fixed synthetic Skyways Service demo tenant. It preserves the existing `abc@gmail.com` membership and administrator-owned Galley drafts and published versions. A seed-owned Galley draft is published only by the smoke test through the existing `OperationalFormService` validation. If an administrator-owned draft already exists, complete a new draft in Administration → Form Master → Galley Area by adding the required Aircraft Registration selector and publishing after validation; the reporting API explains this requirement until a valid version is published. Migrations run automatically on API startup, but demo data does not.
4. From `backend/`, start the API:

   ```sh
   set -a
   source ../.env
   set +a
   ./mvnw spring-boot:run
   ```

   The Maven run goal activates loopback-only `local-auth`. The profile issues short-lived demo tokens for the configured local test identities only when they have active memberships. `abc@gmail.com` remains unchanged; separate test identities are `admin.test@ecabin.local` (ADMIN), `auditor.test@ecabin.local` (AUDITOR), `reviewer.test@ecabin.local` (VIEW + REVIEW and Reviewer team membership), and `maintenance.test@ecabin.local` (MAINTENANCE_TECHNICIAN and maintenance team membership). All permissions remain tenant-scoped. Check `http://localhost:8080/actuator/health`.
5. Run `cd frontend && pnpm install && pnpm dev`; open `http://localhost:3000`. Choose a local test identity on the login page. The UI obtains a short-lived development token and sends it as a bearer token; the signing key changes when the backend restarts.

To inspect or verify the configured database, load the same local connection values as `.env` (the repository uses database `postgres` and schema `ecabin_ledger` by default). The smoke-test command in step 3 runs `SELECT current_database(), current_schema()` before it migrates or seeds. With `psql` installed, from `backend/` connect to the default local target using:

```sh
set -a
source ../.env
set +a
PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" psql -h localhost -p 5432 -U "$SPRING_DATASOURCE_USERNAME" -d postgres
```

If the configured JDBC URL uses a different database or host, update the `psql` connection arguments to match it. Once connected, run:

```sql
SET search_path TO ecabin_ledger;
SELECT current_database(), current_schema();
SELECT id, operator_id, code, name, isactive
FROM ecabin_ledger.cabin_areas_mst
WHERE UPPER(code) = 'GALLEY';
SELECT id, operator_id, form_key, name, area_id, purpose, workflow_stage, published_version_id, isactive
FROM ecabin_ledger.operational_forms_dtl
ORDER BY operator_id, form_key;
SELECT form_id, id, version_number, status, published_at
FROM ecabin_ledger.operational_form_versions_dtl
ORDER BY form_id, version_number;
```

On the synthetic demo tenant, expect one active `GALLEY` area. After the opt-in initialization, expect either the existing administrator-configured Galley report form or the published `LOCAL_DEMO_GALLEY_AREA` form mapped to that area, purpose `DEFECT_REPORT`, stage `INITIAL_REPORT`, and a published version whose configured fields pass backend publication validation. The demo version has seven fields, including Aircraft Registration and evidence. If an existing administrator-owned draft lacks the selector, initialization preserves it and the area forms API reports that it must be fixed and published through Form Master before it can be used.

The local profile, demo seed, and local filesystem evidence store are for workstation development only. Do not use local auth, local storage, the local signing key, or demo accounts in staging or production. `SUPERADMIN` remains reserved and unimplemented; `abc@gmail.com` retains its existing membership and local demo behavior, but this does not provide a separate platform principal or global administration APIs.

Environment variables are documented in `.env.example`. Separate development, test, staging and production credentials; inject secrets from the deployment secret manager. Never commit tokens, secrets or private keys.

## Verification

```sh
(cd backend && ./mvnw clean test)
(cd backend && ./mvnw clean package)
(cd frontend && pnpm lint)
(cd frontend && pnpm build)
```

The regular Maven build does not require a database: PostgreSQL migration/workflow tests are skipped unless `ECABIN_DB_VERIFY=true` and `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` are set. When enabled, integration test rows are rolled back. Add `ECABIN_SEED_DEMO=true` to apply the development seed (the fixture is idempotent). The Testcontainers tenant test runs when Docker is available and is skipped otherwise. Local demo authentication uses an ephemeral key and stops working when the backend restarts; it is strictly loopback-only and is not an enterprise login implementation.

## Deployment and operations

The API Dockerfile is `backend/Dockerfile` and expects `backend/` as its build context; the web Dockerfile is `frontend/Dockerfile` and expects `frontend/` as its build context. Deploy behind TLS and a trusted ingress; set exact CORS origins, gateway issuer/audience, DB pool sizing and managed DB credentials. Run Flyway using a migration credential before/with controlled rollout. Keep `/actuator/health` available to health checks; restrict metrics and detailed actuator endpoints to the private network. Use structured logs and request IDs; never log tokens, authorization headers or secrets. Configure managed PostgreSQL backups/PITR and regularly rehearse restore and rollback. Roll back application versions independently; use forward-compatible expand/migrate/contract DB changes.

## Known limitations before production

- Enterprise gateway details and browser token adapter have not been provided or verified.
- Role provisioning/admin APIs and complete endpoint-by-endpoint permission tests remain incomplete; the request authorization gate has unit coverage but not full Spring Security integration coverage.
- `SUPERADMIN` is reserved but not implemented. Its platform scope, controlled bootstrap, recovery, and audit process require an approved operational design.
- Full live PostgreSQL concurrency/security integration coverage requires Docker/Testcontainers; those tests were skipped in this environment. Reviewer membership and route SQL was validated against the local schema migration, but its end-to-end concurrent workflow cases remain to be added/run.
- Azure/Azurite object storage and ClamAV must be provisioned and configured in the target environment; no cloud account or malware scanner was available for this repository verification.
- Uploads that are stored but fail to commit their metadata may leave orphaned blobs; production needs a bounded orphan-cleanup/retention worker and operational alerting.
- Notifications are only an in-app data/service foundation; no delivery worker/channel is deployed.
- Demo seed and Testcontainers database migration require runtime verification in an environment with PostgreSQL/Docker.
- No end-to-end browser test or regulated maintenance-system certification evidence is included.

## Severity and permission administration

Flyway V20 adds tenant-scoped `defect_severities_mst` and a fixed, global `permissions_mst` reference catalog. Existing `defects_dtl.severity` values remain as their stable text snapshots; the new nullable `(operator_id, severity_id)` reference points to a same-tenant severity row. Existing defect history is preserved, while new reports require an active severity in the authenticated tenant. The four seeded names are starter UI labels only, not certified maintenance classifications or airworthiness thresholds. Severity changes use the existing GET/POST configuration API, server-side table paging/search/sorting/export, shared CSV template/preview/commit, soft-delete semantics, and append-only configuration audit events.

`permissions_mst` documents the server's fixed `Permission` enum and is intentionally read-only. There is no duplicate role-permission table: tenant assignments remain in `operator_roles_mst.permissions`, and `PermissionService` resolves those assignments for API authorization, `/me`, and permission-filtered navigation. The Role-Permission Mapping screen is read-only and displays each role description, granted codes, and the catalog's descriptions/restrictions. The Roles screen remains the existing administration path for supported role grants; grant codes are validated against the Java enum, ADMIN remains protected, and organization administrators cannot grant `ADMINISTER_ORGANIZATION` to another role or create `SUPERADMIN`. Role master changes continue to write an audit snapshot.

All three screens are available under Administration: **Severity Master**, **Permission Master**, and **Role-Permission Mapping**. Permission descriptions are maintained in V20 alongside the server-controlled catalog; changing them requires a reviewed Flyway migration. The V20 migration seeds default severities additively for current organizations and future organizations, and attaches these navigation entries when the existing Administration root is provisioned. To verify after a database migration, select each screen under Administration, inspect inactive severities with the status filter, add/edit/deactivate a severity, and report a defect using the active tenant severity. The optional `PostgresMigrationSmokeTest` checks the new resources, catalogs, menu entries, removal of the legacy cabin-zones table, and severity snapshot integrity.
