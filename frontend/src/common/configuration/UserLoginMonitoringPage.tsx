"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import DataTable from "@/common/tables/DataTable";
import DialogPortal from "@/common/dialogs/DialogPortal";
import { alert, responseErrorMessage } from "@/common/alerts";
import { formatDateTime } from "@/common/dateTime";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type Summary = { totalActiveUsers: number; loggedInLast30Days: number; inactive30DaysOrMore: number; neverLoggedIn: number };
type Activity = { id: string; externalUserId: string; displayName: string; role: string; membershipStatus: number; lastLoginAt: string | null; lastLogoutAt: string | null; lastActivityAt: string | null; daysSinceLogin: number | null; inactivityStatus: "NEVER_LOGGED_IN" | "ACTIVE_RECENTLY" | "INACTIVE_30_DAYS" };
type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number };
type Event = { id: string; eventType: "LOGIN" | "LOGOUT"; occurredAt: string; requestId: string | null; authenticationSource: string | null };

function timestamp(value: string | null) {
  if (!value) return "Never recorded";
  return formatDateTime(value);
}

function inactivityLabel(value: Activity["inactivityStatus"]) {
  return value === "NEVER_LOGGED_IN" ? "NA" : value === "ACTIVE_RECENTLY" ? "Active" : "Inactive 30+ days";
}

