import test from "node:test";
import assert from "node:assert/strict";
import { conditionalVisibilityIssue, isFieldRequired, isFieldVisible } from "./conditionalFieldVisibility.mjs";

test("conditional workflow fields follow the configured decision value", () => {
  const decision = { key: "decision", visible: true };
  const team = { visible: true, visibleWhen: { fieldKey: "decision", equals: "APPROVE" } };
  const reason = { visible: true, visibleWhen: { fieldKey: "decision", equals: "REJECT" } };
  const fields = [{ ...decision, key: "decision" }, { ...team, key: "team" }, { ...reason, key: "reason" }];
  assert.equal(isFieldVisible(fields[1], { decision: "APPROVE" }, fields), true);
  assert.equal(isFieldVisible(fields[1], { decision: "REJECT" }, fields), false);
  assert.equal(isFieldVisible(fields[2], { decision: "REJECT" }, fields), true);
  assert.equal(isFieldVisible(fields[2], { decision: "APPROVE" }, fields), false);
  assert.equal(isFieldVisible(fields[1], {}, fields), false);
});

test("ordinary configured fields remain visible and explicitly hidden fields remain hidden", () => {
  assert.equal(isFieldVisible({ visible: true }, {}), true);
  assert.equal(isFieldVisible({}, {}), true);
  assert.equal(isFieldVisible({ visible: false }, { decision: "APPROVE" }), false);
});

test("conditional remarks are required only for the configured Decline outcome", () => {
  const decision = { key: "decision" };
  const remarks = { required: false, requiredWhen: { fieldKey: "decision", equals: "DECLINE" } };
  assert.equal(isFieldRequired(remarks, { decision: "ACCEPT" }, [decision, { ...remarks, key: "remarks" }]), false);
  assert.equal(isFieldRequired(remarks, { decision: "DECLINE" }, [decision, { ...remarks, key: "remarks" }]), true);
  assert.equal(isFieldRequired({ required: true }, { decision: "ACCEPT" }), true);
});

test("visibility supports text, booleans, dropdown answers and multi-select selections", () => {
  const fields = [
    { key: "text", type: "TEXT" }, { key: "flag", type: "CHECKBOX" },
    { key: "choice", type: "DROPDOWN" }, { key: "many", type: "MULTI_SELECT" },
    { key: "a", visibleWhen: { fieldKey: "text", equals: "yes" } },
    { key: "b", visibleWhen: { fieldKey: "flag", equals: "true" } },
    { key: "c", visibleWhen: { fieldKey: "choice", equals: "option-id" } },
    { key: "d", visibleWhen: { fieldKey: "many", equals: "option-id" } },
  ];
  const values = { text: "yes", flag: true, choice: "option-id", many: ["other", "option-id"] };
  for (const target of fields.slice(4)) assert.equal(isFieldVisible(target, values, fields), true);
});

test("a hidden source cannot make a dependent field visible", () => {
  const fields = [{ key: "source", visible: false }, { key: "target", visibleWhen: { fieldKey: "source", equals: "yes" } }];
  assert.equal(isFieldVisible(fields[1], { source: "yes" }, fields), false);
});

test("conditional rule validation rejects missing, self-referencing, and cyclic sources", () => {
  assert.match(conditionalVisibilityIssue([{ key: "x", visibleWhen: { fieldKey: "missing", equals: "yes" } }]), /reference another configured answer/);
  assert.match(conditionalVisibilityIssue([{ key: "x", visibleWhen: { fieldKey: "x", equals: "yes" } }]), /reference another configured answer/);
  assert.match(conditionalVisibilityIssue([
    { key: "a", visibleWhen: { fieldKey: "b", equals: "yes" } },
    { key: "b", visibleWhen: { fieldKey: "a", equals: "yes" } },
  ]), /cannot contain a cycle/);
});

test("hidden required fields do not block client validation and stale hidden answers are omitted", async () => {
  const { answersWithoutUntouchedDefaults, configuredAnswerIssue } = await import("./defectReportMapping.ts");
  const fields = [
    { key: "show", type: "CHECKBOX", required: false },
    { key: "details", label: "Details", type: "TEXT", required: true, visibleWhen: { fieldKey: "show", equals: "true" } },
  ];
  assert.equal(configuredAnswerIssue(fields, { show: false, details: "stale" }), "");
  assert.deepEqual(answersWithoutUntouchedDefaults(fields, { show: false, details: "stale" }, new Set()), { show: false });
  assert.match(configuredAnswerIssue(fields, { show: true, details: "" }), /Complete Details/);
});
