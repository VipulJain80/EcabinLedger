"use client";

import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";
import DialogPortal from "@/common/dialogs/DialogPortal";
import { alert, responseErrorMessage } from "@/common/alerts";
import { badgeStyle } from "@/common/workflow/workflowStatuses";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type WorkflowStatus = { id: string; moduleCode: string; statusCode: string; displayName: string; aliasName: string | null; description: string | null; isActive: boolean; sortOrder: number; isInitial: boolean; isTerminal: boolean; backgroundColor: string; textColor: string; borderColor: string; icon: string | null; displayStyle: "SOLID" | "OUTLINE" | "SOFT" };

export default function WorkflowStatusMasterPage({ request }: { request: Request }) {
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [search, setSearch] = useState("");
  const [sorting, setSorting] = useState<SortingState>([{ id: "sortOrder", desc: false }]);
  const [editing, setEditing] = useState<WorkflowStatus | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({ displayName: "", aliasName: "", description: "", sortOrder: "", isActive: true, backgroundColor: "#64748B", textColor: "#FFFFFF", borderColor: "#475569", icon: "", displayStyle: "SOLID" as "SOLID" | "OUTLINE" | "SOFT", reason: "" });

  const query = useQuery<ServerTablePage<WorkflowStatus>>({
    queryKey: ["workflow-status-master", page, pageSize, search, sorting],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ moduleCode: "DEFECT", activeOnly: "false", page: String(page), size: String(pageSize) });
      if (search) params.set("search", search);
      if (sorting[0]) { params.set("sortBy", sorting[0].id); params.set("sortDirection", sorting[0].desc ? "DESC" : "ASC"); }
      const response = await request(`/workflow-statuses?${params}`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load workflow statuses."));
      return response.json() as Promise<ServerTablePage<WorkflowStatus>>;
    },
  });

  function openEdit(row: WorkflowStatus) {
    setEditing(row);
    setForm({ displayName: row.displayName, aliasName: row.aliasName || "", description: row.description || "", sortOrder: String(row.sortOrder), isActive: row.isActive, backgroundColor: row.backgroundColor, textColor: row.textColor, borderColor: row.borderColor, icon: row.icon || "", displayStyle: row.displayStyle || "SOLID", reason: "" });
  }
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editing || saving) return;
    setSaving(true);
    try {
      const response = await request(`/workflow-statuses/${encodeURIComponent(editing.id)}`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ displayName: form.displayName.trim(), aliasName: form.aliasName.trim(), description: form.description.trim(), sortOrder: Number(form.sortOrder), isActive: form.isActive, backgroundColor: form.backgroundColor, textColor: form.textColor, borderColor: form.borderColor, icon: form.icon, displayStyle: form.displayStyle, reason: form.reason.trim() }),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not save workflow status."));
      setEditing(null);
      await client.invalidateQueries({ queryKey: ["workflow-status-master"] });
      await client.invalidateQueries({ queryKey: ["workflow-status-definitions", "DEFECT"] });
      await alert.success("Workflow status updated.");
    } catch (error) {
      await alert.error(error instanceof Error ? error.message : "Could not save workflow status.");
    } finally { setSaving(false); }
  }

  const columns = useMemo<ColumnDef<WorkflowStatus, unknown>[]>(() => [
    { accessorKey: "statusCode", header: "Status code" },
    { accessorKey: "displayName", header: "Display name" },
    { id: "badgePreview", header: "Badge preview", enableSorting: false, cell: ({ row }) => <span className="master-badge" style={badgeStyle(row.original)}>{row.original.icon && <span aria-hidden="true">{row.original.icon}</span>}{row.original.displayName}</span> },
    { accessorKey: "aliasName", header: "Alias", cell: ({ getValue }) => String(getValue() || "—") },
    { accessorKey: "description", header: "Description", cell: ({ getValue }) => String(getValue() || "—") },
    { accessorKey: "sortOrder", header: "Order" },
    { accessorKey: "isInitial", header: "Initial", cell: ({ getValue }) => getValue() ? "Yes" : "—" },
    { accessorKey: "isTerminal", header: "Terminal", cell: ({ getValue }) => getValue() ? "Yes" : "—" },
    { accessorKey: "isActive", header: "Status", cell: ({ getValue }) => getValue() ? "Active" : "Inactive" },
    { id: "actions", header: "Actions", enableSorting: false, enableHiding: false, cell: ({ row }) => <button type="button" className="button-secondary" onClick={() => openEdit(row.original)}>Edit metadata</button> },
  ], []);

  return <div className="content configuration-content workflow-status-master-page">
    <div className="heading"><div><h1>Workflow Status Master</h1></div></div>
    <section className="configuration-table-card">
      <div className="configuration-table-head workflow-status-master-head"><div><div className="eyebrow">DEFECT MODULE</div><h2>Status definitions</h2></div><span className="role-protected-badge">Workflow rules remain code controlled</span></div>
      <DataTable<WorkflowStatus> className="master-data-table workflow-status-master-table" columns={columns} data={query.data?.items || []} total={query.data?.totalElements || 0} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={query.isPending || query.isFetching} error={query.isError} errorMessage={query.error instanceof Error ? query.error.message : "Could not load workflow statuses."} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(0); }} onSortingChange={(next) => setSorting(next)} onSearchChange={(value) => { setSearch(value); setPage(0); }} onRetry={() => void query.refetch()} onExport={() => undefined} exporting={false} exportable={false} selectable={false} />
    </section>
    {editing && <DialogPortal onClose={() => { if (!saving) setEditing(null); }} closeOnEscape={!saving} labelledBy="workflow-status-edit-title" className="modal master-form-modal"><div className="modal-heading flex items-start justify-between"><div><div className="eyebrow">{editing.moduleCode} · {editing.statusCode}</div><h2 id="workflow-status-edit-title">Edit status metadata</h2><p>The status code is stable and cannot be changed.</p></div><button type="button" className="close" onClick={() => setEditing(null)} disabled={saving} aria-label="Close">×</button></div>
      <form className="master-form-content flex flex-col gap-4" onSubmit={save}>
        <label>Display name<input required maxLength={100} value={form.displayName} onChange={(event) => setForm({ ...form, displayName: event.target.value })} /></label>
        <label>Alias name<input maxLength={100} placeholder="Optional; leave blank to clear" value={form.aliasName} onChange={(event) => setForm({ ...form, aliasName: event.target.value })} /></label>
        <label>Description<textarea maxLength={500} rows={3} value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} /></label>
        <label>Display order<input type="number" min={0} required value={form.sortOrder} onChange={(event) => setForm({ ...form, sortOrder: event.target.value })} /></label>
        <fieldset className="grid grid-cols-2 gap-3 rounded-xl border border-[#dce5df] p-3"><legend>Badge appearance</legend><label>Background<input type="color" value={form.backgroundColor} onChange={event => setForm({ ...form, backgroundColor: event.target.value })} /></label><label>Text<input type="color" value={form.textColor} onChange={event => setForm({ ...form, textColor: event.target.value })} /></label><label>Border<input type="color" value={form.borderColor} onChange={event => setForm({ ...form, borderColor: event.target.value })} /></label><label>Icon (optional)<input maxLength={32} value={form.icon} onChange={event => setForm({ ...form, icon: event.target.value })} /></label><label>Display style<select value={form.displayStyle} onChange={event => setForm({ ...form, displayStyle: event.target.value as typeof form.displayStyle })}><option value="SOLID">Solid</option><option value="OUTLINE">Outline</option><option value="SOFT">Soft</option></select></label><div className="flex items-end"><span className="master-badge" style={{ backgroundColor: form.displayStyle === "SOLID" ? form.backgroundColor : form.displayStyle === "SOFT" ? `${form.backgroundColor}22` : "transparent", color: form.textColor, borderColor: form.borderColor }}>{form.icon && <span aria-hidden="true">{form.icon}</span>}{form.displayName || "Preview"}</span></div></fieldset>
        <label className="flex items-center gap-2"><input type="checkbox" checked={form.isActive} onChange={(event) => setForm({ ...form, isActive: event.target.checked })} /> Active for new status assignments</label>
        <label>Change reason<input required maxLength={1000} value={form.reason} onChange={(event) => setForm({ ...form, reason: event.target.value })} /></label>
        <p className="modal-note"><span>i</span> Inactive statuses remain visible on existing cases. The database prevents assigning an inactive code to a new or updated case.</p>
        <div className="modal-actions flex justify-end gap-2 border-t border-[#edf0ec] pt-[14px]"><button type="button" className="button-secondary" onClick={() => setEditing(null)} disabled={saving}>Cancel</button><button type="submit" className="button-primary" disabled={saving}>{saving ? "Saving…" : "Save changes"}</button></div>
      </form>
    </DialogPortal>}
  </div>;
}
