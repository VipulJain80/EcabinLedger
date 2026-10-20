/** Formats visible date/time values consistently in India Standard Time. */
export function formatDateTime(value: string | number | Date | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const dateOnly = typeof value === "string" ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null;
  const date = value instanceof Date
    ? value
    : dateOnly
      ? new Date(Date.UTC(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]), 12))
      : new Date(value);
  if (Number.isNaN(date.getTime())) return "Unavailable";

  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Kolkata",
    day: "2-digit",
    month: "short",
    year: "numeric",
    ...(dateOnly ? {} : { hour: "2-digit", minute: "2-digit", hourCycle: "h23" as const }),
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value ?? "";
  return dateOnly ? `${part("day")} ${part("month")} ${part("year")}` : `${part("day")} ${part("month")} ${part("year")} ${part("hour")}:${part("minute")}`;
}
