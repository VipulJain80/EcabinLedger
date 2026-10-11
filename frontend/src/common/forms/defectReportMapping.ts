export type DefectBinding = "DEFECT_CATEGORY" | "COMPONENT_ID" | "CONDITION_ID" | "ZONE" | "ROW_NUMBER" | "SEAT_REFERENCE" | "TITLE" | "DESCRIPTION" | "REMARKS" | "SEVERITY" | "EVIDENCE_ATTACHMENTS";
export type ReportField = { key: string; label: string; type: string; required: boolean; visible?: boolean | null; readOnly?: boolean | null; optionSource?: string | null; binding?: DefectBinding | null; optionFilter?: { componentFieldKey?: string | null } | null; fileRules?: { minCount?: number; extensions?: string[]; mimeTypes?: string[]; maxFileSizeBytes?: number } | null };

type PersistedDefectBinding = Exclude<DefectBinding, "EVIDENCE_ATTACHMENTS">;
const mandatory: PersistedDefectBinding[] = ["DEFECT_CATEGORY", "COMPONENT_ID", "CONDITION_ID", "ZONE", "TITLE", "DESCRIPTION", "SEVERITY"];

export function defectReportBindingIssue(fields: readonly ReportField[], areaCode?: string): string {
  const unsupported = fields.find(field => field.type !== "SECTION" && !field.binding);
  if (unsupported) return `“${unsupported.label}” has no approved database mapping. Configure an approved backend binding before publishing or submitting this form.`;
  const hiddenRequired = fields.find(field => field.visible === false && field.required);
  if (hiddenRequired) return `Required field “${hiddenRequired.label}” cannot be hidden.`;
  const lockedRequired = fields.find(field => field.readOnly && field.required);
  if (lockedRequired) return `Required field “${lockedRequired.label}” must be editable.`;
  const bound = fields.filter(field => field.binding);
  const galley = areaCode === "GALLEY";
  const required: DefectBinding[] = galley ? ["COMPONENT_ID", "CONDITION_ID", "ZONE", "SEVERITY", "EVIDENCE_ATTACHMENTS"] : mandatory;
  const missing = required.filter(binding => {
    const matches = bound.filter(field => field.binding === binding);
    return matches.length !== 1 || !matches[0].required;
  });
  if (missing.length) {
    const labels: Record<DefectBinding, string> = {
      DEFECT_CATEGORY: "Defect category", COMPONENT_ID: "Component", CONDITION_ID: "Condition", ZONE: "Location",
      ROW_NUMBER: "Row number", SEAT_REFERENCE: "Seat reference", TITLE: "Title", DESCRIPTION: "Description", REMARKS: "Remarks", SEVERITY: "Severity", EVIDENCE_ATTACHMENTS: "Evidence image upload",
    };
    return galley
      ? `Galley Area requires these fields: ${missing.map(binding => labels[binding]).join(", ")}.`
      : `This published defect-report form is missing required bindings: ${missing.map(binding => labels[binding]).join(", ")}. In Form Master, create a new version, configure these required fields, and publish it.`;
  }
  const expected: Partial<Record<DefectBinding, [string, string?]>> = {
    DEFECT_CATEGORY: ["DROPDOWN", "DEFECT_CATEGORIES"], COMPONENT_ID: ["DROPDOWN", "COMPONENTS"],
    CONDITION_ID: ["DROPDOWN", "DEFECT_CONDITIONS"], SEVERITY: ["DROPDOWN", galley ? "GALLEY_SEVERITIES" : "DEFECT_SEVERITIES"],
    ZONE: ["TEXT"], ROW_NUMBER: ["NUMBER"], SEAT_REFERENCE: ["TEXT"], TITLE: ["TEXT"], DESCRIPTION: ["TEXTAREA"], REMARKS: ["TEXTAREA"],
  };
  for (const field of bound.filter(field => field.binding !== "EVIDENCE_ATTACHMENTS")) {
    const [type, source] = expected[field.binding! ] || [];
    if ((["ZONE", "SEAT_REFERENCE", "TITLE"].includes(field.binding!) ? !["TEXT", "TEXTAREA"].includes(field.type) : field.type !== type)) return `“${field.label}” has an incompatible type for its ${field.binding!.replaceAll("_", " ").toLowerCase()} binding.`;
    if (source && field.optionSource !== source) return `“${field.label}” must use the ${source.replaceAll("_", " ").toLowerCase()} reference source.`;
  }
  if (galley) {
    const evidence = bound.find(field => field.binding === "EVIDENCE_ATTACHMENTS");
    if (!evidence || evidence.type !== "IMAGE_UPLOAD" || !evidence.fileRules || (evidence.fileRules.minCount ?? 0) < 1) return "Galley Area requires an image-upload field with at least one required image.";
    if (evidence.fileRules.extensions?.some(x => !["jpg", "jpeg", "png", "webp"].includes(x.toLowerCase())) || evidence.fileRules.mimeTypes?.some(x => !["image/jpeg", "image/png", "image/webp"].includes(x.toLowerCase()))) return "Evidence must allow JPG, JPEG, PNG and WEBP images only.";
    if (bound.some(field => ["DEFECT_CATEGORY", "TITLE", "DESCRIPTION"].includes(field.binding || ""))) return "For Galley reports, category, title and description are generated from the selected area, component, condition and optional remarks.";
    if (bound.find(field => field.binding === "REMARKS")?.required) return "Remarks must be optional for Galley Area.";
  }
  const component = bound.find(field => field.binding === "COMPONENT_ID");
  const condition = bound.find(field => field.binding === "CONDITION_ID");
  if (!condition?.optionFilter || condition.optionFilter.componentFieldKey !== component?.key) return "The condition dropdown must depend on the component-bound field.";
  return "";
}

