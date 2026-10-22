# Frontend Content Security Policy

The Next.js frontend generates one fresh cryptographic nonce for each page request in `frontend/proxy.ts`, reached through `frontend/src/proxy.ts` because the App Router lives at `src/app`. The proxy places the nonce and policy on the internal request so Next.js can nonce framework scripts, then emits the same policy on the response. `frontend/src/instrumentation.ts` validates production configuration during server startup; `next.config.ts` also validates it during production builds.

The shared policy is built by `frontend/cspPolicy.mjs`. Development defaults to `Content-Security-Policy-Report-Only`; production defaults to enforced `Content-Security-Policy`. UAT can temporarily set `CSP_MODE=report-only` while validating violations. Set `CSP_MODE=enforce` to enforce in UAT. Invalid modes, malformed origins, and missing production API configuration fail build or server startup instead of silently disabling CSP.

## Required configuration

Set `NEXT_PUBLIC_API_BASE_URL` to the frontend's API base URL before the build. It must be HTTPS outside local development. Its origin is included in `connect-src`.

Set `CSP_EXTRA_CONNECT_ORIGINS` to a comma-separated list of exact additional service origins that browser code must connect to, such as an approved identity-provider or WebSocket endpoint. Use HTTPS or WSS outside local development. Do not include paths, wildcards, credentials, query strings, or fragments. It may be empty if no additional browser connections are required.

Set `CSP_EXTRA_SCRIPT_ORIGINS` only when a reviewed feature loads scripts from a third party. No external script host is currently required by the checked-in frontend. Use exact HTTPS origins; do not use wildcards.

For local development, the sample `.env.example` uses the API at `http://localhost:8080` and includes `http://127.0.0.1:8080` for the local demo-token request. These HTTP origins are accepted only when `NODE_ENV=development` and the host is loopback.

## Policy and compatibility

The shared directives are `default-src`, nonce-based `script-src` and `style-src`, `style-src-attr`, `img-src`, `font-src`, `connect-src`, `frame-src`, `media-src`, `object-src`, `base-uri`, `form-action`, `frame-ancestors`, `manifest-src`, and `worker-src`. `script-src` never includes `unsafe-inline` or `unsafe-eval`. `style-src-attr 'unsafe-inline'` remains necessary for existing React inline style properties used by configurable workflow badges and the login background. `blob:` is limited to images, frames, and media because the UI previews downloaded evidence and generated files as object URLs. `data:` is not allowed.

There is no configured CSP report collection endpoint in this repository, so report-only findings are visible through browser diagnostics and are not sent to a server. Review and resolve legitimate violations before turning on enforcement in UAT and Production. No per-environment policy branch weakens the directives; only the mode and explicitly configured exact origins vary.

The proxy excludes static assets, image optimization, metadata files, API paths, and prefetch requests. Page HTML gets `Cache-Control: private, no-store` so a response cannot be reused with another request's nonce. Nonce-based rendering makes page responses dynamic and disables shared caching for those HTML responses; static assets remain separately cacheable. The existing `next.config.ts` security headers remain in place. Hosting, gateway, CDN, or reverse-proxy configuration must not replace the application CSP with a second conflicting policy.

## Release checklist

1. Set the production API URL and approved exact additional origins in the build/runtime environment.
2. Keep Development in its default report-only mode. Set UAT to `CSP_MODE=report-only` for diagnosis, then set `CSP_MODE=enforce` after browser flows have no unexplained violations.
3. Leave `CSP_MODE` unset in Production to enforce by default, or explicitly set `CSP_MODE=enforce`.
4. Verify the live response has one CSP mode header, a unique nonce, and HTML script nonce attributes matching that header. Confirm the CDN does not cache page HTML or replace the CSP.
