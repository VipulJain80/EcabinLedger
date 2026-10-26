import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const globalsCss = await readFile(new URL("../../app/globals.css", import.meta.url), "utf8");
const configurationCss = await readFile(new URL("../../app/configuration.css", import.meta.url), "utf8");
const dataTable = await readFile(new URL("./DataTable.tsx", import.meta.url), "utf8");
const registerPage = await readFile(new URL("../../app/loginhome/page.tsx", import.meta.url), "utf8");
const aircraftDefectList = await readFile(new URL("../fleet/AircraftDefectListPage.tsx", import.meta.url), "utf8");
const fleetStatusPage = await readFile(new URL("../fleet/FleetStatusPage.tsx", import.meta.url), "utf8");
const loginMonitoringPage = await readFile(new URL("../configuration/UserLoginMonitoringPage.tsx", import.meta.url), "utf8");

test("base register table rules are scoped instead of changing every table", () => {
  assert.match(globalsCss, /\.workspace \.register \.table-scroll table\s*\{/);
  assert.doesNotMatch(globalsCss, /(?:^|})\s*table\s*\{/);
  assert.doesNotMatch(globalsCss, /(?:^|})\s*(?:thead|th|td|tbody\s+tr)\s*\{/);
});

test("shared data tables wrap long cell values and keep horizontal overflow inside the table", () => {
  assert.match(configurationCss, /\.admin-data-table\s*\{[^}]*table-layout\s*:\s*auto/s);
  assert.match(configurationCss, /\.admin-data-table td\s*\{[^}]*white-space\s*:\s*normal[^}]*overflow-wrap\s*:\s*anywhere/s);
  assert.match(configurationCss, /\.configuration-table-card\.login-monitoring-table \.admin-data-table td:last-child\s*\{[^}]*width\s*:\s*auto[^}]*white-space\s*:\s*normal[^}]*overflow-wrap\s*:\s*anywhere/s);
  assert.match(dataTable, /className="table-scroll admin-data-table-wrap[^\"]*overflow-auto"/);
  assert.match(dataTable, /searchable = true/);
  assert.match(dataTable, /\{searchable && <label className="admin-table-search/);
});

test("long maintenance findings wrap and the register does not constrain its date column as an action cell", () => {
  assert.match(configurationCss, /\.work-queue-description\s*\{[^}]*min-width\s*:\s*0[^}]*overflow-wrap\s*:\s*anywhere/s);
  assert.doesNotMatch(globalsCss, /\.workspace \.register \.table-scroll (?:th|td):last-child\{[^}]*width:\s*35px/);
  assert.doesNotMatch(registerPage, /register-action-cell/);
  assert.match(registerPage, /<th>Case Closed Date<\/th>/);
  assert.doesNotMatch(registerPage, /<th><span className="sr-only">Actions<\/span><\/th>/);
  assert.doesNotMatch(registerPage, /canAdvanceWorkflow|className="row-more"/);
});

test("defect list and fleet summary opt into the Defect Register table styling", () => {
  assert.match(registerPage, /className="register defect-register-data"/);
  assert.match(aircraftDefectList, /className="defect-register-data defect-case-columns"/);
  assert.match(fleetStatusPage, /className="defect-register-data"/);
  assert.match(globalsCss, /\.defect-register-data \.table-scroll table\s*\{[^}]*min-width:\s*1180px[^}]*table-layout:\s*auto/s);
  assert.match(globalsCss, /\.defect-case-columns \.table-scroll td:nth-child\(5\)\s*\{[^}]*width:\s*13%/s);
});

