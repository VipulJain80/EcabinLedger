# eCabin Ledger Email Worker

## 1. Purpose and architecture

The Spring Boot application writes notification jobs to PostgreSQL in the same transaction as their workflow event. This separately operated Python process claims committed jobs and sends email. SMTP is never called by a workflow request.

## 2. Relationship to Spring Boot, V62, and in-app notifications

The worker consumes only `ecabin_ledger.email_outbox_dtl`, `email_outbox_attempts_dtl`, and reads the two email settings tables created by V62. It does not read or write `notifications_dtl`, defects, workflow events, or configuration. In-app notifications remain a separate channel. Email is disabled by default globally and for every event.

The checked-in backend currently has no authoritative verified email/profile source. Spring enqueueing therefore requires an implementation of `VerifiedEmailDirectory`; absent that approved provider, recipients are skipped and no addresses are guessed. No production delivery is enabled by this repository alone.

## 3. Directory structure

```text
email-worker/
  requirements.txt
  .env.example
  src/email_worker/{config,db,outbox,mailer,templates,worker,health}.py
  tests/test_worker_core.py
  deployment/EmailWorkerService.xml.example
```

## 4. Python version and installation

Use the Python version approved for the target Windows Server (Python 3.11 or newer is expected; confirm with operations). Create an isolated virtual environment:

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

`psycopg[binary]` is pinned in `requirements.txt`. Validate the pinned wheel against the organization's package and vulnerability review before production rollout.

## 5. Local development configuration

Copy `.env.example` to a private local environment file and set values only from an approved development configuration. The worker reads process environment variables; export them from a protected shell or use an approved service secret-injection mechanism. It does not parse `.env` files itself. Never commit populated configuration.

`DATABASE_URL` must use `sslmode=verify-full`; provide the approved trust root in the connection string. SMTP host, port, TLS mode, sender, and credentials are deliberately placeholders. The process refuses placeholder/missing values and refuses HTTP application URLs.

## 6. Database role and least-privilege access

Create a dedicated worker login through the DBA procedure. It must not own objects, run Flyway, or have rights on workflow or inbox tables. Grant only `SELECT` on `email_notification_settings_mst` and `email_notification_event_settings_mst`, and the required `SELECT`, `UPDATE` on `email_outbox_dtl`, and `SELECT`, `INSERT`, `UPDATE` on `email_outbox_attempts_dtl`. Confirm whether the existing audit trigger functions and table privileges require additional narrowly scoped privileges in a disposable PostgreSQL test before production. Do not grant schema ownership or broad table access.

Example template (review with DBA; identifiers are placeholders):

```sql
GRANT USAGE ON SCHEMA ecabin_ledger TO EMAIL_WORKER_ROLE;
GRANT SELECT ON ecabin_ledger.email_notification_settings_mst,
  ecabin_ledger.email_notification_event_settings_mst TO EMAIL_WORKER_ROLE;
GRANT SELECT, UPDATE ON ecabin_ledger.email_outbox_dtl TO EMAIL_WORKER_ROLE;
GRANT SELECT, INSERT, UPDATE ON ecabin_ledger.email_outbox_attempts_dtl TO EMAIL_WORKER_ROLE;
```

V62 requires operational roles to retain their normal ownership/trigger permissions. Test audit trigger behavior for a non-owner worker role before provisioning it.

## 7. SMTP FalconID configuration

Set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_TLS_MODE`, `SMTP_FROM_ADDRESS`, and optional `SMTP_FROM_NAME` through an approved secret/configuration store. Do not infer FalconID settings from defaults. The infrastructure owner must provide the hostname, port, TLS mode, authentication method, and approved sender identity. Secrets must never be placed in source, WinSW XML, command history, or logs.

## 8. TLS and certificate validation

PostgreSQL requires hostname and CA validation (`verify-full`). SMTP uses Python's default certificate-verifying SSL context for either explicitly configured `starttls` or `implicit_tls`. No certificate-validation bypass exists. Only deploy after the provider-approved TLS mode and certificate chain are confirmed. This code does not establish which FalconID mode is approved.

## 9. Template and payload format

Template `defect-workflow` version `1` accepts only a small JSON payload: defect ID, reference, event key, current status, and occurrence timestamp. It omits notes, free text, evidence, attachments, and unrestricted URLs. Rendering escapes HTML values. The link points to the authenticated application shell; a defect-specific deep-link contract has not been verified.

## 10. Job lifecycle and idempotency

Workers claim at most `BATCH_SIZE` due rows in a short transaction using `FOR UPDATE SKIP LOCKED`, set `PROCESSING`, and place a lease. The claim query skips jobs whose global or event switch is off. Before processing a job, the worker renews its lease only if it still owns an unexpired claim. In a final short transaction, it rechecks both switches, increments the attempt count, and inserts the attempt-start record. SMTP runs only after that transaction commits. Final updates require the same lease owner. V62's unique key `(operator_id, source_event_id, recipient_user_id, notification_type)` makes enqueue idempotent. A stable Message-ID is derived from the outbox UUID.

SMTP delivery is at-least-once, not exactly-once. A server can accept a message and the worker can fail before the `SENT` update commits; retry may produce a duplicate.

## 11. Retry, lease recovery, and dead letters

Expired processing leases are returned to `RETRY` (or `DEAD` if attempts are exhausted), and the open attempt is marked `LEASE_EXPIRED`. Transient delivery failures use bounded exponential backoff with jitter. Permanent failures and exhausted attempts become `DEAD`. Error records contain only safe categories, not SMTP credentials, full response bodies, or message content. Alert on dead jobs and repeated worker/database failures.

## 12. Global and event switches

Both V62 switches must be enabled at enqueue time and are checked again immediately before SMTP. The worker does not claim jobs while either switch is off. If a switch is disabled after claim, the worker does not send; it releases the row to `RETRY` with a bounded delay, does not consume a delivery attempt, and records a safe `DELIVERY_DISABLED` code. Re-enabling does not create new rows. There is an unavoidable small race between the final setting read and SMTP acceptance; the worker does not hold a database lock while sending.

Settings changes are audited by V62/V63 triggers in the append-only defect event stream and require transaction-local `ecabin.actor_id`; set that value to the authorized operator identity in the same transaction. These are prepared-statement examples (`?` values are bound by the authorized operations client); execute through the approved administrative procedure, not by the worker:

```sql
BEGIN;
SELECT set_config('ecabin.actor_id', ?, true);
SELECT set_config('ecabin.request_id', ?, true);
UPDATE ecabin_ledger.email_notification_settings_mst
   SET is_enabled = ?
 WHERE operator_id = ?;
