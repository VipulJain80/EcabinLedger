"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";
import { alert, responseErrorMessage } from "@/common/alerts";
import { FleetAircraft } from "@/common/fleet/FleetStatusPage";
import { areaFormSelection, selectableCabinAreas, shouldShowCabinAreaChooser, singleAreaForm } from "@/common/fleet/areaFormSelection";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type DefectRow = { id: string; reference: string; title: string; location: string; zone: string; component: string; severity: string; severityName?: string; status: string; reportedAt: string };
type AreaOption = { id: string; code: string; name: string; isactive?: number };
type PublishedForm = { id: string; name: string; versionId: string; versionNumber: number; configuration: { fields: import("@/common/forms/defectReportMapping").ReportField[] } };
const statusOptions = ["", "REPORTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE", "ACTION_ASSIGNED", "IN_PROGRESS", "AWAITING_VERIFICATION", "VERIFIED", "APPROVAL_REQUIRED", "CLOSED"];
const human = (value: string) => value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());

export default function AircraftDefectListPage({ request, aircraftId, organizationCacheKey, onBack, onLogDefect, onOpenDefect, canReport }: {
  request: Request; aircraftId: string; organizationCacheKey: string; onBack: () => void;
  onLogDefect: (aircraft: FleetAircraft, areaId: string, form: PublishedForm) => void; onOpenDefect: (id: string) => void; canReport: boolean;
}) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [selectedAreaId, setSelectedAreaId] = useState("");
  const handledSingleForm = useRef("");
  const areasQuery = useQuery<AreaOption[]>({
    queryKey: ["reference-cabin-areas", organizationCacheKey],
    queryFn: async ({ signal }) => {
      const response = await request("/reference-data", { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load cabin areas for this organization."));
      const referenceData = await response.json() as { areas?: AreaOption[] };
      return Array.isArray(referenceData.areas) ? referenceData.areas.filter(area => area.isactive === undefined || area.isactive === 1) : [];
    },
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
  const areas = areasQuery.data || [];
  const [sorting, setSorting] = useState<SortingState>([{ id: "reportedAt", desc: true }]);
  const aircraftQuery = useQuery<FleetAircraft>({
    queryKey: ["aircraft-detail", aircraftId],
    queryFn: async ({ signal }) => {
      const response = await request(`/aircraft/${encodeURIComponent(aircraftId)}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "This aircraft is unavailable to your account."));
      return response.json();
    },
  });
  const listQuery = useQuery<ServerTablePage<DefectRow>>({
    queryKey: ["aircraft-defects", aircraftId, page, pageSize, search, status, sorting],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize) });
      if (search) params.set("search", search);
      if (status) params.set("status", status);
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/aircraft/${encodeURIComponent(aircraftId)}/defects?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load this aircraft’s defects."));
      return response.json();
    },
  });
  const formsQuery = useQuery<PublishedForm[]>({
    queryKey: ["area-operational-forms", selectedAreaId],
    enabled: Boolean(selectedAreaId),
    queryFn: async ({ signal }) => {
      const response = await request(`/areas/${encodeURIComponent(selectedAreaId)}/forms`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load published forms for this area."));
      return response.json();
    },
  });
  const columns: ColumnDef<DefectRow, unknown>[] = [
    { accessorKey: "reference", header: "Defect ID", cell: ({ row, getValue }) => <button type="button" className="fleet-aircraft-link" onClick={() => onOpenDefect(row.original.id)}>{String(getValue())}</button> },
    { accessorKey: "location", header: "Area" },
    { accessorKey: "zone", header: "Cabin zone" },
    { accessorKey: "component", header: "Component" },
    { accessorKey: "title", header: "Condition" },
    { accessorKey: "severity", header: "Severity", cell: ({ row }) => row.original.severityName || human(row.original.severity) },
    { accessorKey: "status", header: "Workflow status", cell: ({ getValue }) => human(String(getValue())) },
    { accessorKey: "reportedAt", header: "Reported", cell: ({ getValue }) => new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(new Date(String(getValue()))) },
  ];
  const aircraft = aircraftQuery.data;
  const applicableForms = useMemo(() => formsQuery.data || [], [formsQuery.data]);
  useEffect(() => {
    const soleForm = singleAreaForm(applicableForms);
    if (!selectedAreaId || formsQuery.isPending || formsQuery.isError || !soleForm || !aircraft) return;
    const selectionKey = `${selectedAreaId}:${soleForm.id}:${soleForm.versionId}`;
    if (handledSingleForm.current === selectionKey) return;
    handledSingleForm.current = selectionKey;
    onLogDefect(aircraft, selectedAreaId, soleForm);
  }, [applicableForms, aircraft, selectedAreaId, formsQuery.isError, formsQuery.isPending, onLogDefect]);
  return <div className="content fleet-status-content aircraft-defect-content">
    <div className="heading"><div><div className="eyebrow"><span /> CABIN OPERATIONS <em>·</em> AIRCRAFT DEFECTS</div><h1>Defect List</h1><p>{aircraft ? `${aircraft.tailNumber} · MSN ${aircraft.msnNumber || "—"} · ${aircraft.aircraftType}` : aircraftQuery.isPending ? "Loading aircraft…" : "Aircraft unavailable"}</p></div>
      <div className="aircraft-page-actions"><button className="button-secondary" onClick={onBack}>← Defect Summary</button>{aircraft && canReport && <button className="button-primary" aria-controls="aircraft-defect-area-picker" onClick={() => document.getElementById("aircraft-defect-area-picker")?.scrollIntoView({ behavior: "smooth", block: "center" })}><span>＋</span> Log Defect</button>}</div>
    </div>
    {shouldShowCabinAreaChooser(Boolean(aircraft), canReport) && <section className="aircraft-area-picker" id="aircraft-defect-area-picker" aria-labelledby="aircraft-area-picker-title">
      <div><h2 id="aircraft-area-picker-title">Choose Cabin Area</h2><p>Select a configured area to see its active published reporting forms for this aircraft.</p></div>
      {areasQuery.isPending ? <p className="aircraft-area-message" role="status">Loading active cabin areas…</p>
        : areasQuery.isError ? <p className="aircraft-area-message error" role="alert">{areasQuery.error.message}<button type="button" className="button-secondary" onClick={() => void areasQuery.refetch()}>Retry</button></p>
          : selectableCabinAreas(areas).length ? <div className="aircraft-area-chips" role="group" aria-label="Cabin areas">{selectableCabinAreas(areas).map(area => <button type="button" key={area.id} className={selectedAreaId === area.id ? "aircraft-area-chip selected" : "aircraft-area-chip"} aria-pressed={selectedAreaId === area.id} disabled={formsQuery.isFetching} onClick={() => { handledSingleForm.current = ""; setSelectedAreaId(area.id!); }} title={area.code}>{area.name}</button>)}</div> : <p className="aircraft-area-message" role="status">No active cabin areas are configured. Ask an organization administrator to activate an area.</p>}
      {formsQuery.isPending && selectedAreaId && <p className="aircraft-area-message" role="status">Loading published forms…</p>}
      {formsQuery.isError && <p className="aircraft-area-message error" role="alert">{formsQuery.error.message}<button type="button" className="button-secondary" onClick={() => void formsQuery.refetch()}>Retry</button></p>}
      {formsQuery.isSuccess && selectedAreaId && applicableForms.length === 0 && <p className="aircraft-area-message" role="status">No active, published form is configured for this area. Ask an administrator to associate and publish an operational form before reporting.</p>}
      {formsQuery.isSuccess && areaFormSelection(applicableForms) === "multiple" && <div className="aircraft-form-choices" role="group" aria-label="Published forms for selected cabin area"><strong>Choose a reporting form</strong>{applicableForms.map(form => <button type="button" className="aircraft-form-choice" key={`${form.id}-${form.versionId}`} disabled={!aircraft} onClick={() => aircraft && onLogDefect(aircraft, selectedAreaId, form)}><span>{form.name}</span><small>Published v{form.versionNumber}</small></button>)}</div>}
    </section>}
    <section className="configuration-table-card fleet-status-card"><div className="configuration-table-head"><div><div className="eyebrow">AIRCRAFT REGISTER</div><h2>Defects visible to you</h2></div></div>
      {!aircraftQuery.isPending && aircraftQuery.isError ? <p className="aircraft-access-error" role="alert">{aircraftQuery.error.message}</p> : <DataTable<DefectRow> columns={columns} data={listQuery.data?.items || []} total={listQuery.data?.totalElements || 0} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={aircraftQuery.isPending || listQuery.isPending || listQuery.isFetching} error={listQuery.isError} filters={<label className="aircraft-status-filter">Status<select value={status} onChange={event => { setStatus(event.target.value); setPage(0); }}><option value="">All statuses</option>{statusOptions.filter(Boolean).map(value => <option key={value} value={value}>{human(value)}</option>)}</select></label>} onPageChange={setPage} onPageSizeChange={value => { setPageSize(value); setPage(0); }} onSortingChange={next => { setSorting(next); setPage(0); }} onSearchChange={value => { setSearch(value); setPage(0); }} onRetry={() => void listQuery.refetch().catch(error => alert.error(error instanceof Error ? error.message : "Could not refresh aircraft defects."))} onExport={() => undefined} exporting={false} selectable={false} exportable={false} />}
    </section>
  </div>;
}
