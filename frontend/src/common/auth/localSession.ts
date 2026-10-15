const SESSION_KEY = "ecabin.local-auth.session";

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
}

export function clearLocalSession(): void {
  if (typeof window !== "undefined") window.sessionStorage.removeItem(SESSION_KEY);
}

export async function getApplicationAccessToken(): Promise<string | undefined> {
  let gatewayToken: string | undefined;
  try { gatewayToken = await window.eCabinAuth?.getAccessToken(); }
  catch { return undefined; }
  if (gatewayToken) return gatewayToken;
  return getLocalSession()?.accessToken;
}

export const applicationBasePath = process.env.NEXT_PUBLIC_APP_BASE_PATH || "/ecabin";
