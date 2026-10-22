import type { NextConfig } from "next";
import { normalizeAppBasePath } from "./appBasePath.mjs";
import { readCspConfiguration } from "./cspPolicy.mjs";

const appBasePath = normalizeAppBasePath(process.env.APP_BASE_PATH);

// next.config is evaluated during `next build`; require production origin
// configuration there as well as again when the server starts.
if (process.env.NODE_ENV === "production") readCspConfiguration(process.env);

const nextConfig: NextConfig = {
  basePath: appBasePath,
  env: { NEXT_PUBLIC_APP_BASE_PATH: appBasePath },
  output: "standalone",
  poweredByHeader: false,
  async redirects() {
    return [{ source: "/", destination: `${appBasePath}/login`, permanent: false, basePath: false }];
  },
  async headers() {
    return [{ source: "/:path*", headers: [
      { key: "X-Content-Type-Options", value: "nosniff" },
      { key: "X-Frame-Options", value: "DENY" },
      { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
      { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" }
    ] }];
  }
};

export default nextConfig;
