from dataclasses import dataclass
from html import escape


EVENT_LABELS = {
    "DEFECT_REPORTED": "A defect was reported",
    "REVIEW_APPROVED": "A defect was approved for maintenance",
    "REVIEW_REJECTED": "A defect report was returned by review",
    "ACTION_ASSIGNED": "Corrective work was assigned",
    "ACTION_STARTED": "Corrective work started",
    "ACTION_COMPLETED": "Corrective work was completed",
    "INSPECTION_COMPLETED": "An inspection was completed",
    "WORK_VERIFIED": "Corrective work was verified",
    "VERIFICATION_REJECTED": "Corrective work requires further attention",
    "ACTION_CLOSE_SUBMITTED": "Maintenance submitted the case for closure review",
    "CLOSURE_APPROVED": "Case closure was approved",
    "CLOSURE_REJECTED": "Case closure was returned for further work",
    "DEFECT_CLOSED": "The defect was closed",
    "DEFECT_REOPENED": "The defect was reopened",
}


@dataclass(frozen=True)
class RenderedMessage:
    subject: str
    text: str
    html: str


def render(payload: dict, base_url: str) -> RenderedMessage:
    if payload.get("templateKey") != "defect-workflow" or payload.get("templateVersion") != 1:
        raise ValueError("Unsupported email template")
    reference = str(payload.get("reference", ""))
    event_key = str(payload.get("eventKey", ""))
    status = str(payload.get("status", ""))
    occurred_at = str(payload.get("occurredAt", ""))
    label = EVENT_LABELS.get(event_key)
    if not reference or not label or not status or not occurred_at:
        raise ValueError("Incomplete email payload")
    # No external deep-link contract is verified yet; link to the authenticated shell.
    link = f"{base_url}/ecabin/loginhome"
    subject = f"eCabin Ledger: {reference} — {label}"
    text = f"{label}.\nDefect: {reference}\nStatus: {status}\nTime: {occurred_at}\nOpen eCabin Ledger: {link}"
    html = (
        "<!doctype html><html><body style=\"font-family:Arial,sans-serif;color:#1f2937\">"
        f"<h2>{escape(label)}</h2><p>Defect: <strong>{escape(reference)}</strong></p>"
        f"<p>Status: {escape(status)}<br>Time: {escape(occurred_at)}</p>"
        f"<p><a href=\"{escape(link, quote=True)}\">Open eCabin Ledger</a></p></body></html>"
    )
    return RenderedMessage(subject, text, html)

