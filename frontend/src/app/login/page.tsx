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

  return <main className="login-shell relative isolate grid min-h-[100svh] place-items-center overflow-hidden px-6 pt-[90px] pb-[76px] text-white [background-color:#283c43] [background-image:linear-gradient(180deg,#10242b22_0%,#101d2550_100%),var(--login-background-image)] bg-cover bg-center bg-no-repeat [font-family:var(--sans)] max-[700px]:px-[18px] max-[700px]:pt-[105px] max-[700px]:pb-[78px] max-[700px]:bg-[position:57%_center]" style={{ "--login-background-image": `url("${applicationBasePath}/images/login-background.svg")` } as CSSProperties}>
    <header className="login-airline-brand absolute top-[12%] left-1/2 grid -translate-x-1/2 text-center leading-[.88] text-[#ed1b35] font-extrabold tracking-[.8px] [text-shadow:0_1px_12px_#37221b30] max-[700px]:top-[8%]" aria-label="Skyways Service"><span className="text-[clamp(15px,1.15vw,23px)]">SKYWAYS</span><strong className="text-[clamp(29px,2.35vw,46px)] font-medium tracking-[-2px] text-[#f15a24]">Service</strong></header>
    <section className="login-card relative min-h-[348px] w-[min(772px,100%)] rounded-[14px] border-2 border-[#ffffffd9] bg-[#ffffff16] px-[clamp(26px,3vw,56px)] pt-[91px] pb-8 text-center shadow-[0_22px_55px_#09161928] backdrop-blur-[5px] max-[700px]:min-h-0 max-[700px]:rounded-xl max-[700px]:px-[22px] max-[700px]:pt-[76px] max-[700px]:pb-6" aria-labelledby="login-title">
      <div className="absolute top-[-47px] left-1/2 grid size-28 -translate-x-1/2 place-items-center rounded-full bg-white shadow-[0_5px_10px_#1115] max-[700px]:top-[-39px] max-[700px]:size-[92px]" aria-hidden="true"><svg className="size-[61px] fill-none stroke-[#ef3338] stroke-[1.8] [stroke-linecap:round] [stroke-linejoin:round] max-[700px]:size-[52px]" viewBox="0 0 56 56"><circle cx="28" cy="17" r="9"/><path d="M10 43c0-8 7-12 18-12s18 4 18 12c0 5-7 7-18 7s-18-2-18-7Z"/></svg></div>
      <h1 className="mt-1 mb-[43px] text-[clamp(30px,2.55vw,50px)] leading-[1.05] font-bold tracking-[.2px] text-white max-[700px]:mt-0 max-[700px]:mb-[27px]" id="login-title">LOGIN</h1>
      {localAvailable ? <>
        <form className="login-form mx-auto w-[min(100%,658px)]" onSubmit={signIn}>
          <label className="sr-only" htmlFor="login-email">Work email</label>
          <input className="block h-[55px] w-full rounded-[7px] border border-[#ffffffa6] bg-[#ffffffed] px-[18px] text-base text-[#000000] shadow-[0_2px_8px_#161f2226] outline-none placeholder:text-[#000000] focus:border-white focus:shadow-[0_0_0_3px_#ffffff50] max-[700px]:h-[51px] max-[700px]:text-[15px]" id="login-email" type="email" autoComplete="email" autoFocus required list="local-test-identities" placeholder="Enter or select a local test email" value={email} onChange={(event) => setEmail(event.target.value)} />
          <datalist id="local-test-identities">
            <option value="abc@gmail.com" /><option value="admin.test@ecabin.local" /><option value="auditor.test@ecabin.local" />
            <option value="reviewer.test@ecabin.local" /><option value="maintenance.test@ecabin.local" />
          </datalist>
          {error && <p className="mt-[9px] rounded-[5px] bg-[#8c1d22bd] px-[10px] py-2 text-left text-[13px] leading-[1.45] text-white" role="alert">{error}</p>}
          <button type="submit" className="button-primary login-submit relative flex w-full items-center justify-center gap-[10px] disabled:cursor-wait motion-reduce:transition-none">{loading ? "Signing in…" : "Continue with email"}<span className="leading-none" aria-hidden="true">→</span></button>
        </form>
        <p className="mt-[17px] text-[13px] text-[#ffffffde] [text-shadow:0_1px_4px_#0006] max-[700px]:text-xs">Local test identities: <b className="font-bold">abc@gmail.com</b>, admin.test@ecabin.local, auditor.test@ecabin.local, reviewer.test@ecabin.local, maintenance.test@ecabin.local</p>
      </> : <p className="mt-[17px] text-[13px] text-[#ffffffde] [text-shadow:0_1px_4px_#0006] max-[700px]:text-xs" role={error ? "alert" : undefined}>{error || "Continue through your organization’s enterprise access gateway to sign in."}</p>}
    </section>
    <p className="login-footer absolute right-5 bottom-[18px] left-5 m-0 text-center text-[clamp(12px,1.15vw,18px)] font-normal text-[#ffffffed] [text-shadow:0_1px_5px_#0009] max-[700px]:bottom-[15px]">© 2026 Skyways Service. All Rights Reserved.</p>
  </main>;
}
