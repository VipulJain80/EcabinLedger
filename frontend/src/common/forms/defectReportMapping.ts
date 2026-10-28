export type DefectBinding = "DEFECT_CATEGORY" | "COMPONENT_ID" | "CONDITION_ID" | "ZONE" | "ROW_NUMBER" | "SEAT_REFERENCE" | "TITLE" | "DESCRIPTION" | "REMARKS" | "SEVERITY" | "EVIDENCE_ATTACHMENTS" | "AIRCRAFT_ID" | "REVIEW_DECISION" | "REVIEW_MAINTENANCE_TEAM" | "REVIEW_REJECTION_REASON" | "REVIEW_REMARK" | "MAINTENANCE_STATUS" | "SLA_TARGET_DATE" | "MAINTENANCE_REMARK" | "CLOSURE_DECISION" | "CLOSURE_REMARK";
export type ReportField = { key: string; label: string; type: string; required: boolean; defaultValue?: string | null; visible?: boolean | null; readOnly?: boolean | null; visibleWhen?: { fieldKey: string; equals: string } | null; requiredWhen?: { fieldKey: string; equals: string } | null; optionSource?: string | null; binding?: DefectBinding | null; optionFilter?: { componentFieldKey?: string | null } | null; fileRules?: { minCount?: number; maxCount?: number; extensions?: string[]; mimeTypes?: string[]; maxFileSizeBytes?: number } | null };
export function defectReportBindingIssue(fields: readonly ReportField[]): string {
  const hiddenRequired = fields.find(field => field.visible === false && field.required);
  if (hiddenRequired) return `Required field “${hiddenRequired.label}” cannot be hidden.`;
  const lockedRequired = fields.find(field => field.readOnly && field.required);
  if (lockedRequired) return `Required field “${lockedRequired.label}” must be editable.`;
  const bound = fields.filter(field => field.binding);
  const aircraft = bound.filter(field => field.binding === "AIRCRAFT_ID");
  if (aircraft.length !== 1 || !aircraft[0].required || aircraft[0].visible === false || aircraft[0].readOnly === true || aircraft[0].visibleWhen || aircraft[0].requiredWhen)
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

/** Client-side usability check against the selected version's field schema; the backend remains authoritative. */
export function configuredAnswerIssue(fields: readonly ReportField[], answers: Record<string, unknown>): string {
  for (const field of fields) {
    if (field.type === "SECTION" || field.type === "AIRCRAFT_SELECT" || field.visible === false) continue;
    if (field.visibleWhen && String(answers[field.visibleWhen.fieldKey] ?? "") !== field.visibleWhen.equals) continue;
    const value = answers[field.key];
    if (field.type === "FILE_UPLOAD" || field.type === "IMAGE_UPLOAD") {
      const minimum = field.required ? Math.max(1, field.fileRules?.minCount ?? 0) : field.fileRules?.minCount ?? 0;
      const count = Array.isArray(value) ? value.length : 0;
      if (count < minimum) return `Attach at least ${minimum} file(s) for ${field.label}.`;
      if (field.fileRules?.maxCount != null && count > field.fileRules.maxCount) return `Select no more than ${field.fileRules.maxCount} files for ${field.label}.`;
      continue;
    }
    const required = field.required || Boolean(field.requiredWhen && String(answers[field.requiredWhen.fieldKey] ?? "") === field.requiredWhen.equals);
    if (!required) continue;
    const empty = value == null || (typeof value === "string" && value.trim() === "") || (Array.isArray(value) && value.length === 0) || (field.type === "CHECKBOX" && value !== true);
    if (empty) return `Complete ${field.label} before submitting the form.`;
  }
  return "";
}

/** Normalize old Galley source tokens in editable Form Master state without changing stored history. */
export function canonicalizeLegacySeveritySource<T extends ReportField>(fields: readonly T[]): T[] {
  return fields.map(field => field.binding === "SEVERITY" && field.optionSource === "GALLEY_SEVERITIES"
    ? { ...field, optionSource: "DEFECT_SEVERITIES" } as T
    : field);
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
