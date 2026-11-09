import { Fragment, useEffect, useState, type FormEvent } from "react";
import { ArrowRight, Loader2, Route, ShieldAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState, FormField, PageHeader, Panel } from "@/components/common";
import { useCan } from "@/features/auth/AuthContext";
import { ApiError, api, post } from "@/lib/api";
import { fmtMs } from "@/lib/format";
import { useTopic } from "@/lib/live";
import type { TraceHop, TraceRun } from "@/lib/types";
import { isValidHost } from "@/lib/validation";

type TraceEvent = { type: "hop"; hop: TraceHop } | { type: "done"; run: TraceRun };

function upsert(hops: TraceHop[], hop: TraceHop) {
  return [...hops.filter((h) => h.hop !== hop.hop), hop].sort((a, b) => a.hop - b.hop);
}

function PathView({ run }: { run: TraceRun }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5 p-4" aria-label="Network path">
      <span className="rounded-md border bg-muted px-2 py-1 text-xs">NetPulse</span>
      {run.hops.map((h) => (
        <Fragment key={h.hop}>
          <ArrowRight aria-hidden className="size-3.5 text-muted-foreground" />
          <span title={h.hostname ?? h.ip ?? "No reply"} className={`rounded-md border px-2 py-1 font-mono text-xs ${h.ip === run.ip ? "border-status-up/40 bg-status-up/10" : h.ip ? "" : "border-dashed text-muted-foreground"}`}>
            <span className="mr-1 text-muted-foreground">{h.hop}</span>{h.ip ?? "*"}
          </span>
        </Fragment>
      ))}
      {run.status === "RUNNING" && <Loader2 aria-label="Tracing" className="ml-1 size-3.5 animate-spin text-muted-foreground" />}
    </div>
  );
}

const STATUS_TEXT: Record<TraceRun["status"], string> = { RUNNING: "Tracing…", COMPLETED: "Finished", TIMED_OUT: "Timed out", FAILED: "Failed" };

export function TraceroutePage() {
  const { operate } = useCan();
  const [target, setTarget] = useState("");
  const [error, setError] = useState<string>();
  const [starting, setStarting] = useState(false);
  const [run, setRun] = useState<TraceRun | null>(null);

  useTopic<TraceEvent>(run && run.status === "RUNNING" ? `/topic/traceroute/${run.id}` : null, (e) => {
    setRun((r) => {
      if (!r) return r;
      return e.type === "done" ? e.run : { ...r, hops: upsert(r.hops, e.hop) };
    });
  });

  // Hops sent before the subscription existed, or during a reconnect, are recovered from the server's copy.
  const running = run?.status === "RUNNING";
  const runId = run?.id;
  useEffect(() => {
    if (!running || !runId) return;
    const id = window.setInterval(() => { void api<TraceRun>(`/traceroute/${runId}`).then(setRun).catch(() => undefined); }, 2000);
    return () => window.clearInterval(id);
  }, [running, runId]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!isValidHost(target.trim())) { setError("Enter a valid IPv4, IPv6 or hostname"); return; }
    setError(undefined);
    setStarting(true);
    try {
      setRun(await post<TraceRun>("/traceroute", { target: target.trim() }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't start the trace");
    } finally {
      setStarting(false);
    }
  };

  if (!operate) {
    return <><PageHeader title="Traceroute" /><Panel><EmptyState icon={ShieldAlert} title="Operator access needed" description="Ask an admin for the Operator role to run traces." /></Panel></>;
  }

  return (
    <>
      <PageHeader title="Traceroute" description="See each router on the path to a host. Hops appear as they answer." />
      <Panel>
        <form onSubmit={submit} className="flex flex-wrap items-start gap-3 p-4">
          <div className="min-w-64 flex-1">
            <FormField id="target" label="IP or hostname" error={error}>
              <Input id="target" className="font-mono" placeholder="8.8.8.8" value={target} onChange={(e) => setTarget(e.target.value)} aria-invalid={!!error} />
            </FormField>
          </div>
          <Button type="submit" size="sm" className="mt-[26px]" disabled={starting || running}>{(starting || running) && <Loader2 className="animate-spin" />} {running ? "Tracing" : "Start trace"}</Button>
        </form>
      </Panel>

      {!run ? (
        <Panel className="mt-4"><EmptyState icon={Route} title="No trace yet" description="Enter a host to see the routers between NetPulse and it." /></Panel>
      ) : (
        <>
          <Panel className="mt-4" title={`Path to ${run.target}`} action={<span role="status" className="text-xs text-muted-foreground">{STATUS_TEXT[run.status]}{run.status !== "RUNNING" && !run.reachedTarget ? " · target didn't reply" : ""}</span>}>
            <PathView run={run} />
            {run.error && <p className="border-t px-4 py-2.5 text-xs text-status-down">{run.error}</p>}
          </Panel>
          <Panel className="mt-4" title="Hops">
            <div className="overflow-x-auto"><table className="w-full min-w-[640px] text-sm">
              <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">Hop</th><th className="px-3 py-2 font-medium">IP</th><th className="px-3 py-2 font-medium">Hostname</th><th className="px-4 py-2 font-medium">RTT per probe</th></tr></thead>
              <tbody className="divide-y">{run.hops.map((h) => (
                <tr key={h.hop} className="align-top">
                  <td className="px-4 py-2 font-mono text-xs">{h.hop}</td>
                  <td className="px-3 py-2 font-mono text-xs">{h.ip ?? <span className="text-muted-foreground">No reply</span>}{h.otherIps.map((o) => <div key={o} className="text-muted-foreground">{o}</div>)}</td>
                  <td className="px-3 py-2 text-xs [overflow-wrap:anywhere]">{h.hostname ?? "—"}</td>
                  <td className="px-4 py-2 font-mono text-xs tabular-nums whitespace-nowrap">{h.rttsMs.map((r) => (r == null ? "*" : fmtMs(r))).join("   ")}{h.note ? ` ${h.note}` : ""}</td>
                </tr>))}
              </tbody>
            </table></div>
          </Panel>
          <p className="mt-3 text-xs text-muted-foreground">A hop with no reply is usually a router that doesn't answer probes, not a broken path. Later hops that answer prove traffic got through.</p>
        </>
      )}
    </>
  );
}
