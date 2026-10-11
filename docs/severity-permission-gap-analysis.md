# Severity and permission administration audit

Audit conducted before implementation changes on 2026-10-10. Scope: existing PostgreSQL/Flyway schema, backend configuration and security services, frontend administration/navigation, defect reporting, demo seed, and available tests.

## Findings

| Feature | Status | Evidence and gap |
|---|---|---|
| Severity Master | Missing | Severity is a string on `defects_dtl` with a LOW/MEDIUM/HIGH/CRITICAL check inherited from V1. No severity master table, API resource, Administration item, or dynamic report dropdown exists. `DefectService` validates against a hardcoded set; the form has four hardcoded options. |
| Permission Master | Implemented but incomplete | Authorization codes are a fixed `Permission` enum. Tenant role definitions store a pipe-delimited set in `operator_roles_mst.permissions`; this set is used by the server for `/me`, navigation, and service checks. The catalog has no human-readable descriptions or administration read view. |
| Role-to-permission mapping | Implemented but incomplete | The existing role master edits the `operator_roles_mst.permissions` set, so there is no need for a second mapping table. Codes are validated against the Java enum, `SUPERADMIN` is reserved, built-in ADMIN is protected, and the configuration mutations write audit snapshots. The UI exposes codes without descriptions and does not present an explanatory mapping view. |
| Permission administration UI | Implemented but incomplete | Administration has a Roles screen with permission selection. There is no Permission Master / description view or dedicated role-to-permission explanation screen. |
| Permission enforcement | Implemented but incomplete | Backend checks use database grants resolved through the authenticated tenant membership; navigation uses those same grants. The frontend has a static permission type/transition map for UX. Backend catalog remains authoritative, but permissions are not documented in the application and there is no test that every code has a useful description. Team-scoped checks remain independently enforced. |

## Existing data model and policy decisions

- Application tables use `_mst` for master/configuration data and `_dtl` for operational records. New tables will follow that convention.
- Existing tenant role-permission assignments remain the source used by `PermissionService`; a normalized second mapping system would duplicate and risk conflicting with production authorization. The supported code catalog will be fixed/read-only and system-owned.
- `defects_dtl.severity` is an existing, non-null text code. Existing rows must remain valid. A nullable tenant-scoped `severity_id` will link new reports to active master rows while retaining the stable code snapshot for historical display and query compatibility.
- Starter severity labels are operational UI labels only. They are not airworthiness classifications, maintenance limits, or approved safety thresholds.
- `/api/v1/configuration/**` is protected by `ADMINISTER_ORGANIZATION`. Role and master mutations already use server-derived actor metadata and append configuration events to the existing immutable `defect_events_dtl` audit table.
- Organization role grants remain tenant-scoped. No tenant-facing API will create platform identities or assign reserved `SUPERADMIN` authority.

## Verification inventory

Inspected all Flyway migrations, role/permission classes, configuration resource definitions, configuration controllers and services, defect DTO/service/report form, authenticated `/me` and navigation paths, team scope checks, demo seed, frontend configuration screen and shared table, and existing unit/PostgreSQL integration test suites.

## Implementation and verification result (2026-10-10)

### Final status

| Feature | Final status | Implementation |
|---|---|---|
| Severity Master | Fully implemented and PostgreSQL-verified | `defect_severities_mst` is tenant-scoped, indexed, audited, soft-deletable, seeded idempotently for existing and future tenants. The defect code remains a text snapshot; new defects also hold a same-tenant severity reference. |
| Permission Master | Fully implemented and PostgreSQL-verified as read-only | `permissions_mst` has one unique, fixed description per server `Permission` enum. Administrators can view/search/page/export it; mutation APIs are denied. |
| Role-to-permission mapping | Fully implemented and PostgreSQL-verified as read-only view | Reuses `operator_roles_mst.permissions`; no duplicate mapping table. The view shows role descriptions, assigned permission codes and permission descriptions/restrictions. Existing Roles master remains the supported assignment screen. |
| Permission Administration UI | Fully implemented | Administration exposes Severity Master, Permission Master, and Role-Permission Mapping through the existing configuration tables and menu authorization. |
| Permission enforcement | Verified for the new resources | Existing backend role grants remain authoritative. Permission and mapping reads require `ADMINISTER_ORGANIZATION`; POST to either read-only view is forbidden. A custom role cannot receive `ADMINISTER_ORGANIZATION` or reserved `SUPERADMIN`. |

### Database and API decisions

- V20 adds `defect_severities_mst`, `permissions_mst`, nullable `defects_dtl.severity_id`, a tenant-consistent composite FK, the required indexes, role descriptions, and additive tenant/navigation seed triggers. It removes the original four-code database check and widens the preserved text code to 24 characters so tenant-configured stable codes can be used without altering legacy snapshots.
- The permissions catalog contains exactly the 12 existing Java permission enum codes. The catalog is documentation, not an authorization source. Assignment and enforcement remain controlled by the enum and `operator_roles_mst.permissions`.
- `GET /api/v1/configuration/severities` and existing generic POST/activate/deactivate endpoints are reused. Severity import/export reuses the existing configuration pipeline and CSV staging.
- `GET /api/v1/configuration/permissions` and `GET /api/v1/configuration/role-permissions` reuse the generic read/table/export APIs; create/update/activation/deactivation are explicitly denied.
- `/api/v1/reference-data` returns active severities. New defect writes resolve the code under the authenticated tenant and require active status. Historical defects retain their severity text and resolve the label even after soft-deactivation.

### Actual verification

- JDK: OpenJDK 17.0.20.1.
- `./mvnw clean test`: passed; 36 tests discovered, 22 executed successfully, 14 skipped (database-gated local tests and Testcontainers without Docker).
- PostgreSQL 18.6 migration smoke plus seed test using the requested local database/schema: passed; V20 applied, permission catalog matched the enum, existing demo seed remained idempotent, and the existing `abc@gmail.com` identity snapshot was unchanged.
- PostgreSQL operational integration suite: passed; 12 tests, including permission API authorization, new-organization severity/navigation seeding, severity create/deactivate/reactivate, duplicate-code rejection, cross-tenant custom-code rejection, and historical severity display.
- Combined PostgreSQL run: 13 tests passed, 0 failures/errors/skips.
- `./mvnw clean package`: passed and produced the Spring Boot package.
- `pnpm lint`: passed.
- `pnpm build`: passed on Next.js 16.3.3.
- `git diff --check`: passed.

### Remaining limitations

- Flyway emitted a compatibility warning because the local server is PostgreSQL 18.6 while this Flyway version reports testing through PostgreSQL 17. The migration and integration suites passed on 18.6; production should use a supported PostgreSQL release or upgrade Flyway with its own compatibility test.
- `permissions_mst` descriptions intentionally require a reviewed code/migration change; administrators cannot redefine an authorization code's meaning.
- Editable role grants remain limited to the existing Roles master. The dedicated Role-Permission Mapping view is read-only to avoid a parallel privilege-management mechanism. Tenant ADMIN remains protected; `SUPERADMIN` is still reserved and not implemented.
- Docker/Testcontainers tenant-isolation coverage remained skipped because Docker is not available. The local PostgreSQL migration and operational integration suites did run successfully.
