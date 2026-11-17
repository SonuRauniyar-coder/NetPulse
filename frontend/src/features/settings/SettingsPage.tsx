import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Loader2, Mail, Pencil, Plus, Send, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { EmptyState, ErrorState, FormField, PageHeader, Panel, RelativeTime, RowsSkeleton, SeverityBadge, useConfirm } from "@/components/common";
import { ApiError, api, del, post, put } from "@/lib/api";
import type { AppSettings, NotificationChannel, NotificationDelivery } from "@/lib/types";
import { isCidr } from "@/lib/validation";

const channelSchema = z.object({
  name: z.string().trim().min(1, "Name is required").max(100),
  type: z.enum(["EMAIL", "WEBHOOK"]),
  enabled: z.boolean(),
  minSeverity: z.enum(["CRITICAL", "WARNING", "INFO"]),
  recipients: z.string(),
  webhookFormat: z.enum(["SLACK", "DISCORD", "TELEGRAM", "GENERIC"]),
  webhookUrl: z.string(),
  telegramChatId: z.string(),
});
type ChannelForm = z.infer<typeof channelSchema>;

function toForm(c?: NotificationChannel | null): ChannelForm {
  return { name: c?.name ?? "", type: c?.type ?? "WEBHOOK", enabled: c?.enabled ?? true, minSeverity: c?.minSeverity ?? "WARNING", recipients: c?.recipients ?? "", webhookFormat: c?.webhookFormat ?? "SLACK", webhookUrl: "", telegramChatId: c?.telegramChatId ?? "" };
}

