import { readCspConfiguration } from "../cspPolicy.mjs";

export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    // Fail the server startup instead of silently serving pages without a valid CSP.
    readCspConfiguration(process.env);
  }
}
