"use client";

import { ReactNode, useEffect, useRef } from "react";
import { createPortal } from "react-dom";

/** Shared modal host: escapes app-shell clipping/stacking, traps focus, and restores page state. */
export default function DialogPortal({ children, onClose, labelledBy, className = "modal", closeOnEscape = true }: {
  children: ReactNode; onClose: () => void; labelledBy: string; className?: string; closeOnEscape?: boolean;
}) {
  const host = typeof document === "undefined" ? null : document.body;
  const dialogRef = useRef<HTMLElement>(null);
  const closeRef = useRef(onClose);
  useEffect(() => { closeRef.current = onClose; }, [onClose]);
  useEffect(() => {
    if (!host) return;
    const app = document.querySelector<HTMLElement>(".app-shell");
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const oldOverflow = document.body.style.overflow;
    const wasInert = app?.inert ?? false;
    document.body.style.overflow = "hidden";
    if (app) app.inert = true;
    const focusables = () => Array.from(dialogRef.current?.querySelectorAll<HTMLElement>("a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex='-1'])") ?? []);
    requestAnimationFrame(() => (focusables()[0] ?? dialogRef.current)?.focus());
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.target instanceof Element && event.target.closest(".swal2-container")) return;
      if (event.key === "Escape" && closeOnEscape) { event.preventDefault(); closeRef.current(); return; }
      if (event.key !== "Tab") return;
      const items = focusables();
      if (!items.length) { event.preventDefault(); dialogRef.current?.focus(); return; }
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = oldOverflow;
      if (app) app.inert = wasInert;
      previousFocus?.focus();
    };
  }, [host, closeOnEscape]);

  if (!host) return null;
  return createPortal(<div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) closeRef.current(); }}>
    <section ref={dialogRef} className={className} role="dialog" aria-modal="true" aria-labelledby={labelledBy} tabIndex={-1}>{children}</section>
  </div>, host);
}
