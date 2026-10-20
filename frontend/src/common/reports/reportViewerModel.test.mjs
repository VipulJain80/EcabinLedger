import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { formatReportValue, queryKeyFor, validateReportFilters } from "./reportViewerModel.mjs";

const filters = [
  { filterKey: "term", filterType: "TEXT", required: true, configuration: { maxLength: 20 } },
  { filterKey: "minimum", filterType: "NUMBER", required: false, configuration: { min: 1, max: 9 } },
  { filterKey: "includeClosed", filterType: "BOOLEAN", required: false, configuration: {} },
  { filterKey: "fromDate", filterType: "DATE", required: false, configuration: { rangeGroup: "reported", rangeBound: "FROM" } },
  { filterKey: "toDate", filterType: "DATE", required: false, configuration: { rangeGroup: "reported", rangeBound: "TO" } },
  { filterKey: "status", filterType: "SINGLE_SELECT", required: false, configuration: { options: [{ value: "CLOSED", label: "Completed" }] } },
  { filterKey: "teams", filterType: "MULTI_SELECT", required: false, configuration: { options: [{ value: "M1", label: "Maintenance 1" }] } },
  { filterKey: "owner", filterType: "LOOKUP_SINGLE", required: false, lookupKey: "REPORT_OWNERS", configuration: {} },
  { filterKey: "owners", filterType: "LOOKUP_MULTI", required: false, lookupKey: "REPORT_OWNERS", configuration: {} },
];

test("validates all configured filter kinds while preserving omitted optional values", () => {
  assert.deepEqual(validateReportFilters(filters, {
    term: "latch", includeClosed: false, fromDate: "2026-10-01", toDate: "2026-10-02",
    status: "CLOSED", teams: ["M1"], owner: "abc", owners: ["abc"],
  }, { REPORT_OWNERS: [{ value: "abc", label: "ABC" }] }), {});
  assert.deepEqual(validateReportFilters(filters, {}), { term: "This field is required." });
});

test("rejects impossible calendar dates, blank required text, empty required lists and unavailable lookup values", () => {
  const requiredList = { filterKey: "requiredOwners", filterType: "LOOKUP_MULTI", required: true, lookupKey: "REPORT_OWNERS", configuration: {} };
  const errors = validateReportFilters([...filters, requiredList], {
    term: "   ", fromDate: "2026-02-30", owner: "missing", requiredOwners: [],
  }, { REPORT_OWNERS: [{ value: "abc", label: "ABC" }] });
  assert.equal(errors.term, "This field is required.");
  assert.equal(errors.fromDate, "Enter a valid date.");
  assert.match(errors.owner, /available options/);
  assert.equal(errors.requiredOwners, "This field is required.");
});

test("validates select options, numeric limits, text length and paired date ranges", () => {
  const errors = validateReportFilters(filters, {
    term: "x".repeat(21), minimum: 10, status: "UNKNOWN", teams: ["M2"],
    fromDate: "2026-10-03", toDate: "2026-10-02",
  });
  assert.match(errors.term, /20 characters/);
  assert.match(errors.minimum, /no greater than 9/);
  assert.match(errors.status, /available options/);
  assert.match(errors.teams, /available options/);
  assert.match(errors.fromDate, /on or before/);
  assert.match(errors.toDate, /on or after/);
});

test("query keys distinguish report, filters, paging and sorting snapshots", () => {
  assert.notDeepEqual(queryKeyFor("r1", {}, 0, 25, []), queryKeyFor("r2", {}, 0, 25, []));
  assert.notDeepEqual(queryKeyFor("r1", { status: "CLOSED" }, 0, 25, []), queryKeyFor("r1", { status: "OPEN" }, 0, 25, []));
  assert.notDeepEqual(queryKeyFor("r1", {}, 0, 25, []), queryKeyFor("r1", {}, 1, 25, []));
  assert.notDeepEqual(queryKeyFor("r1", {}, 0, 25, []), queryKeyFor("r1", {}, 0, 25, [{ key: "reportedAt", direction: "DESC" }]));
});

