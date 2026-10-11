import test from "node:test";
import assert from "node:assert/strict";
import { formPurposeForStatus } from "./formResolution.ts";

test("maps operational forms from the current workflow state and capability", () => {
  assert.equal(formPurposeForStatus("REPORTED", ["REPORT"]), "DEFECT_REPORT");
  assert.equal(formPurposeForStatus("INSPECTION_REQUIRED", ["INSPECT"]), "INSPECTION");
  assert.equal(formPurposeForStatus("IN_PROGRESS", ["PERFORM_ACTION"]), "CORRECTIVE_ACTION");
  assert.equal(formPurposeForStatus("AWAITING_VERIFICATION", ["VERIFY"]), "VERIFICATION");
  assert.equal(formPurposeForStatus("APPROVAL_REQUIRED", ["APPROVE"]), "CLOSURE_APPROVAL");
  assert.equal(formPurposeForStatus("CLOSED", ["REOPEN"]), "REOPEN");
});

test("does not infer action permission from form availability or status", () => {
  assert.equal(formPurposeForStatus("INSPECTION_REQUIRED", ["VIEW"]), null);
  assert.equal(formPurposeForStatus("REPORTED", undefined), null);
  assert.equal(formPurposeForStatus("REJECTED", ["REPORT", "REOPEN"]), null);
});
