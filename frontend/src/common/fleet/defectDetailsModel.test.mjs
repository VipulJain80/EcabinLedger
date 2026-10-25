import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { answersFromReportSnapshot, canReassignMaintenance, defectDetailsRequestPath, formatHistoryDate, newestFirst, safeReturnPath } from "./defectDetailsModel.ts";

test("maintenance reassignment is offered only with permission, an active action id, and a supported workflow state", () => {
  assert.equal(canReassignMaintenance(["ASSIGN_ACTION"], "action-1", "ACTION_ASSIGNED"), true);
  assert.equal(canReassignMaintenance(["ASSIGN_ACTION"], "action-1", "IN_PROGRESS"), true);
  assert.equal(canReassignMaintenance(["ASSIGN_ACTION"], null, "ACTION_ASSIGNED"), false);
  assert.equal(canReassignMaintenance(["ASSIGN_ACTION"], "action-1", "CLOSED"), false);
  assert.equal(canReassignMaintenance(["PERFORM_ACTION"], "action-1", "IN_PROGRESS"), false);
});

test("activity is explicitly sorted newest first without mutating the response", () => {
  const events = [{ id: "old", occurredAt: "2026-01-01T00:00:00Z" }, { id: "new", occurredAt: "2026-01-02T00:00:00Z" }];
  assert.deepEqual(newestFirst(events).map(event => event.id), ["new", "old"]);
  assert.equal(events[0].id, "old");
});

test("typed report snapshots are adapted using only bindings in the exact form definition", () => {
  const fields = [{ key: "loc", binding: "ZONE" }, { key: "name", binding: "TITLE" }, { key: "remarks", binding: "REMARKS" }, { key: "unbound", binding: null }];
  const result = answersFromReportSnapshot(fields, { zone: "G1", title: "Latch", defectCategory: "GALLEY", componentId: "component-id", conditionId: "condition-id", rowNumber: null, seatReference: null, description: "Condition: Surface damage\nRemarks: Handle is loose", severityId: "severity-id" });
  assert.deepEqual(result, { loc: "G1", name: "Latch", remarks: "Handle is loose" });
});

test("back links accept only same-app relative paths", () => {
  assert.equal(safeReturnPath("/ecabin/loginhome?filter=OPEN", "/ecabin"), "/loginhome?filter=OPEN");
  assert.equal(safeReturnPath("/ecabin", "/ecabin"), "/");
  assert.equal(safeReturnPath("https://outside.example", "/ecabin"), "/loginhome");
  assert.equal(safeReturnPath("//outside.example", "/ecabin"), "/loginhome");
  assert.equal(safeReturnPath("/loginhome", "/ecabin"), "/loginhome");
  assert.equal(safeReturnPath("/loginhome?filter=OPEN", ""), "/loginhome?filter=OPEN");
});

test("detail requests keep the aircraft-list visibility policy scoped to its originating aircraft", () => {
  assert.equal(defectDetailsRequestPath("defect/1", "aircraft 1"), "/aircraft/aircraft%201/defects/defect%2F1");
  assert.equal(defectDetailsRequestPath("defect/1"), "/defects/defect%2F1");
});

test("only the aircraft-specific Defect List passes aircraft visibility context into details", async () => {
  const shell = await readFile(new URL("../../app/loginhome/page.tsx", import.meta.url), "utf8");
  const detailPage = await readFile(new URL("./DefectDetailsPage.tsx", import.meta.url), "utf8");
  assert.match(shell, /onOpenDefect=\{id\s*=>\s*\{\s*void openQueueDefect\(id, selectedAircraftId\);/);
  assert.match(shell, /<MaintenanceWorkQueuePage[\s\S]*?openQueueDefect\(defectId\)/);
  assert.match(shell, /<ReviewerWorkQueuePage[\s\S]*?openQueueDefect\(defectId\)/);
  assert.match(detailPage, /defectDetailsRequestPath\(defectId,aircraftListId\)/);
  assert.match(detailPage, /onBack\?onBack\(\):router\.push\(returnPath\)/);
  assert.match(shell, /setOpenDefectId\(null\);setOpenDefectAircraftListId\(null\)/);
});

test("history formats SLA calendar dates without shifting timezone and distinguishes unset from cleared dates", () => {
  assert.equal(formatHistoryDate("2026-10-18"), "18 Oct 2026");
  assert.equal(formatHistoryDate(""), "Not set");
  assert.equal(formatHistoryDate("", "Cleared"), "Cleared");
  assert.equal(formatHistoryDate(null), "—");
});
