import logging
import signal
import threading

from .config import Config
from .db import connect
from .mailer import MailError, send
from .outbox import begin_attempt, claim, complete, release_disabled, renew_lease, retry_time, settings_enabled, stable_message_id
from .templates import render

LOG = logging.getLogger("ecabin.email_worker")
STOP = threading.Event()


def process_job(config, job, connector=connect, mailer=send):
    worker_id = config.worker_id
    with connector(config.database_url) as conn:
        with conn.transaction():
            # Every write is stamped by the existing audit trigger; use a stable service actor.
            with conn.cursor() as cur:
                cur.execute("SELECT set_config('ecabin.actor_id', %s, true)", (worker_id,))
            if not renew_lease(conn, job, worker_id, config.lease_seconds):
                LOG.info("email job skipped id=%s reason=LEASE_NOT_OWNED", job["id"])
                return
            enabled = settings_enabled(conn, job["operator_id"], job["notification_type"])
    if not enabled:
        with connector(config.database_url) as conn:
            with conn.transaction():
                with conn.cursor() as cur:
                    cur.execute("SELECT set_config('ecabin.actor_id', %s, true)", (worker_id,))
                release_disabled(conn, job, worker_id)
        return

    # The final switch check and attempt increment are atomic. SMTP starts immediately
    # after commit; the small post-commit race is unavoidable. Database failures escape
    # to lease recovery instead of being mislabeled as SMTP failures.
    with connector(config.database_url) as conn:
        with conn.transaction():
            with conn.cursor() as cur:
                cur.execute("SELECT set_config('ecabin.actor_id', %s, true)", (worker_id,))
            attempt_number = begin_attempt(conn, job, worker_id)
            if attempt_number is None:
                release_disabled(conn, job, worker_id)
                return
            job["attempt_count"] = attempt_number

    try:
        payload = dict(job["payload"])
        payload.update(templateKey=job["template_key"], templateVersion=job["template_version"])
        message = render(payload, config.application_base_url)
    except (ValueError, KeyError, TypeError):
        outcome, code, sent = "PERMANENT_FAILURE", "INVALID_EMAIL_PAYLOAD", False
    else:
        try:
            mailer(config, job["recipient_email"], message, stable_message_id(job["id"]))
            outcome, code, sent = "SENT", None, True
        except MailError as exc:
            outcome, code, sent = ("PERMANENT_FAILURE" if exc.permanent else "RETRY_SCHEDULED"), exc.code, False
        except Exception:
            LOG.error("email delivery failed; details withheld")
            outcome, code, sent = "RETRY_SCHEDULED", "INTERNAL_DELIVERY_FAILURE", False
    retry_at = None if sent or outcome == "PERMANENT_FAILURE" else retry_time(job["attempt_count"], config.max_backoff_seconds)
    with connector(config.database_url) as conn:
        with conn.transaction():
            with conn.cursor() as cur:
                cur.execute("SELECT set_config('ecabin.actor_id', %s, true)", (worker_id,))
            complete(conn, job, worker_id, outcome, code, retry_at, sent)
    LOG.info("email job completed id=%s outcome=%s", job["id"], outcome)


def run(config=None):
    config = config or Config.from_env()
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    while not STOP.is_set():
        try:
            with connect(config.database_url) as conn:
                with conn.transaction():
                    with conn.cursor() as cur:
                        cur.execute("SELECT set_config('ecabin.actor_id', %s, true)", (config.worker_id,))
                    jobs = claim(conn, config.worker_id, config.batch_size, config.lease_seconds)
            for job in jobs:
                if STOP.is_set():
                    break
                try:
                    process_job(config, job)
                except Exception:
                    LOG.error("email job processing failed id=%s; job remains lease-protected", job.get("id"))
        except Exception:
            LOG.error("email worker database cycle failed; will retry")
        STOP.wait(config.poll_seconds)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run()

