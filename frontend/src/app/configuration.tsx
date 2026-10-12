"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import { alert, responseErrorMessage } from "@/common/alerts";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";
import BulkUploadDialog, { downloadTemplateCsv } from "@/common/configuration/BulkUploadDialog";
import DialogPortal from "@/common/dialogs/DialogPortal";
import FormMasterPage from "@/common/configuration/FormMasterPage";
import TeamMembershipSelect, { TeamMembershipOption } from "@/common/configuration/TeamMembershipSelect";

type Row = Record<string, unknown> & { id: string; isactive: number };
type MenuItem = { key: string; label: string; description: string };
type Field = { key: string; label: string; required?: boolean; kind?: "select" | "multi-select" | "team-multi-select" | "email" | "textarea" | "number"; options?: Array<{ value: string; label: string }> };
type Request = (path: string, init?: RequestInit) => Promise<Response>;

const definitions: Record<string, { columns: Array<[string, string]>; fields: Field[]; recordLabel: string; create?: boolean; deactivate?: boolean; readOnly?: boolean; bulkImport?: boolean }> = {
  organizations: { recordLabel: "Organization", columns: [["name", "Organization"], ["isactive", "Status"]], fields: [{ key: "name", label: "Organization name", required: true }], create: false, deactivate: false, bulkImport: false },
  fleets: { recordLabel: "Fleet", columns: [["name", "Fleet"], ["isactive", "Status"]], fields: [{ key: "name", label: "Fleet name", required: true }] },
  aircraft: { recordLabel: "Aircraft", columns: [["msnNumber", "MSN Number"], ["tailNumber", "Registration"], ["aircraftType", "Aircraft type"], ["fleetName", "Fleet"], ["isactive", "Status"]], fields: [{ key: "msnNumber", label: "MSN number" }, { key: "tailNumber", label: "Aircraft registration", required: true }, { key: "aircraftType", label: "Aircraft type", required: true }, { key: "fleetId", label: "Fleet", required: true, kind: "select" }] },
  areas: { recordLabel: "Area", columns: [["code", "Area code"], ["name", "Area name"], ["description", "Description"], ["isactive", "Status"]], fields: [{ key: "code", label: "Area code", required: true }, { key: "name", label: "Area name", required: true }, { key: "description", label: "Description", kind: "textarea" }] },
  "defect-categories": { recordLabel: "Defect category", columns: [["code", "Code"], ["name", "Category"], ["description", "Description"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Category name", required: true }, { key: "description", label: "Description", kind: "textarea" }] },
  "defect-conditions": { recordLabel: "Defect condition", columns: [["code", "Condition code"], ["name", "Condition"], ["description", "Description"], ["applicableComponents", "Applicable components"], ["isactive", "Status"]], fields: [{ key: "code", label: "Condition code", required: true }, { key: "name", label: "Condition name", required: true }, { key: "description", label: "Description", kind: "textarea" }, { key: "componentIds", label: "Applicable components (select one or more)", kind: "multi-select", required: true }] },
  components: { recordLabel: "Component", columns: [["code", "Code"], ["name", "Cabin component"], ["areaName", "Area"], ["description", "Description"], ["isactive", "Status"]], fields: [{ key: "code", label: "Code", required: true }, { key: "name", label: "Component name", required: true }, { key: "areaId", label: "Area", required: true, kind: "select" }, { key: "description", label: "Description", kind: "textarea" }] },
  "maintenance-teams": { recordLabel: "Team", columns: [["name", "Team name"], ["teamType", "Team type"], ["isactive", "Status"]], fields: [{ key: "name", label: "Team name", required: true }, { key: "teamType", label: "Team type", required: true, kind: "select", options: [{ value: "MAINTENANCE", label: "Maintenance" }, { value: "REVIEWER", label: "Reviewer" }] }] },
  "reviewer-routing": { recordLabel: "Reviewer routing", columns: [], fields: [], create: false, deactivate: false },
  users: { recordLabel: "User membership", columns: [["displayName", "Name"], ["externalUserId", "Enterprise identity"], ["role", "Application role"], ["teamNames", "Team memberships"], ["isactive", "Status"]], fields: [{ key: "externalUserId", label: "Enterprise identity / subject", required: true }, { key: "displayName", label: "Display name", required: true }, { key: "role", label: "Application role", required: true, kind: "select" }, { key: "teamIds", label: "Team memberships", kind: "team-multi-select" }] },
  "menu-items": { recordLabel: "Menu item", columns: [["label", "Menu / submenu"], ["key", "Key"], ["parentLabel", "Parent menu"], ["requiredPermissions", "Visible to permissions"], ["routeKey", "Application section"], ["displayOrder", "Order"], ["isactive", "Status"]], fields: [{ key: "label", label: "Menu label", required: true }, { key: "key", label: "Menu key", required: true }, { key: "parentId", label: "Parent menu (leave blank for top-level)", kind: "select" }, { key: "requiredPermissions", label: "Visible to permissions (separate alternatives with |)", required: true }, { key: "icon", label: "Icon character" }, { key: "routeKey", label: "Application section key", required: true, kind: "select", options: ["home", "operations", "reports", "audit", "configuration", "defects", "fleet", "quality", "organizations", "fleets", "aircraft", "areas", "defect-categories", "defect-conditions", "components", "maintenance-teams", "users", "menu-items", "roles", "severities", "permissions", "role-permissions", "api-rate-limits", "report-defect"].map((value) => ({ value, label: value })) }, { key: "displayOrder", label: "Display order", required: true, kind: "number" }] },
  roles: { recordLabel: "Application role", columns: [["roleKey", "Role key"], ["name", "Role name"], ["description", "Role description"], ["permissions", "Permissions"], ["systemRole", "Built-in"], ["isactive", "Status"]], fields: [{ key: "roleKey", label: "Role key (letters, numbers, underscores)", required: true }, { key: "name", label: "Role name", required: true }, { key: "description", label: "Role description", required: true, kind: "textarea" }, { key: "permissions", label: "Permissions", required: true, kind: "multi-select" }] },
  severities: { recordLabel: "Severity", columns: [["code", "Stable code"], ["name", "Severity name"], ["description", "Description"], ["sortOrder", "Display order"], ["isactive", "Status"]], fields: [{ key: "code", label: "Stable code", required: true }, { key: "name", label: "Display name", required: true }, { key: "description", label: "Description (not an approved airworthiness threshold)", required: true, kind: "textarea" }, { key: "sortOrder", label: "Display order", required: true, kind: "number" }] },
  permissions: { recordLabel: "Supported permission", columns: [["code", "Code"], ["name", "Permission"], ["description", "What it allows and its limits"], ["module", "Module"], ["systemPermission", "System permission"], ["isactive", "Status"]], fields: [], create: false, deactivate: false, readOnly: true },
  "role-permissions": { recordLabel: "Role permission mapping", columns: [["roleKey", "Role key"], ["name", "Role"], ["description", "Role description"], ["permissionCodes", "Assigned permission codes"], ["permissionDetails", "Permission descriptions and restrictions"], ["systemRole", "Built-in"], ["updated_at", "Last updated"]], fields: [], create: false, deactivate: false, readOnly: true },
  forms: { recordLabel: "Operational form", columns: [], fields: [], create: false, deactivate: false },
};

function fieldOptions(key: string, field: Field, fleets: Row[], roles: Row[], areaRecords: Row[], components: Row[] = [], permissionCatalog: Row[] = []): Array<{ value: string; label: string }> {
  if (key === "aircraft" && field.key === "fleetId") return fleets.map((fleet) => ({ value: String(fleet.id), label: String(fleet.name) }));
  if (key === "users" && field.key === "role") return roles.map((role) => ({ value: String(role.roleKey), label: String(role.name) }));
  if (key === "roles" && field.key === "permissions") return permissionCatalog.filter((item) => item.isactive === 1 && item.organizationAssignable === true).map((permission) => ({ value: String(permission.code), label: `${permission.code} · ${permission.name}` }));
  if (key === "components" && field.key === "areaId") return areaRecords.filter((area) => area.isactive === 1).map((area) => ({ value: String(area.id), label: `${area.name} (${area.code})` }));
  if (key === "defect-conditions" && field.key === "componentIds") return components.filter((component) => component.isactive === 1).map((component) => ({ value: String(component.id), label: `${component.name} (${component.code})` }));
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
  const [aircraftType, setAircraftType] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("adminAircraftType") || "");
  const [exporting, setExporting] = useState(false);
  const [sorting, setSorting] = useState<SortingState>(() => {
    const params = new URLSearchParams(typeof window === "undefined" ? "" : window.location.search);
    const id = params.get("adminSort"); return id ? [{ id, desc: params.get("adminDirection") === "DESC" }] : [];
  });
  const [showForm, setShowForm] = useState(false);
  const [fleets, setFleets] = useState<Row[]>([]);
  const areaOptionsQuery = useQuery<Row[]>({ queryKey: ["configuration-area-options"], enabled: activeKey === "components", staleTime: 10_000, queryFn: async () => { const response = await request("/configuration/areas?includeInactive=false"); if (!response.ok) throw new Error("Could not load active areas."); return response.json() as Promise<Row[]>; } });
  const areaOptions = areaOptionsQuery.data || [];
  const conditionComponentsQuery = useQuery<Row[]>({ queryKey: ["condition-component-options"], enabled: activeKey === "defect-conditions" && showForm, staleTime: 10_000, queryFn: async () => { const response = await request("/configuration/components?includeInactive=false"); if (!response.ok) throw new Error("Could not load active components for condition applicability."); return response.json() as Promise<Row[]>; } });
  const conditionComponents = conditionComponentsQuery.data || [];
  const roleOptionsQuery = useQuery<Row[]>({
    queryKey: ["administration-role-options"], enabled: activeKey === "users", staleTime: 10_000,
    queryFn: async () => { const response = await request("/configuration/roles?includeInactive=false"); if (!response.ok) throw new Error("Could not load application roles."); return response.json() as Promise<Row[]>; },
  });
  const roleOptions = roleOptionsQuery.data || [];
  const permissionCatalogQuery = useQuery<Row[]>({
    queryKey: ["administration-permission-catalog"], enabled: activeKey === "roles" && showForm, staleTime: 60_000,
    queryFn: async ({ signal }) => { const response = await request("/configuration/permissions?includeInactive=false", { signal }); if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load supported permissions.")); return response.json() as Promise<Row[]>; },
  });
  const permissionCatalog = permissionCatalogQuery.data || [];
  const [menuLoading, setMenuLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<Row | null>(null);
  const teamOptionsQuery = useQuery<TeamMembershipOption[]>({
    queryKey: ["administration-team-membership-options"], enabled: activeKey === "users" && showForm, staleTime: 10_000,
    queryFn: async ({ signal }) => { const response = await request("/configuration/maintenance-teams?includeInactive=false", { signal }); if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load active organization teams.")); return response.json() as Promise<TeamMembershipOption[]>; },
  });
  const [showBulkUpload, setShowBulkUpload] = useState(false);

  const activeMenu = menu.find((item) => item.key === activeKey);
  const definition = definitions[activeKey];
  const fleetOptions = useMemo(() => fleets.filter((fleet) => fleet.isactive === 1), [fleets]);
  const tableQuery = useQuery<ServerTablePage<Row>>({
    queryKey: ["administration-table", activeKey, page, pageSize, search, status, role, aircraftType, sorting],
    enabled: Boolean(activeKey && definitions[activeKey] && activeKey !== "forms" && activeKey !== "reviewer-routing"),
    staleTime: 10_000,
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize), status });
      if (search) params.set("search", search);
      if (role) params.set("role", role);
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
    if (aircraftType) params.set("adminAircraftType", aircraftType); else params.delete("adminAircraftType");
    if (sorting[0]) { params.set("adminSort", sorting[0].id); params.set("adminDirection", sorting[0].desc ? "DESC" : "ASC"); }
    else { params.delete("adminSort"); params.delete("adminDirection"); }
    const nextUrl = `${window.location.pathname}?${params.toString()}${window.location.hash}`;
    if (`${window.location.pathname}${window.location.search}${window.location.hash}` !== nextUrl) window.history.pushState(null, "", nextUrl);
  }, [activeKey, aircraftType, page, pageSize, role, search, sorting, status]);

  useEffect(() => {
    const restoreFromUrl = () => {
      const params = new URLSearchParams(window.location.search);
      const nextPage = Number(params.get("adminPage"));
      const nextSize = Number(params.get("adminSize"));
      setPage(Number.isInteger(nextPage) && nextPage >= 0 ? nextPage : 0);
      setPageSize([10,25,50,100].includes(nextSize) ? nextSize : 25);
      setSearch(params.get("adminSearch") || ""); setStatus(["ACTIVE","INACTIVE","ALL"].includes(params.get("adminStatus") || "") ? params.get("adminStatus") || "ACTIVE" : "ACTIVE");
      setRole(params.get("adminRole") || ""); setAircraftType(params.get("adminAircraftType") || "");
      const sort = params.get("adminSort"); setSorting(sort ? [{ id: sort, desc: params.get("adminDirection") === "DESC" }] : []);
      const section = params.get("admin"); if (section && definitions[section]) setActiveKey(section);
    };
    window.addEventListener("popstate", restoreFromUrl);
    return () => window.removeEventListener("popstate", restoreFromUrl);
  }, []);

  const columns = useMemo<ColumnDef<Row, unknown>[]>(() => (definition?.columns || []).map(([key, label]) => ({
    accessorKey: key,
    header: label,
    enableSorting: ["name","msnNumber","tailNumber","aircraftType","fleetName","code","area","description","applicableComponents","displayName","externalUserId","role","roleKey","permissions","permissionCodes","permissionDetails","module","systemPermission","label","key","parentLabel","requiredPermissions","routeKey","displayOrder","sortOrder","created_at","updated_at"].includes(key),
    cell: ({ row, getValue }) => key === "isactive"
      ? <span className={row.original.isactive === 1 ? "config-active" : "config-inactive"}>{row.original.isactive === 1 ? "Active" : "Inactive"}</span>
      : key === "fleetName" ? String(fleets.find((fleet) => fleet.id === row.original.fleetId)?.name ?? getValue() ?? "—") : key === "systemRole" || key === "systemPermission" ? (getValue() ? "Yes" : "No") : key === "permissions" || key === "permissionCodes" ? String(getValue() ?? "").replaceAll("|", " · ").replaceAll("_", " ") : key === "permissionDetails" ? <span style={{ whiteSpace: "pre-line", display: "block", minWidth: 360 }}>{String(getValue() ?? "—")}</span> : key === "teamNames" ? <div className="team-membership-badges">{String(getValue() ?? "").split("|").filter(Boolean).map((name) => <span className="team-membership-badge" key={name}>{name}</span>)}{!String(getValue() ?? "").trim() && <span className="configuration-muted">No teams</span>}</div> : String(getValue() ?? "—"),
  })), [definition, fleets]);
  const tableColumns: ColumnDef<Row, unknown>[] = [
    ...columns,
    { id: "actions", header: "Actions", enableSorting: false, enableHiding: false, cell: ({ row }) => { const protectedRole = activeKey === "roles" && row.original.roleKey === "ADMIN"; return <div className="config-row-actions">{definition?.readOnly ? <span className="role-protected-badge">Read only</span> : protectedRole ? <span className="role-protected-badge">Protected</span> : <>{row.original.isactive === 1 && <button className="button-secondary" onClick={() => openEdit(row.original)}>Edit</button>}{definition?.deactivate !== false && (row.original.isactive === 1 ? <button className="config-danger" onClick={() => void changeActive(row.original, false)}>Deactivate</button> : <button className="button-secondary" onClick={() => void changeActive(row.original, true)}>Reactivate</button>)}</>}</div>; } },
  ];

  async function exportCurrentTable(visibleColumnIds: string[]) {
    if (!visibleColumnIds.length) throw new Error("Select at least one visible data column to export.");
    setExporting(true);
    try {
    const params = new URLSearchParams({ status });
    if (search) params.set("search", search);
    if (role) params.set("role", role);
    if (aircraftType) params.set("aircraftType", aircraftType);
    if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
    params.set("columns", visibleColumnIds.join(","));
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
    for (const field of definition.fields) if (!(activeKey === "users" && editing && field.key === "externalUserId") && !(activeKey === "roles" && editing && field.key === "roleKey")) fields[field.key] = field.kind === "multi-select" ? new FormData(event.currentTarget).getAll(field.key).map(String).join("|") : field.kind === "team-multi-select" ? String(values[field.key] ?? "") : String(values[field.key] ?? "");
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
  if (activeKey === "forms") return <div className="content configuration-content"><FormMasterPage request={request} /></div>;

  return <div className="content configuration-content">
    <div className="heading"><div><div className="eyebrow"><span></span> ORGANIZATION ADMINISTRATION <em>·</em> CONFIGURATION</div><h1>{activeMenu?.label || "Configuration"}</h1><p>{activeMenu?.description || "Manage the settings and reference records used by cabin operations."}</p></div></div>
    <div className="configuration-toolbar">
      <label className="configuration-section-select" htmlFor="configuration-section">Administration area<select id="configuration-section" value={activeKey} onChange={(event) => { setActiveKey(event.target.value); setPage(0); setRole(""); setAircraftType(""); setShowForm(false); }} aria-label="Administration area">{menu.filter((item) => definitions[item.key]).map((item) => <option key={item.key} value={item.key}>{item.label}</option>)}</select></label>
      <label className="configuration-section-select" htmlFor="configuration-status">Record status<select id="configuration-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="ALL">All statuses</option></select></label>
      {activeKey === "users" && <label className="configuration-section-select" htmlFor="configuration-role">Application role<select id="configuration-role" value={role} onChange={(event) => { setRole(event.target.value); setPage(0); }}><option value="">All roles</option>{roleOptions.map((value) => <option key={value.id} value={String(value.roleKey)}>{String(value.name)}</option>)}</select></label>}
    </div>
    <section className="configuration-table-card">
        <div className="configuration-table-head"><div><div className="eyebrow">{activeMenu?.description || "Organization records"}</div><h2>{activeMenu?.label || "Configuration"}</h2></div><div className="master-actions">{definition && activeKey !== "reviewer-routing" && !definition.readOnly && <>{definition.bulkImport !== false && <><button type="button" className="button-secondary" onClick={()=>setShowBulkUpload(true)}>Bulk Upload</button><button type="button" className="button-secondary" onClick={()=>void downloadTemplateCsv(request,activeKey).catch((reason:unknown)=>alert.error(reason instanceof Error?reason.message:"Could not download the sample CSV."))}>Download Sample CSV</button></>}{definition.create !== false && <button className="button-primary" onClick={openCreate}><span>＋</span> Add New</button>}</>}</div></div>
        {activeKey === "reviewer-routing" ? <ReviewerRoutingEditor request={request} /> : definition && <DataTable<Row> columns={tableColumns} data={rows} total={total} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={tableLoading} error={tableQuery.isError} errorMessage={tableQuery.error instanceof Error ? tableQuery.error.message : "Could not load this administration table."} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(0); }} onSortingChange={(next) => { setSorting(next); setPage(0); }} onSearchChange={(value) => { setSearch(value); setPage(0); }} onRetry={() => void tableQuery.refetch()} onExport={(visibleColumnIds) => { void exportCurrentTable(visibleColumnIds).catch((reason: unknown) => alert.error(reason instanceof Error ? reason.message : "Could not export this table.")); }} exporting={exporting} filters={activeKey === "aircraft" ? <label className="admin-inline-filter">Aircraft type<input value={aircraftType} onChange={(event) => { setAircraftType(event.target.value); setPage(0); }} placeholder="All types" /></label> : undefined} />}
        <div className="config-table-foot"><span>Tenant-scoped records</span><span><i>✓</i> Changes are captured in the append-only audit history</span></div>
    </section>
    {showForm && definition && <DialogPortal onClose={() => { if (!saving) setShowForm(false); }} closeOnEscape={!saving} labelledBy="configuration-form-title" className={`modal master-form-modal${activeKey === "roles" ? " role-modal" : ""}`}><div className="modal-heading"><div><div className="eyebrow">MASTER DATA</div><h2 id="configuration-form-title">{editing ? "Edit" : "Add"} {definition.recordLabel}</h2></div><button type="button" className="close" onClick={() => setShowForm(false)} disabled={saving} aria-label="Close">×</button></div><form className="master-form-content" onSubmit={save}>
      <div className="master-form-body">
      {activeKey === "defect-conditions" && conditionComponentsQuery.isLoading && <p className="configuration-field-help" role="status">Loading active cabin components…</p>}
      {definition.fields.map((field) => {
        const disabled = Boolean(editing && ((activeKey === "users" && field.key === "externalUserId") || (activeKey === "roles" && field.key === "roleKey")));
        const value = editing?.[field.key];
        const options = fieldOptions(activeKey, field, activeKey === "menu-items" ? (parentMenuQuery.data || []) : fleetOptions, roleOptions, areaOptions, conditionComponents, permissionCatalog);
        if (field.kind === "team-multi-select") {
          const activeTeamNames = String(editing?.teamNames ?? "").split("|").filter(Boolean);
          const initialIds = String(value ?? "") || (teamOptionsQuery.data || []).filter((team) => activeTeamNames.includes(team.name)).map((team) => team.id).join("|");
          return <fieldset className="team-membership-field master-form-wide" key={field.key}><legend>{field.label}</legend><TeamMembershipSelect key={`${editing?.id || "new-user"}-${initialIds}`} options={teamOptionsQuery.data || []} initialIds={initialIds} loading={teamOptionsQuery.isLoading} error={teamOptionsQuery.error instanceof Error ? teamOptionsQuery.error.message : undefined} disabled={saving} onRetry={() => void teamOptionsQuery.refetch()} /><small>Team membership supports work-queue visibility. It does not grant application roles or permissions.</small></fieldset>;
        }
        return field.kind === "multi-select" ? <fieldset className={`role-permission-field master-form-wide${activeKey === "defect-conditions" ? " condition-component-field" : ""}`} key={field.key}><legend>{field.label}</legend>{activeKey === "roles" && permissionCatalogQuery.isLoading ? <p className="configuration-field-help" role="status">Loading supported permission definitions…</p> : activeKey === "roles" && permissionCatalogQuery.isError ? <div className="configuration-field-error" role="alert">{permissionCatalogQuery.error instanceof Error ? permissionCatalogQuery.error.message : "Could not load supported permissions."}<button type="button" className="button-secondary" onClick={() => void permissionCatalogQuery.refetch()}>Retry</button></div> : conditionComponentsQuery.isError && activeKey === "defect-conditions" ? <p className="configuration-field-error" role="alert">Could not load components. Close and reopen this form to retry.</p> : <div className="role-permission-grid">{options.map((option) => <label key={option.value}><input type="checkbox" name={field.key} value={option.value} defaultChecked={String(value ?? "").split("|").includes(option.value)} disabled={(activeKey === "roles" && permissionCatalogQuery.isLoading) || (activeKey === "defect-conditions" && conditionComponentsQuery.isLoading)} />{option.label}</label>)}</div>}<small>{activeKey === "roles" ? "Choose supported actions for this role. Organization administration remains limited to the protected built-in Administrator role; team membership and workflow checks are still required." : "Conditions are reusable across components. These labels classify findings; they are not approved maintenance procedures."}</small></fieldset> : <label className={field.kind === "textarea" ? "master-form-wide" : undefined} key={field.key}>{field.label}{field.kind === "select" ? <select name={field.key} required={field.required} defaultValue={String(value ?? "")} disabled={disabled}>{!field.required && <option value="">Choose…</option>}{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select> : field.kind === "textarea" ? <textarea name={field.key} required={field.required} defaultValue={String(value ?? "")} maxLength={field.key === "description" ? 1200 : 500} rows={3} /> : <input name={field.key} type={field.kind === "email" ? "email" : field.kind === "number" ? "number" : "text"} min={field.kind === "number" ? 0 : undefined} required={field.required && !disabled} disabled={disabled} defaultValue={String(value ?? (field.kind === "number" ? 100 : ""))} maxLength={field.key === "description" ? 500 : 200} />}</label>;
      })}
      {activeKey === "users" && !editing && <div className="modal-note"><span>i</span> This grants the selected eCabin Ledger role to an existing enterprise identity. It does not create an identity-provider account.</div>}
      </div><div className="modal-actions"><button type="button" className="button-secondary" onClick={() => setShowForm(false)} disabled={saving}>Cancel</button><button className="button-primary" disabled={saving}>{saving ? "Saving…" : editing ? "Update record →" : "Save record →"}</button></div>
    </form></DialogPortal>}
    {showBulkUpload && definition && <BulkUploadDialog resource={activeKey} label={activeMenu?.label || definition.recordLabel} request={request} onClose={()=>setShowBulkUpload(false)} onImported={async()=>{setPage(0);await queryClient.invalidateQueries({queryKey:["administration-table",activeKey]});}} />}
  </div>;
}

function ReviewerRoutingEditor({ request }: { request: Request }) {
  const routing = useQuery<{ teamId: string; teamName: string; eligibleMembers: number }>({
    queryKey: ["reviewer-routing"], queryFn: async ({ signal }) => { const response = await request("/configuration/reviewer-routing/default", { signal }); if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load the Reviewer routing configuration.")); return response.json(); },
  });
  const teams = useQuery<Row[]>({
    queryKey: ["reviewer-team-options"], queryFn: async ({ signal }) => { const response = await request("/configuration/maintenance-teams?includeInactive=false", { signal }); if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load organization teams.")); return response.json(); },
  });
  const [teamId, setTeamId] = useState(""); const [reason, setReason] = useState(""); const [saving, setSaving] = useState(false);
  const eligibleTeams = (teams.data || []).filter((team) => team.teamType === "REVIEWER" && team.isactive === 1 && Number(team.eligibleMembers) > 0);
  const selectedTeamId = teamId || routing.data?.teamId || "";
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSaving(true);
    try { const response = await request("/configuration/reviewer-routing/default", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ teamId: selectedTeamId, reason }) }); if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not update the Reviewer route.")); await routing.refetch(); setTeamId(""); setReason(""); await alert.success("Default Reviewer route updated."); }
    catch (error) { await alert.error(error instanceof Error ? error.message : "Could not update the Reviewer route."); }
    finally { setSaving(false); }
  }
  if (routing.isLoading || teams.isLoading) return <p role="status" className="configuration-field-help">Loading active Reviewer teams and routing configuration…</p>;
  if (routing.isError || teams.isError) return <div className="configuration-field-error" role="alert">{routing.error instanceof Error ? routing.error.message : teams.error instanceof Error ? teams.error.message : "Could not load Reviewer routing."}<button className="button-secondary" type="button" onClick={() => { void routing.refetch(); void teams.refetch(); }}>Retry</button></div>;
  return <div className="reviewer-routing-editor">
    <div className="reviewer-routing-current"><span>Current default route</span><strong>{routing.data?.teamName || "Not configured"}</strong><small>{routing.data?.eligibleMembers ?? 0} active member(s) with REVIEW permission</small></div>
    {!eligibleTeams.length && <p className="configuration-field-error" role="alert">Create an active Reviewer team, add at least one active organization user, and grant that user the REVIEW permission before configuring routing.</p>}
    <form onSubmit={(event) => void save(event)} className="reviewer-routing-form">
      <label>Reviewer team<select required value={selectedTeamId} onChange={(event) => setTeamId(event.target.value)}><option value="">Choose an eligible Reviewer team…</option>{eligibleTeams.map((team) => <option key={team.id} value={String(team.id)}>{String(team.name)}</option>)}</select></label>
      <label>Reason for change<textarea required maxLength={1000} rows={3} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="Explain why this team should receive newly reported defects" /></label>
      <button className="button-primary" disabled={saving || !eligibleTeams.length}>{saving ? "Saving…" : "Save Reviewer route"}</button>
    </form>
    <p className="configuration-field-help">New defects and work returned for review are visible to every active member of the routed team who also has the REVIEW permission. Membership alone never grants that permission.</p>
  </div>;
}
