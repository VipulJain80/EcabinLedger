import test from "node:test";
import assert from "node:assert/strict";
import { isFieldRequired, isFieldVisible } from "./conditionalFieldVisibility.ts";

test("conditional workflow fields follow the configured decision value", () => {
  const team = { visible: true, visibleWhen: { fieldKey: "decision", equals: "APPROVE" } };
  const reason = { visible: true, visibleWhen: { fieldKey: "decision", equals: "REJECT" } };
  assert.equal(isFieldVisible(team, { decision: "APPROVE" }), true);
  assert.equal(isFieldVisible(team, { decision: "REJECT" }), false);
  assert.equal(isFieldVisible(reason, { decision: "REJECT" }), true);
  assert.equal(isFieldVisible(reason, { decision: "APPROVE" }), false);
  assert.equal(isFieldVisible(team, {}), false);
});

test("ordinary configured fields remain visible and explicitly hidden fields remain hidden", () => {
  assert.equal(isFieldVisible({ visible: true }, {}), true);
  assert.equal(isFieldVisible({}, {}), true);
  assert.equal(isFieldVisible({ visible: false }, { decision: "APPROVE" }), false);
});

test("conditional remarks are required only for the configured Decline outcome", () => {
  const remarks = { required: false, requiredWhen: { fieldKey: "decision", equals: "DECLINE" } };
  assert.equal(isFieldRequired(remarks, { decision: "ACCEPT" }), false);
  assert.equal(isFieldRequired(remarks, { decision: "DECLINE" }), true);
  assert.equal(isFieldRequired({ required: true }, { decision: "ACCEPT" }), true);
});
