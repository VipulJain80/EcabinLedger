export const SEARCHABLE_OPTION_THRESHOLD: number;
export function shouldUseSearchableSelect(optionCount: number, maximumCompleteCount?: number): boolean;
export function filterDropdownOptions<T extends { label: string; description?: string }>(options: T[], search: string): T[];
export function nextSelectedValues(current: string[], value: string): string[];
