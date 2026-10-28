import test from "node:test";
import assert from "node:assert/strict";
import { answersWithoutUntouchedDefaults, canonicalizeLegacySeveritySource, configuredAnswerIssue, defectReportBindingIssue, fieldIdentityMetadata, initializeFormValues } from "./defectReportMapping.ts";

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

test("form schema accepts additional fields while validating configured bindings", () => {
  const answers = { category_ref: "cat-id", component_ref: "component-id", condition_ref: "condition-id", zone: "FWD CABIN / ROW 14", title: "Loose trim", details: "Trim is loose", severity_ref: "severity-id", remarks: "Observe" };
  assert.equal(defectReportBindingIssue([...fields, { key: "remarks", label: "Remarks", type: "TEXTAREA", required: false }]), "");
  assert.equal(defectReportBindingIssue(fields), "");
  assert.equal(configuredAnswerIssue([...fields, { key: "remarks", label: "Remarks", type: "TEXTAREA", required: true }], answers), "");
  assert.match(configuredAnswerIssue([...fields, { key: "remarks", label: "Remarks", type: "TEXTAREA", required: true }], { ...answers, remarks: " " }), /Complete Remarks/);
});

test("Aircraft Registration is the only universally required defect-report binding", () => {
  const flexible = fields.map(field => field.binding === "AIRCRAFT_ID" ? field : { ...field, required: false });
  assert.equal(defectReportBindingIssue(flexible), "");
  assert.match(defectReportBindingIssue(fields.filter(field => field.binding !== "AIRCRAFT_ID")), /Aircraft Registration/i);
  assert.match(defectReportBindingIssue(fields.map(field => field.binding === "AIRCRAFT_ID" ? { ...field, visibleWhen: { fieldKey: "show", equals: "yes" } } : field)), /Aircraft Registration/i);
  assert.equal(defectReportBindingIssue(fields.filter(field => field.binding !== "DEFECT_CATEGORY")), "");
  assert.equal(defectReportBindingIssue(fields.map(field => field.binding === "DEFECT_CATEGORY" ? { ...field, required: false } : field)), "");
  assert.match(defectReportBindingIssue(fields.map(field => field.binding === "CONDITION_ID" ? { ...field, optionFilter: { componentFieldKey: "wrong" } } : field)), /depend on the component/);
});

test("aircraft is checked outside the answer map and the frontend does not synthesize domain payloads", () => {
  const issue = defectReportBindingIssue([{ key: "remarks", label: "Remarks", type: "TEXTAREA", required: true, binding: "REMARKS" }]);
  assert.match(issue, /Aircraft Registration/);
  const aircraftOnly = [fields[0]];
  assert.equal(defectReportBindingIssue(aircraftOnly), "");
  assert.equal(configuredAnswerIssue(aircraftOnly, {}), "");
});

test("designer metadata describes internal key, type and required state without repeating the label", () => {
  assert.deepEqual(fieldIdentityMetadata(fields[1]), { key: "category_ref", type: "dropdown", required: true });
  assert.equal(Object.values(fieldIdentityMetadata(fields[1])).includes(fields[1].label), false);
});

test("hidden and read-only required fields cannot bypass defect validation", () => {
  assert.match(defectReportBindingIssue(fields.map(field => field.binding === "TITLE" ? { ...field, visible: false } : field)), /cannot be hidden/i);
  assert.match(defectReportBindingIssue(fields.map(field => field.binding === "TITLE" ? { ...field, readOnly: true } : field)), /must be editable/i);
});

