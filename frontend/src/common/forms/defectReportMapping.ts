export type DefectBinding = "DEFECT_CATEGORY" | "COMPONENT_ID" | "CONDITION_ID" | "ZONE" | "ROW_NUMBER" | "SEAT_REFERENCE" | "TITLE" | "DESCRIPTION" | "REMARKS" | "SEVERITY" | "EVIDENCE_ATTACHMENTS" | "AIRCRAFT_ID" | "REVIEW_DECISION" | "REVIEW_MAINTENANCE_TEAM" | "REVIEW_REJECTION_REASON" | "REVIEW_REMARK" | "MAINTENANCE_STATUS" | "SLA_TARGET_DATE" | "MAINTENANCE_REMARK" | "CLOSURE_DECISION" | "CLOSURE_REMARK";
export type ReportField = { key: string; label: string; type: string; required: boolean; defaultValue?: string | null; visible?: boolean | null; readOnly?: boolean | null; optionSource?: string | null; binding?: DefectBinding | null; optionFilter?: { componentFieldKey?: string | null } | null; fileRules?: { minCount?: number; extensions?: string[]; mimeTypes?: string[]; maxFileSizeBytes?: number } | null };
export function defectReportBindingIssue(fields: readonly ReportField[]): string {
  const unsupported = fields.find(field => field.type !== "SECTION" && !field.binding);
  if (unsupported) return `“${unsupported.label}” has no approved database mapping. Configure an approved backend binding before publishing or submitting this form.`;
  const hiddenRequired = fields.find(field => field.visible === false && field.required);
  if (hiddenRequired) return `Required field “${hiddenRequired.label}” cannot be hidden.`;
  const lockedRequired = fields.find(field => field.readOnly && field.required);
  if (lockedRequired) return `Required field “${lockedRequired.label}” must be editable.`;
  const bound = fields.filter(field => field.binding);
  const aircraft = bound.filter(field => field.binding === "AIRCRAFT_ID");
  if (aircraft.length !== 1 || !aircraft[0].required || aircraft[0].visible === false || aircraft[0].readOnly === true)
    return "Every defect-report form must include a visible, editable, required Aircraft Registration selector.";
  const expected: Partial<Record<DefectBinding, [string, string?]>> = {
    DEFECT_CATEGORY: ["DROPDOWN", "DEFECT_CATEGORIES"], COMPONENT_ID: ["DROPDOWN", "COMPONENTS"],
    CONDITION_ID: ["DROPDOWN", "DEFECT_CONDITIONS"], SEVERITY: ["DROPDOWN"], AIRCRAFT_ID: ["AIRCRAFT_SELECT", "AIRCRAFT"],
    ZONE: ["TEXT"], ROW_NUMBER: ["NUMBER"], SEAT_REFERENCE: ["TEXT"], TITLE: ["TEXT"], DESCRIPTION: ["TEXTAREA"], REMARKS: ["TEXTAREA"],
  };
  for (const field of bound.filter(field => field.binding !== "EVIDENCE_ATTACHMENTS")) {
    const [type, source] = expected[field.binding! ] || [];
    if ((["ZONE", "SEAT_REFERENCE", "TITLE"].includes(field.binding!) ? !["TEXT", "TEXTAREA"].includes(field.type) : field.type !== type)) return `“${field.label}” has an incompatible type for its ${field.binding!.replaceAll("_", " ").toLowerCase()} binding.`;
    if (source && field.optionSource !== source) return `“${field.label}” must use the ${source.replaceAll("_", " ").toLowerCase()} reference source.`;
  }
  const component = bound.find(field => field.binding === "COMPONENT_ID");
  const condition = bound.find(field => field.binding === "CONDITION_ID");
  if (condition && (!condition.optionFilter || condition.optionFilter.componentFieldKey !== component?.key)) return "The condition dropdown must depend on the component-bound field.";
  return "";
}

