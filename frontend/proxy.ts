import { randomBytes } from "node:crypto";
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { buildContentSecurityPolicy, readCspConfiguration } from "./cspPolicy.mjs";

/**
 * Authoritative Next.js 16 Proxy handler. The src-level entry point re-exports
 * this function for compatibility with Next's src/app route discovery.
 */
export function proxy(request: NextRequest) {
  const nonce = randomBytes(18).toString("base64");
  const configuration = readCspConfiguration(process.env);
  const policy = buildContentSecurityPolicy(nonce, configuration);

  // Next.js reads the request CSP to nonce its framework-generated scripts.
  // x-nonce is forwarded for any explicitly approved server-rendered scripts.
  const forwardedHeaders = new Headers(request.headers);
  forwardedHeaders.set("x-nonce", nonce);
  forwardedHeaders.set("Content-Security-Policy", policy);

  const response = NextResponse.next({ request: { headers: forwardedHeaders } });
  if (configuration.mode === "enforce") {
    response.headers.set("Content-Security-Policy", policy);
    response.headers.delete("Content-Security-Policy-Report-Only");
  } else {
    response.headers.set("Content-Security-Policy-Report-Only", policy);
    response.headers.delete("Content-Security-Policy");
  }
  // Nonce-bearing HTML must never be reused by a browser, CDN, or reverse proxy.
  response.headers.set("Cache-Control", "private, no-store, max-age=0, must-revalidate");
  return response;
}
