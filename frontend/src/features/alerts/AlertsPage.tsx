import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BellOff, Check, Loader2, Pencil, Plus, Trash2, Wrench } from "lucide-react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState, FormField, PageHeader, Pager, Panel, RelativeTime, RowsSkeleton, SeverityBadge, useConfirm } from "@/components/common";
import { useCan } from "@/features/auth/AuthContext";
import { ApiError, api, del, post, put } from "@/lib/api";
import { fmtDateTime, localToIso, toLocalInput } from "@/lib/format";
import type { Alert, AlertRule, AlertState, Device, MaintenanceWindow, Page, RuleType, Severity } from "@/lib/types";

const RULE_LABEL: Record<RuleType, string> = { DEVICE_DOWN: "Device down", RTT_ABOVE: "RTT above", LOSS_ABOVE: "Packet loss above", PORT_CLOSED: "Port closed", INTERFACE_DOWN: "Interface down", INTERFACE_UTILIZATION: "Interface utilization above" };
const needsThreshold = (t: RuleType) => t === "RTT_ABOVE" || t === "LOSS_ABOVE" || t === "INTERFACE_UTILIZATION";
const ALL_DEVICES = "all";

function useDevices() {
  return useQuery({ queryKey: ["devices", "picker"], queryFn: () => api<Page<Device>>("/devices?size=200"), staleTime: 30_000 });
}

function AlertTable({ state, deviceId }: { state?: AlertState; deviceId?: string }) {
  const { operate } = useCan();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ page: String(page), size: "25" });
  if (state) params.set("state", state);
  if (deviceId) params.set("deviceId", deviceId);
  const q = useQuery({ queryKey: ["alerts", "list", state, deviceId, page], queryFn: () => api<Page<Alert>>(`/alerts?${params}`), placeholderData: keepPreviousData, refetchInterval: 30_000 });
  const ack = useMutation({
    mutationFn: (id: string) => post<Alert>(`/alerts/${id}/acknowledge`),
    onSuccess: () => { toast.success("Alert acknowledged"); void qc.invalidateQueries({ queryKey: ["alerts"] }); },
    onError: (e: Error) => { toast.error(e.message); void qc.invalidateQueries({ queryKey: ["alerts"] }); },
  });

  if (q.isError) return <ErrorState message={q.error.message} onRetry={() => q.refetch()} />;
  if (!q.data) return <RowsSkeleton />;
  if (q.data.content.length === 0) {
    const text = state === "TRIGGERED" ? "Nothing needs attention right now." : state === "ACKNOWLEDGED" ? "Acknowledged alerts stay here until they clear." : "Alerts for this device appear here.";
    return <EmptyState icon={BellOff} title="No alerts" description={state === "RESOLVED" ? "Cleared alerts are kept here for reference." : text} />;
  }
  return (
    <>
      <div className="overflow-x-auto"><table className="w-full min-w-[760px] text-sm">
        <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">Severity</th><th className="px-3 py-2 font-medium">Alert</th>{!deviceId && <th className="px-3 py-2 font-medium">Device</th>}<th className="px-3 py-2 font-medium">Triggered</th><th className="px-3 py-2 font-medium">State</th><th aria-label="Actions" className="px-4 py-2" /></tr></thead>
        <tbody className="divide-y">{q.data.content.map((a) => (
          <tr key={a.id}>
            <td className="px-4 py-2"><SeverityBadge severity={a.severity} /></td>
            <td className="px-3 py-2"><p className="text-sm">{a.message}</p><p className="text-xs text-muted-foreground">{a.ruleName}</p></td>
            {!deviceId && <td className="px-3 py-2 text-sm"><Link className="hover:underline" to={`/devices/${a.deviceId}`}>{a.deviceName}</Link></td>}
            <td className="px-3 py-2 text-xs"><RelativeTime iso={a.triggeredAt} /></td>
            <td className="px-3 py-2 text-xs">{a.state === "ACKNOWLEDGED" ? `Acknowledged by ${a.acknowledgedBy}` : a.state === "RESOLVED" ? <>Resolved <RelativeTime iso={a.resolvedAt} />{a.resolveReason && a.resolveReason !== "Condition cleared" ? ` · ${a.resolveReason}` : ""}</> : "Triggered"}</td>
            <td className="px-4 py-2 text-right">{operate && a.state === "TRIGGERED" && <Button size="xs" variant="outline" disabled={ack.isPending} onClick={() => ack.mutate(a.id)}><Check /> Acknowledge</Button>}</td>
          </tr>))}
        </tbody>
      </table></div>
      <Pager page={page} totalPages={q.data.page.totalPages} onChange={setPage} />
    </>
  );
}

