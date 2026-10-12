export type PublishedFieldShape = { key: string; label: string; type: string; required: boolean; order: number; optionSource?: string | null; helpText?: string | null };

export const supportedPublishedFieldTypes = new Set([
  "TEXT", "TEXTAREA", "NUMBER", "DROPDOWN", "MULTI_SELECT", "DATE", "CHECKBOX", "FILE_UPLOAD", "IMAGE_UPLOAD", "AIRCRAFT_SELECT", "SECTION",
]);

export function orderedPublishedFields<T extends { order: number }>(fields: readonly T[]): T[] {
  return [...fields].sort((left, right) => left.order - right.order);
}

/** Operational rendering keeps configured evidence together at the end, preserving all other field order. */
export function orderedOperationalFields<T extends { order: number; binding?: string | null }>(fields: readonly T[]): T[] {
  const ordered = orderedPublishedFields(fields);
  const regular = ordered.filter(field => field.binding !== "EVIDENCE_ATTACHMENTS");
  const evidence = ordered.filter(field => field.binding === "EVIDENCE_ATTACHMENTS");
  return [...regular, ...evidence];
}

export function unsupportedPublishedField<T extends { type: string }>(fields: readonly T[]): T | undefined {
  return fields.find(field => !supportedPublishedFieldTypes.has(field.type));
}

export function isPublishedFormForArea(form: { areaId: string }, areaId: string): boolean {
  return form.areaId === areaId;
}
