import { useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { BellOff, Cable, ChartLine, ChevronLeft, ChevronRight, Globe, History, Network, Pencil, RefreshCw, ShieldAlert } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState, PageHeader, Panel, RelativeTime, RowsSkeleton, StatusBadge } from "@/components/common";
import { MetricChart, RangePicker, UptimeBar } from "@/components/charts";
import { ApiError, api, post } from "@/lib/api";
import { fmtMs, fmtPct } from "@/lib/format";
import type { CheckDetail, CheckSummary, Device, MetricPoint, Page, Range, StatusEvent, TcpDetail, UptimeResponse } from "@/lib/types";
import { NotFound } from "@/pages/misc";
import { DeviceFormDialog } from "./DeviceFormDialog";

const LIVE = 30_000;

function Fact({ label, children, mono }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="flex justify-between gap-4 px-4 py-2.5 text-sm">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={`min-w-0 truncate text-right ${mono ? "font-mono text-xs" : ""}`}>{children}</dd>
    </div>
  );
}

const TCP_LABEL: Record<TcpDetail["state"], string> = { OPEN: "Open", CLOSED: "Closed (refused)", FILTERED_OR_UNKNOWN: "Filtered or unknown" };

function LatencyTab({ id }: { id: string }) {
  const [range, setRange] = useState<Range>("1h");
  const q = useQuery({ queryKey: ["devices", id, "metrics", range], queryFn: () => api<MetricPoint[]>(`/devices/${id}/metrics?range=${range}`), refetchInterval: LIVE });
  const charts: { key: keyof MetricPoint; unit: string; label: string; title: string }[] = [
    { key: "rttAvg", unit: "ms", label: "Avg RTT", title: "Latency" },
    { key: "lossPct", unit: "%", label: "Packet loss", title: "Packet loss" },
    { key: "jitter", unit: "ms", label: "Jitter", title: "Jitter" },
  ];
  return (
    <div className="space-y-4">
      <div className="flex justify-end"><RangePicker value={range} onChange={setRange} /></div>
      {q.isError ? <Panel><ErrorState message={q.error.message} onRetry={() => q.refetch()} /></Panel>
        : !q.data ? <Skeleton className="h-64" />
        : q.data.length === 0 ? <Panel><EmptyState icon={ChartLine} title="No measurements in this range" description="Charts fill in as ping checks complete." /></Panel>
        : charts.map((c) => <Panel key={c.key} title={c.title}><div className="p-4"><MetricChart data={q.data} dataKey={c.key} unit={c.unit} label={c.label} range={range} /></div></Panel>)}
    </div>
  );
}

function HistoryTab({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const checks = useQuery({ queryKey: ["devices", id, "checks", page], queryFn: () => api<Page<CheckSummary>>(`/devices/${id}/checks?size=20&page=${page}`), placeholderData: keepPreviousData, refetchInterval: LIVE });
  const events = useQuery({ queryKey: ["devices", id, "events"], queryFn: () => api<Page<StatusEvent>>(`/devices/${id}/events?size=10`), refetchInterval: LIVE });
  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <Panel title="Check history" className="lg:col-span-2">
        {checks.isError ? <ErrorState message={checks.error.message} onRetry={() => checks.refetch()} />
          : !checks.data ? <RowsSkeleton />
          : checks.data.content.length === 0 ? <EmptyState icon={History} title="No checks yet" description="Results are listed here after the first cycle." />
          : <div className="overflow-x-auto"><table className="w-full min-w-[560px] text-sm">
              <thead className="border-b text-left text-xs text-muted-foreground"><tr>
                <th className="px-4 py-2 font-medium">Time</th><th className="px-3 py-2 font-medium">Status</th><th className="px-3 py-2 text-right font-medium">RTT</th><th className="px-3 py-2 text-right font-medium">Loss</th><th className="px-4 py-2 font-medium">Result</th>
              </tr></thead>
              <tbody className="divide-y">{checks.data.content.map((c) => (
                <tr key={c.id}>
                  <td className="px-4 py-2 text-xs"><RelativeTime iso={c.checkedAt} /></td>
                  <td className="px-3 py-2"><StatusBadge status={c.status} /></td>
                  <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtMs(c.ping?.rttAvg)}</td>
                  <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtPct(c.ping?.lossPct, 0)}</td>
                  <td className="max-w-64 truncate px-4 py-2 text-xs text-muted-foreground" title={c.reason}>{c.reason}</td>
                </tr>))}
              </tbody>
            </table></div>}
        {checks.data && checks.data.page.totalPages > 1 && (
          <footer className="flex items-center justify-end gap-2 border-t px-4 py-2.5 text-xs text-muted-foreground">
            <span>Page {page + 1} of {checks.data.page.totalPages}</span>
            <Button variant="outline" size="icon-xs" aria-label="Newer" disabled={page === 0} onClick={() => setPage(page - 1)}><ChevronLeft /></Button>
            <Button variant="outline" size="icon-xs" aria-label="Older" disabled={page + 1 >= checks.data.page.totalPages} onClick={() => setPage(page + 1)}><ChevronRight /></Button>
          </footer>
        )}
      </Panel>
      <Panel title="Status changes">
        {!events.data ? <RowsSkeleton rows={3} /> : events.data.content.length === 0
          ? <EmptyState icon={History} title="No changes yet" description="State transitions appear here." />
          : <ul className="divide-y">{events.data.content.map((e) => (
              <li key={e.id} className="px-4 py-2.5">
                <div className="flex items-center gap-2"><StatusBadge status={e.fromStatus} /><span aria-hidden className="text-muted-foreground">→</span><StatusBadge status={e.toStatus} /></div>
                <p className="mt-1 text-xs text-muted-foreground"><RelativeTime iso={e.occurredAt} />{e.reason ? ` · ${e.reason}` : ""}</p>
              </li>))}
            </ul>}
      </Panel>
    </div>
  );
}

