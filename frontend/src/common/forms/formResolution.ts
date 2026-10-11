export type OperationalPurpose = "DEFECT_REPORT" | "INSPECTION" | "CORRECTIVE_ACTION" | "VERIFICATION" | "CLOSURE_APPROVAL" | "REOPEN";

const PURPOSE_BY_STATUS: Record<string, [OperationalPurpose, string]> = {
  REPORTED: ["DEFECT_REPORT", "REPORT"],
  INSPECTION_REQUIRED: ["INSPECTION", "INSPECT"],
  INSPECTION_IN_PROGRESS: ["INSPECTION", "INSPECT"],
  ACTION_ASSIGNED: ["CORRECTIVE_ACTION", "PERFORM_ACTION"],
  IN_PROGRESS: ["CORRECTIVE_ACTION", "PERFORM_ACTION"],
  AWAITING_VERIFICATION: ["VERIFICATION", "VERIFY"],
  APPROVAL_REQUIRED: ["CLOSURE_APPROVAL", "APPROVE"],
  CLOSED: ["REOPEN", "REOPEN"],
};

/** Presentation helper only. Spring independently validates status and permission. */
export function formPurposeForStatus(status: string, permissions: readonly string[] | undefined): OperationalPurpose | null {
  const match = PURPOSE_BY_STATUS[status];
  return match && permissions?.includes(match[1]) ? match[0] : null;
}
