const SESSION_KEY = "ecabin.local-auth.session";
const BLOCKED_TOKEN_KEY = "ecabin.auth.blocked-token";
const SESSION_NOTICE_KEY = "ecabin.auth.logout-notice";
export const SESSION_LOGOUT_EVENT_KEY = "ecabin.auth.logout-event";

let cachedGatewayToken: string | undefined;
let gatewayTokenRequest: Promise<string | undefined> | undefined;
let sessionEndPromise: Promise<void> | undefined;

declare global { interface Window { eCabinAuth?: { getAccessToken: () => Promise<string> }; } }

type LocalSession = { email: string; accessToken: string; expiresAt: number };

export function getLocalSession(): LocalSession | null {
  if (typeof window === "undefined" || process.env.NODE_ENV !== "development") return null;
  try {
    const raw = window.sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Partial<LocalSession>;
    if (typeof session.email !== "string" || typeof session.accessToken !== "string" || typeof session.expiresAt !== "number" || session.expiresAt <= Date.now()) {
      clearLocalSession();
      return null;
    }
    return session as LocalSession;
  } catch {
    clearLocalSession();
    return null;
  }
}

export function saveLocalSession(email: string, accessToken: string, expiresAt: string): void {
  if (process.env.NODE_ENV !== "development") throw new Error("Local demo authentication is available only in development builds.");
  const expiry = Date.parse(expiresAt);
  if (!Number.isFinite(expiry) || expiry <= Date.now()) throw new Error("The local demo session has expired. Sign in again.");
  window.sessionStorage.setItem(SESSION_KEY, JSON.stringify({ email, accessToken, expiresAt: expiry } satisfies LocalSession));
  cachedGatewayToken = undefined;
  gatewayTokenRequest = undefined;
  sessionEndPromise = undefined;
}

export function clearLocalSession(): void {
  try { if (typeof window !== "undefined") window.sessionStorage.removeItem(SESSION_KEY); }
  catch { /* A disabled browser storage area must not prevent redirecting to sign-in. */ }
}

export async function tokenFingerprint(token: string): Promise<string> {
  const digest = await window.crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function endApplicationSession(token: string | undefined, reason: "inactivity" | "expired" | "other-tab" | "sign-out", broadcast = true): Promise<void> {
  sessionEndPromise ??= (async () => {
    clearLocalSession();
    cachedGatewayToken = undefined;
    gatewayTokenRequest = undefined;
    try {
      if (token) window.localStorage.setItem(BLOCKED_TOKEN_KEY, await tokenFingerprint(token));
    } catch {
      // Authentication still ends in this tab if Web Crypto or local storage is unavailable.
    }
    try {
      window.sessionStorage.setItem(SESSION_NOTICE_KEY, reason);
      if (broadcast) window.localStorage.setItem(SESSION_LOGOUT_EVENT_KEY, JSON.stringify({ at: Date.now(), reason }));
    } catch {
      // Authentication still ends in this tab if browser storage is unavailable.
    }
  })();
  return sessionEndPromise;
}

export function consumeSessionNotice(): string | null {
  if (typeof window === "undefined") return null;
  try {
    const notice = window.sessionStorage.getItem(SESSION_NOTICE_KEY);
    window.sessionStorage.removeItem(SESSION_NOTICE_KEY);
    return notice;
  } catch { return null; }
}

export async function getApplicationAccessToken(): Promise<string | undefined> {
  if (typeof window === "undefined") return undefined;
  let gatewayToken = cachedGatewayToken;
  if (!gatewayToken) {
    gatewayTokenRequest ??= (async () => {
      try { return await window.eCabinAuth?.getAccessToken(); }
      catch { return undefined; }
    })();
    gatewayToken = await gatewayTokenRequest;
    if (gatewayToken) cachedGatewayToken = gatewayToken;
    else gatewayTokenRequest = undefined;
  }
  const token = gatewayToken || getLocalSession()?.accessToken;
  if (!token) return undefined;
  try {
    const fingerprint = await tokenFingerprint(token);
    const blockedFingerprint = window.localStorage.getItem(BLOCKED_TOKEN_KEY);
    if (blockedFingerprint === fingerprint) return undefined;
    if (blockedFingerprint) window.localStorage.removeItem(BLOCKED_TOKEN_KEY);
    sessionEndPromise = undefined;
  } catch {
    // The authenticated API still enforces JWT expiry if Web Crypto or storage is unavailable.
  }
  return token;
}

export const applicationBasePath = process.env.NEXT_PUBLIC_APP_BASE_PATH || "/ecabin";