export function DeviceAlertsTab({ deviceId }: { deviceId: string }) {
  return <Panel><AlertTable deviceId={deviceId} /></Panel>;
}

const ruleSchema = z.object({
  name: z.string().trim().min(1, "Name is required").max(100, "100 characters or fewer"),
  type: z.enum(["DEVICE_DOWN", "RTT_ABOVE", "LOSS_ABOVE", "PORT_CLOSED", "INTERFACE_DOWN", "INTERFACE_UTILIZATION"]),
  severity: z.enum(["CRITICAL", "WARNING", "INFO"]),
  deviceId: z.string(),
  threshold: z.string(),
  port: z.string().refine((v) => v === "" || (/^\d+$/.test(v) && Number(v) >= 1 && Number(v) <= 65535), "1-65535, or empty for every port"),
  interfaceName: z.string().max(255),
  durationMinutes: z.string().refine((v) => /^\d+(\.\d+)?$/.test(v) && Number(v) <= 1440, "0 to 1440 minutes"),
  enabled: z.boolean(),
}).superRefine((v, ctx) => {
  if (needsThreshold(v.type)) {
    const n = Number(v.threshold);
    if (v.threshold === "" || Number.isNaN(n) || n < 0) ctx.addIssue({ code: "custom", path: ["threshold"], message: "Enter a number of 0 or more" });
    else if (v.type !== "RTT_ABOVE" && n > 100) ctx.addIssue({ code: "custom", path: ["threshold"], message: "A percentage can't be above 100" });
  }
});
type RuleForm = z.infer<typeof ruleSchema>;

function ruleToForm(r?: AlertRule | null): RuleForm {
  return { name: r?.name ?? "", type: r?.type ?? "DEVICE_DOWN", severity: r?.severity ?? "CRITICAL", deviceId: r?.deviceId ?? ALL_DEVICES, threshold: r?.threshold?.toString() ?? "", port: r?.port?.toString() ?? "", interfaceName: r?.interfaceName ?? "", durationMinutes: String((r?.durationSeconds ?? 60) / 60), enabled: r?.enabled ?? true };
}

