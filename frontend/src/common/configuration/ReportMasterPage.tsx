"use client";

import { FormEvent, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import DataTable from "@/common/tables/DataTable";
import { alert, responseErrorMessage } from "@/common/alerts";
import { changeRoleGrant, emptyRoleGrant, roleAccessPayload } from "./reportAdminModel.mjs";
import type { ReportAdminGrant } from "./reportAdminModel.mjs";
import { SearchableSelect } from "@/common/selects/SearchableSelect";
import { shouldUseSearchableSelect } from "@/common/selects/searchableDropdownModel.mjs";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type ReportSummary = { id: string; reportCode: string; definitionVersion: number; name: string; description: string | null; sortOrder: number; active: boolean; current: boolean; version: number; sourceTemplateId: string | null; sourceTemplateVersion: number | null };
type ReportPage = { items: ReportSummary[]; page: number; size: number; totalElements: number; totalPages: number };
type RoutineParameter = { parameterKey: string; parameterType: string; required: boolean; filterable: boolean; sortOrder: number };
type RoutineOption = { id: string; routineKey: string; contractVersion: number; parameters: RoutineParameter[] };
type LookupOption = { id: string; lookupKey: string; valueType: string };
type FilterDefinition = { id: string; filterKey: string; label: string; filterType: string; parameterKey: string; required: boolean; sortOrder: number; configuration: Record<string, unknown>; lookupSourceId: string | null; lookupKey: string | null };
type ColumnDefinition = { id: string; columnKey: string; label: string; dataType: string; sortOrder: number; visible: boolean; sortable: boolean; exportable: boolean; formatConfiguration: Record<string, unknown> };
type RoleGrant = { roleKey: string; roleName: string; canView: boolean; canExecute: boolean; canExport: boolean };
type ReportDetail = { report: ReportSummary; routineId: string; filters: FilterDefinition[]; columns: ColumnDefinition[]; roleAccess: RoleGrant[] };
type Template = { id: string; templateCode: string; templateVersion: number; name: string; description: string | null; sortOrder: number; filters: FilterDefinition[]; columns: ColumnDefinition[] };
type RoleOption = { roleKey: string; name: string; isactive: number };
type FilterDraft = { filterKey: string; label: string; filterType: string; parameterKey: string; required: boolean; sortOrder: number; configurationText: string; lookupSourceId: string };
type ColumnDraft = { columnKey: string; label: string; dataType: string; sortOrder: number; visible: boolean; sortable: boolean; exportable: boolean; formatText: string };
type GrantDraft = ReportAdminGrant;
type Tab = "overview" | "filters" | "columns" | "access";

const filterTypes = ["TEXT", "NUMBER", "BOOLEAN", "DATE", "SINGLE_SELECT", "MULTI_SELECT", "LOOKUP_SINGLE", "LOOKUP_MULTI"];
const columnTypes = ["TEXT", "NUMBER", "BOOLEAN", "DATE", "DATETIME", "UUID"];
const emptyFilter = (order: number): FilterDraft => ({ filterKey: "", label: "", filterType: "TEXT", parameterKey: "", required: false, sortOrder: order, configurationText: "{}", lookupSourceId: "" });
const emptyColumn = (order: number): ColumnDraft => ({ columnKey: "", label: "", dataType: "TEXT", sortOrder: order, visible: true, sortable: false, exportable: true, formatText: "{}" });
const toJson = (value: string, label: string): Record<string, unknown> => {
  let parsed: unknown;
  try { parsed = JSON.parse(value || "{}"); } catch { throw new Error(`${label} must contain valid JSON.`); }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error(`${label} must be a JSON object.`);
  return parsed as Record<string, unknown>;
};

export default function ReportMasterPage({ request }: { request: Request }) {
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("ALL");
  const [selectedId, setSelectedId] = useState("");
  const [creating, setCreating] = useState(false);
  const [tab, setTab] = useState<Tab>("overview");
  const [sorting, setSorting] = useState<SortingState>([]);
  const [name, setName] = useState("");
  const [reportCode, setReportCode] = useState("");
  const [description, setDescription] = useState("");
  const [sortOrder, setSortOrder] = useState(100);
  const [routineId, setRoutineId] = useState("");
  const [filters, setFilters] = useState<FilterDraft[]>([]);
  const [columns, setColumns] = useState<ColumnDraft[]>([]);
  const [grants, setGrants] = useState<GrantDraft[]>([]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [templateKey, setTemplateKey] = useState("");
  const [templateCodeOverride, setTemplateCodeOverride] = useState("");
  const [enablingTemplate, setEnablingTemplate] = useState(false);
  const [changingActivation, setChangingActivation] = useState(false);
  const [loadedDetailKey, setLoadedDetailKey] = useState("");

  const reports = useQuery({
    queryKey: ["report-admin-list", page, pageSize, search, status],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize), status });
      if (search.trim()) params.set("search", search.trim());
      const response = await request(`/report-admin/reports?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load tenant reports."));
      return response.json() as Promise<ReportPage>;
    },
  });
  const detail = useQuery({
    queryKey: ["report-admin-detail", selectedId], enabled: Boolean(selectedId) && !creating,
    queryFn: async ({ signal }) => {
      const response = await request(`/report-admin/reports/${encodeURIComponent(selectedId)}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load this report definition."));
      return response.json() as Promise<ReportDetail>;
    },
  });
  const routines = useQuery({ queryKey: ["report-admin-routines"], queryFn: async ({ signal }) => {
    const response = await request("/report-admin/routines", { signal });
    if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load approved report routines."));
    return response.json() as Promise<RoutineOption[]>;
  } });
  const lookups = useQuery({ queryKey: ["report-admin-lookups"], queryFn: async ({ signal }) => {
    const response = await request("/report-admin/lookups", { signal });
    if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load registered lookup sources."));
    return response.json() as Promise<LookupOption[]>;
  } });
  const templates = useQuery({ queryKey: ["report-admin-templates"], queryFn: async ({ signal }) => {
    const response = await request("/report-templates", { signal });
    if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load approved report templates."));
    return response.json() as Promise<Template[]>;
  } });
  const roles = useQuery({ queryKey: ["report-admin-roles"], queryFn: async ({ signal }) => {
    const response = await request("/configuration/roles?includeInactive=false", { signal });
    if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load active tenant roles."));
    return response.json() as Promise<RoleOption[]>;
  } });

  const detailKey = detail.data ? `${detail.data.report.id}:${detail.data.report.version}:${(roles.data || []).map((role) => role.roleKey).join(",")}` : "";
  if (detail.data && !creating && loadedDetailKey !== detailKey) {
    const value = detail.data;
    setLoadedDetailKey(detailKey);
    setName(value.report.name);
    setReportCode(value.report.reportCode);
    setDescription(value.report.description || "");
    setSortOrder(value.report.sortOrder);
    setRoutineId(value.routineId);
    setFilters(value.filters.map((item) => ({ filterKey: item.filterKey, label: item.label, filterType: item.filterType, parameterKey: item.parameterKey, required: item.required, sortOrder: item.sortOrder, configurationText: JSON.stringify(item.configuration || {}, null, 2), lookupSourceId: item.lookupSourceId || "" })));
    setColumns(value.columns.map((item) => ({ columnKey: item.columnKey, label: item.label, dataType: item.dataType, sortOrder: item.sortOrder, visible: item.visible, sortable: item.sortable, exportable: item.exportable, formatText: JSON.stringify(item.formatConfiguration || {}, null, 2) })));
    const existing = new Map(value.roleAccess.map((item) => [item.roleKey, item]));
    setGrants((roles.data || []).filter((item) => item.isactive === 1).map((role) => {
      const grant = existing.get(role.roleKey);
      return { roleKey: role.roleKey, roleName: role.name, canView: grant?.canView || false, canExecute: grant?.canExecute || false, canExport: grant?.canExport || false };
    }));
  }

  const selectedReport = detail.data?.report;
  const selectedRoutine = routines.data?.find((item) => item.id === (creating ? routineId : detail.data?.routineId));
  const listColumns = useMemo<ColumnDef<ReportSummary, unknown>[]>(() => [
    { accessorKey: "reportCode", header: "Report code", enableSorting: false },
    { accessorKey: "name", header: "Report", enableSorting: false },
    { id: "state", header: "Status", accessorFn: (row) => row.active ? "Active" : "Inactive", enableSorting: false },
    { accessorKey: "definitionVersion", header: "Version", enableSorting: false },
    { id: "actions", header: "Actions", enableSorting: false, enableHiding: false, cell: ({ row }) => <button type="button" className="button-secondary" onClick={() => openReport(row.original.id)}>Configure</button> },
  ], []);

  function openReport(id: string) {
    setError(""); setCreating(false); setSelectedId(id); setTab("overview");
  }
  function startCreate() {
    setError(""); setCreating(true); setSelectedId(""); setTab("overview"); setReportCode(""); setName(""); setDescription(""); setSortOrder(100);
    setRoutineId(""); setFilters([]); setColumns([]); setGrants((roles.data || []).filter((role) => role.isactive === 1).map(emptyRoleGrant));
  }
  function selectTemplate(key: string) {
    setTemplateKey(key); setError("");
    const template = templates.data?.find((item) => `${item.id}:${item.templateVersion}` === key);
    if (!template) return;
    setTemplateCodeOverride(template.templateCode);
  }
  async function enableTemplate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!templateKey) return;
    const [templateId, version] = templateKey.split(":");
    setEnablingTemplate(true); setError("");
    try {
      const response = await request(`/report-admin/templates/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(version)}/enable`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify(templateCodeOverride.trim() ? { reportCode: templateCodeOverride.trim() } : {}),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not enable this approved template."));
      const created = await response.json() as ReportDetail;
      client.setQueryData(["report-admin-detail", created.report.id], created);
      await client.invalidateQueries({ queryKey: ["report-admin-list"] });
      setTemplateKey(""); setSelectedId(created.report.id); setCreating(false); setTab("overview");
      await alert.success("Template enabled for this organization. No role access was granted.");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not enable this approved template."); }
    finally { setEnablingTemplate(false); }
  }
  function filterPayload() {
    return filters.map((item) => ({ filterKey: item.filterKey.trim(), label: item.label.trim(), filterType: item.filterType, parameterKey: item.parameterKey.trim(), required: item.required, sortOrder: Number(item.sortOrder), configuration: toJson(item.configurationText, `Filter ${item.filterKey || "configuration"}`), lookupSourceId: item.filterType.startsWith("LOOKUP_") ? item.lookupSourceId || null : null }));
  }
  function columnPayload() {
    return columns.map((item) => ({ columnKey: item.columnKey.trim(), label: item.label.trim(), dataType: item.dataType, sortOrder: Number(item.sortOrder), visible: item.visible, sortable: item.sortable, exportable: item.exportable, formatConfiguration: toJson(item.formatText, `Column ${item.columnKey || "format"}`) }));
  }
  async function saveReport() {
    setSaving(true); setError("");
    try {
      const filtersValue = filterPayload(); const columnsValue = columnPayload();
      if (columnsValue.length === 0) throw new Error("Add at least one output column before saving.");
      const body = creating
        ? { reportCode: reportCode.trim(), name: name.trim(), description: description.trim() || null, routineId, sortOrder: Number(sortOrder), filters: filtersValue, columns: columnsValue }
        : { expectedVersion: selectedReport?.version, name: name.trim(), description: description.trim() || null, sortOrder: Number(sortOrder), filters: filtersValue, columns: columnsValue };
      if (creating && !routineId) throw new Error("Choose an approved active report routine.");
      const response = await request(creating ? "/report-admin/reports" : `/report-admin/reports/${encodeURIComponent(selectedId)}`, {
        method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not save the report configuration."));
      const saved = await response.json() as ReportDetail;
      client.setQueryData(["report-admin-detail", saved.report.id], saved);
      setSelectedId(saved.report.id); setCreating(false);
      await client.invalidateQueries({ queryKey: ["report-admin-list"] });
      await alert.success("Report configuration saved. Role access remains separate.");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not save the report configuration."); }
    finally { setSaving(false); }
  }
  async function saveRoleAccess() {
    if (!selectedReport) return;
    setSaving(true); setError("");
    try {
      const roleAccess = roleAccessPayload(grants);
      const response = await request(`/report-admin/reports/${encodeURIComponent(selectedId)}/role-access`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ expectedVersion: selectedReport.version, roleAccess }),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not update report role access."));
      const saved = await response.json() as ReportDetail;
      client.setQueryData(["report-admin-detail", selectedId], saved);
      await client.invalidateQueries({ queryKey: ["report-admin-list"] });
      await alert.success("Report role access updated.");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not update report role access."); }
    finally { setSaving(false); }
  }
  async function toggleActive() {
    if (!selectedReport) return;
    setChangingActivation(true); setError("");
    try {
      const response = await request(`/report-admin/reports/${encodeURIComponent(selectedId)}/activation`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ expectedVersion: selectedReport.version, active: !selectedReport.active }),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not change report status."));
      const saved = await response.json() as ReportDetail;
      client.setQueryData(["report-admin-detail", selectedId], saved);
      await client.invalidateQueries({ queryKey: ["report-admin-list"] });
      await alert.success(saved.report.active ? "Report activated." : "Report deactivated.");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not change report status."); }
    finally { setChangingActivation(false); }
  }

  const pageData = reports.data;
  const editing = creating || Boolean(selectedId);
  const editorLoading = Boolean(selectedId) && detail.isLoading;
  const editorError = Boolean(selectedId) && detail.isError;
  const availableTemplates = templates.data || [];
  function addFilter() { setFilters((current) => [...current, emptyFilter(current.length ? Math.max(...current.map((item) => item.sortOrder)) + 10 : 10)]); }
  function updateFilter(index: number, changes: Partial<FilterDraft>) { setFilters((current) => current.map((item, i) => i === index ? { ...item, ...changes } : item)); }
  function addColumn() { setColumns((current) => [...current, emptyColumn(current.length ? Math.max(...current.map((item) => item.sortOrder)) + 10 : 10)]); }
  function updateColumn(index: number, changes: Partial<ColumnDraft>) { setColumns((current) => current.map((item, i) => i === index ? { ...item, ...changes } : item)); }
  function updateGrant(roleKey: string, changes: Partial<GrantDraft>) { setGrants((current) => changeRoleGrant(current, roleKey, changes)); }

  return <main className="report-admin-page">
    <header className="heading"><div><h1>Report Master</h1></div><button type="button" className="button-primary" onClick={startCreate}>Create report</button></header>

    <section className="report-admin-card" aria-label="Enable approved report template">
      <div><h2>Enable approved template</h2><p>Enabling creates an organization-owned copy. It does not add any report role grants.</p></div>
      {templates.isLoading ? <p role="status">Loading approved templates…</p> : templates.isError ? <div role="alert" className="configuration-field-error">{templates.error instanceof Error ? templates.error.message : "Could not load templates."}<button className="button-secondary" type="button" onClick={() => void templates.refetch()}>Retry</button></div> : availableTemplates.length === 0 ? <p className="configuration-field-help">No active approved templates are available. Create a tenant report using an active approved routine.</p> : <form className="report-template-enable" onSubmit={(event) => void enableTemplate(event)}>
        {shouldUseSearchableSelect(availableTemplates.length)?<SearchableSelect id="report-admin-template" label="Approved template" options={availableTemplates.map(template=>({value:`${template.id}:${template.templateVersion}`,label:`${template.name} · v${template.templateVersion}`}))} value={templateKey} placeholder="Choose template…" required onChange={selectTemplate} className="text-sm font-semibold"/>:<label>Approved template<select value={templateKey} required onChange={(event) => selectTemplate(event.target.value)}><option value="">Choose template…</option>{availableTemplates.map((template) => <option key={`${template.id}:${template.templateVersion}`} value={`${template.id}:${template.templateVersion}`}>{template.name} · v{template.templateVersion}</option>)}</select></label>}
        <label>Tenant report code<input value={templateCodeOverride} onChange={(event) => setTemplateCodeOverride(event.target.value.toUpperCase())} maxLength={80} pattern="[A-Z][A-Z0-9_]{0,79}" aria-describedby="report-code-help" /></label>
        <small id="report-code-help">Optional. Leave as suggested to use the template code.</small>
        <button type="submit" className="button-secondary" disabled={!templateKey || enablingTemplate}>{enablingTemplate ? "Enabling…" : "Enable template"}</button>
      </form>}
    </section>

    <section className="report-admin-card" aria-label="Tenant report definitions">
      <div className="report-admin-card-heading"><div><h2>Active Reports</h2></div><label>Status<select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="ALL">All statuses</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label></div>
      <DataTable<ReportSummary> className="master-data-table" columns={listColumns} data={pageData?.items || []} total={pageData?.totalElements || 0} page={pageData?.page || page} pageSize={pageData?.size || pageSize} sorting={sorting} search={search} loading={reports.isLoading} error={reports.isError} errorMessage={reports.error instanceof Error ? reports.error.message : "Could not load reports."} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(0); }} onSortingChange={setSorting} onSearchChange={(value) => { setSearch(value); setPage(0); }} onRetry={() => void reports.refetch()} onExport={() => undefined} exporting={false} exportable={false} selectable={false} />
      {pageData && pageData.totalPages > 1 && <p className="configuration-field-help">Page {pageData.page + 1} of {pageData.totalPages} · {pageData.totalElements.toLocaleString()} reports</p>}
    </section>

    {editing && <section className="report-admin-card report-admin-editor" aria-label="Report configuration">
      <div className="report-admin-card-heading"><div><div className="eyebrow">{creating ? "NEW REPORT" : `REPORT · ${selectedReport?.reportCode || "Loading"}`}</div><h2>{creating ? "Create report" : selectedReport?.name || "Report settings"}</h2></div>{selectedReport && <div className="report-admin-heading-actions"><span className={selectedReport.active ? "config-active" : "config-inactive"}>{selectedReport.active ? "Active" : "Inactive"}</span><button type="button" className="button-secondary" disabled={changingActivation} onClick={() => void toggleActive()}>{changingActivation ? "Saving…" : selectedReport.active ? "Deactivate" : "Activate"}</button></div>}</div>
      {editorLoading && <p role="status">Loading report settings…</p>}
      {editorError && <div className="configuration-field-error" role="alert">{detail.error instanceof Error ? detail.error.message : "Could not load this report."}<button type="button" className="button-secondary" onClick={() => void detail.refetch()}>Retry</button></div>}
      {!editorLoading && !editorError && <>
        <div className="report-admin-tabs" role="tablist" aria-label="Report settings sections">{(["overview", "filters", "columns", "access"] as Tab[]).map((item) => <button key={item} type="button" role="tab" aria-selected={tab === item} className={tab === item ? "active" : ""} onClick={() => setTab(item)}>{item === "access" ? "Role Access" : item[0].toUpperCase() + item.slice(1)}</button>)}</div>
        {tab === "overview" && <form className="report-admin-form" onSubmit={(event) => { event.preventDefault(); void saveReport(); }}>
          <label>Report code<input value={reportCode} onChange={(event) => setReportCode(event.target.value.toUpperCase())} disabled={!creating} required maxLength={80} pattern="[A-Z][A-Z0-9_]{0,79}" /></label>
          <label>Display name<input value={name} onChange={(event) => setName(event.target.value)} required maxLength={120} /></label>
          <label className="report-admin-wide">Description<textarea value={description} onChange={(event) => setDescription(event.target.value)} maxLength={1000} rows={3} /></label>
          <label>Display order<input type="number" value={sortOrder} min={0} max={99999} onChange={(event) => setSortOrder(Number(event.target.value))} required /></label>
          {creating ? <>{shouldUseSearchableSelect(routines.data?.length||0)?<SearchableSelect id="report-admin-routine" label="Approved routine" options={(routines.data||[]).map(routine=>({value:routine.id,label:`${routine.routineKey} · contract ${routine.contractVersion}`}))} value={routineId} onChange={setRoutineId} placeholder="Choose active approved routine…" required disabled={routines.isLoading||routines.isError} className="text-sm font-semibold"/>:<label>Approved routine<select value={routineId} onChange={(event) => setRoutineId(event.target.value)} required><option value="">Choose active approved routine…</option>{(routines.data || []).map((routine) => <option key={routine.id} value={routine.id}>{routine.routineKey} · contract {routine.contractVersion}</option>)}</select></label>}{routines.isLoading && <small role="status">Loading routines…</small>}{routines.isError && <small className="configuration-field-error">{routines.error instanceof Error ? routines.error.message : "Could not load routines."}</small>}{!routines.isLoading && !routines.isError && !routines.data?.length && <small>No active reviewed routine is registered.</small>}</> : <div className="report-admin-routine"><span>Registered routine</span><strong>{selectedRoutine?.routineKey || "Routine not currently active/available"}</strong><small>Routine assignment is fixed by the approved server registry.</small></div>}
          {creating && !routines.isError && routines.data?.length === 0 && <p className="configuration-field-help report-admin-wide">There are no active approved routines yet. Reports can’t be created until a routine is registered through reviewed deployment configuration.</p>}
          <div className="report-admin-actions report-admin-wide"><button type="button" className="button-secondary" onClick={() => { setCreating(false); setSelectedId(""); }}>Cancel</button><button type="submit" className="button-primary" disabled={saving || (creating && !routines.data?.length)}>{saving ? "Saving…" : creating ? "Create report" : "Save report details"}</button></div>
        </form>}
        {tab === "filters" && <div className="report-admin-section">
          <div className="report-admin-section-heading"><div><h3>Report filters</h3><p>Filter parameter mappings must match the selected routine’s registered parameters.</p></div><button type="button" className="button-secondary" onClick={addFilter}>Add filter</button></div>
          {filters.length === 0 && <p className="configuration-field-help">No filters configured.</p>}
          {filters.map((filter, index) => <article className="report-admin-item" key={`filter-${index}`}>
            <div className="report-admin-item-heading"><strong>Filter {index + 1}</strong><button type="button" className="config-danger" onClick={() => setFilters((current) => current.filter((_, i) => i !== index))}>Remove</button></div>
            <label>Stable key<input value={filter.filterKey} onChange={(event) => updateFilter(index, { filterKey: event.target.value })} pattern="[a-z][a-zA-Z0-9_]{0,63}" maxLength={64} /></label>
            <label>Label<input value={filter.label} onChange={(event) => updateFilter(index, { label: event.target.value })} maxLength={100} /></label>
            <label>Type<select value={filter.filterType} onChange={(event) => updateFilter(index, { filterType: event.target.value, lookupSourceId: event.target.value.startsWith("LOOKUP_") ? filter.lookupSourceId : "" })}>{filterTypes.map((type) => <option key={type} value={type}>{type.replaceAll("_", " ")}</option>)}</select></label>
            <label>Routine parameter<select value={filter.parameterKey} onChange={(event) => updateFilter(index, { parameterKey: event.target.value })}><option value="">Choose registered parameter…</option>{(selectedRoutine?.parameters || []).filter((parameter) => parameter.filterable).map((parameter) => <option key={parameter.parameterKey} value={parameter.parameterKey}>{parameter.parameterKey} · {parameter.parameterType}{parameter.required ? " · required" : ""}</option>)}</select></label>
            <label>Order<input type="number" min={0} max={99999} value={filter.sortOrder} onChange={(event) => updateFilter(index, { sortOrder: Number(event.target.value) })} /></label>
            <label className="report-admin-check"><input type="checkbox" checked={filter.required} onChange={(event) => updateFilter(index, { required: event.target.checked })} />Required</label>
            {filter.filterType.startsWith("LOOKUP_") && <>{shouldUseSearchableSelect(lookups.data?.length||0)?<SearchableSelect id={`report-admin-lookup-${index}`} label="Registered lookup source" options={(lookups.data||[]).map(lookup=>({value:lookup.id,label:`${lookup.lookupKey} · ${lookup.valueType}`}))} value={filter.lookupSourceId} onChange={value=>updateFilter(index,{lookupSourceId:value})} placeholder="Choose lookup source…" className="text-sm font-semibold"/>:<label>Registered lookup source<select value={filter.lookupSourceId} onChange={(event) => updateFilter(index, { lookupSourceId: event.target.value })}><option value="">Choose lookup source…</option>{(lookups.data || []).map((lookup) => <option key={lookup.id} value={lookup.id}>{lookup.lookupKey} · {lookup.valueType}</option>)}</select></label>}{lookups.isError && <small className="configuration-field-error">{lookups.error instanceof Error ? lookups.error.message : "Could not load sources."}<button type="button" onClick={() => void lookups.refetch()}>Retry</button></small>}</>}
            <label className="report-admin-wide">Validation/configuration JSON<textarea value={filter.configurationText} onChange={(event) => updateFilter(index, { configurationText: event.target.value })} rows={3} spellCheck={false} /></label>
          </article>)}
          <div className="report-admin-actions"><button type="button" className="button-primary" disabled={saving || creating} onClick={() => void saveReport()}>{saving ? "Saving…" : "Save filters"}</button>{creating && <p className="configuration-field-help">Create the report from Overview before adding filters.</p>}</div>
        </div>}
        {tab === "columns" && <div className="report-admin-section">
          <div className="report-admin-section-heading"><div><h3>Output columns</h3><p>Column metadata controls presentation and sorting; it does not define database query behavior.</p></div><button type="button" className="button-secondary" onClick={addColumn}>Add column</button></div>
          {columns.length === 0 && <p className="configuration-field-error">Add at least one output column before activating this report.</p>}
          {columns.map((column, index) => <article className="report-admin-item" key={`column-${index}`}>
            <div className="report-admin-item-heading"><strong>Column {index + 1}</strong><button type="button" className="config-danger" onClick={() => setColumns((current) => current.filter((_, i) => i !== index))}>Remove</button></div>
            <label>Stable key<input value={column.columnKey} onChange={(event) => updateColumn(index, { columnKey: event.target.value })} pattern="[a-z][a-zA-Z0-9_]{0,63}" maxLength={64} /></label>
            <label>Display label<input value={column.label} onChange={(event) => updateColumn(index, { label: event.target.value })} maxLength={100} /></label>
            <label>Data type<select value={column.dataType} onChange={(event) => updateColumn(index, { dataType: event.target.value })}>{columnTypes.map((type) => <option key={type} value={type}>{type}</option>)}</select></label>
            <label>Order<input type="number" min={0} max={99999} value={column.sortOrder} onChange={(event) => updateColumn(index, { sortOrder: Number(event.target.value) })} /></label>
            <label className="report-admin-check"><input type="checkbox" checked={column.visible} onChange={(event) => updateColumn(index, { visible: event.target.checked })} />Visible</label>
            <label className="report-admin-check"><input type="checkbox" checked={column.sortable} onChange={(event) => updateColumn(index, { sortable: event.target.checked })} />Sortable</label>
            <label className="report-admin-check"><input type="checkbox" checked={column.exportable} onChange={(event) => updateColumn(index, { exportable: event.target.checked })} />Exportable</label>
            <label className="report-admin-wide">Formatting JSON<textarea value={column.formatText} onChange={(event) => updateColumn(index, { formatText: event.target.value })} rows={2} spellCheck={false} /></label>
          </article>)}
          <div className="report-admin-actions"><button type="button" className="button-primary" disabled={saving || creating} onClick={() => void saveReport()}>{saving ? "Saving…" : "Save columns"}</button>{creating && <p className="configuration-field-help">Create the report from Overview before adding columns.</p>}</div>
        </div>}
        {tab === "access" && <div className="report-admin-section">
          {!selectedReport && <p className="configuration-field-help">Save the report first. New reports receive no role grants automatically.</p>}
          {roles.isLoading && <p role="status">Loading active tenant roles…</p>}
          {roles.isError && <div className="configuration-field-error" role="alert">{roles.error instanceof Error ? roles.error.message : "Could not load tenant roles."}<button type="button" className="button-secondary" onClick={() => void roles.refetch()}>Retry</button></div>}
          {!roles.isLoading && !roles.isError && (roles.data || []).filter((role) => role.isactive === 1).length === 0 && <p className="configuration-field-help">No active tenant roles are available.</p>}
          {!roles.isError && (roles.data || []).filter((role) => role.isactive === 1).map((role) => {
            const grant = grants.find((item) => item.roleKey === role.roleKey) || emptyRoleGrant(role);
            return <fieldset className="report-admin-grant" key={role.roleKey}><legend>{role.name} <span>{role.roleKey}</span></legend>
              <label><input type="checkbox" checked={grant.canView} onChange={(event) => updateGrant(role.roleKey, { canView: event.target.checked })} />View</label>
              <label><input type="checkbox" checked={grant.canExecute} disabled={!grant.canView} onChange={(event) => updateGrant(role.roleKey, { canExecute: event.target.checked })} />Execute</label>
              <label><input type="checkbox" checked={grant.canExport} disabled={!grant.canExecute} onChange={(event) => updateGrant(role.roleKey, { canExport: event.target.checked })} />Export</label>
            </fieldset>;
          })}
          {selectedReport && <div className="report-admin-actions"><button type="button" className="button-primary" disabled={saving || roles.isLoading || roles.isError} onClick={() => void saveRoleAccess()}>{saving ? "Saving…" : "Save role access"}</button><p className="configuration-field-help">Execution requires View. Export requires View and Execute. Only this tenant’s active roles can be assigned.</p></div>}
        </div>}
        {error && <p className="configuration-field-error report-admin-error" role="alert">{error}</p>}
      </>}
    </section>}
  </main>;
}
