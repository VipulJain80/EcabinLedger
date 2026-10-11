import assert from "node:assert/strict";
import test from "node:test";
import { isPublishedFormForArea, orderedPublishedFields, unsupportedPublishedField } from "./publishedFormDefinition.ts";

test("renderer keeps the published Form Master field order, labels, types, and required rules", () => {
  const publishedFields = [
    { key: "remarks", label: "Remarks", type: "TEXTAREA", required: true, order: 1, helpText: "Record the observation." },
    { key: "component", label: "Component", type: "DROPDOWN", required: true, order: 0, optionSource: "COMPONENTS" },
  ];

  assert.deepEqual(orderedPublishedFields(publishedFields), [publishedFields[1], publishedFields[0]]);
  assert.deepEqual(orderedPublishedFields(publishedFields).map(({ label, type, required, optionSource }) => ({ label, type, required, optionSource })), [
    { label: "Component", type: "DROPDOWN", required: true, optionSource: "COMPONENTS" },
    { label: "Remarks", type: "TEXTAREA", required: true, optionSource: undefined },
  ]);
  assert.equal(unsupportedPublishedField(publishedFields), undefined);
});

test("the exact published version is usable only for its mapped cabin area", () => {
  const form = { id: "passenger-cabin", versionId: "published-v1", areaId: "cabin" };
  assert.equal(isPublishedFormForArea(form, "cabin"), true);
  assert.equal(isPublishedFormForArea(form, "galley"), false);
});

test("an unsupported published field is reported instead of rendered as a generic input", () => {
  const fields = [{ key: "unknown", label: "Unknown control", type: "RATING", required: true, order: 0 }];
  assert.equal(unsupportedPublishedField(fields), fields[0]);
});