export default function UserLoginMonitoringPage({ request }: { request: Request }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const [activity, setActivity] = useState("ALL");
  const [sorting, setSorting] = useState<SortingState>([{ id: "lastLoginAt", desc: true }]);
  const [selected, setSelected] = useState<Activity | null>(null);
  const [eventPage, setEventPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const [exportingHistory, setExportingHistory] = useState(false);

  const summaryQuery = useQuery<Summary>({ queryKey: ["login-monitoring-summary"], queryFn: async ({ signal }) => { const response = await request("/administration/login-monitoring/summary", { signal }); if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load login monitoring summary.")); return response.json(); } });
  const usersQuery = useQuery<Page<Activity>>({
    queryKey: ["login-monitoring-users", page, size, search, status, activity, sorting],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), size: String(size), status, activity });
      if (search.trim()) params.set("search", search.trim());
      if (sorting[0]) { params.set("sortBy", sorting[0].id === "membershipStatus" ? "status" : sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/administration/login-monitoring/users?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load organization login activity."));
      return response.json();
    },
  });
  const eventsQuery = useQuery<Page<Event>>({
    queryKey: ["login-monitoring-events", selected?.externalUserId, eventPage], enabled: Boolean(selected),
    queryFn: async ({ signal }) => { const response = await request(`/administration/login-monitoring/users/${encodeURIComponent(selected!.externalUserId)}/events?page=${eventPage}&size=10`, { signal }); if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load this user&#39;s event history.")); return response.json(); },
  });
  const filters = <div className="login-monitoring-filters flex flex-wrap items-center gap-[10px] max-[540px]:w-full">
    <select aria-label="Membership filter" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="ACTIVE">Active memberships</option><option value="INACTIVE">Inactive memberships</option><option value="ALL">All memberships</option></select>
    <select aria-label="Login activity filter" value={activity} onChange={(event) => { setActivity(event.target.value); setPage(0); }}><option value="ALL">All activity</option><option value="RECENT">Logged in within 30 days</option><option value="INACTIVE_30">Inactive 30+ days</option><option value="NEVER">Never logged in</option></select>
  </div>;
  const columns = useMemo<ColumnDef<Activity, unknown>[]>(() => [
    { accessorKey: "displayName", header: "User name", enableSorting: true, cell: ({ row }) => <div className="login-user-cell"><strong>{row.original.displayName}</strong><small>{row.original.externalUserId}</small></div> },
    { accessorKey: "role", header: "Application role", enableSorting: true },
    { accessorKey: "membershipStatus", header: "Membership", enableSorting: true, cell: ({ row }) => <span className={row.original.membershipStatus === 1 ? "config-active" : "config-inactive"}>{row.original.membershipStatus === 1 ? "Active" : "Inactive"}</span> },
    { accessorKey: "lastLoginAt", header: "Last successful login", enableSorting: true, cell: ({ row }) => timestamp(row.original.lastLoginAt) },
    { accessorKey: "lastLogoutAt", header: "Last recorded logout", enableSorting: false, cell: ({ row }) => timestamp(row.original.lastLogoutAt) },
    { accessorKey: "daysSinceLogin", header: "Days since login", enableSorting: true, cell: ({ row }) => row.original.daysSinceLogin == null ? "—" : row.original.daysSinceLogin },
    { accessorKey: "inactivityStatus", header: "Classification", enableSorting: false, cell: ({ row }) => <span className={row.original.inactivityStatus === "INACTIVE_30_DAYS" ? "config-inactive" : row.original.inactivityStatus === "NEVER_LOGGED_IN" ? "login-never-badge" : "config-active"}>{inactivityLabel(row.original.inactivityStatus)}</span> },
    { id: "history", header: "Activity", enableSorting: false, enableHiding: false, meta: { exportable: false }, cell: ({ row }) => <button type="button" className="button-secondary" onClick={() => { setSelected(row.original); setEventPage(0); }}>View history</button> },
  ], []);

  async function downloadWorkbook(path: string, filename: string, fallback: string) {
    const response = await request(path);
    if (!response.ok) throw new Error(await responseErrorMessage(response, fallback));
    const objectUrl = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = filename;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000);
  }

  async function exportUsers(visibleColumns: string[]) {
    if (exporting) return;
    setExporting(true);
    try {
      const sortBy = sorting[0]?.id === "membershipStatus" ? "status" : sorting[0]?.id || "lastLoginAt";
      const params = new URLSearchParams({ columns: visibleColumns.join(","), status, activity, sortBy, sortDirection: sorting[0]?.desc ? "DESC" : "ASC" });
      if (search.trim()) params.set("search", search.trim());
      await downloadWorkbook(`/administration/login-monitoring/users/export?${params}`, "ecabin-user-login-monitoring.xlsx", "Could not export login activity.");
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not export login activity."); }
    finally { setExporting(false); }
  }

  async function exportHistory() {
    if (!selected || exportingHistory) return;
    setExportingHistory(true);
    try {
      await downloadWorkbook(`/administration/login-monitoring/users/${encodeURIComponent(selected.externalUserId)}/events/export`, "ecabin-login-event-history.xlsx", "Could not export event history.");
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not export event history."); }
    finally { setExportingHistory(false); }
  }

  const retry = () => { void summaryQuery.refetch(); void usersQuery.refetch(); };
  const userData = usersQuery.data;
  return <main className="content configuration-content login-monitoring-page">
    <div className="heading"><div><h1>User Login Monitoring</h1></div></div>
    {(summaryQuery.isError || usersQuery.isError) && <div className="configuration-field-error login-monitoring-alert flex items-center gap-3" role="alert">{[summaryQuery.error, usersQuery.error].filter(Boolean).map((error) => (error as Error).message).join(" ")}<button type="button" className="button-secondary" onClick={retry}>Retry</button></div>}
    <section className="login-monitoring-summary my-[22px] grid grid-cols-[repeat(4,minmax(0,1fr))] gap-4 max-[900px]:grid-cols-[repeat(2,minmax(0,1fr))] max-[540px]:grid-cols-1" aria-label="Login activity summary">
      <article className="grid min-w-0 gap-2 p-5"><span>Active organization users</span><strong>{summaryQuery.data?.totalActiveUsers ?? (summaryQuery.isLoading ? "—" : "0")}</strong><small>Current active memberships</small></article>
      <article className="grid min-w-0 gap-2 p-5"><span>Logged in within 30 days</span><strong>{summaryQuery.data?.loggedInLast30Days ?? (summaryQuery.isLoading ? "—" : "0")}</strong><small>Based on last successful login</small></article>
      <article className="grid min-w-0 gap-2 p-5"><span>Inactive for 30+ days</span><strong>{summaryQuery.data?.inactive30DaysOrMore ?? (summaryQuery.isLoading ? "—" : "0")}</strong><small>Administrator review required</small></article>
      <article className="grid min-w-0 gap-2 p-5"><span>Never logged in</span><strong>{summaryQuery.data?.neverLoggedIn ?? (summaryQuery.isLoading ? "—" : "0")}</strong><small>Shown separately from inactivity</small></article>
    </section>
    <section className="configuration-table-card login-monitoring-table">
      <div className="configuration-table-head flex min-h-[82px] min-w-0 items-center justify-between gap-3 px-6 py-[18px] max-[700px]:min-h-[70px] max-[700px]:items-start max-[700px]:flex-col max-[700px]:p-4"><div><div className="eyebrow">ORGANIZATION MEMBERSHIPS</div><h2>Recorded login activity</h2></div></div>
      <DataTable<Activity> columns={columns} data={userData?.items || []} total={userData?.totalElements || 0} page={page} pageSize={size} sorting={sorting} search={search} loading={usersQuery.isLoading || usersQuery.isFetching} error={usersQuery.isError} errorMessage={usersQuery.error instanceof Error ? usersQuery.error.message : "Could not load login activity."} filters={filters} onPageChange={setPage} onPageSizeChange={(next) => { setSize(next); setPage(0); }} onSortingChange={(next) => { setSorting(next); setPage(0); }} onSearchChange={(next) => { setSearch(next); setPage(0); }} onRetry={() => void usersQuery.refetch()} onExport={(visibleColumns) => { void exportUsers(visibleColumns); }} exporting={exporting} selectable={false}/>
      <div className="config-table-foot"><span>{userData?.totalElements ?? 0} organization memberships</span><span>Last known activity is not a real-time online status.</span></div>
    </section>
    <aside className="login-monitoring-note mt-4 p-[16px_18px]"><strong>Authentication history availability</strong><p>Local demo sign-ins and explicit application sign-outs are recorded. Enterprise Microsoft sign-in events will appear after the gateway provides a confirmed event callback. Ordinary API requests are not treated as logins.</p><p>To deactivate an inactive membership, use Administration → Users and roles. The existing confirmation requires an audit reason and protects the last active administrator. Deactivation does not remove the user&apos;s enterprise identity.</p></aside>
    {selected && <DialogPortal onClose={() => setSelected(null)} labelledBy="login-history-title" className="modal login-history-modal"><div className="modal-heading flex items-start justify-between mb-[18px]"><div><div className="eyebrow">MEMBERSHIP EVENT HISTORY</div><h2 id="login-history-title">{selected.displayName}</h2><p>{selected.externalUserId} · {selected.role}</p></div><button type="button" className="close" onClick={() => setSelected(null)} aria-label="Close event history">×</button></div>
      <div className="login-history-toolbar"><span>{eventsQuery.data ? `${eventsQuery.data.totalElements.toLocaleString()} events` : "Recent authentication events"}</span><button type="button" className="button-secondary" onClick={() => void exportHistory()} disabled={eventsQuery.isLoading || !eventsQuery.data?.totalElements || exportingHistory}>{exportingHistory ? "Preparing Excel…" : "Export Excel"}</button></div>
      {eventsQuery.isLoading ? <div className="config-loading" role="status">Loading event history…</div> : eventsQuery.isError ? <div className="configuration-field-error" role="alert">{eventsQuery.error instanceof Error ? eventsQuery.error.message : "Could not load event history."}<button type="button" className="button-secondary" onClick={() => void eventsQuery.refetch()}>Retry</button></div> : !eventsQuery.data?.items.length ? <div className="rate-empty">No login or explicit logout events have been recorded for this membership.</div> : <div className="table-scroll login-history-events"><table className="admin-data-table"><thead><tr><th>EVENT</th><th>TIME</th><th>SOURCE</th><th>REQUEST</th></tr></thead><tbody>{eventsQuery.data.items.map((event) => <tr key={event.id}><td>{event.eventType === "LOGIN" ? "Successful login" : "Explicit application logout"}</td><td><time dateTime={event.occurredAt}>{timestamp(event.occurredAt)}</time></td><td>{event.authenticationSource || "Not available"}</td><td>{event.requestId || "Not available"}</td></tr>)}</tbody></table></div>}
      <div className="login-history-footer"><div className="admin-pagination">{eventsQuery.data && eventsQuery.data.totalPages > 1 && <><button type="button" className="button-secondary" disabled={eventPage === 0 || eventsQuery.isFetching} onClick={() => setEventPage((current) => current - 1)}>Previous</button><span>Page {eventPage + 1} of {eventsQuery.data.totalPages}</span><button type="button" className="button-secondary" disabled={eventPage + 1 >= eventsQuery.data.totalPages || eventsQuery.isFetching} onClick={() => setEventPage((current) => current + 1)}>Next</button></>}</div><button type="button" className="button-secondary" onClick={() => setSelected(null)}>Close</button></div>
    </DialogPortal>}
  </main>;
}
