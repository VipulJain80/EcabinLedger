"use client";

import { useState } from "react";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import { useQuery } from "@tanstack/react-query";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";
import { responseErrorMessage } from "@/common/alerts";

type WorkItem = {
  id: string;
  defectId: string;
  aircraftRegistration: string;
  msnNumber: string | null;
  aircraftType: string;
  zone: string;
  component: string | null;
  condition: string | null;
  description: string;
  assignedTeam: string;
  defectStatus: string;
  correctiveActionStatus: string;
  assignmentDate: string;
  reportedAt: string;
};
type Request = (path: string, init?: RequestInit) => Promise<Response>;

const human = (value: string) => value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (character) => character.toUpperCase());
const dateTime = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));

export default function MaintenanceWorkQueuePage({ request, onOpen }: { request: Request; onOpen: (defectId: string) => void }) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [search, setSearch] = useState("");
  const [actionStatus, setActionStatus] = useState("ALL");
  const [sorting, setSorting] = useState<SortingState>([{ id: "assignmentDate", desc: true }]);
  const query = useQuery<ServerTablePage<WorkItem>>({
    queryKey: ["maintenance-work-queue", page, pageSize, search, actionStatus, sorting],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize), actionStatus });
      if (search.trim()) params.set("search", search.trim());
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/maintenance-work-queue?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load your team work queue."));
      return response.json() as Promise<ServerTablePage<WorkItem>>;
    },
  });
  const columns: ColumnDef<WorkItem, unknown>[] = [
    { accessorKey: "defectId", header: "Defect ID", enableSorting: true, cell: ({ row }) => <button className="work-queue-open" type="button" onClick={() => onOpen(row.original.id)}>{row.original.defectId}</button> },
    { accessorKey: "aircraftRegistration", header: "Aircraft Registration" },
    { accessorKey: "msnNumber", header: "MSN", cell: ({ getValue }) => String(getValue() || "—") },
    { accessorKey: "aircraftType", header: "Aircraft Type" },
    { accessorKey: "zone", header: "Cabin Zone" },
    { accessorKey: "component", header: "Component", cell: ({ getValue }) => String(getValue() || "—") },
    { accessorKey: "condition", header: "Condition", cell: ({ getValue }) => String(getValue() || "—") },
    { accessorKey: "description", header: "Finding", cell: ({ getValue }) => <span className="work-queue-description">{String(getValue())}</span> },
    { accessorKey: "assignedTeam", header: "Assigned Team" },
    { accessorKey: "defectStatus", header: "Defect Status", cell: ({ getValue }) => human(String(getValue())) },
    { accessorKey: "correctiveActionStatus", header: "Action Status", cell: ({ getValue }) => human(String(getValue())) },
    { accessorKey: "assignmentDate", header: "Assigned", cell: ({ getValue }) => dateTime(String(getValue())) },
    { accessorKey: "reportedAt", header: "Reported", cell: ({ getValue }) => dateTime(String(getValue())) },
  ];

  return <div className="content maintenance-work-queue">
    <div className="heading"><div><div className="eyebrow"><span /> CABIN OPERATIONS <em>·</em> TEAM WORK</div><h1>Maintenance work queue</h1><p>Current corrective actions assigned to your active maintenance teams.</p></div></div>
    <section className="configuration-table-card">
      <div className="configuration-table-head"><div><div className="eyebrow">SHARED TEAM ASSIGNMENTS</div><h2>Assigned work</h2></div></div>
      <DataTable<WorkItem> columns={columns} data={query.data?.items || []} total={query.data?.totalElements || 0} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={query.isPending || query.isFetching} error={query.isError} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(0); }} onSortingChange={(next) => { setSorting(next); setPage(0); }} onSearchChange={(value) => { setSearch(value); setPage(0); }} onRetry={() => void query.refetch()} onExport={() => undefined} exporting={false} exportable={false} selectable={false} filters={<label className="admin-inline-filter">Action status<select value={actionStatus} onChange={(event) => { setActionStatus(event.target.value); setPage(0); }}><option value="ALL">All open work</option><option value="ASSIGNED">Assigned</option><option value="IN_PROGRESS">In progress</option></select></label>} />
    </section>
  </div>;
}
