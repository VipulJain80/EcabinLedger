import type { QueryKey } from "@tanstack/react-query";

/** Shared query identities for reference/configuration data. Organization IDs come from GET /me. */
export const referenceQueryKeys = {
  root: (organizationId: string) => ["reference-data", organizationId] as const,
  list: (organizationId: string, resource: string) => ["reference-data", organizationId, resource] as const,
  conditions: (organizationId: string, componentId: string) => ["reference-data", organizationId, "conditions", componentId] as const,
  aircraftTypes: (organizationId: string) => ["reference-data", organizationId, "aircraft-types"] as const,
  forms: (organizationId: string, areaId: string, purpose: string, stage: string) => ["operational-forms", organizationId, areaId, purpose, stage] as const,
  formOptions: (organizationId: string, formId: string, versionId: string, fieldKey: string, areaId: string, componentId: string) => ["form-options", organizationId, formId, versionId, fieldKey, areaId, componentId] as const,
  workflowStatuses: (moduleCode: string) => ["workflow-status-definitions", moduleCode] as const,
};

export type RefreshTask = { body: Record<string, unknown>; queryKey: QueryKey };