export function DeviceDetailPage() {
  const { id = "" } = useParams();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const device = useQuery({ queryKey: ["devices", id, "detail"], queryFn: () => api<Device>(`/devices/${id}`), refetchInterval: LIVE, retry: (n, e) => !(e instanceof ApiError && e.status < 500) && n < 1 });
  const latest = useQuery({ queryKey: ["devices", id, "latest"], queryFn: () => api<CheckDetail | undefined>(`/devices/${id}/checks/latest`).then((v) => v ?? null), refetchInterval: LIVE });
  const uptime = useQuery({ queryKey: ["devices", id, "uptime"], queryFn: () => api<UptimeResponse>(`/devices/${id}/uptime`), refetchInterval: LIVE });

  if (device.error instanceof ApiError && [400, 404].includes(device.error.status)) return <NotFound />;
  if (device.isError) return <Panel><ErrorState message={device.error.message} onRetry={() => device.refetch()} /></Panel>;
  if (!device.data) return <RowsSkeleton rows={6} />;
  const d = device.data;
  const check = latest.data;

  const checkNow = () => post(`/devices/${id}/check-now`).then(() => toast.success("Check queued")).catch((e: Error) => toast.error(e.message));

  return (
    <>
      <PageHeader title={d.name} description={`${d.type[0] + d.type.slice(1).toLowerCase()}${d.location ? ` · ${d.location}` : ""}`}
        action={<div className="flex items-center gap-2">
          <Button size="sm" variant="outline" disabled={!d.enabled} onClick={checkNow}><RefreshCw /> Check now</Button>
          <Button size="sm" variant="outline" onClick={() => setEditing(true)}><Pencil /> Edit</Button>
        </div>} />
      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
        <StatusBadge status={d.status} />
        <span className="font-mono text-xs">{d.host}</span>
        {d.icmpBlocked && <span className="inline-flex items-center gap-1 text-xs text-muted-foreground"><ShieldAlert aria-hidden className="size-3.5" /> ICMP blocked; reachable over TCP</span>}
        <span className="text-xs text-muted-foreground">Checked <RelativeTime iso={d.lastCheckedAt} fallback="not yet" /></span>
      </div>

      <Tabs defaultValue="overview">
        <div className="overflow-x-auto"><TabsList>
          {["Overview", "Latency & Loss", "Ports", "DNS", "SNMP Interfaces", "Alerts", "History"].map((t) => <TabsTrigger key={t} value={t.split(" ")[0].toLowerCase()}>{t}</TabsTrigger>)}
        </TabsList></div>

        <TabsContent value="overview" className="mt-3 space-y-4">
          <Panel title="Last 24 hours" action={<span className="font-mono text-xs tabular-nums text-muted-foreground">{uptime.data ? `${fmtPct(uptime.data.uptimePct, 2)} uptime` : ""}</span>}>
            <div className="p-4">{uptime.isError ? <ErrorState message={uptime.error.message} onRetry={() => uptime.refetch()} /> : uptime.data ? <UptimeBar segments={uptime.data.segments} /> : <Skeleton className="h-8" />}</div>
          </Panel>
          <div className="grid gap-4 lg:grid-cols-2">
            <Panel title="Latest ping">
              {!check?.check.ping ? <EmptyState icon={ChartLine} title="No result yet" description={d.enabled ? "The first check is on its way." : "Monitoring is paused."} /> : (
                <dl className="divide-y">
                  <Fact label="Probes" mono>{check.check.ping.received} of {check.check.ping.sent} replied</Fact>
                  <Fact label="Packet loss" mono>{fmtPct(check.check.ping.lossPct, 0)}</Fact>
                  <Fact label="RTT min / avg / max" mono>{fmtMs(check.check.ping.rttMin)} / {fmtMs(check.check.ping.rttAvg)} / {fmtMs(check.check.ping.rttMax)}</Fact>
                  <Fact label="Jitter" mono>{fmtMs(check.check.ping.jitter)}</Fact>
                  {check.check.ping.errorCategory && <Fact label="Error">{check.check.ping.errorCategory.replaceAll("_", " ").toLowerCase()}</Fact>}
                  <Fact label="Assessment">{check.check.reason}</Fact>
                </dl>)}
            </Panel>
            <Panel title="Device">
              <dl className="divide-y">
                <Fact label="Host" mono>{d.host}</Fact>
                <Fact label="Resolved IP" mono>{d.resolvedIp ?? "—"}</Fact>
                <Fact label="Check interval" mono>{d.checkIntervalSeconds}s</Fact>
                <Fact label="Tags">{d.tags.length ? d.tags.join(", ") : "—"}</Fact>
                <Fact label="Status since"><RelativeTime iso={d.statusChangedAt} fallback="—" /></Fact>
                <Fact label="Added"><RelativeTime iso={d.createdAt} /></Fact>
              </dl>
            </Panel>
          </div>
        </TabsContent>

        <TabsContent value="latency" className="mt-3"><LatencyTab id={id} /></TabsContent>

        <TabsContent value="ports" className="mt-3">
          <Panel>
            {!check?.tcp.length ? <EmptyState icon={Cable} title="No port results" description={d.ports.length ? "Results appear after the next check." : "Edit this device to add TCP ports to check."} action={!d.ports.length ? <Button size="sm" variant="outline" onClick={() => setEditing(true)}>Add ports</Button> : undefined} />
              : <table className="w-full text-sm"><thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">Port</th><th className="px-3 py-2 font-medium">State</th><th className="px-3 py-2 text-right font-medium">Connect time</th><th className="px-4 py-2 font-medium">Detail</th></tr></thead>
                <tbody className="divide-y">{check.tcp.map((t) => (
                  <tr key={t.port}>
                    <td className="px-4 py-2 font-mono text-xs">{t.port}{d.ports.find((p) => p.port === t.port)?.label ? ` (${d.ports.find((p) => p.port === t.port)?.label})` : ""}</td>
                    <td className="px-3 py-2 text-xs">{TCP_LABEL[t.state]}</td>
                    <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtMs(t.connectMs)}</td>
                    <td className="px-4 py-2 text-xs text-muted-foreground">{t.error}</td>
                  </tr>))}
                </tbody></table>}
          </Panel>
        </TabsContent>

        <TabsContent value="dns" className="mt-3">
          <Panel>
            {!check?.dns.length ? <EmptyState icon={Globe} title="No DNS results" description={d.dnsChecks.length ? "Results appear after the next check." : "Edit this device to add DNS lookups."} action={!d.dnsChecks.length ? <Button size="sm" variant="outline" onClick={() => setEditing(true)}>Add lookups</Button> : undefined} />
              : <div className="overflow-x-auto"><table className="w-full min-w-[640px] text-sm"><thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">Query</th><th className="px-3 py-2 font-medium">Server</th><th className="px-3 py-2 font-medium">Result</th><th className="px-3 py-2 text-right font-medium">Time</th><th className="px-4 py-2 font-medium">Answers</th></tr></thead>
                <tbody className="divide-y">{check.dns.map((r, i) => (
                  <tr key={i} className="align-top">
                    <td className="px-4 py-2 font-mono text-xs">{r.recordType} {r.queryName}</td>
                    <td className="px-3 py-2 font-mono text-xs text-muted-foreground">{r.dnsServer ?? "system resolver"}</td>
                    <td className="px-3 py-2 font-mono text-xs">{r.outcome}</td>
                    <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtMs(r.resolveMs)}</td>
                    <td className="px-4 py-2 font-mono text-xs break-all">{r.answers.length ? r.answers.map((a) => <div key={a}>{a}</div>) : <span className="text-muted-foreground">{r.error ?? "—"}</span>}</td>
                  </tr>))}
                </tbody></table></div>}
          </Panel>
        </TabsContent>

        <TabsContent value="snmp" className="mt-3"><Panel><EmptyState icon={Network} title="Planned for Phase 2" description="Interface status, bandwidth utilization and sparklines come from SNMP polling." /></Panel></TabsContent>
        <TabsContent value="alerts" className="mt-3"><Panel><EmptyState icon={BellOff} title="Planned for Phase 3" description="Per-device alert history arrives with the alert engine." /></Panel></TabsContent>
        <TabsContent value="history" className="mt-3"><HistoryTab id={id} /></TabsContent>
      </Tabs>

      <DeviceFormDialog open={editing} device={d} onOpenChange={(o) => { setEditing(o); if (!o) void qc.invalidateQueries({ queryKey: ["devices", id] }); }} />
      <p className="mt-6 text-xs"><Link to="/devices" className="text-muted-foreground hover:text-foreground">← All devices</Link></p>
    </>
  );
}
