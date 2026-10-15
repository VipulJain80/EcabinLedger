"use client";

import { useEffect } from "react";
import { applicationBasePath, clearLocalSession, getApplicationAccessToken } from "@/common/auth/localSession";

const API = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api/v1";

export default function LogoutPage() {
  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const token = await getApplicationAccessToken();
        if (token) await fetch(`${API}/auth/logout`, { method: "POST", headers: { Authorization: `Bearer ${token}` }, credentials: "omit", cache: "no-store", keepalive: true });
      } catch {
        // Local credentials must be cleared even when event recording or the network is unavailable.
      } finally {
        clearLocalSession();
        if (active) window.location.replace(`${applicationBasePath}/login`);
      }
    })();
    return () => { active = false; };
  }, []);
  return <main className="authorization-gate" role="status" aria-live="polite"><section><h1>Signing out…</h1><p>Clearing this browser session.</p></section></main>;
}
