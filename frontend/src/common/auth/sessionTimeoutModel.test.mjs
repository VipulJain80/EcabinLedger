import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { INACTIVITY_LIMIT_MS, MAX_TOKEN_AGE_MS, sessionTimeoutReason, tokenDeadlineFromJwt } from "./sessionTimeoutModel.mjs";

function jwt(claims) {
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url");
  return `header.${payload}.signature`;
}

test("token deadline is capped at thirty minutes from original issue time", () => {
  const issuedAt = 1_800_000_000;
  assert.equal(tokenDeadlineFromJwt(jwt({ iat: issuedAt, exp: issuedAt + 3600 })), (issuedAt + 1800) * 1000);
  assert.equal(tokenDeadlineFromJwt(jwt({ iat: issuedAt, exp: issuedAt + 600 })), (issuedAt + 600) * 1000);
  assert.equal(MAX_TOKEN_AGE_MS, 30 * 60 * 1000);
});

test("inactive session ends at ten minutes even when the token remains valid", () => {
  const lastActivity = 1_000_000;
  assert.equal(sessionTimeoutReason(lastActivity + INACTIVITY_LIMIT_MS - 1, lastActivity, lastActivity + 30 * 60_000), null);
  assert.equal(sessionTimeoutReason(lastActivity + INACTIVITY_LIMIT_MS, lastActivity, lastActivity + 30 * 60_000), "inactivity");
});

test("active use never moves the original token deadline", () => {
  const deadline = 2_000_000;
  assert.equal(sessionTimeoutReason(deadline - 1, deadline - INACTIVITY_LIMIT_MS + 1, deadline), null);
  assert.equal(sessionTimeoutReason(deadline, deadline - 1, deadline), "expired");
  assert.equal(sessionTimeoutReason(deadline, deadline - INACTIVITY_LIMIT_MS, deadline), "inactivity");
});

test("malformed tokens and missing timestamps fail closed", () => {
  assert.throws(() => tokenDeadlineFromJwt("not-a-jwt"));
  assert.throws(() => tokenDeadlineFromJwt(jwt({ exp: 1_800_000_100 })));
  assert.throws(() => tokenDeadlineFromJwt(jwt({ iat: 1_800_000_100, exp: 1_800_000_000 })));
});

test("authenticated shell observes user activity, browser resume, and logout events from other tabs", async () => {
  const source = await readFile(new URL("./SessionTimeout.tsx", import.meta.url), "utf8");
  for (const event of ["mousemove", "click", "keydown", "scroll", "touchstart", "pointerdown"]) {
    assert.match(source, new RegExp(`\\"${event}\\"`));
  }
  assert.match(source, /visibilitychange/);
  assert.match(source, /window\.addEventListener\("storage", onStorage\)/);
  assert.match(source, /SESSION_LOGOUT_EVENT_KEY/);
  assert.match(source, /now - lastPersistedActivity >= 1000/);
  assert.match(source, /if \(!deadline\) return/);
  assert.match(source, /window\.location\.replace\(`\$\{applicationBasePath\}\/login`\)/);
});
