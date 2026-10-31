"use client";

import { useQuery } from "@tanstack/react-query";
import type { CSSProperties } from "react";
import { workflowStatusLabel } from "./workflowStatusLabels.mjs";
import { referenceQueryKeys } from "@/common/query/referenceQueryKeys";

export { workflowStatusLabel } from "./workflowStatusLabels.mjs";

export type BadgeStyle = { backgroundColor: string; textColor: string; borderColor: string; icon: string | null; displayStyle: "SOLID" | "OUTLINE" | "SOFT" };
export type WorkflowStatus = { id: string; moduleCode: string; statusCode: string; displayName: string; aliasName: string | null; description: string | null; isActive: boolean; sortOrder: number; isInitial: boolean; isTerminal: boolean } & BadgeStyle;
export type SeverityDefinition = { id: string; code: string; name: string; isactive: number } & BadgeStyle;
type Request = (path: string, init?: RequestInit) => Promise<Response>;

export function useDefectWorkflowStatuses(request: Request) {
  return useQuery<{ items: WorkflowStatus[] }>({
    queryKey: referenceQueryKeys.workflowStatuses("DEFECT"),
    queryFn: async ({ signal }) => {
      const response = await request("/workflow-statuses?moduleCode=DEFECT&activeOnly=false&page=0&size=100", { signal });
      if (!response.ok) throw new Error("Workflow status labels could not be loaded.");
      return response.json() as Promise<{ items: WorkflowStatus[] }>;
    },
    staleTime: 60_000,
  });
}

export function badgeStyle(style: BadgeStyle): CSSProperties {
  if (style.displayStyle === "OUTLINE") return { backgroundColor: "transparent", color: style.textColor, borderColor: style.borderColor };
  if (style.displayStyle === "SOFT") return { backgroundColor: `${style.backgroundColor}22`, color: style.textColor, borderColor: style.borderColor };
  return { backgroundColor: style.backgroundColor, color: style.textColor, borderColor: style.borderColor };
}

export function useDefectSeverities(request: Request, organizationId: string) {
  return useQuery<SeverityDefinition[]>({
    queryKey: referenceQueryKeys.list(organizationId, "severity-badge-styles"),
    enabled: Boolean(organizationId),
    queryFn: async ({ signal }) => {
      const response = await request("/reference-data/severity-badges", { signal });
      if (!response.ok) throw new Error("Severity definitions could not be loaded.");
      return response.json() as Promise<SeverityDefinition[]>;
    },
    staleTime: 60_000,
  });
}

export function WorkflowStatusBadge({ statusCode, statusId, statuses = [], className = "" }: { statusCode?: string; statusId?: string; statuses?: WorkflowStatus[]; className?: string }) {
  const status = statuses.find(item => (statusId && item.id === statusId) || (statusCode && item.statusCode === statusCode));
  const code = status?.statusCode || statusCode || "UNKNOWN";
  const label = workflowStatusLabel(code, statuses);
  return <span className={`master-badge ${status?.displayStyle === "OUTLINE" ? "master-badge-outline" : ""} ${className}`} style={status ? badgeStyle(status) : undefined} aria-label={`Workflow status: ${label}`}>
    {status?.icon && <span className="master-badge-icon" aria-hidden="true">{status.icon}</span>}{label}
  </span>;
}

export function SeverityBadge({ severityCode, severityId, severityName, request, definitions, organizationId = "", className = "" }: { severityCode?: string; severityId?: string; severityName?: string | null; request: Request; definitions?: SeverityDefinition[]; organizationId?: string; className?: string }) {
  const query = useDefectSeverities(request, organizationId);
  const severity = (definitions || query.data || []).find(item => (severityId && item.id === severityId) || (severityCode && item.code === severityCode));
  const code = severity?.code || severityCode || "UNKNOWN";
  const label = severity?.name || severityName || code.replaceAll("_", " ");
  return <span className={`master-badge ${severity?.displayStyle === "OUTLINE" ? "master-badge-outline" : ""} ${className}`} style={severity ? badgeStyle(severity) : undefined} aria-label={`Severity: ${label}`}>
    {severity?.icon && <span className="master-badge-icon" aria-hidden="true">{severity.icon}</span>}{label}
  </span>;
}

export function IssueMetadata({ severityCode, severityId, statusCode, statusId, request, statuses = [], organizationId = "" }: { severityCode?: string; severityId?: string; statusCode?: string; statusId?: string; request: Request; statuses?: WorkflowStatus[]; organizationId?: string }) {
  return <div className="flex flex-wrap items-center gap-2"><SeverityBadge request={request} organizationId={organizationId} severityCode={severityCode} severityId={severityId} /><WorkflowStatusBadge statusCode={statusCode} statusId={statusId} statuses={statuses} /></div>;
}
