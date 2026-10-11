export type AreaPublishedForm = { id: string; versionId: string };

/** Keep the list-page form picker and its tests aligned on one/some/none behavior. */
export function areaFormSelection(forms: readonly AreaPublishedForm[]): "none" | "single" | "multiple" {
  if (forms.length === 0) return "none";
  return forms.length === 1 ? "single" : "multiple";
}

/** Return the exact API-provided published version only when the area has one choice. */
export function singleAreaForm<T extends AreaPublishedForm>(forms: readonly T[]): T | null {
  return forms.length === 1 ? forms[0] : null;
}

export function shouldShowCabinAreaChooser(hasAircraft: boolean, canReport: boolean): boolean {
  return hasAircraft && canReport;
}

export function selectableCabinAreas<T extends { id?: string }>(areas: readonly T[]): T[] {
  return areas.filter((area) => Boolean(area.id));
}
