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

`operators` is the tenant root. `fleets` own `aircraft`; aircraft, defects, workflow records, attachments and notifications carry tenant ownership. Configuration masters include fleets, aircraft, operator memberships/roles, maintenance teams, cabin zones, defect categories and cabin components. `isactive` is a checked `SMALLINT`: `1` means active and `2` means soft-deleted. Existing aircraft/membership booleans were migrated to this field, and normal operational queries ignore rows with `isactive=2`. `operator_membership_teams` limits team-assigned corrective actions to active members explicitly assigned to that team. `defect_events` is append-only and deliberately has no soft-delete flag; Flyway history is also excluded. Use a separate migration DB role and runtime DB role in production; the migration trigger alone is not a substitute for DB privilege separation.

## Authentication, authorization and tenancy

Production has no local username/password or independent login provider: the enterprise gateway performs Microsoft SSO. The production backend expects a JWT bearer token, uses Spring's issuer discovery/JWKS decoder from `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, validates issuer/time and the `OAUTH2_AUDIENCE` audience, then resolves `operator_id` plus `sub` against an active `operator_memberships` row. The application role comes from that server-side membership, not a request field. Inactive or unknown membership is denied; provisioning never grants ADMIN automatically. A separate `local-auth` profile exists only for loopback workstation demos and must never be enabled in a deployed environment.

This is the repository's current contract, not a confirmed contract for the enterprise gateway. Before deployment, the gateway owner must confirm token format, issuer, audience, JWKS/signing algorithm and rotation, required claims, identity and organization claim mapping, role/group mapping, token forwarding, and the supported browser token adapter. Do not accept gateway identity headers or claims until documented and verified. Production browser integration expects `window.eCabinAuth.getAccessToken()`; the repository does not implement the enterprise gateway SDK/adapter. See the audit for detail.

Roles are `ADMIN`, `SUPERVISOR`, `INSPECTOR`, `MAINTENANCE_TECHNICIAN`, `QUALITY_COMPLIANCE`, and `VIEWER`. Backend permission checks are authoritative. Tenant IDs are resolved from the validated token and active membership; client-supplied tenant identity is not used. Add role/tenant authorization tests for every operational endpoint before launch.

## Defect workflow and audit

The intended state sequence is `REPORTED → UNDER_REVIEW → INSPECTION_REQUIRED → INSPECTION_IN_PROGRESS → INSPECTION_COMPLETE → ACTION_ASSIGNED → IN_PROGRESS → AWAITING_VERIFICATION → VERIFIED → APPROVAL_REQUIRED → CLOSED`. Reopening is explicit (`CLOSED → REOPENED`) and requires a reason. Inspection, action, verification and approval are separate API operations. Workflow events record actor, timestamp, state and evidence reference. Closed records cannot move through a normal transition.

Current evidence is represented by references. The attachment table is metadata scaffolding only: secure object storage, upload/download authorization, content inspection, signed URLs, retention and removal audit are not implemented. Do not treat the MVP as audit-ready until those controls and the gateway integration are complete.

## API

Base path: `/api/v1`. All operational routes require a validated bearer token and active organization membership.

