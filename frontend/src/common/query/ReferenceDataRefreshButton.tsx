"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { responseErrorMessage } from "@/common/alerts";
import type { RefreshTask } from "./referenceQueryKeys";

type Request = (path: string, init?: RequestInit) => Promise<Response>;

/** Refreshes only the supplied allowlisted resources and invalidates their TanStack keys. */
export default function ReferenceDataRefreshButton({ request, tasks, label = "Refresh master data", onRefreshed }: { request: Request; tasks: RefreshTask[]; label?: string; onRefreshed?: () => Promise<void> | void }) {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState("");
  const refresh = useMutation({
    mutationFn: async () => {
      if (!tasks.length) throw new Error("There is no reference data to refresh for this form.");
      const results = await Promise.all(tasks.map(async task => {
        try {
          const response = await request("/reference-data/refresh", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(task.body) });
          if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not refresh master data."));
          await response.json();
          return { task, error: null as string | null };
        } catch (error) {
          return { task, error: error instanceof Error ? error.message : "Could not refresh master data." };
        }
      }));
      return results;
    },
    onSuccess: async results => {
      const succeeded=results.filter(result=>!result.error);
      await Promise.all(succeeded.map(result => queryClient.invalidateQueries({ queryKey: result.task.queryKey })));
      if(succeeded.length) await onRefreshed?.();
      const failures=results.filter(result=>result.error);
      setMessage(failures.length?`${succeeded.length} resource(s) refreshed; ${failures.length} could not be refreshed. ${failures[0].error}`:"Master data refreshed.");
    },
    onError: error => setMessage(error instanceof Error ? error.message : "Could not refresh master data."),
  });
  return <span className="inline-flex flex-wrap items-center gap-2">
    <button type="button" className="button-secondary" disabled={!tasks.length || refresh.isPending} onClick={() => { setMessage(""); refresh.mutate(); }}>{refresh.isPending ? "Refreshing…" : label}</button>
    <span role={message.includes("refreshed") ? "status" : message ? "alert" : undefined} aria-live="polite" className="text-xs text-[#5f6d64]">{message}</span>
  </span>;
}
