"""Minimal process health support; the Windows Service supervisor monitors process state."""

def ready(config) -> bool:
    return bool(config.database_url and config.smtp_host and config.smtp_tls_mode)

