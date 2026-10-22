import assert from "node:assert/strict";
import test from "node:test";
import { buildContentSecurityPolicy, readCspConfiguration } from "./cspPolicy.mjs";

const productionEnvironment = {
  NODE_ENV: "production",
  NEXT_PUBLIC_API_BASE_URL: "https://api.example.test/api/v1",
};

test("production defaults to enforced CSP and permits only configured HTTPS origins", () => {
  const configuration = readCspConfiguration({
    ...productionEnvironment,
    CSP_EXTRA_SCRIPT_ORIGINS: "https://login.example.test",
    CSP_EXTRA_CONNECT_ORIGINS: "https://identity.example.test,wss://events.example.test",
  });
  const policy = buildContentSecurityPolicy("YWJjMTIz", configuration);

  assert.equal(configuration.mode, "enforce");
  assert.match(policy, /script-src 'self' 'nonce-YWJjMTIz' https:\/\/login\.example\.test;/);
  assert.match(policy, /connect-src 'self' https:\/\/api\.example\.test https:\/\/identity\.example\.test wss:\/\/events\.example\.test;/);
  assert.doesNotMatch(policy.match(/script-src [^;]+/)[0], /unsafe-inline|unsafe-eval/);
  assert.match(policy, /style-src-attr 'unsafe-inline'/);
  assert.match(policy, /object-src 'none'/);
  assert.doesNotMatch(policy, /\*|data:/);
});

test("development and explicit UAT report-only use the same policy directives", () => {
  const development = readCspConfiguration({ NODE_ENV: "development" });
  const uat = readCspConfiguration({
    NODE_ENV: "production",
    CSP_MODE: "report-only",
    NEXT_PUBLIC_API_BASE_URL: "https://uat-api.example.test/api/v1",
  });
  const developmentPolicy = buildContentSecurityPolicy("YWJjMTIz", development);
  const uatPolicy = buildContentSecurityPolicy("YWJjMTIz", uat);
  assert.equal(development.mode, "report-only");
  assert.equal(uat.mode, "report-only");
  assert.deepEqual(
    developmentPolicy.split(";").map((part) => part.trim().replace(/connect-src .*/, "connect-src <origins>")),
    uatPolicy.split(";").map((part) => part.trim().replace(/connect-src .*/, "connect-src <origins>")),
  );
  assert.doesNotMatch(developmentPolicy, /unsafe-eval/);
});

test("production fails closed for missing or insecure API configuration", () => {
  assert.throws(() => readCspConfiguration({ NODE_ENV: "production" }), /NEXT_PUBLIC_API_BASE_URL is required/);
  assert.throws(() => readCspConfiguration({ ...productionEnvironment, NEXT_PUBLIC_API_BASE_URL: "http://api.example.test" }), /must use HTTPS/);
  assert.throws(() => readCspConfiguration({ ...productionEnvironment, CSP_MODE: "disabled" }), /CSP_MODE/);
});

test("CSP origin lists reject paths, wildcards, credentials, and non-HTTPS production origins", () => {
  assert.throws(() => readCspConfiguration({ ...productionEnvironment, CSP_EXTRA_CONNECT_ORIGINS: "https://api.example.test/path" }), /only approved origins/);
  assert.throws(() => readCspConfiguration({ ...productionEnvironment, CSP_EXTRA_SCRIPT_ORIGINS: "https://*.example.test" }), /wildcard origins/);
  assert.throws(() => readCspConfiguration({ ...productionEnvironment, CSP_EXTRA_CONNECT_ORIGINS: "https://user:password@api.example.test" }), /only approved origins/);
  assert.throws(() => readCspConfiguration({ ...productionEnvironment, CSP_EXTRA_CONNECT_ORIGINS: "http://api.example.test" }), /only approved origins/);
  assert.throws(() => readCspConfiguration({ NODE_ENV: "development", CSP_EXTRA_SCRIPT_ORIGINS: "ws://localhost:8080" }), /only approved origins/);
});

test("nonce values must be base64 and are embedded in script and style policy sources", () => {
  const configuration = readCspConfiguration({ ...productionEnvironment });
  assert.throws(() => buildContentSecurityPolicy("not a nonce", configuration), /base64/);
  const policy = buildContentSecurityPolicy("QW5vdGhlck5vbmNl", configuration);
  assert.match(policy, /script-src 'self' 'nonce-QW5vdGhlck5vbmNl'/);
  assert.match(policy, /style-src 'self' 'nonce-QW5vdGhlck5vbmNl'/);
});
