import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { normalizeAppBasePath } from "./appBasePath.mjs";

test("application exposes only the login, loginhome, and logout page routes plus root", () => {
  for (const route of ["src/app/page.tsx", "src/app/login/page.tsx", "src/app/loginhome/page.tsx", "src/app/logout/page.tsx"]) {
    assert.equal(existsSync(new URL(route, import.meta.url)), true, `missing ${route}`);
  }
  assert.equal(normalizeAppBasePath("/fleetcare"), "/fleetcare");
});

test("internal screen and configuration navigation do not write screen state into browser URLs", () => {
  const shell = readFileSync(new URL("src/app/loginhome/page.tsx", import.meta.url), "utf8");
  const configuration = readFileSync(new URL("src/app/configuration.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(shell, /window\.history\.(pushState|replaceState)|window\.location\.(search|hash)/);
  assert.doesNotMatch(configuration, /window\.history\.(pushState|replaceState)|window\.location\.(search|hash)/);
});

test("expired sessions return to login and explicit logout removes local credentials", () => {
  const shell = readFileSync(new URL("src/app/loginhome/page.tsx", import.meta.url), "utf8");
  const logout = readFileSync(new URL("src/app/logout/page.tsx", import.meta.url), "utf8");
  assert.match(shell, /if \(expired\)[\s\S]*?clearLocalSession\(\)[\s\S]*?location\.replace\(`\$\{applicationBasePath\}\/login`\)/);
  assert.match(logout, /clearLocalSession\(\)/);
  assert.match(logout, /location\.replace\(`\$\{applicationBasePath\}\/login`\)/);
});
