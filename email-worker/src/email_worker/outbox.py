from datetime import datetime, timedelta, timezone
import random
import uuid

SCHEMA = "ecabin_ledger"


def claim(conn, worker_id: str, batch_size: int, lease_seconds: int):
    """Recover expired leases, then claim a bounded batch in one short transaction."""
    with conn.cursor() as cur:
        cur.execute(f"""
            UPDATE {SCHEMA}.email_outbox_dtl
               SET status=CASE WHEN attempt_count >= max_attempts THEN 'DEAD' ELSE 'RETRY' END,
                   next_attempt_at=CASE WHEN attempt_count >= max_attempts THEN next_attempt_at ELSE now() END,
                   lease_owner=NULL, lease_until=NULL, last_error_code='LEASE_EXPIRED'
             WHERE status='PROCESSING' AND lease_until < now()
        """)
        cur.execute(f"""
          UPDATE {SCHEMA}.email_outbox_attempts_dtl a
             SET outcome='LEASE_EXPIRED',completed_at=now(),error_code='LEASE_EXPIRED',updated_by=%s
            FROM {SCHEMA}.email_outbox_dtl o
           WHERE o.operator_id=a.operator_id AND o.id=a.outbox_id
             AND a.attempt_number=o.attempt_count AND a.outcome='STARTED'
             AND o.status IN ('RETRY','DEAD') AND o.last_error_code='LEASE_EXPIRED'
        """, (worker_id,))
        cur.execute(f"""
            WITH due AS (
              SELECT id FROM {SCHEMA}.email_outbox_dtl
               WHERE status IN ('PENDING','RETRY') AND next_attempt_at <= now()
                 AND attempt_count < max_attempts
                 AND EXISTS (
                   SELECT 1 FROM {SCHEMA}.email_notification_settings_mst g
                   JOIN {SCHEMA}.email_notification_event_settings_mst e USING(operator_id)
                    WHERE g.operator_id=email_outbox_dtl.operator_id
                      AND e.event_key=email_outbox_dtl.notification_type
                      AND g.is_enabled AND e.is_enabled
                 )
               ORDER BY next_attempt_at, created_at, id
               FOR UPDATE SKIP LOCKED LIMIT %s
            )
            UPDATE {SCHEMA}.email_outbox_dtl o
               SET status='PROCESSING', lease_owner=%s,
                   lease_until=now() + (%s * interval '1 second')
              FROM due WHERE o.id=due.id
            RETURNING o.id,o.operator_id,o.source_event_id,o.recipient_user_id,
                      o.recipient_email,o.notification_type,o.template_key,o.template_version,
                      o.payload,o.attempt_count,o.max_attempts,o.request_id
        """, (batch_size, worker_id, lease_seconds))
        return [dict(zip([col.name for col in cur.description], row)) for row in cur.fetchall()]


def settings_enabled(conn, tenant_id, event_key):
    with conn.cursor() as cur:
        cur.execute(f"""
          SELECT g.is_enabled AND e.is_enabled
            FROM {SCHEMA}.email_notification_settings_mst g
            JOIN {SCHEMA}.email_notification_event_settings_mst e USING(operator_id)
           WHERE g.operator_id=%s AND e.event_key=%s
        """, (tenant_id, event_key))
        row = cur.fetchone()
        return bool(row and row[0])


def release_disabled(conn, job, worker_id, delay_seconds=60):
    """Pause a claimed row without consuming a delivery attempt while a switch is off."""
    with conn.cursor() as cur:
        cur.execute(f"""
          UPDATE {SCHEMA}.email_outbox_dtl
             SET status='RETRY',
                 next_attempt_at=now() + (%s * interval '1 second'),
                 lease_owner=NULL,lease_until=NULL,last_error_code='DELIVERY_DISABLED',updated_by=%s
           WHERE operator_id=%s AND id=%s AND status='PROCESSING' AND lease_owner=%s
        """, (delay_seconds, worker_id, job["operator_id"], job["id"], worker_id))
        if cur.rowcount != 1:
            raise RuntimeError("LEASE_LOST")