function RuleDialog({ open, rule, onOpenChange }: { open: boolean; rule?: AlertRule | null; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const devices = useDevices();
  const { register, control, handleSubmit, reset, watch, formState: { errors } } = useForm<RuleForm>({ resolver: zodResolver(ruleSchema), defaultValues: ruleToForm(rule) });
  useEffect(() => { if (open) reset(ruleToForm(rule)); }, [open, rule, reset]);
  const type = watch("type");
  const save = useMutation({
    mutationFn: (v: RuleForm) => {
      const body = { name: v.name, type: v.type, severity: v.severity, deviceId: v.deviceId === ALL_DEVICES ? undefined : v.deviceId, threshold: needsThreshold(v.type) ? Number(v.threshold) : undefined, port: v.type === "PORT_CLOSED" && v.port ? Number(v.port) : undefined, interfaceName: v.interfaceName || undefined, durationSeconds: Math.round(Number(v.durationMinutes) * 60), enabled: v.enabled };
      return rule ? put(`/alert-rules/${rule.id}`, body) : post("/alert-rules", body);
    },
    onSuccess: () => { toast.success(rule ? "Rule saved" : "Rule created"); void qc.invalidateQueries({ queryKey: ["alerts"] }); onOpenChange(false); },
    onError: (e: Error) => toast.error(e instanceof ApiError && e.status === 409 ? "A rule with this name already exists." : e.message),
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
        <DialogHeader><DialogTitle>{rule ? "Edit rule" : "New alert rule"}</DialogTitle><DialogDescription>You're notified when the condition starts, is acknowledged, and clears, not on every check.</DialogDescription></DialogHeader>
        <form id="rule-form" className="space-y-3" onSubmit={handleSubmit((v) => save.mutate(v))}>
          <FormField id="rule-name" label="Name" error={errors.name?.message}><Input id="rule-name" {...register("name")} /></FormField>
          <FormField label="Condition">
            <Controller control={control} name="type" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Condition" className="w-full"><SelectValue /></SelectTrigger><SelectContent>{Object.entries(RULE_LABEL).map(([k, l]) => <SelectItem key={k} value={k}>{l}</SelectItem>)}</SelectContent></Select>} />
          </FormField>
          {needsThreshold(type) && <FormField id="rule-th" label={type === "RTT_ABOVE" ? "Threshold (ms)" : "Threshold (%)"} error={errors.threshold?.message}><Input id="rule-th" className="font-mono" inputMode="decimal" {...register("threshold")} /></FormField>}
          {type === "PORT_CLOSED" && <FormField id="rule-port" label="Port" error={errors.port?.message} hint="Leave empty for every port configured on the device."><Input id="rule-port" className="font-mono" inputMode="numeric" {...register("port")} /></FormField>}
          {(type === "INTERFACE_DOWN" || type === "INTERFACE_UTILIZATION") && <FormField id="rule-if" label="Interface name" hint="Leave empty for every interface."><Input id="rule-if" className="font-mono" placeholder="GigabitEthernet0/1" {...register("interfaceName")} /></FormField>}
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Severity"><Controller control={control} name="severity" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Severity" className="w-full"><SelectValue /></SelectTrigger><SelectContent>{(["CRITICAL", "WARNING", "INFO"] as Severity[]).map((s) => <SelectItem key={s} value={s}>{s[0] + s.slice(1).toLowerCase()}</SelectItem>)}</SelectContent></Select>} /></FormField>
            <FormField id="rule-for" label="For (minutes)" error={errors.durationMinutes?.message}><Input id="rule-for" className="font-mono" inputMode="decimal" {...register("durationMinutes")} /></FormField>
          </div>
          <FormField label="Applies to"><Controller control={control} name="deviceId" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Applies to" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value={ALL_DEVICES}>All devices</SelectItem>{devices.data?.content.map((d) => <SelectItem key={d.id} value={d.id}>{d.name}</SelectItem>)}</SelectContent></Select>} /></FormField>
          <Controller control={control} name="enabled" render={({ field }) => <label className="flex items-center justify-between text-sm font-medium">Enabled <Switch checked={field.value} onCheckedChange={field.onChange} aria-label="Enabled" /></label>} />
        </form>
        <DialogFooter><Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button><Button type="submit" form="rule-form" disabled={save.isPending}>{save.isPending && <Loader2 className="animate-spin" />} Save rule</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function describeRule(r: AlertRule) {
  const unit = r.type === "RTT_ABOVE" ? " ms" : "%";
  const detail = needsThreshold(r.type) ? `${r.threshold}${unit}` : r.type === "PORT_CLOSED" ? (r.port ? `port ${r.port}` : "any port") : "";
  const when = r.durationSeconds ? ` for ${r.durationSeconds / 60} min` : "";
  return `${RULE_LABEL[r.type]} ${detail}${when}`.replace(/\s+/g, " ").trim();
}

function RulesTab() {
  const { admin } = useCan();
  const qc = useQueryClient();
  const [editor, setEditor] = useState<{ open: boolean; rule?: AlertRule | null }>({ open: false });
  const q = useQuery({ queryKey: ["alerts", "rules"], queryFn: () => api<AlertRule[]>("/alert-rules") });
  const remove = useMutation({
    mutationFn: (id: string) => del(`/alert-rules/${id}`),
    onSuccess: () => { toast.success("Rule deleted. Its open alerts were resolved."); void qc.invalidateQueries({ queryKey: ["alerts"] }); },
    onError: (e: Error) => toast.error(e.message),
  });
  const { dialog, confirm } = useConfirm();
  const addButton = admin ? <Button size="sm" onClick={() => setEditor({ open: true })}><Plus /> New rule</Button> : undefined;
  return (
    <Panel title="Alert rules" action={addButton}>
      {q.isError ? <ErrorState message={q.error.message} onRetry={() => q.refetch()} />
        : !q.data ? <RowsSkeleton />
        : q.data.length === 0 ? <EmptyState icon={BellOff} title="No rules yet" description="Create a rule to be alerted when a device goes down or crosses a threshold." action={addButton} />
        : <div className="overflow-x-auto"><table className="w-full min-w-[640px] text-sm">
            <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">Name</th><th className="px-3 py-2 font-medium">Condition</th><th className="px-3 py-2 font-medium">Applies to</th><th className="px-3 py-2 font-medium">Severity</th><th aria-label="Actions" className="px-4 py-2" /></tr></thead>
            <tbody className="divide-y">{q.data.map((r) => (
              <tr key={r.id}>
                <td className="px-4 py-2">{r.name}{!r.enabled && <span className="ml-2 text-xs text-muted-foreground">Disabled</span>}</td>
                <td className="px-3 py-2 text-xs">{describeRule(r)}</td>
                <td className="px-3 py-2 text-xs">{r.deviceName ?? "All devices"}</td>
                <td className="px-3 py-2"><SeverityBadge severity={r.severity} /></td>
                <td className="px-4 py-2 text-right">{admin && <span className="inline-flex gap-1"><Button size="icon-xs" variant="ghost" aria-label={`Edit ${r.name}`} onClick={() => setEditor({ open: true, rule: r })}><Pencil /></Button><Button size="icon-xs" variant="ghost" aria-label={`Delete ${r.name}`} onClick={() => confirm(`Delete "${r.name}"?`, "Any alerts it has open will be resolved. This can't be undone.", () => remove.mutate(r.id))}><Trash2 /></Button></span>}</td>
              </tr>))}
            </tbody>
          </table></div>}
      <RuleDialog open={editor.open} rule={editor.rule} onOpenChange={(o) => setEditor({ open: o })} />
      {dialog}
    </Panel>
  );
}

const windowSchema = z.object({ deviceId: z.string().min(1, "Choose a device"), startsAt: z.string().min(1, "Required"), endsAt: z.string().min(1, "Required"), reason: z.string().max(255) })
  .refine((v) => !v.startsAt || !v.endsAt || new Date(v.endsAt) > new Date(v.startsAt), { path: ["endsAt"], message: "Must be after the start" });
type WindowForm = z.infer<typeof windowSchema>;

function MaintenanceTab() {
  const { operate } = useCan();
  const qc = useQueryClient();
  const devices = useDevices();
  const [open, setOpen] = useState(false);
  const q = useQuery({ queryKey: ["alerts", "maintenance"], queryFn: () => api<MaintenanceWindow[]>("/maintenance-windows") });
  const defaults = (): WindowForm => ({ deviceId: "", startsAt: toLocalInput(new Date()), endsAt: toLocalInput(new Date(Date.now() + 3_600_000)), reason: "" });
  const { register, control, handleSubmit, reset, formState: { errors } } = useForm<WindowForm>({ resolver: zodResolver(windowSchema), defaultValues: defaults() });
  const save = useMutation({
    mutationFn: (v: WindowForm) => post("/maintenance-windows", { deviceId: v.deviceId, startsAt: localToIso(v.startsAt), endsAt: localToIso(v.endsAt), reason: v.reason || undefined }),
    onSuccess: () => { toast.success("Maintenance window scheduled"); void qc.invalidateQueries({ queryKey: ["alerts", "maintenance"] }); setOpen(false); },
    onError: (e: Error) => toast.error(e.message),
  });
  const remove = useMutation({ mutationFn: (id: string) => del(`/maintenance-windows/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ["alerts", "maintenance"] }), onError: (e: Error) => toast.error(e.message) });
  const add = operate ? <Button size="sm" onClick={() => { reset(defaults()); setOpen(true); }}><Plus /> Schedule</Button> : undefined;
  const now = Date.now();
  return (
    <Panel title="Maintenance windows" action={add}>
      {q.isError ? <ErrorState message={q.error.message} onRetry={() => q.refetch()} />
        : !q.data ? <RowsSkeleton />
        : q.data.length === 0 ? <EmptyState icon={Wrench} title="No maintenance scheduled" description="During a window a device can't raise alerts, and the time doesn't count against its SLA." action={add} />
        : <ul className="divide-y">{q.data.map((w) => {
            const active = Date.parse(w.startsAt) <= now && Date.parse(w.endsAt) > now;
            return (
              <li key={w.id} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                <div className="min-w-0 flex-1"><p>{w.deviceName}{active && <span className="ml-2 text-xs font-medium text-status-degraded">In progress</span>}</p><p className="text-xs text-muted-foreground">{fmtDateTime(w.startsAt)} – {fmtDateTime(w.endsAt)}{w.reason ? ` · ${w.reason}` : ""} · by {w.createdBy}</p></div>
                {operate && <Button size="icon-xs" variant="ghost" aria-label={`Remove window for ${w.deviceName}`} onClick={() => remove.mutate(w.id)}><Trash2 /></Button>}
              </li>);
          })}</ul>}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader><DialogTitle>Schedule maintenance</DialogTitle><DialogDescription>New alerts are muted for this device during the window.</DialogDescription></DialogHeader>
          <form id="window-form" className="space-y-3" onSubmit={handleSubmit((v) => save.mutate(v))}>
            <FormField label="Device" error={errors.deviceId?.message}><Controller control={control} name="deviceId" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Device" className="w-full"><SelectValue placeholder="Choose a device" /></SelectTrigger><SelectContent>{devices.data?.content.map((d) => <SelectItem key={d.id} value={d.id}>{d.name}</SelectItem>)}</SelectContent></Select>} /></FormField>
            <div className="grid grid-cols-2 gap-3">
              <FormField id="w-start" label="Start" error={errors.startsAt?.message}><Input id="w-start" type="datetime-local" {...register("startsAt")} /></FormField>
              <FormField id="w-end" label="End" error={errors.endsAt?.message}><Input id="w-end" type="datetime-local" {...register("endsAt")} /></FormField>
            </div>
            <FormField id="w-reason" label="Reason"><Input id="w-reason" {...register("reason")} /></FormField>
          </form>
          <DialogFooter><Button variant="outline" onClick={() => setOpen(false)}>Cancel</Button><Button type="submit" form="window-form" disabled={save.isPending}>{save.isPending && <Loader2 className="animate-spin" />} Schedule</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </Panel>
  );
}

export function AlertsPage() {
  return (
    <>
      <PageHeader title="Alerts" description="What needs attention, what's being handled, and the rules behind them." />
      <Tabs defaultValue="active">
        <div className="overflow-x-auto"><TabsList>
          <TabsTrigger value="active">Active</TabsTrigger><TabsTrigger value="ack">Acknowledged</TabsTrigger><TabsTrigger value="resolved">Resolved</TabsTrigger><TabsTrigger value="rules">Rules</TabsTrigger><TabsTrigger value="maintenance">Maintenance</TabsTrigger>
        </TabsList></div>
        <TabsContent value="active" className="mt-3"><Panel><AlertTable state="TRIGGERED" /></Panel></TabsContent>
        <TabsContent value="ack" className="mt-3"><Panel><AlertTable state="ACKNOWLEDGED" /></Panel></TabsContent>
        <TabsContent value="resolved" className="mt-3"><Panel><AlertTable state="RESOLVED" /></Panel></TabsContent>
        <TabsContent value="rules" className="mt-3"><RulesTab /></TabsContent>
        <TabsContent value="maintenance" className="mt-3"><MaintenanceTab /></TabsContent>
      </Tabs>
    </>
  );
}
