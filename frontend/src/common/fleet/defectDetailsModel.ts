export type DefectActivity = {
  id: string;
  eventType: string;
  fromStatus: string | null;
  toStatus: string | null;
  note: string;
  evidenceReference: string | null;
  inspectionOutcome: string | null;
  actorId: string;
  occurredAt: string;
  details?: DefectEventDetails | null;
};

export function canReassignMaintenance(permissions: string[], activeActionId: string | null | undefined, status: string): boolean {
  return permissions.includes("ASSIGN_ACTION") && Boolean(activeActionId) && ["ACTION_ASSIGNED", "IN_PROGRESS"].includes(status);
}

export type DefectEventDetails = {
  formId: string | null;
  formName: string | null;
  formPurpose: string | null;
  workflowStage: string | null;
  formVersionNumber: number | null;
  submittedDetails: Array<{ label: string; value: string }>;
  maintenanceStatus: string | null;
  slaTargetDate: string | null;
  previousSlaTargetDate: string | null;
  actionStatus: string | null;
  previousActionStatus: string | null;
  maintenanceRemark: string | null;
  decision: string | null;
  maintenanceTeam: string | null;
  evidenceAttachmentId: string | null;
};

export function formatHistoryDate(value: string | null | undefined, emptyLabel = "Not set"): string {
  if (value == null) return "—";
  if (value === "") return emptyLabel;
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (dateOnly) {
    const date = new Date(Date.UTC(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]), 12));
    return new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", year: "numeric", timeZone: "Asia/Kolkata" }).format(date);
  }
  return value;
}

export type ReportSnapshot = {
  defectCategory: string | null;
  componentId: string | null;
  conditionId: string | null;
  zone: string | null;
  rowNumber: number | null;
  seatReference: string | null;
  title: string | null;
  description: string | null;
  severityId: string | null;
};

export function newestFirst(events: DefectActivity[]): DefectActivity[] {
  return [...events].sort((a, b) => Date.parse(b.occurredAt) - Date.parse(a.occurredAt));
}

/** Adapt the typed relational report snapshot into keys from its immutable form definition. */
export function answersFromReportSnapshot(fields: Array<{ key: string; binding?: string | null }>, snapshot: ReportSnapshot | null): Record<string, unknown> {
  if (!snapshot) return {};
  const values: Record<string, unknown> = {
    DEFECT_CATEGORY: snapshot.defectCategory,
    COMPONENT_ID: snapshot.componentId,
    CONDITION_ID: snapshot.conditionId,
    ZONE: snapshot.zone,
    ROW_NUMBER: snapshot.rowNumber,
    SEAT_REFERENCE: snapshot.seatReference,
    TITLE: snapshot.title,
    DESCRIPTION: snapshot.description,
    SEVERITY: snapshot.severityId,
    // Compact Galley reports preserve their optional remark as a labeled section
    // in the typed description snapshot (see OperationalFormService mapping).
    REMARKS: snapshot.description?.includes("\nRemarks: ") ? snapshot.description.slice(snapshot.description.indexOf("\nRemarks: ") + "\nRemarks: ".length) : null,
  };
  return Object.fromEntries(fields
    .filter(field => field.binding && values[field.binding] !== null && values[field.binding] !== undefined)
    .map(field => [field.key, values[field.binding!]]));
}

export function safeReturnPath(candidate: string | null, basePath: string): string {
  // Next.js router methods accept paths relative to basePath and add the
  // configured prefix themselves. Browser URLs already include that prefix.
  const fallback = "/loginhome";
  if (!candidate || !candidate.startsWith("/") || candidate.startsWith("//") || candidate.includes("\\")) return fallback;
  if (!basePath) return candidate;
  if (candidate === basePath) return "/";
  if (!candidate.startsWith(`${basePath}/`)) return fallback;
  return candidate.slice(basePath.length);
}

export function defectDetailsRequestPath(defectId: string, aircraftListId?: string | null): string {
  const encodedDefectId = encodeURIComponent(defectId);
  return aircraftListId
    ? `/aircraft/${encodeURIComponent(aircraftListId)}/defects/${encodedDefectId}`
    : `/defects/${encodedDefectId}`;
}
