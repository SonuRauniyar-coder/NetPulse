export const fmtMs = (v?: number | null) => (v == null ? "—" : v >= 100 ? `${Math.round(v)} ms` : `${v.toFixed(1)} ms`);
export const fmtPct = (v?: number | null, digits = 1) => (v == null ? "—" : `${v.toFixed(digits)}%`);
export const fmtDateTime = (iso: string) => new Date(iso).toLocaleString();
export const fmtClock = (iso: string, withDay = false) =>
  new Date(iso).toLocaleString([], withDay ? { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" } : { hour: "2-digit", minute: "2-digit" });
