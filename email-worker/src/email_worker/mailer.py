from email.message import EmailMessage
import smtplib
import ssl
import re


class MailError(Exception):
    def __init__(self, code: str, permanent: bool = False):
        super().__init__(code)
        self.code = code
        self.permanent = permanent


def send(config, recipient: str, message, message_id: str):
    address_pattern = re.compile(r"^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$")
    if not address_pattern.fullmatch(recipient) or not address_pattern.fullmatch(config.smtp_from_address):
        raise MailError("INVALID_EMAIL_ADDRESS", permanent=True)
    if any(ch in config.smtp_from_name for ch in "\r\n"):
        raise MailError("INVALID_SENDER_CONFIGURATION", permanent=True)
    context = ssl.create_default_context()
    email = EmailMessage()
    email["From"] = f"{config.smtp_from_name} <{config.smtp_from_address}>"
    email["To"] = recipient
    email["Subject"] = message.subject
    email["Message-ID"] = message_id
    email.set_content(message.text)
    email.add_alternative(message.html, subtype="html")
    try:
        if config.smtp_tls_mode == "implicit_tls":
            client = smtplib.SMTP_SSL(config.smtp_host, config.smtp_port, timeout=config.smtp_timeout, context=context)
        elif config.smtp_tls_mode == "starttls":
            client = smtplib.SMTP(config.smtp_host, config.smtp_port, timeout=config.smtp_timeout)
        else:
            raise MailError("SMTP_TLS_MODE_UNSUPPORTED", permanent=True)
        with client as smtp:
            if config.smtp_tls_mode == "starttls":
                smtp.ehlo()
                smtp.starttls(context=context)
                smtp.ehlo()
            smtp.login(config.smtp_username, config.smtp_password)
            refused = smtp.send_message(email)
            if refused:
                raise MailError("SMTP_RECIPIENT_REFUSED", permanent=True)
    except MailError:
        raise
    except smtplib.SMTPResponseException as exc:
        raise MailError("SMTP_PERMANENT" if 500 <= exc.smtp_code < 600 else "SMTP_TRANSIENT", permanent=500 <= exc.smtp_code < 600) from None
    except (OSError, smtplib.SMTPException, ssl.SSLError):
        raise MailError("SMTP_TRANSPORT_FAILURE") from None

