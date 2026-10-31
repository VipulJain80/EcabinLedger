import test from "node:test";
import assert from "node:assert/strict";
import { selectInitialSeverityOption, shouldInitializeSeverityDefault } from "./severityDefaultModel.mjs";

const options = [
  { id: "ordered-first", code: "MAJOR", label: "Major" },
  { id: "configured-default", code: "MEDIUM", label: "Medium", isDefault: true },
];

test("configured active severity is preferred over API order", () => {
  assert.equal(selectInitialSeverityOption(options).id, "configured-default");
});

test("API order is used when no configured default exists", () => {
  assert.equal(selectInitialSeverityOption(options.map(({ id, code, label }) => ({ id, code, label })) ).id, "ordered-first");
});

test("no active options leaves the severity unselected", () => {
  assert.equal(selectInitialSeverityOption([]), null);
});

test("initialization is limited to a new defect report with no prior or edited value", () => {
  const eligible = { preparationMode: true, purpose: "DEFECT_REPORT", hasDefectId: false, hasInitialAnswer: false, isDirty: false, currentValue: "" };
  assert.equal(shouldInitializeSeverityDefault(eligible), true);
  assert.equal(shouldInitializeSeverityDefault({ ...eligible, purpose: "CORRECTIVE_ACTION" }), false);
  assert.equal(shouldInitializeSeverityDefault({ ...eligible, preparationMode: false }), false);
  assert.equal(shouldInitializeSeverityDefault({ ...eligible, hasDefectId: true }), false);
  assert.equal(shouldInitializeSeverityDefault({ ...eligible, hasInitialAnswer: true }), false);
  assert.equal(shouldInitializeSeverityDefault({ ...eligible, isDirty: true }), false);
  assert.equal(shouldInitializeSeverityDefault({ ...eligible, currentValue: "manual-id" }), false);
});