/** Normalize old Galley source tokens in editable Form Master state without changing stored history. */
export function canonicalizeLegacySeveritySource<T extends ReportField>(fields: readonly T[]): T[] {
  return fields.map(field => field.binding === "SEVERITY" && field.optionSource === "GALLEY_SEVERITIES"
    ? { ...field, optionSource: "DEFECT_SEVERITIES" } as T
    : field);
}

export function defectReportPayload(fields: readonly ReportField[], answers: Record<string, unknown>, areaCode?: string): Record<string, unknown> {
  const issue = defectReportBindingIssue(fields);
  if (issue) throw new Error(issue);
  const value = (binding: DefectBinding) => {
    const field = fields.find(item => item.binding === binding);
    if (!field) throw new Error(`This form does not collect ${binding.replaceAll("_", " ").toLowerCase()}, which is required to create a defect. Ask an administrator to add the field and publish a new version.`);
    const answer = field ? answers[field.key] : undefined;
    if (answer === undefined || answer === null || answer === "") throw new Error(`Complete ${field?.label || binding.replaceAll("_", " ")} before saving the defect.`);
    return answer;
  };
  const legacyGalleyProjection = areaCode === "GALLEY" && fields.some(field => field.binding === "REMARKS")
    && !fields.some(field => ["DEFECT_CATEGORY", "TITLE", "DESCRIPTION"].includes(field.binding || ""));
  if (legacyGalleyProjection) return { componentId: value("COMPONENT_ID"), conditionId: value("CONDITION_ID"), zone: value("ZONE"), severity: value("SEVERITY"), remarks: fields.some(field => field.binding === "REMARKS") ? answers[fields.find(field => field.binding === "REMARKS")!.key] || "" : "" };
  const description=value("DESCRIPTION");
  const remarksField=fields.find(field=>field.binding==="REMARKS");
  const remarks=remarksField?answers[remarksField.key]:undefined;
  const combinedDescription=typeof remarks==="string"&&remarks.trim()?`${description}\nRemarks: ${remarks.trim()}`:description;
  if(typeof combinedDescription==="string"&&combinedDescription.length>4000)throw new Error("Description and Remarks must fit within 4000 characters.");
  return {
    category: value("DEFECT_CATEGORY"), componentId: value("COMPONENT_ID"), conditionId: value("CONDITION_ID"),
    zone: value("ZONE"), title: value("TITLE"), description: combinedDescription, severity: value("SEVERITY"),
    ...(fields.some(field => field.binding === "ROW_NUMBER") && answers[fields.find(field => field.binding === "ROW_NUMBER")!.key] ? { rowNumber: answers[fields.find(field => field.binding === "ROW_NUMBER")!.key] } : {}),
    ...(fields.some(field => field.binding === "SEAT_REFERENCE") && answers[fields.find(field => field.binding === "SEAT_REFERENCE")!.key] ? { seatReference: answers[fields.find(field => field.binding === "SEAT_REFERENCE")!.key] } : {}),
  };
}

/** Initialize versioned defaults once without replacing supplied or subsequently edited answers. */
export function initializeFormValues<T extends ReportField>(fields: readonly T[], answers: Record<string, unknown> = {}): Record<string, unknown> {
  const values = { ...answers };
  for (const field of fields) if ((values[field.key] === undefined || values[field.key] === null) && field.defaultValue != null) values[field.key] = field.defaultValue;
  return values;
}

/** A displayed default is a prompt, not a persisted answer, until the user changes that field. */
export function answersWithoutUntouchedDefaults<T extends ReportField>(fields: readonly T[], answers: Record<string, unknown>, dirtyKeys: ReadonlySet<string>, initialAnswers: Record<string, unknown> = {}): Record<string, unknown> {
  const submitted = { ...answers };
  for (const field of fields) if (field.defaultValue != null && !dirtyKeys.has(field.key) && initialAnswers[field.key] == null) delete submitted[field.key];
  return submitted;
}

/** Designer metadata is intentionally distinct from the user-facing label. */
export function fieldIdentityMetadata(field: Pick<ReportField, "key" | "type" | "required">) {
  return { key: field.key, type: field.type.replaceAll("_", " ").toLowerCase(), required: field.required };
}
