"use client";

import { ReactNode, useEffect, useState } from "react";
import {
  ColumnDef, SortingState, VisibilityState, flexRender, getCoreRowModel,
  getPaginationRowModel, useReactTable,
} from "@tanstack/react-table";

export type ServerTablePage<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number };

type DataTableProps<T extends { id: string }> = {
  columns: ColumnDef<T, unknown>[];
  data: T[];
  total: number;
  page: number;
  pageSize: number;
  sorting: SortingState;
  search: string;
  loading: boolean;
  error: boolean;
  filters?: ReactNode;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  onSortingChange: (sorting: SortingState) => void;
  onSearchChange: (search: string) => void;
  onRetry: () => void;
  onExport: () => void;
  exporting: boolean;
  selectable?: boolean;
};

export default function DataTable<T extends { id: string }>({ columns, data, total, page, pageSize, sorting, search, loading, error, filters, onPageChange, onPageSizeChange, onSortingChange, onSearchChange, onRetry, onExport, exporting, selectable = true }: DataTableProps<T>) {
  const [searchDraft, setSearchDraft] = useState(search);
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>({});
  const [showColumns, setShowColumns] = useState(false);
  useEffect(() => setSearchDraft(search), [search]);
  useEffect(() => {
    const timer = window.setTimeout(() => { if (searchDraft !== search) onSearchChange(searchDraft); }, 350);
    return () => window.clearTimeout(timer);
  }, [onSearchChange, searchDraft, search]);

  // TanStack Table intentionally exposes imperative table APIs that React Compiler cannot memoize.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data, columns, getCoreRowModel: getCoreRowModel(), getPaginationRowModel: getPaginationRowModel(),
    getRowId: (row) => row.id,
    manualPagination: true, manualSorting: true, enableRowSelection: true,
    pageCount: Math.max(1, Math.ceil(total / pageSize)),
    state: { pagination: { pageIndex: page, pageSize }, sorting, columnVisibility },
    onPaginationChange: (updater) => {
      const current = { pageIndex: page, pageSize };
      const next = typeof updater === "function" ? updater(current) : updater;
      if (next.pageSize !== pageSize) onPageSizeChange(next.pageSize);
      else onPageChange(next.pageIndex);
    },
    onSortingChange: (updater) => onSortingChange(typeof updater === "function" ? updater(sorting) : updater),
    onColumnVisibilityChange: setColumnVisibility,
  });
  const first = total === 0 ? 0 : page * pageSize + 1;
  const last = Math.min((page + 1) * pageSize, total);
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const visiblePages = [...new Set([0, totalPages - 1, page - 1, page, page + 1].filter((index) => index >= 0 && index < totalPages))].sort((a, b) => a - b);
  const columnCount = table.getVisibleLeafColumns().length + (selectable ? 1 : 0);
  return <>
    <div className="admin-table-tools">
      <label className="admin-table-page-size">Rows<select value={pageSize} onChange={(event) => onPageSizeChange(Number(event.target.value))}><option value={10}>10</option><option value={25}>25</option><option value={50}>50</option><option value={100}>100</option></select></label>
      <label className="admin-table-search"><span aria-hidden="true">⌕</span><input value={searchDraft} onChange={(event) => setSearchDraft(event.target.value)} placeholder="Search records…" aria-label="Search administration records" />{searchDraft && <button type="button" onClick={() => { setSearchDraft(""); onSearchChange(""); }} aria-label="Clear search">×</button>}</label>
      {filters}
      <div className="admin-table-actions">
        <div className="column-picker"><button type="button" className="button-secondary" aria-expanded={showColumns} onClick={() => setShowColumns((open) => !open)}>Columns ▾</button>{showColumns && <div className="column-picker-menu">{table.getAllLeafColumns().filter((column) => column.getCanHide()).map((column) => <label key={column.id}><input type="checkbox" checked={column.getIsVisible()} onChange={column.getToggleVisibilityHandler()} />{String(column.columnDef.header || column.id)}</label>)}</div>}</div>
        <button type="button" className="button-secondary admin-export" onClick={onExport} disabled={exporting}>{exporting ? "Preparing…" : "Export Excel"}</button>
      </div>
    </div>
    <div className="table-scroll admin-data-table-wrap"><table className="admin-data-table"><thead>{table.getHeaderGroups().map((group) => <tr key={group.id}>{selectable && <th className="selection-cell"><span className="sr-only">#</span><input type="checkbox" checked={table.getIsAllPageRowsSelected()} ref={(element) => { if (element) element.indeterminate = table.getIsSomePageRowsSelected(); }} onChange={table.getToggleAllPageRowsSelectedHandler()} aria-label="Select all rows on this page" /></th>}{group.headers.map((header) => <th key={header.id}>{header.isPlaceholder ? null : <button type="button" className={header.column.getCanSort() ? "sortable-heading" : "sortable-heading static"} onClick={header.column.getToggleSortingHandler()} disabled={!header.column.getCanSort()}>{flexRender(header.column.columnDef.header, header.getContext())}{header.column.getCanSort() && <span aria-hidden="true">{header.column.getIsSorted() === "asc" ? " ↑" : header.column.getIsSorted() === "desc" ? " ↓" : " ↕"}</span>}</button>}</th>)}</tr>)}</thead><tbody>
      {loading && Array.from({ length: Math.min(pageSize, 6) }, (_, index) => <tr key={`loading-${index}`} className="table-skeleton-row"><td colSpan={columnCount}><span /></td></tr>)}
      {!loading && error && <tr><td colSpan={columnCount} className="table-state-cell"><strong>We couldn’t load these records.</strong><button className="button-secondary" onClick={onRetry}>Retry</button></td></tr>}
      {!loading && !error && table.getRowModel().rows.map((row) => <tr key={row.id}>{selectable && <td className="selection-cell"><input type="checkbox" checked={row.getIsSelected()} onChange={row.getToggleSelectedHandler()} aria-label={`Select row ${row.original.id}`} /></td>}{row.getVisibleCells().map((cell) => <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>)}</tr>)}
      {!loading && !error && table.getRowModel().rows.length === 0 && <tr><td colSpan={columnCount} className="table-state-cell"><span className="empty-art">⌕</span><strong>No records found</strong><small>Adjust the search or filters and try again.</small></td></tr>}
    </tbody></table></div>
    <div className="admin-table-footer"><span>Showing {first}–{last} of {total.toLocaleString()} records{table.getSelectedRowModel().rows.length > 0 ? ` · ${table.getSelectedRowModel().rows.length} selected on this page` : ""}</span><div className="admin-pagination"><button className="button-secondary" disabled={page <= 0 || loading} onClick={() => onPageChange(page - 1)}>Previous</button><span>Page {page + 1} of {totalPages}</span>{visiblePages.map((index, position) => <span className="admin-page-slot" key={index}>{position > 0 && index - visiblePages[position - 1] > 1 && <i>…</i>}<button type="button" className={index === page ? "admin-page-number active" : "admin-page-number"} aria-current={index === page ? "page" : undefined} disabled={loading} onClick={() => onPageChange(index)}>{index + 1}</button></span>)}<button className="button-secondary" disabled={last >= total || loading} onClick={() => onPageChange(page + 1)}>Next</button></div></div>
  </>;
}
