"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import { alert, responseErrorMessage } from "@/common/alerts";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";

type Row = Record<string, unknown> & { id: string; isactive: number };
type MenuItem = { key: string; label: string; description: string };
type Field = { key: string; label: string; required?: boolean; kind?: "select" | "multi-select" | "email" | "textarea" | "number"; options?: Array<{ value: string; label: string }> };
type Request = (path: string, init?: RequestInit) => Promise<Response>;

const permissions = ["VIEW", "REPORT", "REVIEW", "INSPECT", "ASSIGN_ACTION", "PERFORM_ACTION", "VERIFY", "APPROVE", "CLOSE", "REOPEN", "AUDIT"];
const areas = ["CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT"];
const definitions: Record<string, { columns: Array<[string, string]>; fields: Field[]; recordLabel: string; create?: boolean; deactivate?: boolean }> = {
  organizations: { recordLabel: "Organization", columns: [["name", "Organization"], ["isactive", "Status"]], fields: [{ key: "name", label: "Organization name", required: true }], create: false, deactivate: false },
  fleets: { recordLabel: "Fleet", columns: [["name", "Fleet"], ["isactive", "Status"]], fields: [{ key: "name", label: "Fleet name", required: true }] },
  aircraft: { recordLabel: "Aircraft", columns: [["msnNumber", "MSN Number"], ["tailNumber", "Registration"], ["aircraftType", "Aircraft type"], ["fleetName", "Fleet"], ["isactive", "Status"]], fields: [{ key: "msnNumber", label: "MSN number" }, { key: "tailNumber", label: "Aircraft registration", required: true }, { key: "aircraftType", label: "Aircraft type", required: true }, { key: "fleetId", label: "Fleet", required: true, kind: "select" }] },
  "cabin-zones": { recordLabel: "Cabin zone", columns: [["code", "Code"], ["name", "Cabin zone"], ["area", "Area"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Display name", required: true }, { key: "area", label: "Area", required: true, kind: "select" }] },
  "defect-categories": { recordLabel: "Defect category", columns: [["code", "Code"], ["name", "Category"], ["description", "Description"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Category name", required: true }, { key: "description", label: "Description", kind: "textarea" }] },
  components: { recordLabel: "Component", columns: [["code", "Code"], ["name", "Cabin component"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Component name", required: true }] },
  "maintenance-teams": { recordLabel: "Maintenance team", columns: [["name", "Maintenance team"], ["isactive", "Status"]], fields: [{ key: "name", label: "Team name", required: true }] },
  users: { recordLabel: "User membership", columns: [["displayName", "Name"], ["externalUserId", "Enterprise identity"], ["role", "Application role"], ["isactive", "Status"]], fields: [{ key: "externalUserId", label: "Enterprise identity / subject", required: true }, { key: "displayName", label: "Display name", required: true }, { key: "role", label: "Application role", required: true, kind: "select" }] },
  "menu-items": { recordLabel: "Menu item", columns: [["label", "Menu / submenu"], ["key", "Key"], ["parentLabel", "Parent menu"], ["requiredPermissions", "Visible to permissions"], ["routeKey", "Application section"], ["displayOrder", "Order"], ["isactive", "Status"]], fields: [{ key: "label", label: "Menu label", required: true }, { key: "key", label: "Menu key", required: true }, { key: "parentId", label: "Parent menu (leave blank for top-level)", kind: "select" }, { key: "requiredPermissions", label: "Visible to permissions (separate alternatives with |)", required: true }, { key: "icon", label: "Icon character" }, { key: "routeKey", label: "Application section key", required: true, kind: "select", options: ["home", "operations", "reports", "audit", "configuration", "defects", "fleet", "quality", "organizations", "fleets", "aircraft", "cabin-zones", "defect-categories", "components", "maintenance-teams", "users", "menu-items", "roles"].map((value) => ({ value, label: value })) }, { key: "displayOrder", label: "Display order", required: true, kind: "number" }] },
  roles: { recordLabel: "Application role", columns: [["roleKey", "Role key"], ["name", "Role name"], ["permissions", "Permissions"], ["systemRole", "Built-in"], ["isactive", "Status"]], fields: [{ key: "roleKey", label: "Role key (letters, numbers, underscores)", required: true }, { key: "name", label: "Role name", required: true }, { key: "permissions", label: "Permissions", required: true, kind: "multi-select", options: permissions.map((value) => ({ value, label: value.replaceAll("_", " ") })) }] },
};

function fieldOptions(key: string, field: Field, fleets: Row[], roles: Row[]) {
  if (key === "aircraft" && field.key === "fleetId") return fleets.map((fleet) => ({ value: String(fleet.id), label: String(fleet.name) }));
  if (key === "cabin-zones" && field.key === "area") return areas.map((area) => ({ value: area, label: area.replaceAll("_", " ") }));
  if (key === "users" && field.key === "role") return roles.map((role) => ({ value: String(role.roleKey), label: String(role.name) }));
  if (key === "menu-items" && field.key === "parentId") return [{ value: "", label: "Top-level menu" }, ...fleets.filter((row) => row.parentId == null && row.isactive === 1).map((row) => ({ value: String(row.id), label: String(row.label) }))];
  return field.options || [];
}

export default function ConfigurationPage({ request, initialKey = "" }: { request: Request; initialKey?: string }) {
  const [menu, setMenu] = useState<MenuItem[]>([]);
  const [activeKey, setActiveKey] = useState("");
  const queryClient = useQueryClient();
  const [page, setPage] = useState(() => Number(new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminPage") || "0"));
  const [pageSize, setPageSize] = useState(() => Number(new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminSize") || "25"));
  const [search, setSearch] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminSearch") || "");
  const [status, setStatus] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminStatus") || "ACTIVE");
  const [role, setRole] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminRole") || "");
  const [area, setArea] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminArea") || "");
  const [aircraftType, setAircraftType] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminAircraftType") || "");
  const [exporting, setExporting] = useState(false);
  const [sorting, setSorting] = useState<SortingState>(() => {
    const params = new URLSearchParams(typeof window === "undefined" ? "" : window.location.search);
    const id = params.get("adminSort"); return id ? [{ id, desc: params.get("adminDirection") === "DESC" }] : [];
  });
  const [fleets, setFleets] = useState<Row[]>([]);
  const roleOptionsQuery = useQuery<Row[]>({
    queryKey: ["administration-role-options"], enabled: activeKey === "users", staleTime: 10_000,
    queryFn: async () => { const response = await request("/configuration/roles?includeInactive=false"); if (!response.ok) throw new Error("Could not load application roles."); return response.json() as Promise<Row[]>; },
  });
  const roleOptions = roleOptionsQuery.data || [];
  const [menuLoading, setMenuLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<Row | null>(null);
  const [showForm, setShowForm] = useState(false);

  const activeMenu = menu.find((item) => item.key === activeKey);
  const definition = definitions[activeKey];
  const fleetOptions = useMemo(() => fleets.filter((fleet) => fleet.isactive === 1), [fleets]);
  const tableQuery = useQuery<ServerTablePage<Row>>({
    queryKey: ["administration-table", activeKey, page, pageSize, search, status, role, area, aircraftType, sorting],
    enabled: Boolean(activeKey && definitions[activeKey]),
    staleTime: 10_000,
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize), status });
      if (search) params.set("search", search);
      if (role) params.set("role", role);
      if (area) params.set("area", area);
      if (aircraftType) params.set("aircraftType", aircraftType);
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/configuration/${activeKey}/table?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load this administration table."));
      return response.json() as Promise<ServerTablePage<Row>>;
    },
  });
  const rows = tableQuery.data?.items || [];
  const total = tableQuery.data?.totalElements || 0;
  const tableLoading = tableQuery.isPending || tableQuery.isFetching;
  const refreshRows = useCallback(async () => { await queryClient.invalidateQueries({ queryKey: ["administration-table", activeKey] }); }, [activeKey, queryClient]);
  const parentMenuQuery = useQuery<Row[]>({
    queryKey: ["administration-menu-parents"], enabled: activeKey === "menu-items" && showForm, staleTime: 10_000,
    queryFn: async () => { const response = await request("/configuration/menu-items?includeInactive=false"); if (!response.ok) throw new Error("Could not load parent menu options."); return response.json() as Promise<Row[]>; },
  });

  useEffect(() => {
    if (typeof window === "undefined" || !activeKey) return;
    const params = new URLSearchParams(window.location.search);
    params.set("admin", activeKey); params.set("adminPage", String(page)); params.set("adminSize", String(pageSize));
    if (search) params.set("adminSearch", search); else params.delete("adminSearch");
    params.set("adminStatus", status);
    if (role) params.set("adminRole", role); else params.delete("adminRole");
    if (area) params.set("adminArea", area); else params.delete("adminArea");
    if (aircraftType) params.set("adminAircraftType", aircraftType); else params.delete("adminAircraftType");
    if (sorting[0]) { params.set("adminSort", sorting[0].id); params.set("adminDirection", sorting[0].desc ? "DESC" : "ASC"); }
    else { params.delete("adminSort"); params.delete("adminDirection"); }
    const nextUrl = `${window.location.pathname}?${params.toString()}${window.location.hash}`;
    if (`${window.location.pathname}${window.location.search}${window.location.hash}` !== nextUrl) window.history.pushState(null, "", nextUrl);
  }, [activeKey, area, aircraftType, page, pageSize, role, search, sorting, status]);

  useEffect(() => {
    const restoreFromUrl = () => {
      const params = new URLSearchParams(window.location.search);
      const nextPage = Number(params.get("adminPage"));
      const nextSize = Number(params.get("adminSize"));
      setPage(Number.isInteger(nextPage) && nextPage >= 0 ? nextPage : 0);
      setPageSize([10,25,50,100].includes(nextSize) ? nextSize : 25);
      setSearch(params.get("adminSearch") || ""); setStatus(["ACTIVE","INACTIVE","ALL"].includes(params.get("adminStatus") || "") ? params.get("adminStatus") || "ACTIVE" : "ACTIVE");
      setRole(params.get("adminRole") || ""); setArea(params.get("adminArea") || ""); setAircraftType(params.get("adminAircraftType") || "");
      const sort = params.get("adminSort"); setSorting(sort ? [{ id: sort, desc: params.get("adminDirection") === "DESC" }] : []);
      const section = params.get("admin"); if (section && definitions[section]) setActiveKey(section);
    };
    window.addEventListener("popstate", restoreFromUrl);
    return () => window.removeEventListener("popstate", restoreFromUrl);
  }, []);

  const columns = useMemo<ColumnDef<Row, unknown>[]>(() => (definition?.columns || []).map(([key, label]) => ({
    accessorKey: key,
    header: label,
    enableSorting: ["name","msnNumber","tailNumber","aircraftType","fleetName","code","area","description","displayName","externalUserId","role","roleKey","permissions","label","key","parentLabel","requiredPermissions","routeKey","displayOrder","created_at","updated_at"].includes(key),
    cell: ({ row, getValue }) => key === "isactive"
      ? <span className={row.original.isactive === 1 ? "config-active" : "config-inactive"}>{row.original.isactive === 1 ? "Active" : "Inactive"}</span>
      : key === "fleetName" ? String(fleets.find((fleet) => fleet.id === row.original.fleetId)?.name ?? getValue() ?? "—") : key === "systemRole" ? (getValue() ? "Yes" : "No") : key === "permissions" ? String(getValue() ?? "").replaceAll("|", " · ").replaceAll("_", " ") : String(getValue() ?? "—"),
  })), [definition, fleets]);
  const tableColumns: ColumnDef<Row, unknown>[] = [
    ...columns,
    { id: "actions", header: "Actions", enableSorting: false, enableHiding: false, cell: ({ row }) => { const protectedRole = activeKey === "roles" && row.original.roleKey === "ADMIN"; return <div className="config-row-actions">{protectedRole ? <span className="role-protected-badge">Protected</span> : <>{row.original.isactive === 1 && <button className="button-secondary" onClick={() => openEdit(row.original)}>Edit</button>}{definition?.deactivate !== false && (row.original.isactive === 1 ? <button className="config-danger" onClick={() => void changeActive(row.original, false)}>Deactivate</button> : <button className="button-secondary" onClick={() => void changeActive(row.original, true)}>Reactivate</button>)}</>}</div>; } },
  ];

  async function exportCurrentTable() {
    setExporting(true);
    try {
    const params = new URLSearchParams({ status });
    if (search) params.set("search", search);
    if (role) params.set("role", role);
    if (area) params.set("area", area);
    if (aircraftType) params.set("aircraftType", aircraftType);
    if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
    const response = await request(`/configuration/${activeKey}/export?${params}`);
    if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not export this table."));
    const file = await response.blob();
    const link = document.createElement("a");
    link.href = URL.createObjectURL(file);
    const objectUrl = link.href;
    link.download = `ecabin-${activeKey}.xlsx`;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } finally { setExporting(false); }
  }

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const [menuResponse, fleetResponse] = await Promise.all([request("/configuration/menu"), request("/configuration/fleets")]);
        if (!menuResponse.ok) throw new Error(menuResponse.status === 403 ? "Configuration requires the ADMIN role. Ask an organization administrator to grant access." : "Could not load configuration sections.");
        const menuData: MenuItem[] = await menuResponse.json();
        const fleetData: Row[] = fleetResponse.ok ? await fleetResponse.json() : [];
        const urlKey = new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("admin") || "";
        if (!cancelled) { setMenu(menuData); setFleets(fleetData); setActiveKey(initialKey || urlKey || menuData[0]?.key || ""); }
      } catch (reason) { if (!cancelled) await alert.error(reason instanceof Error ? reason.message : "Could not load configuration."); }
      finally { if (!cancelled) setMenuLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [request, initialKey]);

  function openCreate() { setEditing(null); setShowForm(true); }
  function openEdit(row: Row) { setEditing(row); setShowForm(true); }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget).entries());
    const fields: Record<string, string> = {};
    for (const field of definition.fields) if (!(activeKey === "users" && editing && field.key === "externalUserId") && !(activeKey === "roles" && editing && field.key === "roleKey")) fields[field.key] = field.kind === "multi-select" ? new FormData(event.currentTarget).getAll(field.key).map(String).join("|") : String(values[field.key] ?? "");
    setSaving(true);
    try {
      const response = await request(`/configuration/${activeKey}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: editing?.id, fields }) });
      if (!response.ok) {
        throw new Error(await responseErrorMessage(response, "Could not save this master data record."));
      }
      setShowForm(false); setEditing(null);
      await refreshRows();
      await queryClient.invalidateQueries({ queryKey: ["administration-menu-parents"] });
      if (activeKey === "roles") await queryClient.invalidateQueries({ queryKey: ["administration-role-options"] });
      await alert.success("Master data saved successfully.");
      if (activeKey === "fleets") {
        const fleetResponse = await request("/configuration/fleets");
        if (fleetResponse.ok) setFleets(await fleetResponse.json());
      }
    } catch (reason) { await alert.error(reason instanceof Error ? reason.message : "Could not save this master data record."); }
    finally { setSaving(false); }
  }

  async function changeActive(row: Row, activate: boolean) {
    const label = String(row.name || row.displayName || row.tailNumber || row.code || "this record");
    if (!activate && !await alert.confirm({ title: `Deactivate ${label}?`, text: "The record will be soft-deleted and retained for audit history.", confirmText: "Deactivate", destructive: true })) return;
    const reason = await alert.prompt({ title: `Reason to ${activate ? "reactivate" : "deactivate"} ${label}`, required: true });
    if (!reason) return;
    try {
      const response = activate
        ? await request(`/configuration/${activeKey}/${row.id}/activate`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ reason }) })
        : await request(`/configuration/${activeKey}/${row.id}/deactivate`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ reason }) });
      if (!response.ok) {
        throw new Error(await responseErrorMessage(response, "Could not update this record."));
      }
      await refreshRows();
      await queryClient.invalidateQueries({ queryKey: ["administration-menu-parents"] });
      await alert.success(activate ? "Record reactivated." : "Record deactivated.");
      if (activeKey === "fleets") {
        const fleetResponse = await request("/configuration/fleets");
        if (fleetResponse.ok) setFleets(await fleetResponse.json());
      }
    } catch (reasonValue) { await alert.error(reasonValue instanceof Error ? reasonValue.message : "Could not update this record."); }
  }

  if (menuLoading && !menu.length) return <div className="content"><div className="config-loading">Loading organization configuration…</div></div>;

  return <div className="content configuration-content">
    <div className="heading"><div><div className="eyebrow"><span></span> ORGANIZATION ADMINISTRATION <em>·</em> CONFIGURATION</div><h1>{activeMenu?.label || "Configuration"}</h1><p>{activeMenu?.description || "Manage the settings and reference records used by cabin operations."}</p></div>{definition?.create !== false && <button className="button-primary" onClick={openCreate}><span>＋</span> Add {definition?.recordLabel}</button>}</div>
    <div className="configuration-toolbar">
      <label className="configuration-section-select" htmlFor="configuration-section">Administration area<select id="configuration-section" value={activeKey} onChange={(event) => { setActiveKey(event.target.value); setPage(0); setRole(""); setArea(""); setAircraftType(""); setShowForm(false); }} aria-label="Administration area">{menu.filter((item) => definitions[item.key]).map((item) => <option key={item.key} value={item.key}>{item.label}</option>)}</select></label>
      <label className="configuration-section-select" htmlFor="configuration-status">Record status<select id="configuration-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="ALL">All statuses</option></select></label>
      {activeKey === "users" && <label className="configuration-section-select" htmlFor="configuration-role">Application role<select id="configuration-role" value={role} onChange={(event) => { setRole(event.target.value); setPage(0); }}><option value="">All roles</option>{roleOptions.map((value) => <option key={value.id} value={String(value.roleKey)}>{String(value.name)}</option>)}</select></label>}
      {activeKey === "cabin-zones" && <label className="configuration-section-select" htmlFor="configuration-area">Cabin area<select id="configuration-area" value={area} onChange={(event) => { setArea(event.target.value); setPage(0); }}><option value="">All areas</option>{areas.map((value) => <option key={value} value={value}>{value.replaceAll("_", " ")}</option>)}</select></label>}
    </div>
    <section className="configuration-table-card">
        <div className="configuration-table-head"><div><div className="eyebrow">{activeMenu?.description || "Organization records"}</div><h2>{activeMenu?.label || "Configuration"}</h2></div></div>
        {definition && <DataTable<Row> columns={tableColumns} data={rows} total={total} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={tableLoading} error={tableQuery.isError} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(0); }} onSortingChange={(next) => { setSorting(next); setPage(0); }} onSearchChange={(value) => { setSearch(value); setPage(0); }} onRetry={() => void tableQuery.refetch()} onExport={() => { void exportCurrentTable().catch((reason: unknown) => alert.error(reason instanceof Error ? reason.message : "Could not export this table.")); }} exporting={exporting} filters={activeKey === "aircraft" ? <label className="admin-inline-filter">Aircraft type<input value={aircraftType} onChange={(event) => { setAircraftType(event.target.value); setPage(0); }} placeholder="All types" /></label> : undefined} />}
        <div className="config-table-foot"><span>Tenant-scoped records</span><span><i>✓</i> Changes are captured in the append-only audit history</span></div>
    </section>
    {showForm && definition && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowForm(false); }}><section className={activeKey === "roles" ? "modal role-modal" : "modal"} role="dialog" aria-modal="true" aria-labelledby="configuration-form-title"><div className="modal-heading"><div><div className="eyebrow">MASTER DATA</div><h2 id="configuration-form-title">{editing ? "Edit" : "Add"} {definition.recordLabel}</h2></div><button className="close" onClick={() => setShowForm(false)} aria-label="Close">×</button></div><form onSubmit={save}>
      {definition.fields.map((field) => {
        const disabled = Boolean(editing && ((activeKey === "users" && field.key === "externalUserId") || (activeKey === "roles" && field.key === "roleKey")));
        const value = editing?.[field.key];
        const options = fieldOptions(activeKey, field, activeKey === "menu-items" ? (parentMenuQuery.data || []) : fleetOptions, roleOptions);
        return field.kind === "multi-select" ? <fieldset className="role-permission-field" key={field.key}><legend>{field.label}</legend><div className="role-permission-grid">{options.map((option) => <label key={option.value}><input type="checkbox" name={field.key} value={option.value} defaultChecked={String(value ?? "").split("|").includes(option.value)} />{option.label}</label>)}</div><small>Choose the actions this role can perform. Organization administration remains limited to the built-in Administrator role.</small></fieldset> : <label key={field.key}>{field.label}{field.kind === "select" ? <select name={field.key} required={field.required} defaultValue={String(value ?? "")} disabled={disabled}>{!field.required && <option value="">Choose…</option>}{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select> : field.kind === "textarea" ? <textarea name={field.key} required={field.required} defaultValue={String(value ?? "")} maxLength={500} rows={3} /> : <input name={field.key} type={field.kind === "email" ? "email" : field.kind === "number" ? "number" : "text"} min={field.kind === "number" ? 0 : undefined} required={field.required && !disabled} disabled={disabled} defaultValue={String(value ?? (field.kind === "number" ? 100 : ""))} maxLength={field.key === "description" ? 500 : 200} />}</label>;
      })}
      {activeKey === "users" && !editing && <div className="modal-note"><span>i</span> This grants the selected eCabin Ledger role to an existing enterprise identity. It does not create an identity-provider account.</div>}
      <div className="modal-actions"><button type="button" className="button-secondary" onClick={() => setShowForm(false)}>Cancel</button><button className="button-primary" disabled={saving}>{saving ? "Saving…" : "Save record →"}</button></div>
    </form></section></div>}
  </div>;
}
