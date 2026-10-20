import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { workflowStatusLabel } from "./workflowStatusLabels.mjs";

const catalog = [
  ["REPORTED", "Reported", "New"], ["UNDER_REVIEW", "Under Review", "Review Pending"],
  ["INSPECTION_REQUIRED", "Inspection Required", "Requires Inspection"], ["INSPECTION_IN_PROGRESS", "Inspection In Progress", "Inspecting"],
  ["INSPECTION_COMPLETE", "Inspection Complete", "Inspection Done"], ["ACTION_ASSIGNED", "Action Assigned", "Assigned"],
  ["IN_PROGRESS", "In Progress", "Processing"], ["AWAITING_VERIFICATION", "Awaiting Verification", "Pending Verification"],
  ["VERIFIED", "Verified", "Verified"], ["APPROVAL_REQUIRED", "Approval Required", "Pending Approval"],
  ["CLOSED", "Closed", "Completed"], ["REJECTED", "Rejected", "Declined"],
  ["CANCELLED", "Cancelled", "Withdrawn"], ["REOPENED", "Reopened", "Reopened"],
].map(([statusCode, displayName, aliasName]) => ({ statusCode, displayName, aliasName }));

test("all DEFECT statuses resolve to their catalog alias using the stable code", () => {
  for (const status of catalog) assert.equal(workflowStatusLabel(status.statusCode, catalog), status.aliasName);
  assert.equal(workflowStatusLabel("IN_PROGRESS", catalog), "Processing");
});

test("missing and blank aliases fall back to the master display name", () => {
  assert.equal(workflowStatusLabel("REPORTED", [{ statusCode: "REPORTED", aliasName: "  ", displayName: "Reported" }]), "Reported");
  assert.equal(workflowStatusLabel("REPORTED", [{ statusCode: "REPORTED", displayName: " Reported " }]), "Reported");
});

test("missing catalog entries and empty codes receive safe readable labels", () => {
  assert.equal(workflowStatusLabel("CUSTOM_STATUS"), "Custom Status");
  assert.equal(workflowStatusLabel("  ", catalog), "Unknown status");
  assert.doesNotMatch(workflowStatusLabel("NOT_CONFIGURED", []), /undefined|null/);
});

test("defect status controls show aliases while preserving submitted status codes", async () => {
  const root = new URL("../../", import.meta.url);
  const [aircraft, home, reviewer, badges] = await Promise.all([
    readFile(new URL("common/fleet/AircraftDefectListPage.tsx", root), "utf8"),
    readFile(new URL("app/loginhome/page.tsx", root), "utf8"),
    readFile(new URL("common/fleet/ReviewerWorkQueuePage.tsx", root), "utf8"),
    readFile(new URL("common/workflow/workflowStatuses.tsx", root), "utf8"),
  ]);
  assert.match(aircraft, /workflowStatusLabel\(item\.statusCode, workflowStatuses\)/);
  assert.match(aircraft, /value=\{item\.statusCode\}/);
  assert.match(home, /workflowStatusLabel\(value\.statusCode, workflowStatuses\)/);
  assert.match(home, /value=\{value\.statusCode\}/);
  assert.match(reviewer, /workflowStatusLabel\(code, workflowStatuses\)/);
  assert.match(reviewer, /value=\{code\}/);
  assert.match(badges, /workflowStatusLabel\(code, statuses\)/);
});
