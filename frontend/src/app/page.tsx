"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";

declare global { interface Window { eCabinAuth?: { getAccessToken: () => Promise<string> }; } }

type Defect = {
  id: string; reference: string; tailNumber: string; aircraftType: string; location: string; category: string; zone: string;
  title: string; description: string; severity: string; status: string; reportedBy: string; assignedTo: string | null;
  dueAt: string | null; activeActionId: string | null; reportedAt: string; updatedAt: string;
};
type Page = { items: Defect[]; nextCursor: string | null };
type Summary = { openDefects: number; criticalDefects: number; reportedToday: number; closedThisMonth: number };
type Identity = { organizationName: string; displayName: string; role: string };
type AuditEvent = { id: string; eventType: string; fromStatus: string | null; toStatus: string | null; note: string; evidenceReference: string | null; inspectionOutcome: string | null; actorId: string; occurredAt: string };
const API = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api/v1";
const areas = ["ALL AREAS", "CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT"];
const statuses = ["ALL STATUS", "REPORTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE", "ACTION_ASSIGNED", "IN_PROGRESS", "AWAITING_VERIFICATION", "VERIFIED", "APPROVAL_REQUIRED", "CLOSED", "REOPENED", "REJECTED", "CANCELLED"];
const human = (value: string) => value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase());
const canReport = (role?: string) => ["ADMIN", "SUPERVISOR", "INSPECTOR"].includes(role || "");
const canAdvance = (role: string | undefined, status: string) => {
  if (["ADMIN", "SUPERVISOR"].includes(role || "")) return true;
  if (role === "INSPECTOR") return ["REPORTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "INSPECTION_IN_PROGRESS"].includes(status);
  if (role === "MAINTENANCE_TECHNICIAN") return ["ACTION_ASSIGNED", "IN_PROGRESS"].includes(status);
  if (role === "QUALITY_COMPLIANCE") return ["AWAITING_VERIFICATION", "VERIFIED", "APPROVAL_REQUIRED", "CLOSED"].includes(status);
  return false;
};
async function apiFetch(path: string, init: RequestInit = {}) {
  const token = await window.eCabinAuth?.getAccessToken();
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return fetch(path, { ...init, headers, credentials: "omit", cache: "no-store" });
}

export default function Home() {
  const [items, setItems] = useState<Defect[]>([]);
  const [filter, setFilter] = useState("ALL STATUS");
  const [area, setArea] = useState("ALL AREAS");
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selected, setSelected] = useState<Defect | null>(null);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [summary, setSummary] = useState<Summary>({ openDefects: 0, criticalDefects: 0, reportedToday: 0, closedThisMonth: 0 });
  const [identity, setIdentity] = useState<Identity | null>(null);

  const load = useCallback(async (after: string | null = null, append = false) => {
    setLoading(true); setNotice("");
    const params = new URLSearchParams({ limit: "25" });
    if (filter !== "ALL STATUS") params.set("status", filter);
    if (area !== "ALL AREAS") params.set("location", area);
    if (append && after) params.set("cursor", after);
    try {
      const [response, summaryResponse, identityResponse] = await Promise.all([apiFetch(`${API}/defects?${params}`), apiFetch(`${API}/dashboard/summary`), apiFetch(`${API}/me`)]);
      if (!response.ok || !summaryResponse.ok || !identityResponse.ok) throw new Error(response.status === 401 || response.status === 403 ? "Sign in through your organization’s enterprise access gateway." : "We couldn’t load the defect register. Try again.");
      const [data, summaryData, identityData]: [Page, Summary, Identity] = await Promise.all([response.json(), summaryResponse.json(), identityResponse.json()]);
      setSummary(summaryData);
      setIdentity(identityData);
      setItems((previous) => append ? [...previous, ...data.items] : data.items);
      setCursor(data.nextCursor);
    } catch (error) { setNotice(error instanceof Error ? error.message : "The service is unavailable."); }
    finally { setLoading(false); }
  }, [area, filter]);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(() => { if (!cancelled) return load(); });
    return () => { cancelled = true; };
  }, [load]);

  async function createDefect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSaving(true); setNotice("");
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const payload = Object.fromEntries(form.entries());
    try {
      const response = await apiFetch(`${API}/defects`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload) });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.error?.message || "Could not create the defect. Check your access and the details.");
      }
      setDialog(false); formElement.reset(); setFilter("ALL STATUS"); setArea("ALL AREAS");
      await load();
    } catch (error) { setNotice(error instanceof Error ? error.message : "Could not create the defect."); }
    finally { setSaving(false); }
  }

  async function advance(defect: Defect) {
    try {
      let response: Response;
      const json = { "content-type": "application/json" };
      if (["REPORTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "VERIFIED", "CLOSED"].includes(defect.status)) {
        const next: Record<string, string> = { REPORTED: "UNDER_REVIEW", UNDER_REVIEW: "INSPECTION_REQUIRED", INSPECTION_REQUIRED: "INSPECTION_IN_PROGRESS", VERIFIED: "APPROVAL_REQUIRED", CLOSED: "REOPENED" };
        const status = next[defect.status];
        const note = window.prompt(`Add an audit reason for ${human(status)}.`)?.trim(); if (!note) return;
        response = await apiFetch(`${API}/defects/${defect.id}/transitions`, { method: "POST", headers: json, body: JSON.stringify({ status, note }) });
      } else if (defect.status === "INSPECTION_IN_PROGRESS") {
        const result = window.prompt("Inspection result: PASS, FAIL, CONDITIONAL or REQUIRES_FOLLOW_UP", "PASS")?.trim().toUpperCase(); if (!result) return;
        const findings = window.prompt("Record inspection findings.")?.trim(); if (!findings) return;
        response = await apiFetch(`${API}/defects/${defect.id}/inspections`, { method: "POST", headers: json, body: JSON.stringify({ result, findings, notes: "" }) });
      } else if (defect.status === "INSPECTION_COMPLETE") {
        const description = window.prompt("Describe the required corrective action.")?.trim(); if (!description) return;
        const assignedUserId = window.prompt("Assign to enterprise user ID (leave blank to assign a team).")?.trim();
        const assignedTeam = assignedUserId ? "" : window.prompt("Enter the responsible maintenance team.")?.trim();
        if (!assignedUserId && !assignedTeam) return;
        response = await apiFetch(`${API}/defects/${defect.id}/actions`, { method: "POST", headers: json, body: JSON.stringify({ description, assignedUserId, assignedTeam, priority: defect.severity, dueAt: defect.dueAt }) });
      } else if (defect.status === "ACTION_ASSIGNED" && defect.activeActionId) {
        response = await apiFetch(`${API}/defects/${defect.id}/actions/${defect.activeActionId}/start`, { method: "POST" });
      } else if (defect.status === "IN_PROGRESS" && defect.activeActionId) {
        const completionNotes = window.prompt("Record the corrective action completed.")?.trim(); if (!completionNotes) return;
        response = await apiFetch(`${API}/defects/${defect.id}/actions/${defect.activeActionId}/complete`, { method: "POST", headers: json, body: JSON.stringify({ completionNotes }) });
      } else if (defect.status === "AWAITING_VERIFICATION") {
        const result = window.prompt("Verification result: PASS or FAIL", "PASS")?.trim().toUpperCase(); if (!result) return;
        const notes = window.prompt("Record verification notes.")?.trim(); if (!notes) return;
        const evidenceReference = window.prompt("Enter verification evidence reference.")?.trim(); if (!evidenceReference) return;
        response = await apiFetch(`${API}/defects/${defect.id}/verifications`, { method: "POST", headers: json, body: JSON.stringify({ result, notes, evidenceReference }) });
      } else if (defect.status === "APPROVAL_REQUIRED") {
        const reason = window.prompt("Approval reason.")?.trim(); if (!reason) return;
        const evidenceReference = window.prompt("Closure evidence reference.")?.trim(); if (!evidenceReference) return;
        response = await apiFetch(`${API}/defects/${defect.id}/approvals`, { method: "POST", headers: json, body: JSON.stringify({ decision: "APPROVED", reason, evidenceReference }) });
      } else return;
      if (!response.ok) throw new Error("Could not update defect status.");
      await load();
    } catch (error) { setNotice(error instanceof Error ? error.message : "Could not update defect status."); }
  }

  async function openDetail(defect: Defect) {
    setSelected(defect); setEvents([]);
    try {
      const response = await apiFetch(`${API}/defects/${defect.id}/events`);
      if (response.ok) setEvents(await response.json());
    } catch { /* Keep the defect details available if audit history is temporarily unavailable. */ }
  }

  return <main className="shell">
    <aside className="rail">
      <a className="brand" href="#home" aria-label="eCabin Ledger home"><span className="brand-mark">e</span><span>eCabin <b>Ledger</b></span></a>
      <div className="operator-picker"><span className="operator-icon">{identity?.organizationName.slice(0, 2).toUpperCase() || "—"}</span><span><small>ORGANIZATION</small><strong>{identity?.organizationName || "Loading…"}</strong></span></div>
      <div className="nav-label">OPERATIONS</div>
      <nav className="nav">
        <a className="nav-link selected" href="#defects"><span>▦</span> Defect register <i>{items.length}</i></a>
        <a className="nav-link" href="#aircraft"><span>✈</span> Aircraft</a>
        <a className="nav-link" href="#inspections"><span>◷</span> Inspections</a>
        <a className="nav-link" href="#reports"><span>▤</span> Reports</a>
      </nav>
      <div className="rail-bottom"><div className="compliance"><span className="compliance-icon">✓</span><strong>Audit history enabled</strong><p>Workflow changes are captured with actor and timestamp.</p><a href="#audit">View audit log <span>→</span></a></div><div className="user"><span className="user-avatar">{identity?.displayName.split(/\s+/).map((part) => part[0]).slice(0, 2).join("").toUpperCase() || "—"}</span><span><b>{identity?.displayName || "Enterprise user"}</b><small>{human(identity?.role || "")}</small></span></div></div>
    </aside>
    <section className="workspace" id="home">
      <header className="topbar"><div className="crumb">{identity?.organizationName || "Operations"} <span>/</span> <b>Defect register</b></div><div className="topbar-right"><span className="sync"><i></i> API CONNECTED</span><button aria-label="Help" className="help">?</button><button aria-label="Notifications" className="bell">♧<i></i></button><span className="small-avatar">{identity?.displayName.split(/\s+/).map((part) => part[0]).slice(0, 2).join("").toUpperCase() || "—"}</span></div></header>
      <div className="content" id="defects">
        <div className="heading"><div><div className="eyebrow"><span></span> CABIN OPERATIONS <em>·</em> FLEET OVERVIEW</div><h1>Defect register</h1><p>One clear view from first report to verified closure.</p></div>{canReport(identity?.role) && <button className="button-primary" onClick={() => setDialog(true)}><span>＋</span> Log a defect</button>}</div>
        <section className="metrics" aria-label="Defect summary">
          <article className="metric-card"><div className="metric-head">OPEN DEFECTS <span className="metric-icon green">↗</span></div><strong>{loading ? "—" : summary.openDefects}</strong><div className="metric-foot">Across active fleet <span className="sparkline">▁▃▂▅▃▆▄▇</span></div></article>
          <article className="metric-card"><div className="metric-head">CRITICAL ITEMS <span className="metric-icon red">!</span></div><strong className={summary.criticalDefects ? "critical-number" : ""}>{loading ? "—" : summary.criticalDefects.toString().padStart(2, "0")}</strong><div className="metric-foot"><span className="status-dot amber"></span> Requiring immediate attention</div></article>
          <article className="metric-card"><div className="metric-head">REPORTED TODAY <span className="metric-icon blue">◷</span></div><strong>{loading ? "—" : summary.reportedToday.toString().padStart(2, "0")}</strong><div className="metric-foot">New cabin findings</div></article>
          <article className="metric-card compliance-card"><div className="metric-head">CLOSED THIS MONTH <span className="metric-icon green">✓</span></div><strong>{loading ? "—" : summary.closedThisMonth.toString().padStart(2, "0")}</strong><div className="metric-foot"><span className="status-dot green-dot"></span> With audit event history</div></article>
        </section>
        <section className="register">
          <div className="register-title"><div><div className="eyebrow">LIVE FLEET LOG</div><h2>Recent defects <span className="result-count">{items.length}</span></h2></div><div className="register-tools"><label className="search"><span>⌕</span><input aria-label="Search defects" placeholder="Search defects" onChange={(event) => { const q = event.target.value.toLowerCase(); document.querySelectorAll<HTMLTableRowElement>("[data-search-row]").forEach((row) => { row.hidden = !row.dataset.searchRow?.includes(q); }); }} /></label><button className="filter-button" onClick={() => setFilter(filter === "ALL STATUS" ? "REPORTED" : "ALL STATUS")}>☷ <span>Filter</span></button><button className="more-button" aria-label="More options">•••</button></div></div>
          <div className="filter-row"><div className="chips" role="group" aria-label="Filter by status">{statuses.slice(0, 5).map((value) => <button key={value} className={filter === value ? "chip active" : "chip"} onClick={() => setFilter(value)}>{value === "ALL STATUS" ? "All status" : human(value)}</button>)}</div><label className="area-filter"><span>AREA</span><select value={area} onChange={(event) => setArea(event.target.value)} aria-label="Filter by area">{areas.map((value) => <option key={value} value={value}>{human(value)}</option>)}</select></label></div>
          {notice && <div className="notice" role="alert"><span>!</span>{notice}<button onClick={() => void load()}>Retry</button></div>}
          <div className="table-scroll"><table><thead><tr><th>DEFECT / AIRCRAFT</th><th>LOCATION</th><th>SEVERITY</th><th>STATUS</th><th>REPORTED</th><th>OWNER</th><th></th></tr></thead><tbody>
            {items.map((defect) => <tr key={defect.id} data-search-row={`${defect.title} ${defect.reference} ${defect.tailNumber} ${defect.zone} ${defect.location}`.toLowerCase()} onClick={() => void openDetail(defect)} tabIndex={0} onKeyDown={(event) => { if (event.key === "Enter") void openDetail(defect); }}>
              <td><div className="defect-name"><span className="defect-icon">{defect.location === "GALLEY" ? "▤" : defect.location === "LAVATORY" ? "◉" : "⌂"}</span><span><b>{defect.title}</b><small>{defect.reference} <i>·</i> {defect.tailNumber} · {defect.zone}</small></span></div></td>
              <td><span className="location-tag">{human(defect.location)}</span></td><td><span className={`severity ${defect.severity.toLowerCase()}`}><i></i>{human(defect.severity)}</span></td><td><span className={`status ${defect.status.toLowerCase()}`}>{human(defect.status)}</span></td><td className="date">{new Intl.DateTimeFormat(undefined, { month: "short", day: "2-digit" }).format(new Date(defect.reportedAt))}<small>{new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(new Date(defect.reportedAt))}</small></td><td><span className="owner"><i>{defect.assignedTo ? defect.assignedTo.split(" ").map((name) => name[0]).slice(0, 2).join("") : "—"}</i>{defect.assignedTo || "Unassigned"}</span></td><td>{canAdvance(identity?.role, defect.status) && <button className="row-more" aria-label={`Advance ${defect.reference}`} title="Advance workflow" onClick={(event) => { event.stopPropagation(); void advance(defect); }}>···</button>}</td>
            </tr>)}
            {!loading && items.length === 0 && !notice && <tr><td className="empty" colSpan={7}><span className="empty-art">✧</span><b>No defects in this view</b><small>Try another filter, or log a newly discovered cabin defect.</small>{canReport(identity?.role) && <button onClick={() => setDialog(true)}>Log a defect <span>→</span></button>}</td></tr>}
            {loading && items.length === 0 && <tr><td className="empty" colSpan={7}>Loading your operator’s defect register…</td></tr>}
          </tbody></table></div>
          <div className="table-foot"><span>Showing <b>{items.length}</b> recent records</span>{cursor && <button className="load-more" onClick={() => void load(cursor, true)}>Load more defects <span>↓</span></button>}<span className="audit-note"><i>✓</i> Changes are recorded in the audit trail</span></div>
        </section>
        <footer><span>eCabin Ledger <i>·</i> Operational recordkeeping</span><span>UTC <i>·</i> Data synced just now</span></footer>
      </div>
    </section>

    {dialog && canReport(identity?.role) && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setDialog(false); }}><section className="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><div className="modal-heading"><div><div className="eyebrow">NEW OPERATIONAL RECORD</div><h2 id="modal-title">Log a defect</h2></div><button className="close" onClick={() => setDialog(false)} aria-label="Close">×</button></div><form onSubmit={createDefect}>
      <label>Aircraft registration<input name="tailNumber" required maxLength={16} placeholder="N482NW" /></label><div className="form-row"><label>Cabin area<select name="location"><option value="CABIN">Cabin</option><option value="GALLEY">Galley</option><option value="LAVATORY">Lavatory</option><option value="ATTENDANT_SEAT">Attendant seat</option></select></label><label>Defect category<select name="category"><option value="SEAT">Passenger seat</option><option value="CABIN">Cabin</option><option value="GALLEY">Galley</option><option value="LAVATORY">Lavatory</option><option value="ATTENDANT_SEAT">Attendant seat</option><option value="IFE">IFE</option><option value="LIGHTING">Lighting</option><option value="OVERHEAD_BIN">Overhead bin</option><option value="PSU">PSU</option><option value="EMERGENCY_EQUIPMENT">Emergency equipment</option><option value="OTHER">Other</option></select></label></div>
      <div className="form-row"><label>Cabin zone<input name="zone" required maxLength={80} placeholder="FWD CABIN / ROW" /></label><label>Row<input name="rowNumber" type="number" min="1" max="200" placeholder="14" /></label></div><div className="form-row"><label>Seat<input name="seatReference" maxLength={8} placeholder="14A" /></label><label>Component<input name="component" maxLength={120} placeholder="Seat cover / PSU" /></label></div>
      <label>Defect title<input name="title" required maxLength={160} placeholder="Concise description of finding" /></label><label>Details<textarea name="description" required maxLength={4000} rows={3} placeholder="Describe what was found and the inspection context." /></label>
      <label>Severity<select name="severity"><option value="LOW">Low</option><option value="MEDIUM" selected>Medium</option><option value="HIGH">High</option><option value="CRITICAL">Critical</option></select></label>
      <div className="modal-note"><span>✓</span> A dated report event will be added to the audit history.</div><div className="modal-actions"><button type="button" className="button-secondary" onClick={() => setDialog(false)}>Cancel</button><button className="button-primary" disabled={saving}>{saving ? "Saving…" : "Save defect →"}</button></div>
    </form></section></div>}
    {selected && <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setSelected(null); }}><section className="modal detail-modal" role="dialog" aria-modal="true"><div className="modal-heading"><div><div className="eyebrow">{selected.reference} · {selected.tailNumber}</div><h2>{selected.title}</h2></div><button className="close" onClick={() => setSelected(null)} aria-label="Close">×</button></div><p className="detail-description">{selected.description}</p><div className="detail-grid"><span>Location<b>{human(selected.location)} · {selected.zone}</b></span><span>Severity<b>{human(selected.severity)}</b></span><span>Status<b>{human(selected.status)}</b></span><span>Reported by<b>{selected.reportedBy}</b></span></div><h3 className="timeline-heading">Audit history</h3><div className="timeline">{events.map((item) => <div className="timeline-item" key={item.id}><span className="timeline-dot"></span><div><b>{human(item.eventType)}{item.toStatus ? ` · ${human(item.toStatus)}` : ""}{item.inspectionOutcome ? ` · Inspection ${item.inspectionOutcome}` : ""}</b><p>{item.note}{item.evidenceReference ? `\nEvidence: ${item.evidenceReference}` : ""}</p><small>{item.actorId} · {new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.occurredAt))}</small></div></div>)}{!events.length && <span className="timeline-empty">Loading history or no recorded events.</span>}</div><p className="modal-note"><span>✓</span> Inspections and closure references are retained in an append-only audit history.</p><div className="modal-actions"><button className="button-secondary" onClick={() => setSelected(null)}>Close</button>{!(["RESOLVED", "CLOSED"].includes(selected.status)) && <button className="button-primary" onClick={() => { void advance(selected); setSelected(null); }}>Advance workflow →</button>}</div></section></div>}
  </main>;
}
