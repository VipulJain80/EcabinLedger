const dateValue = (value) => {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
};

export function validateReportFilters(filters, values, lookupOptions = {}) {
  const errors = {};
  for (const filter of filters) {
    const value = values[filter.filterKey];
    const supplied = Object.prototype.hasOwnProperty.call(values, filter.filterKey);
    const empty = !supplied || value === "" || value === null ||
      (filter.filterType === "TEXT" && typeof value === "string" && value.trim() === "") ||
      (Array.isArray(value) && value.length === 0);
    if (empty) {
      if (filter.required) errors[filter.filterKey] = "This field is required.";
      continue;
    }
    if (filter.filterType === "TEXT" && typeof value === "string") {
      const max = Number(filter.configuration?.maxLength ?? 4000);
      if (value.length > max) errors[filter.filterKey] = `Enter no more than ${max} characters.`;
    }
    if (filter.filterType === "NUMBER") {
      const number = Number(value);
      if (!Number.isFinite(number)) errors[filter.filterKey] = "Enter a valid number.";
      else if (filter.configuration?.min != null && number < Number(filter.configuration.min)) errors[filter.filterKey] = `Enter a value of at least ${filter.configuration.min}.`;
      else if (filter.configuration?.max != null && number > Number(filter.configuration.max)) errors[filter.filterKey] = `Enter a value no greater than ${filter.configuration.max}.`;
    }
    if (filter.filterType === "DATE" && !dateValue(value)) errors[filter.filterKey] = "Enter a valid date.";
    if (filter.filterType === "SINGLE_SELECT" || filter.filterType === "LOOKUP_SINGLE") {
      if (typeof value !== "string") errors[filter.filterKey] = "Choose one of the available options.";
      else if (filter.filterType === "SINGLE_SELECT" && !(filter.configuration?.options || []).some((option) => option.value === value)) errors[filter.filterKey] = "Choose one of the available options.";
      else if (filter.filterType === "LOOKUP_SINGLE" && !(lookupOptions[filter.lookupKey] || []).some((option) => option.value === value)) errors[filter.filterKey] = "Choose one of the available options.";
    }
    if (filter.filterType === "MULTI_SELECT" || filter.filterType === "LOOKUP_MULTI") {
      if (!Array.isArray(value) || value.some((item) => typeof item !== "string") || value.length > 200) errors[filter.filterKey] = "Choose valid options.";
      else if (filter.filterType === "MULTI_SELECT" && value.some((item) => !(filter.configuration?.options || []).some((option) => option.value === item))) errors[filter.filterKey] = "Choose only available options.";
      else if (filter.filterType === "LOOKUP_MULTI" && value.some((item) => !(lookupOptions[filter.lookupKey] || []).some((option) => option.value === item))) errors[filter.filterKey] = "Choose only available options.";
    }
    if (filter.filterType === "BOOLEAN" && typeof value !== "boolean") errors[filter.filterKey] = "Choose Yes or No.";
  }

  const ranges = new Map();
  for (const filter of filters) {
    const group = filter.configuration?.rangeGroup;
    const bound = filter.configuration?.rangeBound;
    if (!group || !bound) continue;
    const item = ranges.get(group) || {};
    item[bound] = values[filter.filterKey];
    item[`${bound}Key`] = filter.filterKey;
    ranges.set(group, item);
  }
  for (const range of ranges.values()) {
    if (dateValue(range.FROM) && dateValue(range.TO) && range.FROM > range.TO) {
      errors[range.FROMKey] = "From date must be on or before To date.";
      errors[range.TOKey] = "To date must be on or after From date.";
    }
  }
  return errors;
}

export function queryKeyFor(reportId, filters, page, size, sort) {
  return ["dynamic-report-query", reportId, JSON.stringify(filters), page, size, JSON.stringify(sort)];
}

export function formatReportValue(value, type, format = {}) {
  if (value === null || value === undefined || value === "") return "—";
  if (type === "NUMBER" && Number.isFinite(Number(value))) {
    const options = {};
    if (Number.isInteger(format.decimalPlaces)) options.minimumFractionDigits = options.maximumFractionDigits = format.decimalPlaces;
    if (format.currencyCode) return new Intl.NumberFormat(undefined, { ...options, style: "currency", currency: format.currencyCode }).format(Number(value));
    return new Intl.NumberFormat(undefined, options).format(Number(value));
  }
  return String(value);
}
