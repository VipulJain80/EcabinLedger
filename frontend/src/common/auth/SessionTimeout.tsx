"use client";

import { useEffect } from "react";
import { applicationBasePath, endApplicationSession, getApplicationAccessToken, SESSION_LOGOUT_EVENT_KEY, tokenFingerprint } from "@/common/auth/localSession";
import { INACTIVITY_LIMIT_MS, sessionTimeoutReason, tokenDeadlineFromJwt } from "@/common/auth/sessionTimeoutModel.mjs";

const ACTIVITY_KEY_PREFIX = "ecabin.auth.last-activity.";

export default function SessionTimeout({ active }: { active: boolean }) {
  useEffect(() => {
    if (!active) return;
    let disposed = false;
    let ending = false;
    let token: string | undefined;
    let deadline = 0;
    let lastActivity = 0;
    let lastPersistedActivity = 0;
    let timer: number | undefined;
    let activityKey = "";

    const finish = async (reason: "inactivity" | "expired" | "other-tab", broadcast = true) => {
      if (ending || disposed) return;
      ending = true;
      if (timer !== undefined) window.clearTimeout(timer);
      await endApplicationSession(token, reason, broadcast);
      if (!disposed) window.location.replace(`${applicationBasePath}/login`);
    };

    const evaluate = () => {
      if (!deadline) return;
      const reason = sessionTimeoutReason(Date.now(), lastActivity, deadline);
      if (reason) {
        void finish(reason);
        return;
      }
      if (timer !== undefined) window.clearTimeout(timer);
      timer = window.setTimeout(evaluate, Math.max(1, Math.min(deadline - Date.now(), lastActivity + INACTIVITY_LIMIT_MS - Date.now())));
    };

    const onActivity = () => {
      if (!deadline) return;
      const now = Date.now();
      const reason = sessionTimeoutReason(now, lastActivity, deadline);
      if (reason) {
        void finish(reason);
        return;
      }
      lastActivity = now;
      if (now - lastPersistedActivity >= 1000) {
        lastPersistedActivity = now;
        try { window.localStorage.setItem(activityKey, String(now)); } catch { /* This tab keeps its own in-memory idle timer. */ }
      }
      evaluate();
    };

    const onStorage = (event: StorageEvent) => {
      if (event.key === SESSION_LOGOUT_EVENT_KEY && event.newValue) {
        void finish("other-tab", false);
        return;
      }
      if (event.key === activityKey && event.newValue) {
        const sharedActivity = Number(event.newValue);
        if (Number.isFinite(sharedActivity) && sharedActivity > lastActivity) {
          lastActivity = sharedActivity;
          evaluate();
        }
      }
    };

    const checkWhenVisible = () => { if (document.visibilityState === "visible") evaluate(); };

    const start = async () => {
      token = await getApplicationAccessToken();
      if (disposed) return;
      if (!token) {
        await finish("expired");
        return;
      }
      try {
        deadline = tokenDeadlineFromJwt(token);
        activityKey = `${ACTIVITY_KEY_PREFIX}${await tokenFingerprint(token)}`;
      } catch {
        await finish("expired");
        return;
      }
      if (disposed) return;
      let storedActivity = Number.NaN;
      try { storedActivity = Number(window.localStorage.getItem(activityKey)); } catch { /* Continue with this tab's activity clock. */ }
      lastActivity = Number.isFinite(storedActivity) && storedActivity > 0 ? storedActivity : Date.now();
      evaluate();
    };

    const events: Array<keyof DocumentEventMap> = ["mousemove", "click", "keydown", "scroll", "touchstart", "pointerdown"];
    events.forEach((name) => document.addEventListener(name, onActivity, { passive: true }));
    window.addEventListener("storage", onStorage);
    document.addEventListener("visibilitychange", checkWhenVisible);
    window.addEventListener("focus", checkWhenVisible);
    void start();

    return () => {
      disposed = true;
      if (timer !== undefined) window.clearTimeout(timer);
      events.forEach((name) => document.removeEventListener(name, onActivity));
      window.removeEventListener("storage", onStorage);
      document.removeEventListener("visibilitychange", checkWhenVisible);
      window.removeEventListener("focus", checkWhenVisible);
    };
  }, [active]);

  return null;
}