export function defectReportPayload(fields: readonly ReportField[], answers: Record<string, unknown>, areaCode?: string): Record<string, unknown> {
  const issue = defectReportBindingIssue(fields, areaCode);
  if (issue) throw new Error(issue);
  const value = (binding: DefectBinding) => {
    const field = fields.find(item => item.binding === binding);
    const answer = field ? answers[field.key] : undefined;
    if (answer === undefined || answer === null || answer === "") throw new Error(`Complete ${field?.label || binding.replaceAll("_", " ")} before saving the defect.`);
    return answer;
  };
  if (areaCode === "GALLEY") return { componentId: value("COMPONENT_ID"), conditionId: value("CONDITION_ID"), zone: value("ZONE"), severity: value("SEVERITY"), remarks: fields.some(field => field.binding === "REMARKS") ? answers[fields.find(field => field.binding === "REMARKS")!.key] || "" : "" };
  return {
    category: value("DEFECT_CATEGORY"), componentId: value("COMPONENT_ID"), conditionId: value("CONDITION_ID"),
    zone: value("ZONE"), title: value("TITLE"), description: value("DESCRIPTION"), severity: value("SEVERITY"),
    ...(fields.some(field => field.binding === "ROW_NUMBER") && answers[fields.find(field => field.binding === "ROW_NUMBER")!.key] ? { rowNumber: answers[fields.find(field => field.binding === "ROW_NUMBER")!.key] } : {}),
    ...(fields.some(field => field.binding === "SEAT_REFERENCE") && answers[fields.find(field => field.binding === "SEAT_REFERENCE")!.key] ? { seatReference: answers[fields.find(field => field.binding === "SEAT_REFERENCE")!.key] } : {}),
  };
}

/** Designer metadata is intentionally distinct from the user-facing label. */
export function fieldIdentityMetadata(field: Pick<ReportField, "key" | "type" | "required">) {
  return { key: field.key, type: field.type.replaceAll("_", " ").toLowerCase(), required: field.required };
}
