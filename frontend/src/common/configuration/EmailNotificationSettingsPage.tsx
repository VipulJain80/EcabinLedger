"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { alert, responseErrorMessage } from "@/common/alerts";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type EventSetting = { eventKey: string; label: string; enabled: boolean };
type Settings = { enabled: boolean; events: EventSetting[] };
const QUERY_KEY = ["email-notification-settings"];

export default function EmailNotificationSettingsPage({ request }: { request: Request }) {
  const queryClient = useQueryClient();
  const [saving, setSaving] = useState<string | null>(null);
  const [saveError, setSaveError] = useState("");
  const settingsQuery = useQuery<Settings>({
    queryKey: QUERY_KEY,
    queryFn: async ({ signal }) => {
      const response = await request("/email-notification-settings", { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load email notification settings."));
      return response.json();
    },
  });

  async function update(path: string, key: string, enabled: boolean) {
    if (saving) return;
    setSaving(key);
    setSaveError("");
    try {
      const response = await request(path, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ enabled }),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not save this email notification setting."));
      queryClient.setQueryData(QUERY_KEY, await response.json() as Settings);
      await alert.success("Email notification setting saved.");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Could not save this email notification setting.";
      setSaveError(message);
      await alert.error(message);
    } finally {
      setSaving(null);
    }
  }

  const settings = settingsQuery.data;
  return <div className="email-notification-page">
    <div className="heading"><div><h1>Email Notifications</h1><p>Manage organization-wide email delivery and choose which workflow events may send email.</p></div></div>
    {settingsQuery.isPending && <p className="config-loading" role="status">Loading email notification settings…</p>}
    {settingsQuery.isError && <div className="configuration-field-error" role="alert">{settingsQuery.error instanceof Error ? settingsQuery.error.message : "Could not load email notification settings."}<button type="button" className="button-secondary" onClick={() => void settingsQuery.refetch()}>Retry</button></div>}
    {saveError && <p className="configuration-field-error" role="alert">{saveError}</p>}
    {settings && <div className="email-settings-layout">
      <section className="configuration-table-card email-global-setting" aria-labelledby="email-global-heading">
        <div className="configuration-table-head"><div><div className="eyebrow">ORGANIZATION-WIDE CONTROL</div><h2 id="email-global-heading">Email delivery</h2></div></div>
        <div className="email-settings-row">
          <div><strong>Enable email notifications</strong><p>Email can be sent only when this switch and the relevant workflow event switch are both on. Turning this off pauses pending delivery; queued messages remain in the outbox.</p></div>
          <label className="email-setting-switch"><span>{settings.enabled ? "On" : "Off"}</span><input type="checkbox" role="switch" aria-label="Enable organization-wide email notifications" checked={settings.enabled} disabled={saving !== null} onChange={(event) => void update("/email-notification-settings/global", "global", event.target.checked)} /></label>
        </div>
      </section>
      <section className="configuration-table-card email-event-settings" aria-labelledby="email-events-heading">
        <div className="configuration-table-head"><div><div className="eyebrow">WORKFLOW CONTROLS</div><h2 id="email-events-heading">Workflow event notifications</h2><p>Choose the events that may create email notifications while organization-wide delivery is enabled.</p></div></div>
        <div className="email-event-list">
          {settings.events.map((item) => <div className="email-settings-row" key={item.eventKey}>
            <strong>{item.label}</strong>
            <label className="email-setting-switch"><span>{item.enabled ? "On" : "Off"}</span><input type="checkbox" role="switch" aria-label={`${item.label} email notification`} checked={item.enabled} disabled={saving !== null} onChange={(event) => void update(`/email-notification-settings/events/${encodeURIComponent(item.eventKey)}`, item.eventKey, event.target.checked)} /></label>
          </div>)}
          {!settings.events.length && <p className="configuration-empty">No workflow email events are configured.</p>}
        </div>
      </section>
    </div>}
  </div>;
}
