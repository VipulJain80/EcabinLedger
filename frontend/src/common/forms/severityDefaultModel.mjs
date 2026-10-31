export function selectInitialSeverityOption(options) {
  return options.find(option => option.isDefault === true) ?? options[0] ?? null;
}

export function shouldInitializeSeverityDefault({
  preparationMode,
  purpose,
  hasDefectId,
  hasInitialAnswer,
  isDirty,
  currentValue,
}) {
  return preparationMode === true
    && purpose === "DEFECT_REPORT"
    && !hasDefectId
    && !hasInitialAnswer
    && !isDirty
    && (currentValue == null || currentValue === "");
}