def renew_lease(conn, job, worker_id, lease_seconds):
    with conn.cursor() as cur:
        cur.execute(f"""
          UPDATE {SCHEMA}.email_outbox_dtl
             SET lease_until=now() + (%s * interval '1 second'),updated_by=%s
           WHERE operator_id=%s AND id=%s AND status='PROCESSING'
             AND lease_owner=%s AND lease_until>now()
        """, (lease_seconds, worker_id, job["operator_id"], job["id"], worker_id))
        return cur.rowcount == 1


def begin_attempt(conn, job, worker_id):
    """Count and audit an attempt only when both switches are still enabled."""
    with conn.cursor() as cur:
        cur.execute(f"""
          UPDATE {SCHEMA}.email_outbox_dtl o
             SET attempt_count=o.attempt_count+1,updated_by=%s
           WHERE o.operator_id=%s AND o.id=%s AND o.status='PROCESSING'
             AND o.lease_owner=%s AND o.lease_until>now() AND o.attempt_count<o.max_attempts
             AND EXISTS (
               SELECT 1 FROM {SCHEMA}.email_notification_settings_mst g
               JOIN {SCHEMA}.email_notification_event_settings_mst e USING(operator_id)
                WHERE g.operator_id=o.operator_id AND e.event_key=o.notification_type
                  AND g.is_enabled AND e.is_enabled
             )
          RETURNING o.attempt_count
        """, (worker_id, job["operator_id"], job["id"], worker_id))
        row = cur.fetchone()
        if row is None:
            return None
        attempt_number = row[0]
        cur.execute(f"""
          INSERT INTO {SCHEMA}.email_outbox_attempts_dtl
            (operator_id,outbox_id,attempt_number,started_at,outcome,request_id,created_by,updated_by)
          VALUES (%s,%s,%s,now(),'STARTED',%s,%s,%s)
          ON CONFLICT (operator_id,outbox_id,attempt_number) DO NOTHING
        """, (job["operator_id"], job["id"], attempt_number, job["request_id"], worker_id, worker_id))
        return attempt_number


def complete(conn, job, worker_id, outcome, error_code=None, retry_at=None, sent=False):
    status = "SENT" if sent else ("DEAD" if outcome == "PERMANENT_FAILURE" or job["attempt_count"] >= job["max_attempts"] else "RETRY")
    with conn.cursor() as cur:
        cur.execute(f"""
          UPDATE {SCHEMA}.email_outbox_dtl
             SET status=%s, sent_at=CASE WHEN %s THEN now() ELSE NULL END,
                 next_attempt_at=COALESCE(%s,next_attempt_at), lease_owner=NULL,lease_until=NULL,
                 last_error_code=%s,updated_by=%s
           WHERE operator_id=%s AND id=%s AND status='PROCESSING' AND lease_owner=%s
        """, (status, sent, retry_at, error_code, worker_id, job["operator_id"], job["id"], worker_id))
        if cur.rowcount != 1:
            raise RuntimeError("LEASE_LOST")
        cur.execute(f"""
          UPDATE {SCHEMA}.email_outbox_attempts_dtl
             SET completed_at=now(),outcome=%s,error_code=%s,updated_by=%s
           WHERE operator_id=%s AND outbox_id=%s AND attempt_number=%s AND outcome='STARTED'
        """, (outcome, error_code, worker_id, job["operator_id"], job["id"], job["attempt_count"]))


def retry_time(attempt_count: int, max_backoff: int, rng=random.random) -> datetime:
    ceiling = min(max_backoff, 30 * (2 ** max(0, min(attempt_count - 1, 16))))
    delay = min(max_backoff, max(1, int(ceiling * (0.75 + rng() * 0.5))))
    return datetime.now(timezone.utc) + timedelta(seconds=delay)


def stable_message_id(job_id) -> str:
    return f"<ecabin-{uuid.UUID(str(job_id))}@email.ecabin-ledger.invalid>"

