import { randomBytes } from "node:crypto";
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

function apiOriginFor(request: NextRequest): string {
  const configuredApiUrl = process.env.NEXT_PUBLIC_API_BASE_URL?.trim() || "http://localhost:8080/api/v1";

  try {
    const apiUrl = new URL(configuredApiUrl, request.nextUrl.origin);
    return apiUrl.protocol === "http:" || apiUrl.protocol === "https:"
      ? apiUrl.origin
      : request.nextUrl.origin;
  } catch {
    return request.nextUrl.origin;
  }
}

function configuredOrigins(value: string | undefined): string[] {
  if (!value?.trim()) return [];
  const development = process.env.NODE_ENV === "development";
  return value.split(",").map((entry) => entry.trim()).filter(Boolean).flatMap((entry) => {
    try {
      const url = new URL(entry);
      const localHttpOrigin = development && url.protocol === "http:"
        && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
      if ((url.protocol !== "https:" && !localHttpOrigin) || url.username || url.password
          || url.pathname !== "/" || url.search || url.hash) return [];
      return [url.origin];
    } catch {
      return [];
    }
  });
}

function shouldSkipCsp(pathname: string): boolean {
  return pathname.includes("/_next/static/")
    || pathname.includes("/_next/image")
    || pathname.endsWith("/favicon.ico")
    || /\.(?:avif|css|gif|ico|jpe?g|js|map|png|svg|webp|woff2?)$/i.test(pathname);
}

export function proxy(request: NextRequest) {
  if (shouldSkipCsp(request.nextUrl.pathname)) return NextResponse.next();

  const nonce = randomBytes(18).toString("base64");
  const development = process.env.NODE_ENV === "development";
  const productionUpgrade = process.env.NODE_ENV === "production" ? "upgrade-insecure-requests;" : "";
  const scriptOrigins = configuredOrigins(process.env.CSP_EXTRA_SCRIPT_ORIGINS);
  const connectOrigins = [apiOriginFor(request), ...configuredOrigins(process.env.CSP_EXTRA_CONNECT_ORIGINS)];
  const csp = [
    "default-src 'self';",
    `script-src 'self' 'nonce-${nonce}' ${scriptOrigins.join(" ")}${development ? " 'unsafe-eval'" : ""};`,
    `style-src 'self' 'nonce-${nonce}';`,
    "style-src-attr 'unsafe-inline';",
    "img-src 'self' blob: data:;",
    "font-src 'self';",
    `connect-src 'self' ${connectOrigins.join(" ")};`,
    "frame-src 'self' blob:;",
    "object-src 'none';",
    "base-uri 'self';",
    "form-action 'self';",
    "frame-ancestors 'none';",
    productionUpgrade,
  ].filter(Boolean).join(" ");

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", csp);
  return response;
}
