from dataclasses import dataclass
import os


class ConfigurationError(ValueError):
    pass


@dataclass(frozen=True)
class Config:
    database_url: str
    smtp_host: str
    smtp_port: int
    smtp_username: str
    smtp_password: str
    smtp_tls_mode: str
    smtp_from_address: str
    smtp_from_name: str
    smtp_timeout: float
    application_base_url: str
    worker_id: str
    batch_size: int
    lease_seconds: int
    poll_seconds: float
    max_backoff_seconds: int

    @classmethod
    def from_env(cls, env=None):
        values = os.environ if env is None else env

        def required(name):
            value = values.get(name, "").strip()
            if not value or "APPROVED_" in value or "REDACTED" in value or "SET_IN_" in value or "CONFIRM_" in value:
                raise ConfigurationError(f"{name} must be supplied by deployment configuration")
            return value

        def number(name, default, low, high, cast):
            try:
                value = cast(values.get(name, str(default)))
            except (TypeError, ValueError) as exc:
                raise ConfigurationError(f"{name} must be a valid number") from exc
            if not low <= value <= high:
                raise ConfigurationError(f"{name} is outside the supported range")
            return value

        tls = required("SMTP_TLS_MODE").lower()
        if tls not in {"starttls", "implicit_tls"}:
            raise ConfigurationError("SMTP_TLS_MODE must be starttls or implicit_tls")
        url = required("APPLICATION_BASE_URL").rstrip("/")
        if not url.startswith("https://"):
            raise ConfigurationError("APPLICATION_BASE_URL must use HTTPS")
        db = required("DATABASE_URL")
        if "sslmode=verify-full" not in db.lower():
            raise ConfigurationError("DATABASE_URL must require PostgreSQL certificate and hostname verification")
        port = number("SMTP_PORT", 0, 1, 65535, int)
        smtp_timeout = number("SMTP_TIMEOUT_SECONDS", 15, 1, 60, float)
        lease_seconds = number("LEASE_SECONDS", 120, 30, 900, int)
        if lease_seconds <= smtp_timeout * 6 + 10:
            raise ConfigurationError("LEASE_SECONDS must exceed the bounded SMTP operation budget")
        return cls(
            db, required("SMTP_HOST"), port, required("SMTP_USERNAME"), required("SMTP_PASSWORD"), tls,
            required("SMTP_FROM_ADDRESS"), values.get("SMTP_FROM_NAME", "eCabin Ledger").strip() or "eCabin Ledger",
            smtp_timeout, url,
            values.get("WORKER_ID", "ecabin-email-worker").strip() or "ecabin-email-worker",
            number("BATCH_SIZE", 10, 1, 100, int), lease_seconds,
            number("POLL_SECONDS", 5, 1, 300, float), number("MAX_BACKOFF_SECONDS", 21600, 30, 604800, int),
        )