| Method | Route | Purpose |
|---|---|---|
| GET | `/me` | Resolved organization, display name, application role and granted permissions |
| GET | `/navigation` | Menu and nested submenu items filtered by the authenticated user's permissions |
| GET | `/dashboard/summary` | Tenant-scoped summary counts |
| GET | `/reference-data` | Active categories, cabin zones and components for operational forms |
| GET | `/defects` | Server-paged tenant search by defect ID, registration, category, component, assigned person, aircraft type, date range, status, location, and current user's reports |
| POST | `/defects` | Report defect |
| GET | `/defects/{id}` | Tenant-scoped defect detail |
| POST | `/defects/{id}/transitions` | Apply allowed general transition |
| GET | `/defects/{id}/events` | Tenant-scoped audit timeline |
| GET | `/configuration/menu` | ADMIN-only master-data submenu metadata |
| GET | `/configuration/{resource}` | ADMIN-only tenant-scoped master records; optional `includeInactive=true` |
| POST | `/configuration/{resource}` | Create or update a master-data record |
| POST | `/configuration/{resource}/{id}/activate` | Reactivate a soft-deleted record with a reason |
| POST | `/configuration/{resource}/{id}/deactivate` | Soft-delete a record with a reason |
| POST | `/defects/{id}/inspections` | Record inspection outcome |
| POST | `/defects/{id}/actions` | Assign corrective action |
| POST | `/defects/{id}/actions/{actionId}/start` | Start action |
| POST | `/defects/{id}/actions/{actionId}/complete` | Complete corrective work |
| POST | `/defects/{id}/verification` | Independently verify work |
| POST | `/defects/{id}/approval` | Approve/reject closure |

Configuration resources are `organizations`, `fleets`, `aircraft`, `cabin-zones`, `defect-categories`, `components`, `maintenance-teams`, and `users`. All configuration mutations require an authenticated ADMIN role, resolve tenant identity from the validated principal, and create an audit event. User configuration grants an application membership to an existing enterprise identity; it does not create an identity-provider account. Deletion is a POST-based `isactive=2` change; organization records cannot be deleted, aircraft with defect history cannot be deactivated, and the last active administrator cannot be deactivated. OpenAPI is available at `/api-docs`. The API does not yet expose attachment upload/download.

## Local setup

Requirements: Node.js compatible with Next 16, JDK 17, PostgreSQL, and network access to Maven Central for first dependency resolution. Docker Compose is an optional isolated PostgreSQL alternative.

1. Copy `.env.example` to `.env` and set the local PostgreSQL password. The example targets database `postgres` and schema `ecabin_ledger`. It selects the loopback-only `local-auth` development profile for `abc@gmail.com`; this profile is only for local demos and must never be used in staging or production. If using Compose instead, point the URL/user/password at Compose's `ecabin` database and `ecabin` user.
2. Ensure the selected PostgreSQL service is running. Flyway creates the configured project schema and applies migrations on API startup.
3. From `backend/`, run `./mvnw spring-boot:run` directly. The Maven run goal automatically activates the loopback-only `local-auth` profile; database defaults are in `application.yml`, and optional environment overrides can still be set in the shell. The wrapper downloads the pinned Maven distribution if needed. This profile signs short-lived demo tokens in memory and allows only the seeded `abc@gmail.com` membership in the synthetic Skyways Service DEMO tenant. The local demo seed assigns that account ADMIN so the configuration screens can be exercised; this role assignment exists only in the demo fixture. When started, check `http://localhost:8080/actuator/health`.
4. In another terminal, source `.env` and run `ECABIN_DB_VERIFY=true ECABIN_SEED_DEMO=true ./mvnw -Dtest=PostgresMigrationSmokeTest test` from `backend/`. This idempotently seeds the synthetic demo tenant and the `abc@gmail.com` local membership after migrations.
5. Run `cd frontend && pnpm install && pnpm dev`; open `http://localhost:3000`. In Next.js development mode the UI requests a short-lived local demo token for `abc@gmail.com`; API calls carry it as a bearer token. The token is signed by an in-memory key that changes on backend restart, so the UI refreshes it when needed.

The local profile and demo seed are for workstation development only. Do not use the `local-auth` profile, local signing key, or demo account in staging or production.

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
- Role provisioning/admin APIs and complete endpoint-by-endpoint permission tests remain incomplete.
- Object storage and secure evidence attachment APIs are not implemented.
- Notifications are only an in-app data/service foundation; no delivery worker/channel is deployed.
- Demo seed and Testcontainers database migration require runtime verification in an environment with PostgreSQL/Docker.
- No end-to-end browser test or regulated maintenance-system certification evidence is included.
