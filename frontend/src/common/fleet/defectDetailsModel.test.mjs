import test from "node:test";
import assert from "node:assert/strict";
import { answersFromReportSnapshot, formatHistoryDate, newestFirst, safeReturnPath } from "./defectDetailsModel.ts";

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

test("history formats SLA calendar dates without shifting timezone and distinguishes unset from cleared dates", () => {
  assert.equal(formatHistoryDate("2026-10-18"), "18 Oct 2026");
  assert.equal(formatHistoryDate(""), "Not set");
  assert.equal(formatHistoryDate("", "Cleared"), "Cleared");
  assert.equal(formatHistoryDate(null), "—");
});
