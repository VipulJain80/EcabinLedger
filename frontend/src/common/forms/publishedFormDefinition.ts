export type PublishedFieldShape = { key: string; label: string; type: string; required: boolean; order: number; optionSource?: string | null; helpText?: string | null };

export const supportedPublishedFieldTypes = new Set([
  "TEXT", "TEXTAREA", "NUMBER", "DROPDOWN", "MULTI_SELECT", "DATE", "CHECKBOX", "FILE_UPLOAD", "IMAGE_UPLOAD", "SECTION",
]);

export function orderedPublishedFields<T extends { order: number }>(fields: readonly T[]): T[] {
  return [...fields].sort((left, right) => left.order - right.order);
}

export function unsupportedPublishedField<T extends { type: string }>(fields: readonly T[]): T | undefined {
  return fields.find(field => !supportedPublishedFieldTypes.has(field.type));
}

export function isPublishedFormForArea(form: { areaId: string }, areaId: string): boolean {
  return form.areaId === areaId;
}
