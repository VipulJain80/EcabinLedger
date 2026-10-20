"use client";

import { FormEvent, useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import DataTable from "@/common/tables/DataTable";
import { responseErrorMessage } from "@/common/alerts";
import { formatReportValue, queryKeyFor, validateReportFilters } from "./reportViewerModel.mjs";
import type { ReportFilterModel } from "./reportViewerModel.mjs";
import { hasPermission } from "@/common/rbac/permissions";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type ReportSummary = { id: string; reportCode: string; definitionVersion: number; name: string; description: string | null; sortOrder: number; canExecute: boolean; canExport: boolean };
type ReportColumn = { columnKey: string; label: string; dataType: string; sortOrder: number; visible: boolean; sortable: boolean; exportable: boolean; formatConfiguration: Record<string, unknown> };
type ReportDefinition = { id: string; reportCode: string; definitionVersion: number; name: string; description: string | null; canExecute: boolean; canExport: boolean; filters: ReportFilterModel[]; columns: ReportColumn[] };
type QueryColumn = { key: string; label: string; type: string; format: Record<string, unknown>; sortable: boolean; exportable: boolean };
type QueryResult = { report: { id: string; code: string; name: string }; columns: QueryColumn[]; rows: Array<Record<string, unknown> & { id: string }>; page: number; size: number; totalElements: number; totalPages: number };
type LookupValue = { value: string; label: string };
type SortEntry = { key: string; direction: "ASC" | "DESC" };
type ActiveQuery = { reportId: string; filters: Record<string, unknown>; page: number; size: number; sort: SortEntry[] };

export default function DynamicReportViewer({ request, permissions = [] }: { request: Request; permissions?: readonly string[] }) {
  const [reportId, setReportId] = useState("");
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});
  const [activeQuery, setActiveQuery] = useState<ActiveQuery | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState("");

  const reports = useQuery({
    queryKey: ["dynamic-report-catalog"],
    queryFn: async ({ signal }) => {
      const response = await request("/reports", { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load available reports."));
      return response.json() as Promise<ReportSummary[]>;
    },
  });
  const definition = useQuery({
    queryKey: ["dynamic-report-definition", reportId],
    enabled: Boolean(reportId),
    queryFn: async ({ signal }) => {
      const response = await request(`/reports/${encodeURIComponent(reportId)}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load this report definition."));
      return response.json() as Promise<ReportDefinition>;
    },
  });

  const lookupKeys = [...new Set((definition.data?.filters || [])
    .filter((filter) => filter.filterType === "LOOKUP_SINGLE" || filter.filterType === "LOOKUP_MULTI")
    .map((filter) => filter.lookupKey).filter((key): key is string => Boolean(key)))];
  const lookupQueries = useQueries({ queries: lookupKeys.map((key) => ({
    queryKey: ["dynamic-report-lookup", key],
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      const response = await request(`/reports/lookups/${encodeURIComponent(key)}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load filter options."));
      return response.json() as Promise<LookupValue[]>;
    },
    staleTime: 60_000,
  })) });
  const lookupOptions = Object.fromEntries(lookupKeys.map((key, index) => [key, lookupQueries[index]?.data || []]));
  const canExecute = hasPermission(permissions, "REPORT_VIEW") && hasPermission(permissions, "REPORT_EXECUTE") && Boolean(definition.data?.canExecute);
  const canExport = canExecute && hasPermission(permissions, "REPORT_EXPORT") && Boolean(definition.data?.canExport);

  const result = useQuery({
    queryKey: activeQuery ? queryKeyFor(activeQuery.reportId, activeQuery.filters, activeQuery.page, activeQuery.size, activeQuery.sort) : ["dynamic-report-query", "idle"],
    enabled: Boolean(activeQuery),
    queryFn: async ({ signal }) => {
      const snapshot = activeQuery;
      if (!snapshot) throw new Error("Submit the report filters to run this report.");
      const response = await request(`/reports/${encodeURIComponent(snapshot.reportId)}/query`, {
        method: "POST", signal, headers: { "content-type": "application/json" },
        body: JSON.stringify({ filters: snapshot.filters, page: snapshot.page, size: snapshot.size, sort: snapshot.sort }),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not run this report."));
      return response.json() as Promise<QueryResult>;
    },
  });

  const orderedFilters = [...(definition.data?.filters || [])].sort((a, b) => a.sortOrder - b.sortOrder || a.filterKey.localeCompare(b.filterKey));
  const visibleMetadataColumns = [...(definition.data?.columns || [])].filter((column) => column.visible)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.columnKey.localeCompare(b.columnKey));
  const resultColumns = result.data?.columns || visibleMetadataColumns.map((column) => ({
    key: column.columnKey, label: column.label, type: column.dataType, format: column.formatConfiguration, sortable: column.sortable, exportable: column.exportable,
  }));
  const tableColumns: ColumnDef<Record<string, unknown> & { id: string }, unknown>[] = resultColumns.map((column) => ({
    id: column.key, accessorFn: (row) => row[column.key], header: column.label, enableSorting: column.sortable,
    meta: { exportable: column.exportable },
    cell: ({ getValue }) => <span className="break-words">{formatReportValue(getValue(), column.type, column.format)}</span>,
  }));
  const tableSorting: SortingState = (activeQuery?.sort || []).map((entry) => ({ id: entry.key, desc: entry.direction === "DESC" }));

  function changeReport(nextId: string) {
    setReportId(nextId);
    setValues({});
    setValidationErrors({});
    setActiveQuery(null);
    setExportError("");
  }
  function setFilterValue(key: string, value: unknown) {
    setValues((current) => ({ ...current, [key]: value }));
    setValidationErrors((current) => { const next = { ...current }; delete next[key]; return next; });
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!definition.data || !canExecute) return;
    const errors = validateReportFilters(orderedFilters, values, lookupOptions);
    setValidationErrors(errors);
    if (Object.keys(errors).length) return;
    setActiveQuery({ reportId, filters: { ...values }, page: 0, size: 25, sort: [] });
  }
  function reset() {
    setValues({});
    setValidationErrors({});
    setActiveQuery(null);
  }
  function setPage(page: number) { setActiveQuery((current) => current ? { ...current, page } : current); }
  function setPageSize(size: number) { setActiveQuery((current) => current ? { ...current, size, page: 0 } : current); }
  function setSorting(sorting: SortingState) {
    const next = sorting.slice(0, 3).filter((item) => resultColumns.some((column) => column.key === item.id && column.sortable))
      .map((item) => ({ key: item.id, direction: item.desc ? "DESC" as const : "ASC" as const }));
    setActiveQuery((current) => current ? { ...current, page: 0, sort: next } : current);
  }

  async function exportReport() {
    if (!activeQuery || !canExport || exporting) return;
    setExporting(true);
    setExportError("");
    try {
      const response = await request(`/reports/${encodeURIComponent(activeQuery.reportId)}/export`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ filters: activeQuery.filters, sort: activeQuery.sort }),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not export this report."));
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `${(definition.data?.name || "report").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "report"}.xlsx`;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
    } catch (error) {
      setExportError(error instanceof Error ? error.message : "Could not export this report.");
    } finally {
      setExporting(false);
    }
  }

  function renderFilter(filter: ReportFilterModel) {
    const value = values[filter.filterKey];
    const common = { id: `report-filter-${filter.filterKey}`, className: "dynamic-report-control", "aria-invalid": Boolean(validationErrors[filter.filterKey]), "aria-describedby": validationErrors[filter.filterKey] ? `report-error-${filter.filterKey}` : undefined };
    const placeholder = typeof filter.configuration?.placeholder === "string" ? filter.configuration.placeholder : "Select…";
    const options = filter.filterType === "SINGLE_SELECT" || filter.filterType === "MULTI_SELECT"
      ? (Array.isArray(filter.configuration?.options) ? filter.configuration.options as Array<{ value: string; label: string }> : [])
      : lookupOptions[filter.lookupKey || ""] || [];
    let control;
    if (filter.filterType === "TEXT") control = <input {...common} type="text" maxLength={Number(filter.configuration?.maxLength ?? 4000)} placeholder={placeholder} value={typeof value === "string" ? value : ""} onChange={(event) => setFilterValue(filter.filterKey, event.target.value || undefined)} />;
    else if (filter.filterType === "NUMBER") control = <input {...common} type="number" min={filter.configuration?.min as number | undefined} max={filter.configuration?.max as number | undefined} placeholder={placeholder} value={value === undefined ? "" : String(value)} onChange={(event) => setFilterValue(filter.filterKey, event.target.value === "" ? undefined : Number(event.target.value))} />;
    else if (filter.filterType === "DATE") control = <input {...common} type="date" value={typeof value === "string" ? value : ""} onChange={(event) => setFilterValue(filter.filterKey, event.target.value || undefined)} />;
    else if (filter.filterType === "BOOLEAN") control = <select {...common} value={typeof value === "boolean" ? String(value) : ""} onChange={(event) => setFilterValue(filter.filterKey, event.target.value === "" ? undefined : event.target.value === "true")}><option value="">Choose…</option><option value="true">Yes</option><option value="false">No</option></select>;
    else if (["SINGLE_SELECT", "LOOKUP_SINGLE"].includes(filter.filterType)) control = <select {...common} value={typeof value === "string" ? value : ""} disabled={filter.filterType === "LOOKUP_SINGLE" && lookupQueries[lookupKeys.indexOf(filter.lookupKey || "")]?.isPending} onChange={(event) => setFilterValue(filter.filterKey, event.target.value || undefined)}><option value="">{filter.filterType === "LOOKUP_SINGLE" && lookupQueries[lookupKeys.indexOf(filter.lookupKey || "")]?.isPending ? "Loading options…" : placeholder}</option>{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select>;
    else if (["MULTI_SELECT", "LOOKUP_MULTI"].includes(filter.filterType)) control = <select {...common} multiple value={Array.isArray(value) ? value as string[] : []} aria-label={`${filter.label}${filter.required ? ", required" : ""}`} onChange={(event) => setFilterValue(filter.filterKey, Array.from(event.target.selectedOptions, (option) => option.value))}>{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select>;
    else control = <p className="text-sm text-red-700">Unsupported filter type: {filter.filterType}</p>;
    const lookupError = filter.lookupKey ? lookupQueries[lookupKeys.indexOf(filter.lookupKey)]?.error : null;
    return <div key={filter.filterKey} className="dynamic-report-filter">
      <label htmlFor={`report-filter-${filter.filterKey}`}>{filter.label}{filter.required && <span aria-label="required">*</span>}</label>
      {control}
      {filter.filterType.endsWith("MULTI") && <span className="dynamic-report-help">Use Ctrl or Command to select multiple options.</span>}
      {lookupError && <div className="dynamic-report-lookup-error" role="alert"><span>{lookupError instanceof Error ? lookupError.message : "Could not load options."}</span><button type="button" className="button-secondary" onClick={() => void lookupQueries[lookupKeys.indexOf(filter.lookupKey || "")]?.refetch()}>Retry options</button></div>}
      {validationErrors[filter.filterKey] && <span id={`report-error-${filter.filterKey}`} className="dynamic-report-validation" role="alert">{validationErrors[filter.filterKey]}</span>}
    </div>;
  }

  return <section className="dynamic-report-viewer" aria-labelledby="dynamic-report-heading">
    <div className="dynamic-report-card">
      <div className="dynamic-report-header">
        <div className="dynamic-report-title"><div className="eyebrow">REPORTING</div><h2 id="dynamic-report-heading">Dynamic reports</h2></div>
        <label className="dynamic-report-selector">Report
          <select aria-label="Report" value={reportId} onChange={(event) => changeReport(event.target.value)} disabled={reports.isPending || reports.isError || !reports.data?.length}>
            <option value="">{reports.isPending ? "Loading reports…" : "Select a report"}</option>
            {(reports.data || []).map((report) => <option key={report.id} value={report.id}>{report.name}</option>)}
          </select>
        </label>
      </div>
      {reports.isError && <div className="dynamic-report-alert" role="alert"><span>{reports.error instanceof Error ? reports.error.message : "Could not load available reports."}</span><button type="button" className="button-secondary" onClick={() => void reports.refetch()}>Retry</button></div>}
      {!reports.isPending && !reports.isError && reports.data?.length === 0 && <p className="dynamic-report-empty">No reports are available to your account.</p>}
      {reportId && definition.isPending && <p className="dynamic-report-status" role="status">Loading report definition…</p>}
      {reportId && definition.isError && <div className="dynamic-report-alert" role="alert"><span>{definition.error instanceof Error ? definition.error.message : "Could not load this report."}</span><button type="button" className="button-secondary" onClick={() => void definition.refetch()}>Retry</button></div>}
      {definition.data && <>
        <form className="dynamic-report-form" onSubmit={submit} noValidate>
          <div className="dynamic-report-definition"><div><h3>{definition.data.name}</h3>{!canExecute && <span className="dynamic-report-access-note" role="status">Your role can view this report but does not have execution access.</span>}</div><div className="dynamic-report-action-buttons"><button type="submit" className="button-primary" disabled={!canExecute || definition.isFetching || lookupQueries.some((query) => query.isPending)}>Go</button><button type="button" className="button-secondary" onClick={reset}>Reset filters</button></div></div>
          <div className="dynamic-report-filter-grid">{orderedFilters.length ? orderedFilters.map(renderFilter) : <p className="dynamic-report-empty">This report has no filters.</p>}</div>
        </form>
      </>}
    </div>
    {definition.data && <section className="report-results dynamic-report-results" aria-label="Report results" onCopy={(event) => event.preventDefault()} onCut={(event) => event.preventDefault()}>
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 px-5 py-4 sm:px-6"><div><h3 className="text-lg font-semibold text-black">Results</h3><p className="text-sm text-slate-700">{activeQuery && result.data ? `${result.data.totalElements.toLocaleString()} records` : "Run the report to load results."}</p></div><div className="flex items-center gap-3">{result.data && <span className="text-sm text-slate-700">Page {result.data.totalPages === 0 ? 0 : result.data.page + 1} of {result.data.totalPages}</span>}{activeQuery && canExport && <button type="button" className="button-secondary" onClick={() => void exportReport()} disabled={exporting || result.isFetching}>{exporting ? "Preparing Excel…" : "Export Excel"}</button>}</div></div>
      {exportError && <p className="px-5 pt-3 text-sm text-red-700" role="alert">{exportError}</p>}
      {definition.data.columns.filter((column) => column.visible).length === 0 ? <p className="p-5 text-sm text-slate-700">No visible result columns are configured.</p> : !activeQuery ? <p className="p-5 text-sm text-slate-700">Set the filters above and select <strong>Run report</strong> to load results.</p> : <DataTable<Record<string, unknown> & { id: string }>
        className="defect-register-data" columns={tableColumns} data={result.data?.rows || []} total={result.data?.totalElements || 0} page={result.data?.page || activeQuery?.page || 0} pageSize={result.data?.size || activeQuery?.size || 25} sorting={tableSorting} search="" loading={result.isFetching} error={Boolean(activeQuery && result.isError)} errorMessage={result.error instanceof Error ? result.error.message : undefined}
        onPageChange={setPage} onPageSizeChange={setPageSize} onSortingChange={setSorting} onSearchChange={() => undefined} onRetry={() => void result.refetch()} onExport={() => undefined} exporting={false} selectable={false} searchable={false} exportable={false} />}
    </section>}
  </section>;
}