test("master tables use content-sized rows and scroll wide columns within their cards", async () => {
  const workflowMaster = await readFile(new URL("../configuration/WorkflowStatusMasterPage.tsx", import.meta.url), "utf8");
  const formMaster = await readFile(new URL("../configuration/FormMasterPage.tsx", import.meta.url), "utf8");
  const routingMaster = await readFile(new URL("../configuration/AreaTeamRoutingPage.tsx", import.meta.url), "utf8");
  const rateLimitsMaster = await readFile(new URL("../configuration/ApiRateLimitsPage.tsx", import.meta.url), "utf8");
  const configurationPage = await readFile(new URL("../../app/configuration.tsx", import.meta.url), "utf8");
  assert.match(configurationCss, /\.app-shell \.master-data-table \.admin-data-table\s*\{[^}]*min-width:\s*760px[^}]*table-layout:\s*auto/s);
  assert.match(configurationCss, /\.app-shell \.master-data-table:not\(\.form-master-data-table\) \.admin-data-table td:last-child:has\(button\)\s*\{[^}]*min-width:\s*150px[^}]*white-space:\s*normal/s);
  assert.match(configurationCss, /\.app-shell \.master-data-table \.config-row-actions\s*\{[^}]*flex-wrap:\s*wrap[^}]*white-space:\s*normal/s);
  assert.match(configurationCss, /\.app-shell \.workflow-status-master-table \.admin-data-table\s*\{\s*min-width:\s*1320px\s*\}/);
  assert.match(configurationCss, /\.workflow-status-master-head\s*\{[^}]*display:\s*flex[^}]*padding:\s*18px 22px/s);
  assert.match(configurationCss, /\.workflow-status-master-page \.workflow-status-master-table \.admin-data-table-wrap\s*\{[^}]*min-width:\s*0[^}]*overflow-x:\s*auto/s);
  assert.match(configurationCss, /\.workflow-status-master-page \.workflow-status-master-table \.admin-data-table\s*\{[^}]*width:\s*max-content[^}]*min-width:\s*1320px/s);
  assert.match(workflowMaster, /className="configuration-table-head workflow-status-master-head"/);
  assert.match(configurationCss, /\.app-shell \.master-data-table \.admin-data-table td\s*\{[^}]*height:\s*auto[^}]*overflow-wrap:\s*anywhere/s);
  assert.match(workflowMaster, /className="master-data-table workflow-status-master-table"/);
  assert.match(formMaster, /className="master-data-table form-master-data-table"/);
  assert.match(routingMaster, /className="master-data-table" columns=\{mappingColumns\}/);
  assert.match(rateLimitsMaster, /className="configuration-table-card rate-policy-card master-data-table"/);
  assert.match(configurationPage, /className="master-data-table" columns=\{tableColumns\}/);
  assert.match(configurationPage, /"maintenance-sla-policies",\s*"workflow-statuses",\s*"users"/);
  assert.match(configurationPage, /activeKey === "workflow-statuses"\) return <WorkflowStatusMasterPage/);
});

test("login monitoring filters match the Rows selector and history uses the shared modal surface", () => {
  assert.match(configurationCss, /\.login-monitoring-filters select\s*\{[^}]*height:\s*40px[^}]*border:\s*1px solid #d5dce7[^}]*border-radius:\s*9px/s);
  assert.match(configurationCss, /\.login-monitoring-filters select:focus-visible\s*\{[^}]*outline:/s);
  assert.match(configurationCss, /\.login-monitoring-table \.admin-data-table td:last-child \.button-secondary\s*\{[^}]*white-space:\s*nowrap/s);
  assert.match(loginMonitoringPage, /className="modal login-history-modal"/);
  assert.match(loginMonitoringPage, /className="table-scroll login-history-events"/);
  assert.match(configurationCss, /\.login-history-modal \.login-history-events\s*\{[^}]*max-height:[^}]*overflow:\s*auto/s);
  assert.match(loginMonitoringPage, /aria-label="Membership filter"/);
  assert.match(loginMonitoringPage, /aria-label="Login activity filter"/);
  assert.doesNotMatch(loginMonitoringPage, /<label>Membership</);
  assert.doesNotMatch(loginMonitoringPage, /<label>Login activity</);
  assert.match(loginMonitoringPage, /users\/export\?/);
  assert.match(loginMonitoringPage, /events\/export/);
  assert.match(loginMonitoringPage, /Export Excel/);
  assert.match(configurationCss, /\.login-history-toolbar\s*\{[^}]*justify-content:\s*space-between/s);
  assert.match(configurationCss, /\.login-history-footer\s*\{[^}]*justify-content:\s*space-between/s);
});
