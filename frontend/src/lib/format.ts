export const fmtMs = (v?: number | null) => (v == null ? "—" : v >= 100 ? `${Math.round(v)} ms` : `${v.toFixed(1)} ms`);
export const fmtPct = (v?: number | null, digits = 1) => (v == null ? "—" : `${v.toFixed(digits)}%`);
export const fmtDateTime = (iso: string) => new Date(iso).toLocaleString();
export const fmtClock = (iso: string, withDay = false) =>
  new Date(iso).toLocaleString([], withDay ? { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" } : { hour: "2-digit", minute: "2-digit" });

const scale = (v: number, base: number, units: string[]) => {
  let i = 0;
  while (v >= base && i < units.length - 1) { v /= base; i++; }
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
};
export const fmtBps = (v?: number | null) => (v == null ? "—" : scale(v, 1000, ["bps", "Kbps", "Mbps", "Gbps", "Tbps"]));
export const fmtCount = (v?: number | null) => (v == null ? "—" : v.toLocaleString());
export function fmtDuration(seconds?: number | null) {
  if (seconds == null) return "—";
  const d = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60);
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m`;
  return `${Math.round(seconds)}s`;
}
/** Converts a datetime-local input's value (local time, no zone) to an ISO instant. */
export const localToIso = (v: string) => new Date(v).toISOString();
/** Formats a Date for a datetime-local input, in local time. */
export function toLocalInput(d: Date) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