test("legacy Galley report projection remains compatible without imposing evidence or area restrictions", () => {
  const galley = [
    { key: "aircraftId", label: "Aircraft Registration", type: "AIRCRAFT_SELECT", optionSource: "AIRCRAFT", required: true, binding: "AIRCRAFT_ID" },
    { key: "location", label: "Galley Number / Location", type: "TEXT", required: true, binding: "ZONE" },
    { key: "component", label: "Component", type: "DROPDOWN", optionSource: "COMPONENTS", required: true, binding: "COMPONENT_ID" },
    { key: "condition", label: "Defect Condition", type: "DROPDOWN", optionSource: "DEFECT_CONDITIONS", optionFilter: { componentFieldKey: "component" }, required: true, binding: "CONDITION_ID" },
    { key: "severity", label: "Severity", type: "DROPDOWN", optionSource: "DEFECT_SEVERITIES", required: true, binding: "SEVERITY" },
    { key: "remarks", label: "Remarks", type: "TEXTAREA", required: false, binding: "REMARKS" },
    { key: "evidence", label: "Evidence Image Upload", type: "IMAGE_UPLOAD", required: true, binding: "EVIDENCE_ATTACHMENTS", fileRules: { extensions: ["jpg", "jpeg", "png", "webp"], mimeTypes: ["image/jpeg", "image/png", "image/webp"], minCount: 1, maxCount: 1, maxFileSizeBytes: 5242880 } },
  ];
  assert.equal(defectReportBindingIssue(galley), "");
  assert.equal(defectReportBindingIssue(galley), "");
  assert.equal(configuredAnswerIssue(galley, { location: "Galley 2", component: "component-id", condition: "condition-id", severity: "severity-id", remarks: "Latch loose", evidence: ["file-id"] }), "");
  assert.equal(defectReportBindingIssue(galley.filter(field => field.binding !== "EVIDENCE_ATTACHMENTS")), "");
  assert.equal(defectReportBindingIssue(galley.map(field => field.binding === "REMARKS" ? { ...field, required: true } : field)), "");
  assert.match(defectReportBindingIssue(galley.map(field => field.binding === "SEVERITY" ? { ...field, type: "TEXT" } : field)), /incompatible type/i);
});

test("legacy severity source remains readable and is normalized for edited configurations", () => {
  const legacy = fields.map(field => field.binding === "SEVERITY" ? { ...field, optionSource: "GALLEY_SEVERITIES" } : field);
  assert.equal(defectReportBindingIssue(legacy), "");
  const editable = canonicalizeLegacySeveritySource(legacy);
  assert.equal(editable.find(field => field.binding === "SEVERITY").optionSource, "DEFECT_SEVERITIES");
  assert.equal(legacy.find(field => field.binding === "SEVERITY").optionSource, "GALLEY_SEVERITIES");
  assert.equal(defectReportBindingIssue(editable), "");
});

test("backend capability metadata does not turn configurable defect fields into universal requirements", () => {
  assert.equal(defectReportBindingIssue(fields.map(field => field.binding === "DEFECT_CATEGORY" ? { ...field, required: false } : field)), "");
  assert.match(defectReportBindingIssue(fields.filter(field => field.binding !== "AIRCRAFT_ID")), /Aircraft Registration/i);
});

test("Remarks is accepted in every supported area without rewriting the area field set", () => {
  const remarks = { key: "remarks", label: "Remarks", type: "TEXTAREA", required: false, binding: "REMARKS" };
  for (const area of ["CABIN", "GALLEY", "LAVATORY", "ENTRY_AREA", "ATTENDANT_SEAT", "AIRPORT_TAXI"])
    assert.equal(defectReportBindingIssue([fields[0], remarks]), "", area);
});

test("Text and Textarea defaults initialize without replacing answers and remain non-submitted until edited", () => {
  const defaults = [{ key: "summary", label: "Summary", type: "TEXT", required: false, defaultValue: "Starting text" }, { key: "details", label: "Details", type: "TEXTAREA", required: false, defaultValue: "Starting details" }, { key: "blank", label: "Blank", type: "TEXT", required: false, defaultValue: "" }, { key: "none", label: "None", type: "TEXTAREA", required: false, defaultValue: null }];
  const initial = initializeFormValues(defaults, { summary: "User input" });
  assert.deepEqual(initial, { summary: "User input", details: "Starting details", blank: "" });
  assert.deepEqual(answersWithoutUntouchedDefaults(defaults, initial, new Set(), { summary: "User input" }), { summary: "User input" });
  assert.deepEqual(answersWithoutUntouchedDefaults(defaults, { ...initial, details: "Edited" }, new Set(["details"]), { summary: "User input" }), { summary: "User input", details: "Edited" });
  assert.deepEqual(initializeFormValues(defaults, { summary: "" }).summary, "");
});
