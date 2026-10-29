export type ConditionalVisibility = { fieldKey: string; equals: string };
export type ConditionalRequired = { fieldKey: string; equals: string };
export type ConditionalField = {
  key: string;
  type?: string;
  visible?: boolean | null;
  readOnly?: boolean | null;
  required?: boolean;
  visibleWhen?: ConditionalVisibility | null;
  requiredWhen?: ConditionalRequired | null;
};
export function isFieldVisible(field: ConditionalField, values: Record<string, unknown>, fields?: readonly ConditionalField[], evaluating?: ReadonlySet<string>): boolean;
export function isFieldRequired(field: ConditionalField, values: Record<string, unknown>, fields?: readonly ConditionalField[]): boolean;
export function conditionalVisibilityIssue(fields: readonly ConditionalField[]): string;
