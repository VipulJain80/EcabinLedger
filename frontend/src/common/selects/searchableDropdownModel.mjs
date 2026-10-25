export const SEARCHABLE_OPTION_THRESHOLD = 10;

export function shouldUseSearchableSelect(optionCount, maximumCompleteCount = Number.POSITIVE_INFINITY) {
  return Number.isFinite(optionCount) && optionCount >= SEARCHABLE_OPTION_THRESHOLD && optionCount < maximumCompleteCount;
}

export function filterDropdownOptions(options, search) {
  const normalized = String(search || "").trim().toLocaleLowerCase();
  if (!normalized) return options;
  return options.filter(option => `${option.label} ${option.description || ""}`.toLocaleLowerCase().includes(normalized));
}

export function nextSelectedValues(current, value) {
  return current.includes(value) ? current.filter(item => item !== value) : [...current, value];
}
