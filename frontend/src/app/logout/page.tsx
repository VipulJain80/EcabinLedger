"use client";

import { useEffect } from "react";
import { applicationBasePath, endApplicationSession, getApplicationAccessToken } from "@/common/auth/localSession";

const API = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api/v1";

export default function LogoutPage() {
  useEffect(() => {
    let active = true;
    void (async () => {
      let token: string | undefined;
      try {
        token = await getApplicationAccessToken();
        if (token) await fetch(`${API}/auth/logout`, { method: "POST", headers: { Authorization: `Bearer ${token}` }, credentials: "omit", cache: "no-store", keepalive: true });
      } catch {
        // Local credentials must be cleared even when event recording or the network is unavailable.
      } finally {
        await endApplicationSession(token, "sign-out");
        if (active) window.location.replace(`${applicationBasePath}/login`);
      }
    })();
    return () => { active = false; };
  }, []);
  return <main className="grid min-h-[100svh] place-items-center bg-[#eaf1ff] p-6 font-[var(--sans)] text-[#000000]" role="status" aria-live="polite"><section className="w-[min(520px,100%)] rounded-2xl border border-[#e1e8f2] bg-white p-8 text-center shadow-[0_14px_32px_#263d5a1c]"><h1 className="my-[10px] text-2xl text-[#000000]">Signing out…</h1><p className="mb-5 mt-0 leading-[1.6] text-[#000000]">Clearing this browser session.</p></section></main>;
}
