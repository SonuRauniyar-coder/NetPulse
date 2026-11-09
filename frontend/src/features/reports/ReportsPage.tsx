import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, CircleX, Download, FileText } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState, ErrorState, FormField, PageHeader, Panel, RowsSkeleton } from "@/components/common";
import { api, downloadFile } from "@/lib/api";
import { fmtCount, fmtDuration, fmtPct, localToIso, toLocalInput } from "@/lib/format";
import type { UptimeReport } from "@/lib/types";

const DAY = 86_400_000;

export function ReportsPage() {
  const [from, setFrom] = useState(() => toLocalInput(new Date(Date.now() - 7 * DAY)));
  const [to, setTo] = useState(() => toLocalInput(new Date()));
  const [sla, setSla] = useState("99.9");
  const valid = !!from && !!to && new Date(from) < new Date(to) && sla !== "" && Number(sla) >= 0 && Number(sla) <= 100;
  const qs = valid ? `from=${encodeURIComponent(localToIso(from))}&to=${encodeURIComponent(localToIso(to))}&slaTarget=${encodeURIComponent(sla)}` : "";
  const q = useQuery({ queryKey: ["reports", "uptime", qs], queryFn: () => api<UptimeReport>(`/reports/uptime?${qs}`), enabled: valid });
  const exportCsv = () => downloadFile(`/reports/uptime/export.csv?${qs}`, "netpulse-uptime.csv").catch((e: Error) => toast.error(e.message));

  return (
    <>
      <PageHeader title="Reports" description="Uptime and SLA per device. Paused time and maintenance windows don't count." action={<Button size="sm" variant="outline" disabled={!q.data} onClick={exportCsv}><Download /> Export CSV</Button>} />
      <Panel>
        <div className="grid gap-3 p-4 sm:grid-cols-3">
          <FormField id="r-from" label="From"><Input id="r-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
          <FormField id="r-to" label="To" error={from && to && new Date(from) >= new Date(to) ? "Must be after the start" : undefined}><Input id="r-to" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} /></FormField>
          <FormField id="r-sla" label="SLA target (%)" error={sla === "" || Number(sla) < 0 || Number(sla) > 100 || Number.isNaN(Number(sla)) ? "Enter 0 to 100" : undefined}><Input id="r-sla" className="font-mono" inputMode="decimal" value={sla} onChange={(e) => setSla(e.target.value)} /></FormField>
        </div>
      </Panel>
      <Panel className="mt-4" title="Availability">
        {q.isError ? <ErrorState message={q.error.message} onRetry={() => q.refetch()} />
          : !q.data ? (valid ? <RowsSkeleton /> : <EmptyState icon={FileText} title="Choose a valid range" description="Set a start, an end and an SLA target." />)
          : q.data.devices.length === 0 ? <EmptyState icon={FileText} title="No devices" description="Add devices to report on their availability." />
          : <div className="overflow-x-auto"><table className="w-full min-w-[760px] text-sm">
              <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">Device</th><th className="px-3 py-2 text-right font-medium">Uptime</th><th className="px-3 py-2 font-medium">SLA</th><th className="px-3 py-2 text-right font-medium">Checks</th><th className="px-3 py-2 text-right font-medium">Downtime</th><th className="px-4 py-2 text-right font-medium">Incidents</th></tr></thead>
              <tbody className="divide-y">{q.data.devices.map((d) => (
                <tr key={d.deviceId}>
                  <td className="px-4 py-2"><Link className="hover:underline" to={`/devices/${d.deviceId}`}>{d.name}</Link><span className="ml-2 font-mono text-xs text-muted-foreground">{d.host}</span></td>
                  <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtPct(d.uptimePct, 3)}</td>
                  <td className="px-3 py-2 text-xs">{d.slaMet == null ? <span className="text-muted-foreground">No data</span> : d.slaMet
                    ? <span className="inline-flex items-center gap-1 text-status-up"><CheckCircle2 aria-hidden className="size-3.5" /> Met</span>
                    : <span className="inline-flex items-center gap-1 text-status-down"><CircleX aria-hidden className="size-3.5" /> Missed</span>}</td>
                  <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtCount(d.observedChecks)}</td>
                  <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtDuration(d.downtimeSeconds)}</td>
                  <td className="px-4 py-2 text-right font-mono text-xs tabular-nums">{d.incidents}</td>
                </tr>))}
              </tbody>
            </table></div>}
      </Panel>
    </>
  );
}
