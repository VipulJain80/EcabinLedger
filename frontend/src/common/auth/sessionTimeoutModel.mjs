export const MAX_TOKEN_AGE_MS = 30 * 60 * 1000;
export const INACTIVITY_LIMIT_MS = 10 * 60 * 1000;

export function tokenDeadlineFromJwt(token) {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("Invalid access token.");
  const encoded = parts[1].replaceAll("-", "+").replaceAll("_", "/");
  const payload = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, "=")));
  const issuedAt = Number(payload.iat) * 1000;
  const expiresAt = Number(payload.exp) * 1000;
  if (!Number.isFinite(issuedAt) || !Number.isFinite(expiresAt) || expiresAt <= issuedAt) {
    throw new Error("Invalid access token lifetime.");
  }
  return Math.min(expiresAt, issuedAt + MAX_TOKEN_AGE_MS);
}

export function sessionTimeoutReason(now, lastActivity, tokenDeadline) {
  if (now - lastActivity >= INACTIVITY_LIMIT_MS) return "inactivity";
  if (now >= tokenDeadline) return "expired";
  return null;
}
