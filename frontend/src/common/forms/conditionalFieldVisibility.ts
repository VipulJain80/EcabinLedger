export type ConditionalVisibility = { fieldKey: string; equals: string };
export type ConditionalRequired = { fieldKey: string; equals: string };

/** Evaluate the allow-listed data-only visibility rule stored in a published form definition. */
export function isFieldVisible(field: { visible?: boolean | null; visibleWhen?: ConditionalVisibility | null }, values: Record<string, unknown>): boolean {
  if (field.visible === false) return false;
  const rule = field.visibleWhen;
  return !rule || String(values[rule.fieldKey] ?? "") === rule.equals;
}

/** Evaluate the allow-listed conditional required rule stored in a published form definition. */
export function isFieldRequired(field: { required: boolean; requiredWhen?: ConditionalRequired | null }, values: Record<string, unknown>): boolean {
  return field.required || Boolean(field.requiredWhen && String(values[field.requiredWhen.fieldKey] ?? "") === field.requiredWhen.equals);
}
