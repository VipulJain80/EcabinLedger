"use client";

export function accessFailureMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : "";
  if (/session (has )?expired|sign in again/i.test(message)) return "Your session has expired. Please sign in again.";
  if (/\b(401|403)\b|unauthorized|not authorized|forbidden|access denied/i.test(message)) return "Access denied";
  if (/failed to fetch|networkerror|network request failed|load failed|fetch failed/i.test(message)) return "Unable to connect. Please check your connection and try again.";
  if (/access unavailable|could not verify|service unavailable|\b5\d\d\b/i.test(message)) return "Access unavailable";
  return "Something went wrong. Please try again.";
}

export default function AccessStatePage({ message, loading = false, applicationName, actionLabel, onAction }: {
  message?: string;
  loading?: boolean;
  applicationName?: string | null;
  actionLabel?: string;
  onAction?: () => void;
}) {
  const heading = applicationName?.trim() ? `${applicationName.trim()} ACCESS` : "APPLICATION ACCESS";
  return <main className="grid min-h-[100svh] place-items-center bg-[#eaf1ff] p-6 font-[var(--sans)] text-[#000000]" role={loading ? "status" : "alert"} aria-live="polite">
    <section className="w-[min(520px,100%)] rounded-2xl border border-[#e1e8f2] bg-white p-8 text-center shadow-[0_14px_32px_#263d5a1c]">
      <h1 className="eyebrow my-0 text-2xl text-[#000000]">{heading}</h1>
      <p className="mb-5 mt-[10px] leading-[1.6] text-[#000000]">{loading ? "Verifying access…" : message || "Access unavailable"}</p>
      {!loading && actionLabel && onAction && <button type="button" className="button-primary" onClick={onAction}>{actionLabel}</button>}
    </section>
  </main>;
}
