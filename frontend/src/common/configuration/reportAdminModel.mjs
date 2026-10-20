export function emptyRoleGrant(role) {
  return { roleKey: role.roleKey, roleName: role.name, canView: false, canExecute: false, canExport: false };
}

export function changeRoleGrant(grants, roleKey, changes) {
  return grants.map((grant) => {
    if (grant.roleKey !== roleKey) return grant;
    const next = { ...grant, ...changes };
    if (changes.canView === false) {
      next.canExecute = false;
      next.canExport = false;
    }
    if (changes.canExecute === false) next.canExport = false;
    return next;
  });
}

export function roleAccessPayload(grants) {
  return grants
    .filter((grant) => grant.canView || grant.canExecute || grant.canExport)
    .map(({ roleKey, canView, canExecute, canExport }) => ({ roleKey, canView, canExecute, canExport }));
}
