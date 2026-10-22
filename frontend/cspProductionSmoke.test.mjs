import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { cpSync, existsSync } from "node:fs";
import test from "node:test";

const standaloneServer = new URL("./.next/standalone/server.js", import.meta.url);
test("production HTML enforces a fresh CSP nonce that matches inline scripts", {
  skip: !existsSync(standaloneServer),
  timeout: 45_000,
}, async () => {
  // Match the repository's deployment packaging: standalone omits these assets
  // and the Dockerfile copies them alongside the generated server.
  cpSync(new URL("./.next/static/", import.meta.url), new URL("./.next/standalone/.next/static/", import.meta.url), { recursive: true });
  cpSync(new URL("./public/", import.meta.url), new URL("./.next/standalone/public/", import.meta.url), { recursive: true });
  const port = await availablePort();
  const environment = {
    ...process.env,
    NODE_ENV: "production",
    NEXT_PUBLIC_API_BASE_URL: "https://api.example.test/api/v1",
    PORT: String(port),
    HOSTNAME: "127.0.0.1",
  };
  delete environment.CSP_MODE;

  const server = spawn(process.execPath, [standaloneServer.pathname], {
    cwd: new URL("./.next/standalone/", import.meta.url),
    env: environment,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let serverOutput = "";
  server.stdout.on("data", (chunk) => { serverOutput += chunk.toString(); });
  server.stderr.on("data", (chunk) => { serverOutput += chunk.toString(); });

  try {
    const first = await waitForPage(port, server, () => serverOutput);
    const second = await fetchPage(port);
    const firstBody = await first.text();
    const secondBody = await second.text();
    const firstCsp = first.headers.get("content-security-policy");
    const secondCsp = second.headers.get("content-security-policy");
    assert.ok(firstCsp, "production must send enforced Content-Security-Policy");
    assert.equal(first.headers.get("content-security-policy-report-only"), null);
    assert.equal(first.status, 200);
    assert.equal(first.headers.get("x-content-type-options"), "nosniff");
    assert.equal(first.headers.get("x-frame-options"), "DENY");
    assert.equal(first.headers.get("referrer-policy"), "strict-origin-when-cross-origin");
    assert.match(firstCsp, /connect-src 'self' https:\/\/api\.example\.test/);
    assert.doesNotMatch(firstCsp.match(/script-src [^;]+/)[0], /unsafe-inline|unsafe-eval/);
    assert.doesNotMatch(firstCsp, /https:\/\/unapproved\.invalid/);
    assert.match(first.headers.get("cache-control") || "", /private, no-store/);

    const firstNonce = nonceFromPolicy(firstCsp);
    const secondNonce = nonceFromPolicy(secondCsp);
    assert.ok(firstNonce && secondNonce);
    assert.notEqual(firstNonce, secondNonce, "each HTML response must use a fresh nonce");
    assert.ok(scriptNonceMatches(firstBody, firstNonce), "rendered inline scripts must use the response nonce");
    assert.ok(scriptNonceMatches(secondBody, secondNonce), "subsequent rendered inline scripts must use the new response nonce");

    const stylesheetPath = firstBody.match(/href="([^"]+\.css(?:\?[^"]*)?)"/)?.[1];
    assert.ok(stylesheetPath, "the rendered page should link its local stylesheet");
    const stylesheet = await fetch(new URL(stylesheetPath, `http://127.0.0.1:${port}`));
    assert.equal(stylesheet.status, 200, `static stylesheet ${stylesheetPath} should remain available`);
    assert.equal(stylesheet.headers.get("content-security-policy"), null, "static CSS should not receive a per-page nonce policy");
    assert.equal(stylesheet.headers.get("x-frame-options"), "DENY");
  } finally {
    server.kill("SIGTERM");
  }
});

function nonceFromPolicy(policy) {
  return policy?.match(/'nonce-([^']+)'/)?.[1] ?? null;
}

function scriptNonceMatches(html, nonce) {
  const scriptTags = [...html.matchAll(/<script\b([^>]*)>(.*?)<\/script>/gs)];
  const inlineScripts = scriptTags.filter(([, attributes, body]) => !/\bsrc=/.test(attributes) && body.trim());
  return inlineScripts.length > 0 && inlineScripts.every(([, attributes]) => {
    const value = attributes.match(/\bnonce="([^"]+)"/)?.[1];
    return value === nonce;
  });
}

async function availablePort() {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return port;
}

async function fetchPage(port) {
  return fetch(`http://127.0.0.1:${port}/ecabin/login`, { cache: "no-store" });
}

async function waitForPage(port, process, getOutput) {
  const deadline = Date.now() + 30_000;
  let lastError;
  while (Date.now() < deadline) {
    if (process.exitCode !== null) throw new Error(`Standalone server exited early: ${getOutput()}`);
    try {
      const response = await fetchPage(port);
      if (response.status === 200) return response;
      lastError = new Error(`Unexpected status ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Standalone server did not become ready: ${lastError}; ${getOutput()}`);
}
