import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip as ReTooltip, XAxis, YAxis } from "recharts";
import { fmtClock, fmtDateTime } from "@/lib/format";
import type { MetricPoint, Range, UptimeSegment } from "@/lib/types";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

const RANGES: Range[] = ["1h", "6h", "24h", "7d"];

export function RangePicker({ value, onChange }: { value: Range; onChange: (r: Range) => void }) {
  return (
    <div role="group" aria-label="Time range" className="inline-flex rounded-md border p-0.5">
      {RANGES.map((r) => (
        <button key={r} type="button" aria-pressed={value === r} onClick={() => onChange(r)}
          className={cn("rounded-sm px-2.5 py-1 font-mono text-xs", value === r ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:text-foreground")}>
          {r}
        </button>
      ))}
    </div>
  );
}

/** Single-measure time series: one axis, 2px line, recessive grid, crosshair tooltip, and a table alternative. */
export function MetricChart({ data, dataKey, unit, label, range }: { data: MetricPoint[]; dataKey: keyof MetricPoint; unit: string; label: string; range: Range }) {
  const gradientId = `fill-${String(dataKey)}`;
  const value = (v: unknown) => (typeof v === "number" ? `${v.toFixed(unit === "%" ? 0 : 1)}${unit === "%" ? "%" : ` ${unit}`}` : "no reply");
  return (
    <div>
      <div className="h-64" role="img" aria-label={`${label} over the last ${range}`}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <defs>
              <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--chart-line)" stopOpacity={0.22} />
                <stop offset="100%" stopColor="var(--chart-line)" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid vertical={false} stroke="var(--chart-grid)" />
            <XAxis dataKey="t" tickFormatter={(t) => fmtClock(t, range === "7d")} minTickGap={56} tickLine={false} axisLine={false}
              tick={{ fill: "var(--muted-foreground)", fontSize: 11 }} />
            <YAxis width={44} tickLine={false} axisLine={false} tick={{ fill: "var(--muted-foreground)", fontSize: 11 }} domain={unit === "%" ? [0, 100] : [0, "auto"]} />
            <ReTooltip
              cursor={{ stroke: "var(--muted-foreground)", strokeDasharray: "3 3" }}
              contentStyle={{ background: "var(--popover)", border: "1px solid var(--border)", borderRadius: 6, fontSize: 12 }}
              labelStyle={{ color: "var(--muted-foreground)" }}
              itemStyle={{ color: "var(--foreground)" }}
              labelFormatter={(t) => fmtDateTime(String(t))}
              formatter={(v) => [value(v), label]}
            />
            <Area type="linear" dataKey={dataKey} stroke="var(--chart-line)" strokeWidth={2} fill={`url(#${gradientId})`}
              connectNulls={false} isAnimationActive={false} activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--card)" }} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      <details className="mt-2 text-xs text-muted-foreground">
        <summary className="cursor-pointer">View as table</summary>
        <div className="mt-2 max-h-48 overflow-auto rounded border">
          <table className="w-full">
            <thead><tr className="text-left"><th className="px-2 py-1 font-medium">Time</th><th className="px-2 py-1 font-medium">{label}</th></tr></thead>
            <tbody>{data.map((p) => <tr key={p.t} className="border-t"><td className="px-2 py-1">{fmtDateTime(p.t)}</td><td className="px-2 py-1 font-mono">{value(p[dataKey])}</td></tr>)}</tbody>
          </table>
        </div>
      </details>
    </div>
  );
}

const SEGMENT_CLS: Record<string, string> = { UP: "bg-status-up", DEGRADED: "bg-status-degraded", DOWN: "bg-status-down" };

/** Status-page style 24h bar. An empty segment means no observation, deliberately distinct from "up". */
export function UptimeBar({ segments }: { segments: UptimeSegment[] }) {
  return (
    <div>
      <div className="flex h-8 gap-[2px]">
        {segments.map((s) => (
          <Tooltip key={s.start}>
            <TooltipTrigger asChild>
              <div tabIndex={0} aria-label={`${fmtClock(s.start)}: ${s.status ?? "no data"}`}
                className={cn("flex-1 rounded-[2px]", s.status ? SEGMENT_CLS[s.status] : "bg-status-empty")} />
            </TooltipTrigger>
            <TooltipContent>
              <p>{fmtClock(s.start)} – {fmtClock(s.end)}</p>
              <p>{s.checks ? `${s.up} up · ${s.degraded} degraded · ${s.down} down` : "No data"}</p>
            </TooltipContent>
          </Tooltip>
        ))}
      </div>
      <div className="mt-1.5 flex justify-between text-xs text-muted-foreground"><span>24h ago</span><span>Now</span></div>
    </div>
  );
}
