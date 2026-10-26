export const MAX_TOKEN_AGE_MS: number;
export const INACTIVITY_LIMIT_MS: number;
export function tokenDeadlineFromJwt(token: string): number;
export function sessionTimeoutReason(now: number, lastActivity: number, tokenDeadline: number): "expired" | "inactivity" | null;
