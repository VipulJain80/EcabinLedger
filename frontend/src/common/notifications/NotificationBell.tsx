"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { responseErrorMessage } from "@/common/alerts";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type NotificationItem = { id: string; type: string; message: string; defectId: string | null; defectReference: string | null; createdAt: string; readAt: string | null };
type Inbox = { unreadCount: number; notifications: NotificationItem[] };

const human = (value: string) => value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());

export default function NotificationBell({ request, onOpenDefect, open: controlledOpen, onOpenChange }: { request: Request; onOpenDefect: (defectId: string) => void; open?: boolean; onOpenChange?: (open: boolean) => void }) {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  const open = controlledOpen ?? uncontrolledOpen;
  const setOpen = (next: boolean) => {
    if (controlledOpen === undefined) setUncontrolledOpen(next);
    onOpenChange?.(next);
  };
  const client = useQueryClient();
  const inbox = useQuery<Inbox>({
    queryKey: ["notification-inbox"],
    queryFn: async ({ signal }) => {
      const response = await request("/notifications", { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load notifications."));
      return response.json();
    },
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  });
  const markRead = useMutation({
    mutationFn: async (notificationId: string) => {
      const response = await request(`/notifications/${encodeURIComponent(notificationId)}/read`, { method: "POST" });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not update this notification."));
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ["notification-inbox"] }),
  });
  const markAllRead = useMutation({
    mutationFn: async () => {
      const response = await request("/notifications/read-all", { method: "POST" });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not mark notifications as read."));
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ["notification-inbox"] }),
  });
  const count = inbox.data?.unreadCount || 0;

  async function openNotification(item: NotificationItem) {
    if (!item.readAt) await markRead.mutateAsync(item.id);
    if (item.defectId) {
      setOpen(false);
      onOpenDefect(item.defectId);
    }
  }

  return <div className="notification-bell-wrap">
    <button type="button" className="notification-bell" aria-label={count ? `Notifications, ${count} unread` : "Notifications"} aria-expanded={open} aria-controls="notification-inbox" onClick={() => setOpen(!open)} title="Notifications">
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>
      {count > 0 && <span className="notification-count">{count > 99 ? "99+" : count}</span>}
    </button>
    {open && <section className="notification-inbox" id="notification-inbox" aria-label="Notifications">
      <header><div><strong>Notifications</strong><small>{count} unread</small></div><button type="button" disabled={!count || markAllRead.isPending} onClick={() => markAllRead.mutate()}>{markAllRead.isPending ? "Saving…" : "Mark all read"}</button></header>
      {markRead.isError && <p className="notification-error" role="alert">{markRead.error.message}</p>}
      {markAllRead.isError && <p className="notification-error" role="alert">{markAllRead.error.message}</p>}
      {inbox.isPending ? <p className="notification-state" role="status">Loading notifications…</p>
        : inbox.isError ? <div className="notification-state" role="alert"><p>{inbox.error.message}</p><button type="button" onClick={() => void inbox.refetch()}>Try again</button></div>
        : !inbox.data?.notifications.length ? <p className="notification-state">You’re all caught up.</p>
        : <ul>{inbox.data.notifications.map(item => <li key={item.id} className={item.readAt ? "is-read" : "is-unread"}>
          <button type="button" className="notification-item" disabled={markRead.isPending} onClick={() => void openNotification(item).catch(() => undefined)}>
            <span className="notification-item-top"><strong>{human(item.type)}</strong><time dateTime={item.createdAt}>{new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.createdAt))}</time></span>
            <span>{item.message}</span>
            {item.defectReference && <span className="notification-defect-reference">{item.defectReference} · View defect →</span>}
          </button>
        </li>)}</ul>}
    </section>}
  </div>;
}
