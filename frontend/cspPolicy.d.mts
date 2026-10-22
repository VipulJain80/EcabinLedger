export type CspConfiguration = {
  mode: "enforce" | "report-only";
  apiOrigin: string;
  scriptOrigins: string[];
  connectOrigins: string[];
};

export function readCspConfiguration(environment?: NodeJS.ProcessEnv): CspConfiguration;
export function buildContentSecurityPolicy(nonce: string, configuration: CspConfiguration): string;
