import type { NextConfig } from "next";
import { normalizeAppBasePath } from "./appBasePath.mjs";

const appBasePath = normalizeAppBasePath(process.env.APP_BASE_PATH);

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
