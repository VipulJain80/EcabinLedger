import test from "node:test";
import assert from "node:assert/strict";
import { defectReportBindingIssue, defectReportPayload, fieldIdentityMetadata } from "./defectReportMapping.ts";

const fields = [
  { key: "aircraftId", label: "Aircraft Registration", type: "AIRCRAFT_SELECT", optionSource: "AIRCRAFT", required: true, binding: "AIRCRAFT_ID" },
  { key: "category_ref", label: "Defect Category", type: "DROPDOWN", optionSource: "DEFECT_CATEGORIES", required: true, binding: "DEFECT_CATEGORY" },
  { key: "component_ref", label: "Component", type: "DROPDOWN", optionSource: "COMPONENTS", required: true, binding: "COMPONENT_ID" },
  { key: "condition_ref", label: "Defect Condition", type: "DROPDOWN", optionSource: "DEFECT_CONDITIONS", optionFilter: { componentFieldKey: "component_ref" }, required: true, binding: "CONDITION_ID" },
  { key: "zone", label: "Cabin location", type: "TEXT", required: true, binding: "ZONE" },
  { key: "title", label: "Finding", type: "TEXT", required: true, binding: "TITLE" },
  { key: "details", label: "Details", type: "TEXTAREA", required: true, binding: "DESCRIPTION" },
  { key: "severity_ref", label: "Severity", type: "DROPDOWN", optionSource: "DEFECT_SEVERITIES", required: true, binding: "SEVERITY" },
];

test("report properties map only approved configured fields and reject unbound business data", () => {
  const answers = { category_ref: "cat-id", component_ref: "component-id", condition_ref: "condition-id", zone: "FWD CABIN / ROW 14", title: "Loose trim", details: "Trim is loose", severity_ref: "severity-id", remarks: "Observe" };
  assert.match(defectReportBindingIssue([...fields, { key: "remarks", label: "Remarks", type: "TEXTAREA", required: false }]), /no approved database mapping/i);
  assert.equal(defectReportBindingIssue(fields), "");
  assert.deepEqual(defectReportPayload(fields, answers), {
    category: "cat-id", componentId: "component-id", conditionId: "condition-id", zone: "FWD CABIN / ROW 14",
    title: "Loose trim", description: "Trim is loose", severity: "severity-id",
  });
  assert.throws(() => defectReportPayload([...fields, { key: "remarks", label: "Remarks", type: "TEXTAREA", required: false }], answers), /no approved database mapping/i);
});

test("missing or misconfigured mandatory backend properties block reporting", () => {
  assert.match(defectReportBindingIssue(fields.filter(field => field.binding !== "SEVERITY")), /severity/i);
  assert.match(defectReportBindingIssue(fields.map(field => field.binding === "CONDITION_ID" ? { ...field, optionFilter: { componentFieldKey: "wrong" } } : field)), /depend on the component/);
  assert.throws(() => defectReportPayload(fields, { category_ref: "cat-id" }), /Complete Component/);
});

test("a remarks-only published form explains that a new bound version is required", () => {
  const issue = defectReportBindingIssue([{ key: "remarks", label: "Remarks", type: "TEXTAREA", required: true, binding: "REMARKS" }]);
  assert.match(issue, /Defect category, Component, Condition, Location, Title, Description, Severity, Aircraft Registration/);
  assert.match(issue, /create a new version/);
  assert.match(issue, /publish it/);
});

test("designer metadata describes internal key, type and required state without repeating the label", () => {
  assert.deepEqual(fieldIdentityMetadata(fields[1]), { key: "category_ref", type: "dropdown", required: true });
  assert.equal(Object.values(fieldIdentityMetadata(fields[1])).includes(fields[1].label), false);
});

test("hidden and read-only required fields cannot bypass defect validation", () => {
  assert.match(defectReportBindingIssue(fields.map(field => field.binding === "TITLE" ? { ...field, visible: false } : field)), /cannot be hidden/i);
  assert.match(defectReportBindingIssue(fields.map(field => field.binding === "TITLE" ? { ...field, readOnly: true } : field)), /must be editable/i);
});

test("Galley Area form uses its configured fields and keeps its evidence and severity choices constrained", () => {
  const galley = [
    { key: "aircraftId", label: "Aircraft Registration", type: "AIRCRAFT_SELECT", optionSource: "AIRCRAFT", required: true, binding: "AIRCRAFT_ID" },
    { key: "location", label: "Galley Number / Location", type: "TEXT", required: true, binding: "ZONE" },
    { key: "component", label: "Component", type: "DROPDOWN", optionSource: "COMPONENTS", required: true, binding: "COMPONENT_ID" },
    { key: "condition", label: "Defect Condition", type: "DROPDOWN", optionSource: "DEFECT_CONDITIONS", optionFilter: { componentFieldKey: "component" }, required: true, binding: "CONDITION_ID" },
    { key: "severity", label: "Severity", type: "DROPDOWN", optionSource: "GALLEY_SEVERITIES", required: true, binding: "SEVERITY" },
    { key: "remarks", label: "Remarks", type: "TEXTAREA", required: false, binding: "REMARKS" },
    { key: "evidence", label: "Evidence Image Upload", type: "IMAGE_UPLOAD", required: true, binding: "EVIDENCE_ATTACHMENTS", fileRules: { extensions: ["jpg", "jpeg", "png", "webp"], mimeTypes: ["image/jpeg", "image/png", "image/webp"], minCount: 1, maxCount: 1, maxFileSizeBytes: 5242880 } },
  ];
  assert.equal(defectReportBindingIssue(galley, "GALLEY"), "");
  assert.deepEqual(defectReportPayload(galley, { location: "Galley 2", component: "component-id", condition: "condition-id", severity: "severity-id", remarks: "Latch loose" }, "GALLEY"), { componentId: "component-id", conditionId: "condition-id", zone: "Galley 2", severity: "severity-id", remarks: "Latch loose" });
  assert.match(defectReportBindingIssue(galley.filter(field => field.binding !== "EVIDENCE_ATTACHMENTS"), "GALLEY"), /Evidence image upload/);
  assert.match(defectReportBindingIssue(galley.map(field => field.binding === "REMARKS" ? { ...field, required: true } : field), "GALLEY"), /Remarks must be optional/);
  assert.match(defectReportBindingIssue(galley.map(field => field.binding === "SEVERITY" ? { ...field, optionSource: "DEFECT_SEVERITIES" } : field), "GALLEY"), /galley severities/i);
});