function ChannelDialog({ open, channel, onOpenChange }: { open: boolean; channel?: NotificationChannel | null; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const { register, control, handleSubmit, reset, watch, setError, formState: { errors } } = useForm<ChannelForm>({ resolver: zodResolver(channelSchema), defaultValues: toForm(channel) });
  useEffect(() => { if (open) reset(toForm(channel)); }, [open, channel, reset]);
  const type = watch("type");
  const format = watch("webhookFormat");
  const save = useMutation({
    mutationFn: (v: ChannelForm) => {
      const body = { name: v.name, type: v.type, enabled: v.enabled, minSeverity: v.minSeverity, recipients: v.type === "EMAIL" ? v.recipients : undefined, webhookFormat: v.type === "WEBHOOK" ? v.webhookFormat : undefined, webhookUrl: v.type === "WEBHOOK" && v.webhookUrl ? v.webhookUrl : undefined, telegramChatId: v.type === "WEBHOOK" && v.telegramChatId ? v.telegramChatId : undefined };
      return channel ? put(`/notifications/channels/${channel.id}`, body) : post("/notifications/channels", body);
    },
    onSuccess: () => { toast.success("Channel saved"); void qc.invalidateQueries({ queryKey: ["notifications"] }); onOpenChange(false); },
    onError: (e: Error) => { if (e instanceof ApiError && e.status === 400) setError(type === "EMAIL" ? "recipients" : "webhookUrl", { message: e.message }); else toast.error(e.message); },
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
        <DialogHeader><DialogTitle>{channel ? "Edit channel" : "New channel"}</DialogTitle><DialogDescription>Where alert notifications are delivered.</DialogDescription></DialogHeader>
        <form id="channel-form" className="space-y-3" onSubmit={handleSubmit((v) => save.mutate(v))}>
          <FormField id="c-name" label="Name" error={errors.name?.message}><Input id="c-name" {...register("name")} /></FormField>
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Type"><Controller control={control} name="type" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Type" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="WEBHOOK">Webhook</SelectItem><SelectItem value="EMAIL">Email</SelectItem></SelectContent></Select>} /></FormField>
            <FormField label="Minimum severity"><Controller control={control} name="minSeverity" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Minimum severity" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="CRITICAL">Critical</SelectItem><SelectItem value="WARNING">Warning</SelectItem><SelectItem value="INFO">Info</SelectItem></SelectContent></Select>} /></FormField>
          </div>
          {type === "EMAIL" ? (
            <FormField id="c-rec" label="Recipients" error={errors.recipients?.message} hint="Separate addresses with commas."><Input id="c-rec" {...register("recipients")} /></FormField>
          ) : (
            <>
              <FormField label="Format"><Controller control={control} name="webhookFormat" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Format" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="SLACK">Slack</SelectItem><SelectItem value="DISCORD">Discord</SelectItem><SelectItem value="TELEGRAM">Telegram</SelectItem><SelectItem value="GENERIC">Generic JSON</SelectItem></SelectContent></Select>} /></FormField>
              <FormField id="c-url" label="Webhook URL" error={errors.webhookUrl?.message} hint={channel?.webhookHost ? `Saved for ${channel.webhookHost}. Leave blank to keep it.` : "Encrypted when saved and never shown again."}><Input id="c-url" type="password" autoComplete="off" className="font-mono" {...register("webhookUrl")} /></FormField>
              {format === "TELEGRAM" && <FormField id="c-chat" label="Chat ID"><Input id="c-chat" className="font-mono" {...register("telegramChatId")} /></FormField>}
            </>
          )}
          <Controller control={control} name="enabled" render={({ field }) => <label className="flex items-center justify-between text-sm font-medium">Enabled <Switch checked={field.value} onCheckedChange={field.onChange} aria-label="Enabled" /></label>} />
        </form>
        <DialogFooter><Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button><Button type="submit" form="channel-form" disabled={save.isPending}>{save.isPending && <Loader2 className="animate-spin" />} Save channel</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Channels() {
  const qc = useQueryClient();
  const [editor, setEditor] = useState<{ open: boolean; channel?: NotificationChannel | null }>({ open: false });
  const { dialog, confirm } = useConfirm();
  const q = useQuery({ queryKey: ["notifications", "channels"], queryFn: () => api<NotificationChannel[]>("/notifications/channels") });
  const deliveries = useQuery({ queryKey: ["notifications", "deliveries"], queryFn: () => api<NotificationDelivery[]>("/notifications/deliveries"), refetchInterval: 15_000 });
  const test = useMutation({ mutationFn: (id: string) => post(`/notifications/channels/${id}/test`), onSuccess: () => toast.success("Test sent"), onError: (e: Error) => toast.error(e.message) });
  const remove = useMutation({ mutationFn: (id: string) => del(`/notifications/channels/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ["notifications"] }), onError: (e: Error) => toast.error(e.message) });
  const add = <Button size="sm" onClick={() => setEditor({ open: true })}><Plus /> Add channel</Button>;
  return (
    <>
      <Panel title="Notification channels" action={add}>
        {q.isError ? <ErrorState message={q.error.message} onRetry={() => q.refetch()} />
          : !q.data ? <RowsSkeleton rows={2} />
          : q.data.length === 0 ? <EmptyState icon={Mail} title="No channels" description="Alerts always show in the app. Add email or a webhook to be told elsewhere." action={add} />
          : <ul className="divide-y">{q.data.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center gap-3 px-4 py-2.5">
                <div className="min-w-0 flex-1"><p className="text-sm">{c.name}{!c.enabled && <span className="ml-2 text-xs text-muted-foreground">Disabled</span>}</p><p className="truncate text-xs text-muted-foreground">{c.type === "EMAIL" ? c.recipients : `${c.webhookFormat} · ${c.webhookHost}`}</p></div>
                <SeverityBadge severity={c.minSeverity} />
                <Button size="xs" variant="outline" disabled={test.isPending} onClick={() => test.mutate(c.id)}>{test.isPending && test.variables === c.id ? <Loader2 className="animate-spin" /> : <Send />} Send test</Button>
                <Button size="icon-xs" variant="ghost" aria-label={`Edit ${c.name}`} onClick={() => setEditor({ open: true, channel: c })}><Pencil /></Button>
                <Button size="icon-xs" variant="ghost" aria-label={`Delete ${c.name}`} onClick={() => confirm(`Delete "${c.name}"?`, "Pending notifications for this channel will be dropped.", () => remove.mutate(c.id))}><Trash2 /></Button>
              </li>))}
            </ul>}
        <ChannelDialog open={editor.open} channel={editor.channel} onOpenChange={(o) => setEditor({ open: o })} />
        {dialog}
      </Panel>
      {!!deliveries.data?.length && (
        <Panel title="Recent deliveries">
          <div className="overflow-x-auto"><table className="w-full min-w-[640px] text-sm">
            <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">When</th><th className="px-3 py-2 font-medium">Channel</th><th className="px-3 py-2 font-medium">Message</th><th className="px-4 py-2 font-medium">Result</th></tr></thead>
            <tbody className="divide-y">{deliveries.data.slice(0, 10).map((d) => (
              <tr key={d.id}><td className="px-4 py-2 text-xs"><RelativeTime iso={d.createdAt} /></td><td className="px-3 py-2 text-xs">{d.channelName}</td><td className="max-w-80 truncate px-3 py-2 text-xs" title={d.subject}>{d.subject}</td>
                <td className="px-4 py-2 text-xs">{d.status === "SENT" ? "Sent" : d.status === "FAILED" ? <span className="text-status-down">Failed: {d.lastError}</span> : `Retrying (attempt ${d.attempts})${d.lastError ? `: ${d.lastError}` : ""}`}</td></tr>))}
            </tbody>
          </table></div>
        </Panel>
      )}
    </>
  );
}

const number = (min: number, max: number) => z.coerce.number({ invalid_type_error: "Enter a number" }).min(min, `At least ${min}`).max(max, `At most ${max}`);
const settingsSchema = z.object({
  degradedRttMs: number(1, 60000), degradedLossPct: number(0, 100), downAfterFailures: number(1, 100).int(), upAfterSuccesses: number(1, 100).int(), defaultIntervalSeconds: number(10, 3600).int(), rawDays: number(1, 365).int(), rollupDays: number(1, 3650).int(),
  scanCidrs: z.string().refine((v) => v.split(/[\s,]+/).filter(Boolean).every(isCidr), "One CIDR per line, like 10.0.0.0/8"),
}).refine((v) => v.rollupDays >= v.rawDays, { path: ["rollupDays"], message: "Must be at least the raw retention" });
type SettingsInput = z.input<typeof settingsSchema>;
type SettingsOutput = z.output<typeof settingsSchema>;

function General() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["settings"], queryFn: () => api<AppSettings>("/settings") });
  const { register, handleSubmit, reset, formState: { errors, isDirty } } = useForm<SettingsInput, unknown, SettingsOutput>({ resolver: zodResolver(settingsSchema) });
  useEffect(() => { if (q.data) reset({ ...q.data, scanCidrs: q.data.scanCidrs.join("\n") }); }, [q.data, reset]);
  const save = useMutation({
    mutationFn: (v: SettingsOutput) => put<AppSettings>("/settings", { ...v, scanCidrs: v.scanCidrs.split(/[\s,]+/).filter(Boolean) }),
    onSuccess: (d) => { qc.setQueryData(["settings"], d); toast.success("Settings saved"); },
    onError: (e: Error) => toast.error(e.message),
  });
  if (q.isError) return <Panel><ErrorState message={q.error.message} onRetry={() => q.refetch()} /></Panel>;
  if (!q.data) return <Panel><RowsSkeleton /></Panel>;
  const field = (name: keyof SettingsInput, label: string, hint?: string) => (
    <FormField id={`s-${name}`} label={label} hint={hint} error={errors[name]?.message}><Input id={`s-${name}`} className="font-mono" inputMode="decimal" {...register(name)} /></FormField>
  );
  return (
    <form onSubmit={handleSubmit((v) => save.mutate(v))} className="space-y-4">
      <Panel title="Status thresholds"><div className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-4">
        {field("degradedRttMs", "Degraded above RTT (ms)")}{field("degradedLossPct", "Degraded above loss (%)")}{field("downAfterFailures", "Down after failures", "Consecutive failed cycles")}{field("upAfterSuccesses", "Up after successes", "Consecutive good cycles")}
      </div></Panel>
      <Panel title="Monitoring and retention"><div className="grid gap-3 p-4 sm:grid-cols-3">
        {field("defaultIntervalSeconds", "Default interval (seconds)")}{field("rawDays", "Keep raw results (days)")}{field("rollupDays", "Keep hourly rollups (days)")}
      </div></Panel>
      <Panel title="Authorized scan ranges"><div className="p-4">
        <FormField id="s-cidrs" label="Networks that may be swept" error={errors.scanCidrs?.message} hint="One CIDR per line. Add only networks you own or are authorized to scan. Changes are recorded in the audit log.">
          <Textarea id="s-cidrs" rows={5} className="font-mono" {...register("scanCidrs")} />
        </FormField>
      </div></Panel>
      <div className="flex justify-end"><Button type="submit" size="sm" disabled={save.isPending || !isDirty}>{save.isPending && <Loader2 className="animate-spin" />} Save settings</Button></div>
    </form>
  );
}

export function SettingsPage() {
  return (
    <>
      <PageHeader title="Settings" description="Notifications, thresholds, retention and scan authorization." />
      <div className="space-y-4"><Channels /><General /></div>
    </>
  );
}
