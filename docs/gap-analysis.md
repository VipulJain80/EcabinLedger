# Existing implementation audit and current state

Reviewed the complete checked-out tree before changing it. This workspace has no Git metadata. At audit start there was no Maven Wrapper, Java runtime, or pre-existing backend tests; the repository has two Dockerfiles and a PostgreSQL compose service. A Maven Wrapper, JDK 17 build environment, workflow/RBAC tests, and a Testcontainers tenant-isolation test have since been added. The integration test is skipped when Docker is unavailable.

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
