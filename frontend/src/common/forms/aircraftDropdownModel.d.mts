export type AircraftOption = { id: string; tailNumber: string; msnNumber: string | null; aircraftType: string };
export type AircraftOptionPage = { items: AircraftOption[]; page: number; size: number; totalElements: number; totalPages: number };
export function aircraftOptionLabel(option: AircraftOption): string;
export function mergeAircraftPages(pages: AircraftOptionPage[]): AircraftOption[];
export function nextAircraftPage(lastPage: AircraftOptionPage): number | undefined;
export function aircraftOptionRequestPath(search: string, page: number): string;
export function aircraftDropdownState(state: { pending: boolean; error: boolean; options: AircraftOption[] }): "loading" | "error" | "empty" | "ready";
export function moveActiveAircraftIndex(index: number, direction: "next" | "previous" | "first" | "last", length: number): number;
