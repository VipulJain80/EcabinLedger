export type ReportAdminGrant = {
  roleKey: string;
  roleName: string;
  canView: boolean;
  canExecute: boolean;
  canExport: boolean;
};

export function emptyRoleGrant(role: { roleKey: string; name: string }): ReportAdminGrant;
export function changeRoleGrant(grants: ReportAdminGrant[], roleKey: string, changes: Partial<ReportAdminGrant>): ReportAdminGrant[];
export function roleAccessPayload(grants: ReportAdminGrant[]): Array<Pick<ReportAdminGrant, "roleKey" | "canView" | "canExecute" | "canExport">>;
