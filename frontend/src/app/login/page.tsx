"use client";

import { CSSProperties, FormEvent, useEffect, useState, useSyncExternalStore } from "react";
import { applicationBasePath, clearLocalSession, getLocalSession, saveLocalSession } from "@/common/auth/localSession";

const API = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api/v1";
const subscribeClient = () => () => {};
const localAuthAvailable = () => process.env.NODE_ENV === "development" && (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1");

export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const localAvailable = useSyncExternalStore(subscribeClient, localAuthAvailable, () => false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const local = getLocalSession();
      const gatewayToken = await window.eCabinAuth?.getAccessToken().catch(() => undefined);
      const token = local?.accessToken || gatewayToken;
      if (!token) return;
      try {
        const response = await fetch(`${API}/me`, { headers: { Authorization: `Bearer ${token}` }, credentials: "omit", cache: "no-store" });
        if (response.ok && !cancelled) window.location.replace(`${applicationBasePath}/loginhome`);
        else if (local) clearLocalSession();
      } catch { /* Keep the login page available if the API is temporarily unreachable. */ }
    })();
    return () => { cancelled = true; };
  }, []);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    if (!localAvailable) {
      setError("Sign-in is provided by your organization’s enterprise access gateway. Open this page through that gateway.");
      return;
    }
    setLoading(true);
    try {
      const response = await fetch("http://127.0.0.1:8080/api/dev/token", {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ email: email.trim().toLowerCase() }), cache: "no-store",
      });
      if (!response.ok) {
        setError(response.status === 403
          ? "Choose a configured local test identity with an active demo membership."
          : "Could not sign in. Check that the backend is running with the local demo profile.");
        return;
      }
      const issued: { accessToken: string; expiresAt: string } = await response.json();
      saveLocalSession(email.trim().toLowerCase(), issued.accessToken, issued.expiresAt);
      window.location.replace(`${applicationBasePath}/loginhome`);
    } catch {
      setError("Could not connect to the backend. Make sure it is running, then try again.");
    } finally { setLoading(false); }
  }

  return <main className="login-shell" style={{ "--login-background-image": `url("${applicationBasePath}/images/login-background.svg")` } as CSSProperties}>
    <header className="login-airline-brand" aria-label="Skyways Service"><span>SKYWAYS</span><strong>Service</strong></header>
    <section className="login-card" aria-labelledby="login-title">
      <div className="login-user-icon" aria-hidden="true"><svg viewBox="0 0 56 56"><circle cx="28" cy="17" r="9"/><path d="M10 43c0-8 7-12 18-12s18 4 18 12c0 5-7 7-18 7s-18-2-18-7Z"/></svg></div>
      <h1 id="login-title">LOGIN</h1>
      {localAvailable ? <>
        <form className="login-form" onSubmit={signIn}>
          <label className="login-email-label" htmlFor="login-email">Work email</label>
          <input id="login-email" type="email" autoComplete="email" autoFocus required list="local-test-identities" placeholder="Enter or select a local test email" value={email} onChange={(event) => setEmail(event.target.value)} />
          <datalist id="local-test-identities">
            <option value="abc@gmail.com" /><option value="admin.test@ecabin.local" /><option value="auditor.test@ecabin.local" />
            <option value="reviewer.test@ecabin.local" /><option value="maintenance.test@ecabin.local" />
          </datalist>
          {error && <p className="login-error" role="alert">{error}</p>}
          <button type="submit" className="login-submit" disabled={loading}>{loading ? "Signing in…" : "Continue with email"}<span aria-hidden="true">→</span></button>
        </form>
        <p className="login-help">Local test identities: <b>abc@gmail.com</b>, admin.test@ecabin.local, auditor.test@ecabin.local, reviewer.test@ecabin.local, maintenance.test@ecabin.local</p>
      </> : <p className="login-help" role={error ? "alert" : undefined}>{error || "Continue through your organization’s enterprise access gateway to sign in."}</p>}
    </section>
    <p className="login-footer">© 2026 Skyways Service. All Rights Reserved.</p>
  </main>;
}
