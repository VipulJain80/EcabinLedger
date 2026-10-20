import assert from "node:assert/strict";
import test from "node:test";
import { changeRoleGrant, emptyRoleGrant, roleAccessPayload } from "./reportAdminModel.mjs";

test("new report role grants default to no access and do not include organization administrators implicitly", () => {
  const admin = emptyRoleGrant({ roleKey: "ADMIN", name: "Administrator" });
  assert.deepEqual(admin, { roleKey: "ADMIN", roleName: "Administrator", canView: false, canExecute: false, canExport: false });
  assert.deepEqual(roleAccessPayload([admin]), []);
});

test("clearing view or execute also clears dependent report permissions", () => {
  const grants = [{ roleKey: "REVIEWER", roleName: "Reviewer", canView: true, canExecute: true, canExport: true }];
  assert.deepEqual(changeRoleGrant(grants, "REVIEWER", { canView: false }), [
    { roleKey: "REVIEWER", roleName: "Reviewer", canView: false, canExecute: false, canExport: false },
  ]);
  assert.deepEqual(changeRoleGrant(grants, "REVIEWER", { canExecute: false }), [
    { roleKey: "REVIEWER", roleName: "Reviewer", canView: true, canExecute: false, canExport: false },
  ]);
});

test("role access request includes only explicit grants and only fields accepted by the API", () => {
  const payload = roleAccessPayload([
    { roleKey: "ADMIN", roleName: "Administrator", canView: false, canExecute: false, canExport: false },
    { roleKey: "REVIEWER", roleName: "Reviewer", canView: true, canExecute: true, canExport: false },
  ]);
  assert.deepEqual(payload, [{ roleKey: "REVIEWER", canView: true, canExecute: true, canExport: false }]);
});
