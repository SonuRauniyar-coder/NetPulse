import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Download, History, ScrollText } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState, FormField, PageHeader, Pager, Panel, RelativeTime, RowsSkeleton, StatusBadge } from "@/components/common";
import { useCan } from "@/features/auth/AuthContext";
import { api, downloadFile } from "@/lib/api";
import { localToIso } from "@/lib/format";
import type { AuditEntry, Page, StatusEvent } from "@/lib/types";

const ALL = "all";
const STATUSES = ["UP", "DEGRADED", "DOWN", "UNKNOWN", "PAUSED"];

function StatusEvents() {
  const [status, setStatus] = useState(ALL);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  useEffect(() => setPage(0), [status, from, to]);
  const filter = new URLSearchParams();
  if (status !== ALL) filter.set("status", status);
  if (from) filter.set("from", localToIso(from));
  if (to) filter.set("to", localToIso(to));
  const invalid = !!from && !!to && new Date(from) >= new Date(to);
  const q = useQuery({ queryKey: ["events", filter.toString(), page], queryFn: () => api<Page<StatusEvent>>(`/events?${filter}&page=${page}&size=25`), placeholderData: keepPreviousData, enabled: !invalid, refetchInterval: 30_000 });
  const exportCsv = () => downloadFile(`/events/export.csv?${filter}`, "netpulse-events.csv").catch((e: Error) => toast.error(e.message));
  return (
    <Panel title="Status changes" action={<Button size="sm" variant="outline" disabled={invalid} onClick={exportCsv}><Download /> Export CSV</Button>}>
      <div className="grid gap-3 border-b p-4 sm:grid-cols-3">
        <FormField label="New status"><Select value={status} onValueChange={setStatus}><SelectTrigger aria-label="New status" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value={ALL}>Any status</SelectItem>{STATUSES.map((s) => <SelectItem key={s} value={s}>{s[0] + s.slice(1).toLowerCase()}</SelectItem>)}</SelectContent></Select></FormField>
        <FormField id="e-from" label="From"><Input id="e-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
        <FormField id="e-to" label="To" error={invalid ? "Must be after the start" : undefined}><Input id="e-to" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} /></FormField>
      </div>
      {q.isError ? <ErrorState message={q.error.message} onRetry={() => q.refetch()} />
        : !q.data ? <RowsSkeleton />
        : q.data.content.length === 0 ? <EmptyState icon={History} title="No matching events" description="Every status change is recorded here." />
        : <div className="overflow-x-auto"><table className="w-full min-w-[640px] text-sm">
            <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">When</th><th className="px-3 py-2 font-medium">Device</th><th className="px-3 py-2 font-medium">Change</th><th className="px-4 py-2 font-medium">Reason</th></tr></thead>
            <tbody className="divide-y">{q.data.content.map((e) => (
              <tr key={e.id}>
                <td className="px-4 py-2 text-xs"><RelativeTime iso={e.occurredAt} /></td>
                <td className="px-3 py-2"><Link className="hover:underline" to={`/devices/${e.deviceId}`}>{e.deviceName}</Link></td>
                <td className="px-3 py-2"><span className="inline-flex items-center gap-1.5"><StatusBadge status={e.fromStatus} /><span aria-hidden className="text-muted-foreground">→</span><StatusBadge status={e.toStatus} /></span></td>
                <td className="px-4 py-2 text-xs text-muted-foreground">{e.reason}</td>
              </tr>))}
            </tbody>
          </table></div>}
      {q.data && <Pager page={page} totalPages={q.data.page.totalPages} onChange={setPage} />}
    </Panel>
  );
}

function AuditLog() {
  const [actor, setActor] = useState("");
  const [page, setPage] = useState(0);
  useEffect(() => setPage(0), [actor]);
  const qs = new URLSearchParams({ page: String(page), size: "25" });
  if (actor.trim()) qs.set("actor", actor.trim());
  const q = useQuery({ queryKey: ["audit", qs.toString()], queryFn: () => api<Page<AuditEntry>>(`/audit?${qs}`), placeholderData: keepPreviousData });
  return (
    <Panel title="Audit log" action={<Input aria-label="Filter by user" placeholder="Exact username" className="h-8 w-44" value={actor} onChange={(e) => setActor(e.target.value)} />}>
      {q.isError ? <ErrorState message={q.error.message} onRetry={() => q.refetch()} />
        : !q.data ? <RowsSkeleton />
        : q.data.content.length === 0 ? <EmptyState icon={ScrollText} title="Nothing recorded" description="Device edits, rule changes, user changes and scans appear here." />
        : <div className="overflow-x-auto"><table className="w-full min-w-[640px] text-sm">
            <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">When</th><th className="px-3 py-2 font-medium">User</th><th className="px-3 py-2 font-medium">Action</th><th className="px-3 py-2 font-medium">Detail</th><th className="px-4 py-2 font-medium">Source IP</th></tr></thead>
            <tbody className="divide-y">{q.data.content.map((a) => (
              <tr key={a.id}>
                <td className="px-4 py-2 text-xs"><RelativeTime iso={a.occurredAt} /></td>
                <td className="px-3 py-2 font-mono text-xs">{a.actor}</td>
                <td className="px-3 py-2 font-mono text-xs">{a.action}</td>
                <td className="px-3 py-2 text-xs [overflow-wrap:anywhere]">{a.summary}</td>
                <td className="px-4 py-2 font-mono text-xs text-muted-foreground">{a.clientIp ?? "—"}</td>
              </tr>))}
            </tbody>
          </table></div>}
      {q.data && <Pager page={page} totalPages={q.data.page.totalPages} onChange={setPage} />}
    </Panel>
  );
}

export function EventLogPage() {
  const { admin } = useCan();
  if (!admin) return <><PageHeader title="Event Log" description="Every status change, with its reason." /><StatusEvents /></>;
  return (
    <>
      <PageHeader title="Event Log" description="Every status change, and who changed what." />
      <Tabs defaultValue="status">
        <TabsList><TabsTrigger value="status">Status changes</TabsTrigger><TabsTrigger value="audit">Audit log</TabsTrigger></TabsList>
        <TabsContent value="status" className="mt-3"><StatusEvents /></TabsContent>
        <TabsContent value="audit" className="mt-3"><AuditLog /></TabsContent>
      </Tabs>
    </>
  );
}
