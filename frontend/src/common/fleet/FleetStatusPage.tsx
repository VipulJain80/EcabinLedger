"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";
import { alert, responseErrorMessage } from "@/common/alerts";

export type FleetAircraft = { id: string; msnNumber: string | null; tailNumber: string; aircraftType: string; openDefects: number };
type FleetRow = FleetAircraft;
type FleetSummary = { totalDefects: number; awaitingReview: number; inMaintenance: number; closed: number };
type FleetFilters = { fleets: Array<{ id: string; name: string }>; aircraftTypes: string[] };
type Request = (path: string, init?: RequestInit) => Promise<Response>;

export default function FleetStatusPage({ request, onSelectAircraft }: { request: Request; onSelectAircraft: (aircraft: FleetRow) => void }) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [search, setSearch] = useState("");
  const [fleetId, setFleetId] = useState("");
  const [aircraftType, setAircraftType] = useState("");
  const [sorting, setSorting] = useState<SortingState>([]);
  const [exporting, setExporting] = useState(false);
  const query = useQuery<ServerTablePage<FleetRow>>({
    queryKey: ["fleet-status", page, pageSize, search, fleetId, aircraftType, sorting],
    staleTime: 10_000,
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize) });
      if (search) params.set("search", search);
      if (fleetId) params.set("fleetId", fleetId);
      if (aircraftType) params.set("aircraftType", aircraftType);
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/fleet-status/table?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load fleet status."));
      return response.json() as Promise<ServerTablePage<FleetRow>>;
    },
  });
  const filters = useQuery<FleetFilters>({
    queryKey: ["fleet-status-filters"],
    queryFn: async ({ signal }) => {
      const response = await request("/reference-data", { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load fleet filters."));
      return response.json();
    },
    staleTime: 60_000,
  });
  const summary = useQuery<FleetSummary>({
    queryKey: ["fleet-defect-summary"],
    staleTime: 10_000,
    queryFn: async ({ signal }) => {
      const response = await request("/fleet-status/summary", { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load the authorized defect summary."));
      return response.json();
    },
  });
  const columns: ColumnDef<FleetRow, unknown>[] = [
    { accessorKey: "msnNumber", header: "MSN Number", cell: ({ row, getValue }) => <button type="button" className="fleet-aircraft-link" onClick={() => onSelectAircraft(row.original)}>{String(getValue() || "—")}</button> },
    { accessorKey: "tailNumber", header: "Aircraft Registration", cell: ({ row, getValue }) => <button type="button" className="fleet-aircraft-link" onClick={() => onSelectAircraft(row.original)}>{String(getValue() || "—")}</button> },
    { accessorKey: "aircraftType", header: "Aircraft Type" },
    { accessorKey: "openDefects", header: "Open Defects" },
  ];

  async function exportAll(visibleColumnIds: string[]) {
    if (!visibleColumnIds.length) throw new Error("Select at least one visible data column to export.");
    setExporting(true);
    try {
      const params = new URLSearchParams();
      if (search) params.set("search", search);
      if (fleetId) params.set("fleetId", fleetId);
      if (aircraftType) params.set("aircraftType", aircraftType);
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      params.set("columns", visibleColumnIds.join(","));
      const response = await request(`/fleet-status/export?${params}`);
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not export fleet status."));
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = "ecabin-fleet-status.xlsx"; link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } finally { setExporting(false); }
  }

  const data = query.data;
  return <div className="content fleet-status-content">
    <div className="heading"><div><div className="eyebrow"><span /> CABIN OPERATIONS <em>·</em> FLEET OVERVIEW</div><h1>Defect Summary</h1><p>Fleet and defect status visible to your account.</p></div></div>
    <section className="metrics aircraft-summary-metrics" aria-label="Authorized fleet defect summary">
      <article className="metric-card"><div className="metric-head">TOTAL DEFECTS <span className="metric-icon blue">▦</span></div><strong>{summary.isPending ? "—" : summary.data?.totalDefects ?? "—"}</strong><div className="metric-foot">Visible in your authorized scope</div></article>
      <article className="metric-card"><div className="metric-head">AWAITING REVIEW <span className="metric-icon red">◷</span></div><strong>{summary.isPending ? "—" : summary.data?.awaitingReview ?? "—"}</strong><div className="metric-foot">Reported and under review</div></article>
      <article className="metric-card"><div className="metric-head">IN MAINTENANCE <span className="metric-icon green">↗</span></div><strong>{summary.isPending ? "—" : summary.data?.inMaintenance ?? "—"}</strong><div className="metric-foot">Assigned or in progress</div></article>
      <article className="metric-card"><div className="metric-head">CLOSED <span className="metric-icon green">✓</span></div><strong>{summary.isPending ? "—" : summary.data?.closed ?? "—"}</strong><div className="metric-foot">With recorded closure status</div></article>
    </section>
    <section className="configuration-table-card fleet-status-card">
      <div className="configuration-table-head"><div><div className="eyebrow">AIRCRAFT OVERVIEW</div><h2>Fleet Status</h2></div></div>
      <DataTable<FleetRow> columns={columns} data={data?.items || []} total={data?.totalElements || 0} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={query.isPending || query.isFetching} error={query.isError} filters={<div className="fleet-status-filters"><label>Fleet<select value={fleetId} onChange={event => { setFleetId(event.target.value); setPage(0); }}><option value="">All fleets</option>{(filters.data?.fleets || []).map(fleet => <option key={fleet.id} value={fleet.id}>{fleet.name}</option>)}</select></label><label>Aircraft type<select value={aircraftType} onChange={event => { setAircraftType(event.target.value); setPage(0); }}><option value="">All types</option>{(filters.data?.aircraftTypes || []).map(type => <option key={type} value={type}>{type}</option>)}</select></label></div>} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(0); }} onSortingChange={(next) => { setSorting(next); setPage(0); }} onSearchChange={(value) => { setSearch(value); setPage(0); }} onRetry={() => void query.refetch()} onExport={(visibleColumns) => { void exportAll(visibleColumns).catch((reason: unknown) => alert.error(reason instanceof Error ? reason.message : "Could not export fleet status.")); }} exporting={exporting} selectable={false} />
    </section>
  </div>;
}
