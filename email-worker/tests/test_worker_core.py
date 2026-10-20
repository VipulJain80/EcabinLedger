import unittest
import time

from email_worker.config import Config, ConfigurationError
from email_worker.outbox import retry_time, stable_message_id
from email_worker.templates import render


class WorkerCoreTests(unittest.TestCase):
    def valid_env(self):
        return {
            "DATABASE_URL": "postgresql://worker:secret@db.example:9999/app?sslmode=verify-full",
            "SMTP_HOST": "smtp.example", "SMTP_PORT": "2525", "SMTP_USERNAME": "worker",
            "SMTP_PASSWORD": "secret", "SMTP_TLS_MODE": "starttls", "SMTP_FROM_ADDRESS": "alerts@example.com",
            "APPLICATION_BASE_URL": "https://ledger.example", "WORKER_ID": "test-worker",
        }

    def test_configuration_requires_verified_database_tls(self):
        env = self.valid_env()
        self.assertEqual(Config.from_env(env).smtp_tls_mode, "starttls")
        env["DATABASE_URL"] = env["DATABASE_URL"].replace("verify-full", "require")
        with self.assertRaises(ConfigurationError):
            Config.from_env(env)

    def test_configuration_fails_closed_on_placeholders(self):
        env = self.valid_env()
        env["SMTP_PASSWORD"] = "SET_IN_SECRET_STORE"
        with self.assertRaises(ConfigurationError):
            Config.from_env(env)

    def test_template_escapes_user_controlled_values_and_uses_shell_link(self):
        payload = {"templateKey":"defect-workflow", "templateVersion":1, "eventKey":"DEFECT_REPORTED",
                   "reference":"<script>alert(1)</script>", "status":"REPORTED & NEW", "occurredAt":"2026-10-20T10:00:00Z"}
        message = render(payload, "https://ledger.example")
        self.assertNotIn("<script>", message.html)
        self.assertIn("&lt;script&gt;", message.html)
        self.assertIn("REPORTED &amp; NEW", message.html)
        self.assertIn("https://ledger.example/ecabin/loginhome", message.text)

    def test_unknown_or_incomplete_template_payload_is_rejected(self):
        with self.assertRaises(ValueError):
            render({"templateKey":"unknown", "templateVersion":1}, "https://ledger.example")

    def test_retry_is_bounded_and_message_id_is_stable(self):
        now = time.time()
        t = retry_time(50, 300, rng=lambda: 0.5)
        self.assertLessEqual(t.timestamp() - now, 301)
        expected = "<ecabin-00000000-0000-4000-8000-000000000001@email.ecabin-ledger.invalid>"
        self.assertEqual(stable_message_id("00000000-0000-4000-8000-000000000001"), expected)


if __name__ == "__main__":
    unittest.main()

