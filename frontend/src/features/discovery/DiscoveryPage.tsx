import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Loader2, Plus, Radar, ShieldAlert, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState, ErrorState, FormField, PageHeader, Panel, RelativeTime, RowsSkeleton } from "@/components/common";
import { useCan } from "@/features/auth/AuthContext";
import { DeviceFormDialog } from "@/features/devices/DeviceFormDialog";
import { api, post } from "@/lib/api";
import { fmtMs } from "@/lib/format";
import type { DiscoveredHost, Page, Scan, ScanDetail } from "@/lib/types";
import { isCidr } from "@/lib/validation";

const STATUS: Record<Scan["status"], string> = { RUNNING: "Scanning", COMPLETED: "Completed", PARTIAL: "Partial", FAILED: "Failed", INTERRUPTED: "Interrupted" };

function Progress({ scan }: { scan: Scan }) {
  const pct = scan.total ? Math.round((scan.scanned * 100) / scan.total) : 0;
  return (
    <div>
      <div className="flex justify-between text-xs text-muted-foreground"><span>{scan.scanned} of {scan.total} addresses probed</span><span className="font-mono">{pct}%</span></div>
      <div role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label="Scan progress" className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-status-empty">
        <div className="h-full rounded-full transition-[width]" style={{ width: `${pct}%`, background: "var(--chart-line)" }} />
      </div>
    </div>
  );
}

