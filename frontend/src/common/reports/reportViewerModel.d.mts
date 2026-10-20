export type ReportFilterModel = {
  filterKey: string;
  label: string;
  filterType: "TEXT" | "NUMBER" | "BOOLEAN" | "DATE" | "SINGLE_SELECT" | "MULTI_SELECT" | "LOOKUP_SINGLE" | "LOOKUP_MULTI";
  required: boolean;
  sortOrder: number;
  lookupKey: string | null;
  configuration: Record<string, unknown>;
};
export function validateReportFilters(filters: ReportFilterModel[], values: Record<string, unknown>, lookupOptions?: Record<string, Array<{ value: string; label: string }>>): Record<string, string>;
export function queryKeyFor(reportId: string, filters: Record<string, unknown>, page: number, size: number, sort: Array<{ key: string; direction: "ASC" | "DESC" }>): unknown[];
export function formatReportValue(value: unknown, type: string, format?: Record<string, unknown>): string;
