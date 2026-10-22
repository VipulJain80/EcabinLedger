const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

function parseOriginList(value, variableName, nodeEnv, protocols) {
  if (!value?.trim()) return [];

  return value.split(",").map((entry) => {
    const candidate = entry.trim();
    if (candidate.includes("*")) {
      throw new Error(`${variableName} must not contain wildcard origins.`);
    }
    let url;
    try {
      url = new URL(candidate);
    } catch {
      throw new Error(`${variableName} must contain only valid origins.`);
    }

    const localDevelopmentOrigin = nodeEnv === "development"
      && (url.protocol === "http:" || (protocols.includes("wss:") && url.protocol === "ws:"))
      && LOOPBACK_HOSTS.has(url.hostname);
    if ((!protocols.includes(url.protocol) && !localDevelopmentOrigin)
      || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
      throw new Error(`${variableName} must contain only approved origins without paths or credentials.`);
    }
    return url.origin;
  });
}

function apiOriginFromEnvironment(environment, nodeEnv) {
  const configuredUrl = environment.NEXT_PUBLIC_API_BASE_URL?.trim();
  if (!configuredUrl) {
    if (nodeEnv === "production") {
      throw new Error("NEXT_PUBLIC_API_BASE_URL is required when building or running the production frontend.");
    }
    return "http://localhost:8080";
  }

  let url;
  try {
    url = new URL(configuredUrl);
  } catch {
    throw new Error("NEXT_PUBLIC_API_BASE_URL must be an absolute HTTP(S) URL.");
  }
  if ((url.protocol !== "http:" && url.protocol !== "https:")
    || url.username || url.password || url.search || url.hash) {
    throw new Error("NEXT_PUBLIC_API_BASE_URL must be an absolute HTTP(S) URL without credentials, query, or fragment.");
  }
  if (nodeEnv === "production" && url.protocol !== "https:") {
    throw new Error("NEXT_PUBLIC_API_BASE_URL must use HTTPS outside development.");
  }
  if (nodeEnv !== "development" && url.protocol === "http:" && !LOOPBACK_HOSTS.has(url.hostname)) {
    throw new Error("HTTP API origins are allowed only for loopback development.");
  }
  return url.origin;
}

export function readCspConfiguration(environment = process.env) {
  const nodeEnv = environment.NODE_ENV ?? "development";
  const configuredMode = environment.CSP_MODE?.trim().toLowerCase();
  const mode = configuredMode || (nodeEnv === "production" ? "enforce" : "report-only");
  if (mode !== "enforce" && mode !== "report-only") {
    throw new Error("CSP_MODE must be either 'enforce' or 'report-only'.");
  }

  return {
    mode,
    apiOrigin: apiOriginFromEnvironment(environment, nodeEnv),
    scriptOrigins: parseOriginList(environment.CSP_EXTRA_SCRIPT_ORIGINS, "CSP_EXTRA_SCRIPT_ORIGINS", nodeEnv, ["https:"]),
    connectOrigins: parseOriginList(environment.CSP_EXTRA_CONNECT_ORIGINS, "CSP_EXTRA_CONNECT_ORIGINS", nodeEnv, ["https:", "wss:"]),
  };
}

export function buildContentSecurityPolicy(nonce, configuration) {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(nonce)) {
    throw new Error("CSP nonce must be a base64 value.");
  }
  const scriptOrigins = [...new Set(configuration.scriptOrigins)];
  const connectOrigins = [...new Set([configuration.apiOrigin, ...configuration.connectOrigins])];
  return [
    "default-src 'self';",
    `script-src 'self' 'nonce-${nonce}'${scriptOrigins.length ? ` ${scriptOrigins.join(" ")}` : ""};`,
    `style-src 'self' 'nonce-${nonce}';`,
    "style-src-attr 'unsafe-inline';",
    "img-src 'self' blob:;",
    "font-src 'self';",
    `connect-src 'self' ${connectOrigins.join(" ")};`,
    "frame-src 'self' blob:;",
    "media-src 'self' blob:;",
    "object-src 'none';",
    "base-uri 'self';",
    "form-action 'self';",
    "frame-ancestors 'none';",
    "manifest-src 'self';",
    "worker-src 'self';",
  ].join(" ");
}