export function DiscoveryPage() {
  const { operate } = useCan();
  const qc = useQueryClient();
  const [cidr, setCidr] = useState("");
  const [error, setError] = useState<string>();
  const [scanId, setScanId] = useState<string | null>(null);
  const [adding, setAdding] = useState<DiscoveredHost | null>(null);

  const history = useQuery({ queryKey: ["discovery", "history"], queryFn: () => api<Page<Scan>>("/discovery/scans?size=8"), enabled: operate });
  const activeId = scanId ?? history.data?.content[0]?.id ?? null;
  const detail = useQuery({
    queryKey: ["discovery", "scan", activeId],
    queryFn: () => api<ScanDetail>(`/discovery/scans/${activeId}`),
    enabled: operate && !!activeId,
    refetchInterval: (q) => (q.state.data?.scan.status === "RUNNING" ? 1000 : false),
  });
  const start = useMutation({
    mutationFn: (c: string) => post<Scan>("/discovery/scans", { cidr: c }),
    onSuccess: (s) => { setScanId(s.id); void qc.invalidateQueries({ queryKey: ["discovery", "history"] }); },
    onError: (e: Error) => setError(e.message),
  });

  if (!operate) {
    return <><PageHeader title="Discovery" /><Panel><EmptyState icon={ShieldAlert} title="Operator access needed" description="Ask an admin for the Operator role to run scans." /></Panel></>;
  }

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!isCidr(cidr)) { setError("Enter a network in CIDR form, like 192.168.1.0/24"); return; }
    setError(undefined);
    start.mutate(cidr.trim());
  };
  const scan = detail.data?.scan;
  const running = scan?.status === "RUNNING";

  return (
    <>
      <PageHeader title="Discovery" description="Find live hosts on a local network, with MAC vendor and reverse DNS." />
      <div role="note" className="mb-4 flex items-center gap-2 rounded-lg border border-status-degraded/30 bg-status-degraded/10 px-4 py-2.5 text-sm text-status-degraded">
        <TriangleAlert aria-hidden className="size-4 shrink-0" /> Scan only networks you own or are authorized to scan.
      </div>
      <Panel>
        <form onSubmit={submit} className="flex flex-wrap items-start gap-3 p-4">
          <div className="min-w-64 flex-1">
            <FormField id="scan-cidr" label="Network" error={error} hint="Up to a /22 (1,024 addresses), inside the ranges an admin has authorized.">
              <Input id="scan-cidr" className="font-mono" placeholder="192.168.1.0/24" value={cidr} onChange={(e) => setCidr(e.target.value)} aria-invalid={!!error} />
            </FormField>
          </div>
          <Button type="submit" size="sm" className="mt-[26px]" disabled={start.isPending || running}>{(start.isPending || running) && <Loader2 className="animate-spin" />} {running ? "Scanning" : "Start scan"}</Button>
        </form>
      </Panel>

      {detail.isError ? <Panel className="mt-4"><ErrorState message={detail.error.message} onRetry={() => detail.refetch()} /></Panel>
        : !activeId ? <Panel className="mt-4"><EmptyState icon={Radar} title="No scans yet" description="Results list every host that answered, and whether it's already monitored." /></Panel>
        : !detail.data ? <Panel className="mt-4"><RowsSkeleton /></Panel>
        : (
          <Panel className="mt-4" title={`Results for ${scan!.cidr}`} action={<span role="status" className="text-xs text-muted-foreground">{STATUS[scan!.status]} · {scan!.found} found</span>}>
            {(running || scan!.error) && <div className="space-y-2 border-b p-4">{running && <Progress scan={scan!} />}{scan!.error && <p className="text-xs text-status-degraded">{scan!.error}</p>}</div>}
            {detail.data.hosts.length === 0
              ? <EmptyState icon={Radar} title={running ? "Probing…" : "No hosts answered"} description={running ? "Hosts are listed once the sweep finishes." : "Nothing responded to ping or ARP in this range."} />
              : <div className="overflow-x-auto"><table className="w-full min-w-[760px] text-sm">
                  <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">IP</th><th className="px-3 py-2 font-medium">Hostname</th><th className="px-3 py-2 font-medium">MAC</th><th className="px-3 py-2 font-medium">Vendor</th><th className="px-3 py-2 text-right font-medium">RTT</th><th aria-label="Actions" className="px-4 py-2" /></tr></thead>
                  <tbody className="divide-y">{detail.data.hosts.map((h) => (
                    <tr key={h.ip}>
                      <td className="px-4 py-2 font-mono text-xs">{h.ip}</td>
                      <td className="px-3 py-2 text-xs [overflow-wrap:anywhere]">{h.hostname ?? "—"}</td>
                      <td className="px-3 py-2 font-mono text-xs">{h.mac ?? "—"}</td>
                      <td className="max-w-56 truncate px-3 py-2 text-xs" title={h.vendor}>{h.vendor ?? "—"}</td>
                      <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{h.icmpResponded ? fmtMs(h.rttMs) : <span title="Answered ARP but not ping" className="font-sans text-muted-foreground">ARP only</span>}</td>
                      <td className="px-4 py-2 text-right">{h.monitored
                        ? <span className="inline-flex items-center gap-1 text-xs text-muted-foreground"><Check aria-hidden className="size-3.5" /> Monitored</span>
                        : <Button size="xs" variant="outline" onClick={() => setAdding(h)}><Plus /> Add to monitoring</Button>}</td>
                    </tr>))}
                  </tbody>
                </table></div>}
          </Panel>
        )}

      {!!history.data?.content.length && (
        <Panel className="mt-4" title="Recent scans">
          <ul className="divide-y">{history.data.content.map((s) => (
            <li key={s.id}><button type="button" onClick={() => setScanId(s.id)} className="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm hover:bg-muted">
              <span className="font-mono text-xs">{s.cidr}</span><span className="text-xs text-muted-foreground">{STATUS[s.status]} · {s.found} found · by {s.requestedBy}</span>
              <span className="ml-auto text-xs text-muted-foreground"><RelativeTime iso={s.startedAt} /></span>
            </button></li>))}
          </ul>
        </Panel>
      )}

      <DeviceFormDialog open={!!adding} initial={adding ? { name: adding.hostname ?? adding.ip, host: adding.ip } : undefined}
        onOpenChange={(o) => { if (!o) { setAdding(null); void qc.invalidateQueries({ queryKey: ["discovery"] }); } }} />
    </>
  );
}
