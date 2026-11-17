import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Network, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { EmptyState, ErrorState, FormField, Panel, RelativeTime, RowsSkeleton } from "@/components/common";
import { TrafficSparkline, UtilizationBar } from "@/components/charts";
import { useCan } from "@/features/auth/AuthContext";
import { ApiError, api, post, put } from "@/lib/api";
import { fmtBps, fmtDuration } from "@/lib/format";
import type { SnmpConfig, SnmpInterface, SnmpVersion, SnmpView } from "@/lib/types";

const OPER: Record<number, string> = { 1: "Up", 2: "Down", 3: "Testing", 4: "Unknown", 5: "Dormant", 6: "Not present", 7: "Lower layer down" };

function interfaceState(i: SnmpInterface) {
  if (i.adminStatus === 2) return { text: "Admin down", cls: "text-status-neutral" };
  const text = OPER[i.operStatus ?? 4] ?? "Unknown";
  return { text, cls: i.operStatus === 1 ? "text-status-up" : i.operStatus === 2 || i.operStatus === 7 ? "text-status-down" : "text-status-neutral" };
}

function ConfigDialog({ deviceId, config, open, onOpenChange }: { deviceId: string; config?: SnmpConfig; open: boolean; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const [version, setVersion] = useState<SnmpVersion>("V2C");
  const [enabled, setEnabled] = useState(true);
  const [port, setPort] = useState("161");
  const [interval, setIntervalValue] = useState("60");
  const [community, setCommunity] = useState("");
  const [username, setUsername] = useState("");
  const [authProtocol, setAuthProtocol] = useState("SHA");
  const [authPassword, setAuthPassword] = useState("");
  const [privPassword, setPrivPassword] = useState("");
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!open) return;
    setVersion(config?.version ?? "V2C"); setEnabled(config?.enabled ?? true); setPort(String(config?.port ?? 161)); setIntervalValue(String(config?.intervalSeconds ?? 60));
    setCommunity(""); setUsername(""); setAuthPassword(""); setPrivPassword(""); setError(undefined);
  }, [open, config]);

  const save = useMutation({
    mutationFn: () => put(`/devices/${deviceId}/snmp`, { enabled, version, port: Number(port), intervalSeconds: Number(interval), community: community || undefined, username: username || undefined, authProtocol: version === "V3" ? authProtocol : undefined, authPassword: authPassword || undefined, privPassword: privPassword || undefined }),
    onSuccess: () => { toast.success("SNMP settings saved. Polling starts shortly."); void qc.invalidateQueries({ queryKey: ["devices", deviceId, "snmp"] }); onOpenChange(false); },
    onError: (e: Error) => setError(e instanceof ApiError && e.fieldErrors ? Object.values(e.fieldErrors).join(" ") : e.message),
  });
  const keep = config && config.version === version ? "Leave blank to keep the saved value." : undefined;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
        <DialogHeader><DialogTitle>SNMP settings</DialogTitle><DialogDescription>Credentials are encrypted when saved and are never shown again.</DialogDescription></DialogHeader>
        <form id="snmp-form" className="space-y-3" onSubmit={(e) => { e.preventDefault(); setError(undefined); save.mutate(); }}>
          <label className="flex items-center justify-between text-sm font-medium">Polling enabled <Switch checked={enabled} onCheckedChange={setEnabled} aria-label="Polling enabled" /></label>
          <FormField label="Version">
            <Select value={version} onValueChange={(v) => setVersion(v as SnmpVersion)}><SelectTrigger aria-label="Version" className="w-full"><SelectValue /></SelectTrigger>
              <SelectContent><SelectItem value="V2C">v2c (community string, not encrypted)</SelectItem><SelectItem value="V3">v3 authPriv (signed and encrypted)</SelectItem></SelectContent></Select>
          </FormField>
          <div className="grid grid-cols-2 gap-3">
            <FormField id="snmp-port" label="UDP port"><Input id="snmp-port" className="font-mono" inputMode="numeric" value={port} onChange={(e) => setPort(e.target.value)} /></FormField>
            <FormField id="snmp-int" label="Interval (seconds)"><Input id="snmp-int" className="font-mono" inputMode="numeric" value={interval} onChange={(e) => setIntervalValue(e.target.value)} /></FormField>
          </div>
          {version === "V2C" ? (
            <FormField id="snmp-comm" label="Community" hint={keep}><Input id="snmp-comm" type="password" autoComplete="off" value={community} onChange={(e) => setCommunity(e.target.value)} /></FormField>
          ) : (
            <>
              <FormField id="snmp-user" label="Username" hint={keep}><Input id="snmp-user" autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} /></FormField>
              <FormField label="Authentication">
                <Select value={authProtocol} onValueChange={setAuthProtocol}><SelectTrigger aria-label="Authentication protocol" className="w-full"><SelectValue /></SelectTrigger>
                  <SelectContent><SelectItem value="SHA">SHA-1</SelectItem><SelectItem value="SHA256">SHA-256</SelectItem></SelectContent></Select>
              </FormField>
              <FormField id="snmp-auth" label="Authentication passphrase" hint="At least 8 characters."><Input id="snmp-auth" type="password" autoComplete="off" value={authPassword} onChange={(e) => setAuthPassword(e.target.value)} /></FormField>
              <FormField id="snmp-priv" label="Privacy passphrase (AES-128)" hint="At least 8 characters."><Input id="snmp-priv" type="password" autoComplete="off" value={privPassword} onChange={(e) => setPrivPassword(e.target.value)} /></FormField>
            </>
          )}
          {error && <p role="alert" className="text-xs text-status-down">{error}</p>}
        </form>
        <DialogFooter><Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button><Button type="submit" form="snmp-form" disabled={save.isPending}>{save.isPending && <Loader2 className="animate-spin" />} Save</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function SnmpTab({ deviceId }: { deviceId: string }) {
  const { operate } = useCan();
  const [editing, setEditing] = useState(false);
  const q = useQuery({ queryKey: ["devices", deviceId, "snmp"], queryFn: () => api<SnmpView>(`/devices/${deviceId}/snmp`), refetchInterval: 15_000 });
  const config = q.data?.config;
  const poll = () => post(`/devices/${deviceId}/snmp/poll`).then(() => toast.success("Poll queued")).catch((e: Error) => toast.error(e.message));

  return (
    <div className="space-y-4">
      {q.isError ? <Panel><ErrorState message={q.error.message} onRetry={() => q.refetch()} /></Panel>
        : !q.data ? <Panel><RowsSkeleton /></Panel>
        : !config ? <Panel><EmptyState icon={Network} title="SNMP isn't set up" description="Add credentials to read this device's interfaces and bandwidth." action={operate ? <Button size="sm" onClick={() => setEditing(true)}>Configure SNMP</Button> : undefined} /></Panel>
        : (
          <>
            <Panel title="System" action={operate ? <div className="flex gap-2"><Button size="xs" variant="outline" onClick={poll}><RefreshCw /> Poll now</Button><Button size="xs" variant="outline" onClick={() => setEditing(true)}>Settings</Button></div> : undefined}>
              <dl className="grid gap-x-6 gap-y-2 p-4 text-sm sm:grid-cols-2">
                <div><dt className="text-xs text-muted-foreground">Name</dt><dd className="font-mono text-xs">{config.sysName ?? "—"}</dd></div>
                <div><dt className="text-xs text-muted-foreground">Location</dt><dd className="text-xs">{config.sysLocation ?? "—"}</dd></div>
                <div><dt className="text-xs text-muted-foreground">Uptime</dt><dd className="font-mono text-xs">{fmtDuration(config.sysUptimeTicks == null ? null : config.sysUptimeTicks / 100)}</dd></div>
                <div><dt className="text-xs text-muted-foreground">Last poll</dt><dd className="text-xs"><RelativeTime iso={config.lastPollAt} fallback="Waiting for first poll" /> · SNMP {config.version === "V2C" ? "v2c" : "v3"}{config.enabled ? "" : " · disabled"}</dd></div>
                {config.sysDescr && <div className="sm:col-span-2"><dt className="text-xs text-muted-foreground">Description</dt><dd className="text-xs [overflow-wrap:anywhere]">{config.sysDescr}</dd></div>}
              </dl>
              {config.lastError && <p role="alert" className="border-t px-4 py-2.5 text-xs text-status-down">{config.lastError}</p>}
            </Panel>
            <Panel title="Interfaces">
              {q.data.interfaces.length === 0 ? <EmptyState icon={Network} title="No interfaces yet" description="They appear after the first successful poll." />
                : <div className="overflow-x-auto"><table className="w-full min-w-[840px] text-sm">
                    <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">Interface</th><th className="px-3 py-2 font-medium">State</th><th className="px-3 py-2 text-right font-medium">Speed</th><th className="px-3 py-2 text-right font-medium">In</th><th className="px-3 py-2 text-right font-medium">Out</th><th className="px-3 py-2 font-medium">Utilization</th><th className="px-3 py-2 font-medium">Traffic</th><th className="px-4 py-2 text-right font-medium">Errors in/out</th></tr></thead>
                    <tbody className="divide-y">{q.data.interfaces.map((i) => {
                      const state = interfaceState(i);
                      const name = i.descr ?? `ifIndex ${i.ifIndex}`;
                      return (
                        <tr key={i.ifIndex}>
                          <td className="px-4 py-2 font-mono text-xs">{name}<span className="ml-1.5 font-sans text-muted-foreground" title="Counter width">{i.counterBits}-bit</span></td>
                          <td className={`px-3 py-2 text-xs font-medium ${state.cls}`}>{state.text}</td>
                          <td className="px-3 py-2 text-right font-mono text-xs">{i.speedBps ? fmtBps(i.speedBps) : "—"}</td>
                          <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtBps(i.inBps)}</td>
                          <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtBps(i.outBps)}</td>
                          <td className="px-3 py-2"><UtilizationBar pct={i.utilizationPct} /></td>
                          <td className="px-3 py-1"><TrafficSparkline samples={i.samples} label={name} /></td>
                          <td className="px-4 py-2 text-right font-mono text-xs tabular-nums">{i.inErrors ?? "—"} / {i.outErrors ?? "—"}</td>
                        </tr>);
                    })}</tbody>
                  </table></div>}
            </Panel>
          </>
        )}
      <ConfigDialog deviceId={deviceId} config={config} open={editing} onOpenChange={setEditing} />
    </div>
  );
}
