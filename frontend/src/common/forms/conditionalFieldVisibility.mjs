function answerMatches(value, expected) {
  if (value == null) return false;
  if (Array.isArray(value)) return value.some(item => item != null && String(item) === expected);
  return String(value) === expected;
}

/** Evaluate one allow-listed equality condition; hidden sources fail closed. */
export function isFieldVisible(field, values, fields = [field], evaluating = new Set()) {
  if (field.visible === false || evaluating.has(field.key)) return false;
  const path = new Set(evaluating);
  path.add(field.key);
  const rule = field.visibleWhen;
  if (!rule) return true;
  const source = fields.find(candidate => candidate.key === rule.fieldKey);
  return Boolean(source && isFieldVisible(source, values, fields, path) && answerMatches(values[rule.fieldKey], rule.equals));
}

/** Evaluate the existing allow-listed conditional required rule. */
export function isFieldRequired(field, values, fields = [field]) {
  const rule = field.requiredWhen;
  const source = rule && fields.find(candidate => candidate.key === rule.fieldKey);
  return Boolean(field.required || (rule && source && isFieldVisible(source, values, fields) && answerMatches(values[rule.fieldKey], rule.equals)));
}

/** Validate one-source exact equality rules; reject missing, self and cyclic references. */
export function conditionalVisibilityIssue(fields) {
  const byKey = new Map(fields.map(field => [field.key, field]));
  for (const field of fields) {
    const rule = field.visibleWhen;
    if (!rule) continue;
    const source = byKey.get(rule.fieldKey);
    if (!source || source.key === field.key || source.type === "SECTION" || source.type === "AIRCRAFT_SELECT"
        || source.readOnly === true || field.type === "SECTION" || typeof rule.equals !== "string" || !rule.equals.trim()) {
      return `“${field.key}” must reference another configured answer and an exact, non-empty value.`;
    }
  }
  const visited = new Set();
  const active = new Set();
  const visit = field => {
    if (visited.has(field.key)) return true;
    if (active.has(field.key)) return false;
    active.add(field.key);
    const source = field.visibleWhen ? byKey.get(field.visibleWhen.fieldKey) : undefined;
    if (source && !visit(source)) return false;
    active.delete(field.key);
    visited.add(field.key);
    return true;
  };
  for (const field of fields) if (!visit(field)) return "Conditional visibility rules cannot contain a cycle.";
  return "";
}