UPDATE ecabin_ledger.email_notification_event_settings_mst
   SET is_enabled = ?
 WHERE operator_id = ? AND event_key = ?;
COMMIT;
```

Per-event changes use the same transaction-local actor setup and update `email_notification_event_settings_mst` by tenant and `event_key`. Keep global and event switches off until profile, SMTP, security, and operational reviews pass.

## 13. Windows Server deployment

Use an organization-approved Windows Server version, Python installation, deployment path, service account, secret provider, and patching process. Install to a restricted directory; grant the service account read/execute on code and write access only to its log directory. Provision secrets outside the repository.

## 14. WinSW installation

Obtain the WinSW binary from its approved distribution channel, verify its checksum/signature using organizational policy, and keep the binary outside source control. Copy `deployment/EmailWorkerService.xml.example`, replace only approved absolute paths, and configure the service identity using the approved WinSW installation procedure. Never put passwords in XML.

## 15. Service lifecycle

After installation, use the approved WinSW executable:

```powershell
 .\EmailWorkerService.exe install
 .\EmailWorkerService.exe start
 .\EmailWorkerService.exe status
 .\EmailWorkerService.exe stop
 .\EmailWorkerService.exe uninstall
```

The worker handles SIGTERM/SIGINT and stops claiming work on shutdown. An in-flight SMTP request is bounded by `SMTP_TIMEOUT_SECONDS` and the service stop timeout.

## 16. Logging, health, monitoring, and alerting

Logs include process errors and job UUID/outcome only; never recipient address, payload, credentials, SQL, or SMTP response text. WinSW rotates stdout/stderr files. Monitor service availability, DB connectivity, pending/retry/dead counts, oldest pending age, lease expirations, and send failures using approved observability tooling. A separate HTTP health endpoint is not exposed.

## 17. Firewall and network paths

Allow outbound-only Windows Server traffic to the approved PostgreSQL hostname/port with TLS verification, and the approved FalconID SMTP hostname/port with the provider-approved TLS mode. Do not expose PostgreSQL publicly, allow inbound Internet access to the worker, or use broad egress rules. Confirm DNS, certificate chain, time synchronization, routing, and hostname allowlists before rollout. Ports and hosts are unresolved and must not be guessed.

## 18. Credential rotation

Use a managed secret store or service environment injection, rotate database and SMTP credentials through their owners, restart/reload the service according to the secret provider procedure, and confirm successful connection without displaying secrets. Revoke prior credentials after validation.

## 19. Troubleshooting

- Configuration failure: check required variables and remove placeholder values.
- Database TLS failure: verify CA chain, DNS name, system clock, and `verify-full` URL.
- No email rows: settings default disabled; also confirm a verified profile provider is installed and recipient is eligible.
- Repeated retries: inspect safe error categories and connectivity; do not paste SMTP transcripts with credentials.
- Lease expiry: check worker stop/crash events and ensure lease exceeds normal SMTP timeout plus processing overhead.
- No delivery despite an enabled switch: inspect event switch, recipient membership, verified identity mapping, and dead-letter counts.

## 20. Tests

Run core worker tests without sending email:

```powershell
$env:PYTHONPATH = 'src'
python -m unittest discover -s tests -v
```

PostgreSQL claim concurrency, trigger permissions, rollback, and lease tests require a disposable PostgreSQL database initialized by the repository's migration suite. They have not been run as part of this change. Never target shared, staging, or production databases.

## 21. Rollback and safe shutdown

Disable the global setting through the approved audited procedure, stop the Windows service, and leave unsent jobs intact for a later controlled restart. Do not delete outbox rows or reverse V62/V63. Roll back worker code by restoring the prior reviewed package and WinSW configuration. A paused job resumes only after both switches are enabled and remains idempotent.

## 22. Known limitations and production blockers

- No authoritative profile table, email column, verification flag, or subject-to-profile mapping is present in this repository. The `VerifiedEmailDirectory` provider is required before enqueue can create jobs.
- FalconID hostname, port, TLS mode, auth method, and sender identity are not confirmed.
- Production database endpoint, CA trust material, and restricted worker credentials are not supplied.
- Production application base URL is not supplied.
- Approved retention, alert thresholds, and operational ownership are unresolved.
- The product policy for which specific maintenance/auditor recipients receive each event needs review; current code follows existing in-app team membership queries where they exist, and closure submission targets active configured closure-team members with `APPROVE`.
- PostgreSQL integration tests are required to validate non-owner trigger/table grants and concurrent worker claims before production.
