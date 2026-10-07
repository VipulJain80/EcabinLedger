"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

type Row = Record<string, unknown> & { id: string; isactive: number };
type MenuItem = { key: string; label: string; description: string };
type Field = { key: string; label: string; required?: boolean; kind?: "select" | "email" | "textarea"; options?: Array<{ value: string; label: string }> };
type Request = (path: string, init?: RequestInit) => Promise<Response>;

const roles = ["ADMIN", "SUPERVISOR", "INSPECTOR", "MAINTENANCE_TECHNICIAN", "QUALITY_COMPLIANCE", "VIEWER"];
const areas = ["CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT"];
const definitions: Record<string, { columns: Array<[string, string]>; fields: Field[]; recordLabel: string; create?: boolean; deactivate?: boolean }> = {
  organizations: { recordLabel: "Organization", columns: [["name", "Organization"], ["isactive", "Status"]], fields: [{ key: "name", label: "Organization name", required: true }], create: false, deactivate: false },
  fleets: { recordLabel: "Fleet", columns: [["name", "Fleet"], ["isactive", "Status"]], fields: [{ key: "name", label: "Fleet name", required: true }] },
  aircraft: { recordLabel: "Aircraft", columns: [["tailNumber", "Registration"], ["aircraftType", "Aircraft type"], ["fleetName", "Fleet"], ["isactive", "Status"]], fields: [{ key: "tailNumber", label: "Aircraft registration", required: true }, { key: "aircraftType", label: "Aircraft type", required: true }, { key: "fleetId", label: "Fleet", required: true, kind: "select" }] },
  "cabin-zones": { recordLabel: "Cabin zone", columns: [["code", "Code"], ["name", "Cabin zone"], ["area", "Area"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Display name", required: true }, { key: "area", label: "Area", required: true, kind: "select" }] },
  "defect-categories": { recordLabel: "Defect category", columns: [["code", "Code"], ["name", "Category"], ["description", "Description"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Category name", required: true }, { key: "description", label: "Description", kind: "textarea" }] },
  components: { recordLabel: "Component", columns: [["code", "Code"], ["name", "Cabin component"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Component name", required: true }] },
  "maintenance-teams": { recordLabel: "Maintenance team", columns: [["name", "Maintenance team"], ["isactive", "Status"]], fields: [{ key: "name", label: "Team name", required: true }] },
  users: { recordLabel: "User membership", columns: [["displayName", "Name"], ["externalUserId", "Enterprise identity"], ["role", "Application role"], ["isactive", "Status"]], fields: [{ key: "externalUserId", label: "Enterprise identity / subject", required: true }, { key: "displayName", label: "Display name", required: true }, { key: "role", label: "Application role", required: true, kind: "select" }] },
};

function fieldOptions(key: string, field: Field, fleets: Row[]) {
  if (key === "aircraft" && field.key === "fleetId") return fleets.map((fleet) => ({ value: String(fleet.id), label: String(fleet.name) }));
  if (key === "cabin-zones" && field.key === "area") return areas.map((area) => ({ value: area, label: area.replaceAll("_", " ") }));
  if (key === "users" && field.key === "role") return roles.map((role) => ({ value: role, label: role.replaceAll("_", " ") }));
  return field.options || [];
}

export default function ConfigurationPage({ request }: { request: Request }) {
  const [menu, setMenu] = useState<MenuItem[]>([]);
  const [activeKey, setActiveKey] = useState("");
  const [rows, setRows] = useState<Row[]>([]);
  const [fleets, setFleets] = useState<Row[]>([]);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Row | null>(null);
  const [showForm, setShowForm] = useState(false);

  const activeMenu = menu.find((item) => item.key === activeKey);
  const definition = definitions[activeKey];
  const fleetOptions = useMemo(() => fleets.filter((fleet) => fleet.isactive === 1), [fleets]);

  const loadRows = useCallback(async (key: string, showInactive: boolean, signal?: AbortSignal) => {
    if (!key) return;
    setLoading(true); setError("");
    try {
      const response = await request(`/configuration/${key}?includeInactive=${showInactive}`, { signal });
      if (signal?.aborted) return;
      if (!response.ok) throw new Error(response.status === 403 ? "Your account does not have organization administrator access." : "Could not load this master data table.");
      setRows(await response.json());
    } catch (reason) { if (!signal?.aborted) setError(reason instanceof Error ? reason.message : "Could not load configuration."); }
    finally { if (!signal?.aborted) setLoading(false); }
  }, [request]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const [menuResponse, fleetResponse] = await Promise.all([request("/configuration/menu"), request("/configuration/fleets")]);
        if (!menuResponse.ok) throw new Error(menuResponse.status === 403 ? "Configuration requires the ADMIN role. Ask an organization administrator to grant access." : "Could not load configuration sections.");
        const menuData: MenuItem[] = await menuResponse.json();
        const fleetData: Row[] = fleetResponse.ok ? await fleetResponse.json() : [];
        if (!cancelled) { setMenu(menuData); setFleets(fleetData); setActiveKey(menuData[0]?.key || ""); }
      } catch (reason) { if (!cancelled) setError(reason instanceof Error ? reason.message : "Could not load configuration."); }
      finally { if (!cancelled) setLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [request]);

  useEffect(() => {
    if (!activeKey) return;
    const controller = new AbortController();
    void Promise.resolve().then(() => loadRows(activeKey, includeInactive, controller.signal));
    return () => controller.abort();
  }, [activeKey, includeInactive, loadRows]);

  function openCreate() { setEditing(null); setShowForm(true); setError(""); }
  function openEdit(row: Row) { setEditing(row); setShowForm(true); setError(""); }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget).entries());
    const fields: Record<string, string> = {};
    for (const field of definition.fields) if (!(activeKey === "users" && editing && field.key === "externalUserId")) fields[field.key] = String(values[field.key] ?? "");
    setSaving(true); setError("");
    try {
      const response = await request(`/configuration/${activeKey}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: editing?.id, fields }) });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.error?.message || "Could not save this master data record.");
      }
      setShowForm(false); setEditing(null);
      await loadRows(activeKey, includeInactive);
      if (activeKey === "fleets") {
        const fleetResponse = await request("/configuration/fleets");
        if (fleetResponse.ok) setFleets(await fleetResponse.json());
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not save this master data record."); }
    finally { setSaving(false); }
  }

  async function changeActive(row: Row, activate: boolean) {
    const reason = window.prompt(`Reason to ${activate ? "reactivate" : "deactivate"} ${String(row.name || row.displayName || row.tailNumber || row.code || "this record")}:`)?.trim();
    if (!reason) return;
    setError("");
    try {
      const response = activate
        ? await request(`/configuration/${activeKey}/${row.id}/activate`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ reason }) })
        : await request(`/configuration/${activeKey}/${row.id}/deactivate`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ reason }) });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.error?.message || "Could not update this record.");
      }
      await loadRows(activeKey, includeInactive);
      if (activeKey === "fleets") {
        const fleetResponse = await request("/configuration/fleets");
        if (fleetResponse.ok) setFleets(await fleetResponse.json());
      }
    } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : "Could not update this record."); }
  }

  if (loading && !menu.length) return <div className="content"><div className="config-loading">Loading organization configuration…</div></div>;

  return <div className="content configuration-content">
    <div className="heading"><div><div className="eyebrow"><span></span> ORGANIZATION ADMINISTRATION <em>·</em> MASTER DATA</div><h1>Configuration</h1><p>Maintain the operator reference data used by cabin operations and defect workflows.</p></div>{definition?.create !== false && <button className="button-primary" onClick={openCreate}><span>＋</span> Add {definition?.recordLabel}</button>}</div>
    <div className="configuration-layout">
      <nav className="configuration-nav" aria-label="Master data sections"><div className="eyebrow">MASTER DATA</div>{menu.map((item) => <button key={item.key} className={item.key === activeKey ? "configuration-nav-item active" : "configuration-nav-item"} onClick={() => { setActiveKey(item.key); setShowForm(false); }}><span>{item.label}</span><small>{item.description}</small></button>)}</nav>
      <section className="configuration-table-card">
        <div className="configuration-table-head"><div><div className="eyebrow">{activeMenu?.description || "Organization records"}</div><h2>{activeMenu?.label || "Master data"} <span className="result-count">{rows.length}</span></h2></div><label className="include-inactive"><input type="checkbox" checked={includeInactive} onChange={(event) => setIncludeInactive(event.target.checked)} /> Include inactive</label></div>
        {error && <div className="notice" role="alert"><span>!</span>{error}<button onClick={() => void loadRows(activeKey, includeInactive)}>Retry</button></div>}
        <div className="table-scroll"><table><thead><tr>{definition?.columns.map(([key, label]) => <th key={key}>{label.toUpperCase()}</th>)}<th>ACTIONS</th></tr></thead><tbody>
          {loading && <tr><td className="empty" colSpan={(definition?.columns.length || 1) + 1}>Loading master records…</td></tr>}
          {!loading && rows.map((row) => <tr key={row.id}>
            {definition?.columns.map(([key]) => <td key={key}>{key === "isactive" ? <span className={row.isactive === 1 ? "config-active" : "config-inactive"}>{row.isactive === 1 ? "Active" : "Inactive"}</span> : key === "fleetName" ? String(fleets.find((fleet) => fleet.id === row.fleetId)?.name ?? row.fleetName ?? "—") : String(row[key] ?? "—")}</td>)}
            <td><div className="config-row-actions">{row.isactive === 1 && <button className="button-secondary" onClick={() => openEdit(row)}>Edit</button>}{definition?.deactivate !== false && (row.isactive === 1 ? <button className="config-danger" onClick={() => void changeActive(row, false)}>Deactivate</button> : <button className="button-secondary" onClick={() => void changeActive(row, true)}>Reactivate</button>)}</div></td>
          </tr>)}
          {!loading && rows.length === 0 && <tr><td className="empty" colSpan={(definition?.columns.length || 1) + 1}><span className="empty-art">✧</span><b>No records yet</b><small>Add a record to configure this part of the operator workspace.</small></td></tr>}
        </tbody></table></div>
        <div className="config-table-foot"><span>Tenant-scoped records</span><span><i>✓</i> Changes are captured in the append-only audit history</span></div>
      </section>
    </div>
    {showForm && definition && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowForm(false); }}><section className="modal" role="dialog" aria-modal="true" aria-labelledby="configuration-form-title"><div className="modal-heading"><div><div className="eyebrow">MASTER DATA</div><h2 id="configuration-form-title">{editing ? "Edit" : "Add"} {definition.recordLabel}</h2></div><button className="close" onClick={() => setShowForm(false)} aria-label="Close">×</button></div><form onSubmit={save}>
      {definition.fields.map((field) => {
        const disabled = Boolean(editing && activeKey === "users" && field.key === "externalUserId");
        const value = editing?.[field.key];
        const options = fieldOptions(activeKey, field, fleetOptions);
        return <label key={field.key}>{field.label}{field.kind === "select" ? <select name={field.key} required={field.required} defaultValue={String(value ?? "")} disabled={disabled}>{!field.required && <option value="">Choose…</option>}{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select> : field.kind === "textarea" ? <textarea name={field.key} required={field.required} defaultValue={String(value ?? "")} maxLength={500} rows={3} /> : <input name={field.key} type={field.kind === "email" ? "email" : "text"} required={field.required && !disabled} disabled={disabled} defaultValue={String(value ?? "")} maxLength={field.key === "description" ? 500 : 200} />}</label>;
      })}
      {activeKey === "users" && !editing && <div className="modal-note"><span>i</span> This grants the selected eCabin Ledger role to an existing enterprise identity. It does not create an identity-provider account.</div>}
      <div className="modal-actions"><button type="button" className="button-secondary" onClick={() => setShowForm(false)}>Cancel</button><button className="button-primary" disabled={saving}>{saving ? "Saving…" : "Save record →"}</button></div>
    </form></section></div>}
  </div>;
}
