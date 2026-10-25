export function aircraftOptionLabel(option) {
  return `${option.tailNumber} — MSN ${option.msnNumber || "—"}`;
}

export function mergeAircraftPages(pages) {
  const seen = new Set();
  return pages.flatMap(page => page.items).filter(option => {
    if (seen.has(option.id)) return false;
    seen.add(option.id);
    return true;
  });
}

export function nextAircraftPage(lastPage) {
  return lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined;
}

export function aircraftOptionRequestPath(search, page) {
  const params = new URLSearchParams({ search, page: String(page), size: "20" });
  return `/aircraft/options?${params}`;
}

export function aircraftDropdownState({ pending, error, options }) {
  if (pending) return "loading";
  if (error && options.length === 0) return "error";
  if (options.length === 0) return "empty";
  return "ready";
}

export function moveActiveAircraftIndex(index, direction, length) {
  if (length === 0) return -1;
  if (direction === "first") return 0;
  if (direction === "last") return length - 1;
  if (index < 0) return direction === "previous" ? length - 1 : 0;
  return (index + (direction === "previous" ? -1 : 1) + length) % length;
}
