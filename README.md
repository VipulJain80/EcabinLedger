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

The API is a modular monolith with boundaries for defect workflow, inspections, corrective actions, verification/approval, audit and notifications. Keep the relational database authoritative. Scale first with stateless API replicas, bounded DB pools, query/index tuning, and background work for notifications and reports. Likely bottlenecks are tenant-wide dashboard aggregation, audit growth, attachment transfer, and database connections. Roadmap: (1) modular monolith + relational DB, (2) cache/background workers, (3) read replicas/search, (4) extract measured high-load modules, (5) multi-region only when operational needs justify it.

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
  service/                         defect lifecycle and operational workflows
backend/src/main/resources/db/     seed data and Flyway schema migrations
backend/src/test/java/             workflow, RBAC and PostgreSQL isolation tests
backend/Dockerfile                 API container build
docs/gap-analysis.md               repository audit and prioritized gaps
compose.yaml                       local PostgreSQL only
```

## Data model

`operators` is the tenant root. `fleets` own `aircraft`; aircraft, defects, workflow records, attachments and notifications carry tenant ownership. Defects retain location, category, component, severity, current status, assignee and due date. Separate tables hold inspections, corrective actions, verification, closure approvals and attachment metadata. `isactive` is a checked `SMALLINT`: `1` means active and `2` means soft-deleted. Existing aircraft/membership booleans were migrated to this field, and normal operational queries ignore rows with `isactive=2`. `operator_membership_teams` limits team-assigned corrective actions to active members explicitly assigned to that team. `defect_events` is append-only and deliberately has no soft-delete flag; Flyway history is also excluded. Use a separate migration DB role and runtime DB role in production; the migration trigger alone is not a substitute for DB privilege separation.

## Authentication, authorization and tenancy

There is no local username/password or independent login provider. The gateway performs Microsoft SSO. The current backend configuration proves only that the API expects a JWT bearer token, uses Spring's issuer discovery/JWKS decoder from `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, validates issuer/time and the `OAUTH2_AUDIENCE` audience, then resolves `operator_id` plus `sub` against an active `operator_memberships` row. The application role comes from that server-side membership, not a request field. Inactive or unknown membership is denied; provisioning never grants ADMIN automatically.

This is the repository's current contract, not a confirmed contract for the enterprise gateway. Before deployment, the gateway owner must confirm token format, issuer, audience, JWKS/signing algorithm and rotation, required claims, identity and organization claim mapping, role/group mapping, token forwarding, and the supported browser token adapter. Do not accept gateway identity headers or claims until documented and verified. Browser integration currently expects `window.eCabinAuth.getAccessToken()`; the repository does not implement the gateway SDK/adapter. See the audit for detail.

Roles are `ADMIN`, `SUPERVISOR`, `INSPECTOR`, `MAINTENANCE_TECHNICIAN`, `QUALITY_COMPLIANCE`, and `VIEWER`. Backend permission checks are authoritative. Tenant IDs are resolved from the validated token and active membership; client-supplied tenant identity is not used. Add role/tenant authorization tests for every operational endpoint before launch.

## Defect workflow and audit

The intended state sequence is `REPORTED → UNDER_REVIEW → INSPECTION_REQUIRED → INSPECTION_IN_PROGRESS → INSPECTION_COMPLETE → ACTION_ASSIGNED → IN_PROGRESS → AWAITING_VERIFICATION → VERIFIED → APPROVAL_REQUIRED → CLOSED`. Reopening is explicit (`CLOSED → REOPENED`) and requires a reason. Inspection, action, verification and approval are separate API operations. Workflow events record actor, timestamp, state and evidence reference. Closed records cannot move through a normal transition.

Current evidence is represented by references. The attachment table is metadata scaffolding only: secure object storage, upload/download authorization, content inspection, signed URLs, retention and removal audit are not implemented. Do not treat the MVP as audit-ready until those controls and the gateway integration are complete.

## API

Base path: `/api/v1`. All operational routes require a validated bearer token and active organization membership.

