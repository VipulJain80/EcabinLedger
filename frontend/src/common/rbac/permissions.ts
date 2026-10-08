export type Permission = "VIEW" | "REPORT" | "REVIEW" | "INSPECT" | "ASSIGN_ACTION" | "PERFORM_ACTION" | "VERIFY" | "APPROVE" | "CLOSE" | "REOPEN" | "AUDIT" | "ADMINISTER_ORGANIZATION";

export function hasPermission(grants: readonly string[] | undefined, required: Permission) {
  return grants?.includes(required) ?? false;
}

const transitionPermissions: Record<string, Permission> = {
  UNDER_REVIEW: "REVIEW", INSPECTION_REQUIRED: "REVIEW", REJECTED: "REVIEW", CANCELLED: "REVIEW",
  INSPECTION_IN_PROGRESS: "INSPECT", INSPECTION_COMPLETE: "INSPECT", ACTION_ASSIGNED: "ASSIGN_ACTION",
  IN_PROGRESS: "PERFORM_ACTION", AWAITING_VERIFICATION: "PERFORM_ACTION", VERIFIED: "VERIFY",
  APPROVAL_REQUIRED: "APPROVE", CLOSED: "CLOSE", REOPENED: "REOPEN",
};

export function canPerformAction(grants: readonly string[] | undefined, action: string) {
  const permission = transitionPermissions[action];
  return permission ? hasPermission(grants, permission) : false;
}

const nextPermissionByStatus: Record<string, Permission> = {
  REPORTED: "REVIEW", UNDER_REVIEW: "REVIEW", INSPECTION_REQUIRED: "INSPECT", INSPECTION_IN_PROGRESS: "INSPECT",
  INSPECTION_COMPLETE: "ASSIGN_ACTION", ACTION_ASSIGNED: "PERFORM_ACTION", IN_PROGRESS: "PERFORM_ACTION",
  AWAITING_VERIFICATION: "VERIFY", VERIFIED: "APPROVE", APPROVAL_REQUIRED: "CLOSE", CLOSED: "REOPEN",
};

export function canAdvanceWorkflow(grants: readonly string[] | undefined, status: string) {
  const permission = nextPermissionByStatus[status];
  return permission ? hasPermission(grants, permission) : false;
}
