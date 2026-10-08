"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";
import { alert, responseErrorMessage } from "@/common/alerts";

type FleetRow = { id: string; msnNumber: string | null; tailNumber: string; aircraftType: string; openDefects: number };
type Request = (path: string, init?: RequestInit) => Promise<Response>;

export default function FleetStatusPage({ request }: { request: Request }) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [search, setSearch] = useState("");
  const [sorting, setSorting] = useState<SortingState>([]);
  const [exporting, setExporting] = useState(false);
  const query = useQuery<ServerTablePage<FleetRow>>({
    queryKey: ["fleet-status", page, pageSize, search, sorting],
    staleTime: 10_000,
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize) });
      if (search) params.set("search", search);
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/fleet-status/table?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load fleet status."));
      return response.json() as Promise<ServerTablePage<FleetRow>>;
    },
  });
  const columns: ColumnDef<FleetRow, unknown>[] = [
    { accessorKey: "msnNumber", header: "MSN Number", cell: ({ getValue }) => String(getValue() || "—") },
    { accessorKey: "tailNumber", header: "Aircraft Registration" },
    { accessorKey: "aircraftType", header: "Aircraft Type" },
    { accessorKey: "openDefects", header: "Open Defects" },
  ];

  async function exportAll() {
    setExporting(true);
    try {
      const params = new URLSearchParams();
      if (search) params.set("search", search);
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/fleet-status/export?${params}`);
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not export fleet status."));
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = "ecabin-fleet-status.xlsx"; link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } finally { setExporting(false); }
  }

  const data = query.data;
  return <div className="content fleet-status-content">
    <div className="heading"><div><div className="eyebrow"><span /> CABIN OPERATIONS <em>·</em> FLEET OVERVIEW</div><h1>Defect Summary</h1><p>Open cabin defects across your active aircraft fleet.</p></div></div>
    <section className="configuration-table-card fleet-status-card">
      <div className="configuration-table-head"><div><div className="eyebrow">AIRCRAFT OVERVIEW</div><h2>Fleet Status</h2></div></div>
      <DataTable<FleetRow> columns={columns} data={data?.items || []} total={data?.totalElements || 0} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={query.isPending || query.isFetching} error={query.isError} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(0); }} onSortingChange={(next) => { setSorting(next); setPage(0); }} onSearchChange={(value) => { setSearch(value); setPage(0); }} onRetry={() => void query.refetch()} onExport={() => { void exportAll().catch((reason: unknown) => alert.error(reason instanceof Error ? reason.message : "Could not export fleet status.")); }} exporting={exporting} selectable={false} />
    </section>
  </div>;
}