| Method | Route | Purpose |
|---|---|---|
| GET | `/me` | Resolved organization, display name and application role |
| GET | `/dashboard/summary` | Tenant-scoped summary counts |
| GET | `/defects` | Server-paged tenant defect register filtered by status and location |
| POST | `/defects` | Report defect |
| GET | `/defects/{id}` | Tenant-scoped defect detail |
| POST | `/defects/{id}/transitions` | Apply allowed general transition |
| GET | `/defects/{id}/events` | Tenant-scoped audit timeline |
| POST | `/defects/{id}/inspections` | Record inspection outcome |
| POST | `/defects/{id}/actions` | Assign corrective action |
| POST | `/defects/{id}/actions/{actionId}/start` | Start action |
| POST | `/defects/{id}/actions/{actionId}/complete` | Complete corrective work |
| POST | `/defects/{id}/verification` | Independently verify work |
| POST | `/defects/{id}/approval` | Approve/reject closure |

OpenAPI is available at `/api-docs`. The API does not yet expose attachment upload/download or a full aircraft/fleet administration API.

## Local setup

Requirements: Node.js compatible with Next 16, JDK 17, PostgreSQL, and network access to Maven Central for first dependency resolution. Docker Compose is an optional isolated PostgreSQL alternative.

1. Copy `.env.example` to `.env`; set the local PostgreSQL password and issuer/audience from an approved test gateway. The example targets database `postgres` and schema `ecabin_ledger`. If using Compose instead, point the URL/user/password at Compose's `ecabin` database and `ecabin` user. A test token must be signed by the configured issuer, have the configured audience, `operator_id` UUID and `sub`; add a matching active membership row. Do not use production tokens in local environments.
2. Ensure the selected PostgreSQL service is running. Flyway creates the configured project schema and applies migrations on API startup.
3. Run the backend with `cd backend && ./mvnw spring-boot:run` (the wrapper downloads the pinned Maven distribution).
4. Run `cd frontend && pnpm install && pnpm dev`; open `http://localhost:3000`.
5. For local exploration only, apply `backend/src/main/resources/db/dev-seed.sql` after migrations. It creates the user-requested `Air India Express (DEMO)` operator with entirely synthetic aircraft registrations, users, defects, workflow records and audit entries. The seed must never run in production.

Environment variables are documented in `.env.example`. Separate development, test, staging and production credentials; inject secrets from the deployment secret manager. Never commit tokens, secrets or private keys.

## Verification

```sh
(cd backend && ./mvnw clean test)
(cd backend && ./mvnw clean package)
(cd frontend && pnpm lint)
(cd frontend && pnpm build)
```

The regular Maven build does not require a database: PostgreSQL migration/workflow tests are skipped unless `ECABIN_DB_VERIFY=true` and `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` are set. When enabled, integration test rows are rolled back. Add `ECABIN_SEED_DEMO=true` to apply the development seed (the fixture is idempotent). The Testcontainers tenant test runs when Docker is available and is skipped otherwise.

## Deployment and operations

The API Dockerfile is `backend/Dockerfile` and expects `backend/` as its build context; the web Dockerfile is `frontend/Dockerfile` and expects `frontend/` as its build context. Deploy behind TLS and a trusted ingress; set exact CORS origins, gateway issuer/audience, DB pool sizing and managed DB credentials. Run Flyway using a migration credential before/with controlled rollout. Keep `/actuator/health` available to health checks; restrict metrics and detailed actuator endpoints to the private network. Use structured logs and request IDs; never log tokens, authorization headers or secrets. Configure managed PostgreSQL backups/PITR and regularly rehearse restore and rollback. Roll back application versions independently; use forward-compatible expand/migrate/contract DB changes.

## Known limitations before production

- Enterprise gateway details and browser token adapter have not been provided or verified.
- Role provisioning/admin APIs and complete endpoint-by-endpoint permission tests remain incomplete.
- Object storage and secure evidence attachment APIs are not implemented.
- Notifications are only an in-app data/service foundation; no delivery worker/channel is deployed.
- Demo seed and Testcontainers database migration require runtime verification in an environment with PostgreSQL/Docker.
- No end-to-end browser test or regulated maintenance-system certification evidence is included.
