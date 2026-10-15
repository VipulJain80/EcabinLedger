export function normalizeAppBasePath(value = "/ecabin") {
  if (typeof value !== "string" || !/^\/[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error("APP_BASE_PATH must be one safe path segment, for example /ecabin or /fleetcare.");
  }
  return value;
}
