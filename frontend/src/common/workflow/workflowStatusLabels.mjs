/** @typedef {{ statusCode: string, aliasName?: string | null, displayName?: string | null }} WorkflowStatusLabelRecord */

/** @param {string | null | undefined} code @param {readonly WorkflowStatusLabelRecord[]} [items] */
export function workflowStatusLabel(code, items = []) {
  const statusCode = typeof code === "string" ? code.trim() : "";
  const status = items.find((item) => item.statusCode === statusCode);
  const alias = status?.aliasName?.trim();
  if (alias) return alias;
  const displayName = status?.displayName?.trim();
  if (displayName) return displayName;
  if (!statusCode) return "Unknown status";
  return statusCode.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}