test("formats configured numbers and safely renders absent values", () => {
  assert.equal(formatReportValue(null, "TEXT"), "—");
  assert.equal(formatReportValue(12.3, "NUMBER", { decimalPlaces: 2 }), "12.30");
});

test("viewer consumes the registered routes and backend stable row IDs", async () => {
  const source = await readFile(new URL("./DynamicReportViewer.tsx", import.meta.url), "utf8");
  assert.match(source, /request\("\/reports"/);
  assert.match(source, /`\/reports\/\$\{encodeURIComponent\(reportId\)\}`/);
  assert.match(source, /`\/reports\/\$\{encodeURIComponent\(snapshot\.reportId\)\}\/query`/);
  assert.match(source, /`\/reports\/lookups\/\$\{encodeURIComponent\(key\)\}`/);
  assert.match(source, /enabled: Boolean\(activeQuery\)/);
  assert.match(source, /function changeReport\(nextId: string\)[\s\S]*setValues\(\{\}\)[\s\S]*setActiveQuery\(null\)/);
  assert.match(source, /enableSorting: column\.sortable/);
  assert.match(source, /function setSorting\(sorting: SortingState\)[\s\S]*column\.key === item\.id && column\.sortable/);
  assert.match(source, /selectable=\{false\} searchable=\{false\} exportable=\{false\}/);
  const table = await readFile(new URL("../tables/DataTable.tsx", import.meta.url), "utf8");
  assert.match(table, /getRowId: \(row\) => row\.id/);
});

test("viewer gates execution and export with application and per-report grants", async () => {
  const source = await readFile(new URL("./DynamicReportViewer.tsx", import.meta.url), "utf8");
  assert.match(source, /hasPermission\(permissions, "REPORT_VIEW"\)[\s\S]*hasPermission\(permissions, "REPORT_EXECUTE"\)[\s\S]*definition\.data\?\.canExecute/);
  assert.match(source, /canExecute && hasPermission\(permissions, "REPORT_EXPORT"\) && Boolean\(definition\.data\?\.canExport\)/);
  assert.match(source, /`\/reports\/\$\{encodeURIComponent\(activeQuery\.reportId\)\}\/export`/);
  assert.match(source, /JSON\.stringify\(\{ filters: activeQuery\.filters, sort: activeQuery\.sort \}\)/);
  assert.match(source, /Export Excel/);
});

test("Reports navigation is separate from report data access and summary values use defect-read authorization", async () => {
  const page = await readFile(new URL("../../app/loginhome/page.tsx", import.meta.url), "utf8");
  assert.match(page, /currentNavKey === "reports" && hasPermission\(identity\?\.permissions, "REPORT_VIEW"\) && <DynamicReportViewer/);
  assert.match(page, /const canOpenReports = hasReportView \|\| identityData\.permissions\.includes\("ADMINISTER_ORGANIZATION"\)/);
  assert.match(page, /Report data access is not granted/);
  assert.match(page, /currentNavKey !== "reports" && <section className="register defect-register-data">/);
  assert.match(page, /if \(hasDefectRead && \(hasReportView \|\| hasDefectViewAll\)\)[\s\S]*dashboard\/summary/);
  assert.match(page, /currentNavKey === "reports" && summaryAvailable && <section className="metrics reports-copy-protected/);
  assert.match(page, /className="metrics reports-copy-protected/);
  assert.match(page, /currentNavKey !== "reports" && <section className="register defect-register-data">/);
  const css = await readFile(new URL("../../app/globals.css", import.meta.url), "utf8");
  assert.match(css, /\.reports-copy-protected,\.report-results\{[^}]*user-select:none/);
  assert.match(css, /@media print\{body:has\(\.reports-page-shell\) \.app-shell\{display:none/);
});
