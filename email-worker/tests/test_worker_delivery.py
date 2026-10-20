import contextlib
import unittest
from unittest.mock import patch, Mock

from email_worker.config import Config
from email_worker.mailer import MailError
from email_worker import worker


class FakeConnection:
    def transaction(self):
        return contextlib.nullcontext()

    def cursor(self):
        return FakeCursor()


class FakeCursor:
    def __enter__(self): return self
    def __exit__(self, *args): return False
    def execute(self, *args): pass


@contextlib.contextmanager
def fake_connect(_database_url):
    yield FakeConnection()


class DeliveryStateTests(unittest.TestCase):
    def config(self):
        return Config("postgresql://unused?sslmode=verify-full", "smtp", 2525, "u", "p", "starttls",
                      "alerts@example.test", "eCabin", 3, "https://ledger.example", "worker", 2, 60, 1, 300)

    def job(self):
        return {"id":"00000000-0000-4000-8000-000000000001", "operator_id":"tenant", "source_event_id":"event",
                "recipient_user_id":"user", "recipient_email":"person@example.test", "notification_type":"DEFECT_REPORTED",
                "template_key":"defect-workflow", "template_version":1,
                "payload":{"reference":"D-1", "eventKey":"DEFECT_REPORTED", "status":"REPORTED", "occurredAt":"2026-10-20T10:00:00Z"},
                "attempt_count":1, "max_attempts":8, "request_id":None}

    def run_job(self, enabled, mailer, attempt_number=1):
        with patch.object(worker, "connect", return_value=FakeConnection()), \
             patch.object(worker, "renew_lease", return_value=True), patch.object(worker, "begin_attempt", return_value=attempt_number), \
             patch.object(worker, "settings_enabled", return_value=enabled), \
             patch.object(worker, "complete") as complete, patch.object(worker, "release_disabled") as release:
            worker.process_job(self.config(), self.job(), connector=fake_connect, mailer=mailer)
            return complete, release

    def test_disabled_switch_retries_without_sending(self):
        mailer = Mock()
        completed, released = self.run_job(False, mailer)
        mailer.assert_not_called()
        released.assert_called_once()
        completed.assert_not_called()

    def test_switch_disabled_at_final_pre_send_check_does_not_send_or_consume_attempt(self):
        mailer = Mock()
        completed, released = self.run_job(True, mailer, attempt_number=None)
        mailer.assert_not_called()
        released.assert_called_once()
        completed.assert_not_called()

    def test_smtp_success_marks_sent(self):
        completed, _ = self.run_job(True, Mock())
        args, kwargs = completed.call_args
        self.assertEqual(args[3], "SENT")
        self.assertTrue(args[6])

    def test_transient_failure_schedules_retry_without_leaking_message(self):
        def fail(*_args): raise MailError("SMTP_TRANSPORT_FAILURE")
        completed, _ = self.run_job(True, fail)
        args, kwargs = completed.call_args
        self.assertEqual(args[3:5], ("RETRY_SCHEDULED", "SMTP_TRANSPORT_FAILURE"))

    def test_permanent_failure_is_marked_for_dead_letter(self):
        def fail(*_args): raise MailError("SMTP_PERMANENT", permanent=True)
        completed, _ = self.run_job(True, fail)
        args, kwargs = completed.call_args
        self.assertEqual(args[3:5], ("PERMANENT_FAILURE", "SMTP_PERMANENT"))


if __name__ == "__main__":
    unittest.main()
