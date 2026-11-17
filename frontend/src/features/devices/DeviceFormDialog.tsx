import { useEffect } from "react";
import { Controller, useFieldArray, useForm, type FieldPath } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Plus, X } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { ApiError, post, put } from "@/lib/api";
import type { Device, DeviceRequest, DeviceType, DnsRecordType } from "@/lib/types";
import { deviceSchema, type DeviceFormInput, type DeviceFormValues } from "@/lib/validation";

const TYPES: DeviceType[] = ["ROUTER", "SWITCH", "FIREWALL", "SERVER", "WEBSITE"];
const RECORDS: DnsRecordType[] = ["A", "AAAA", "MX", "NS", "TXT", "PTR"];
const label = (t: string) => t[0] + t.slice(1).toLowerCase();

function toForm(d?: Device | null): DeviceFormInput {
  return {
    name: d?.name ?? "", host: d?.host ?? "", type: d?.type ?? "SERVER", location: d?.location ?? "",
    tags: d?.tags.join(", ") ?? "", checkIntervalSeconds: d?.checkIntervalSeconds ?? 60, enabled: d?.enabled ?? true,
    ports: d?.ports.map((p) => ({ port: p.port, label: p.label ?? "" })) ?? [],
    dnsChecks: d?.dnsChecks.map((c) => ({ recordType: c.recordType, queryName: c.queryName ?? "", dnsServer: c.dnsServer ?? "" })) ?? [],
  };
}

function toRequest(v: DeviceFormValues): DeviceRequest {
  return {
    ...v,
    tags: v.tags.split(",").map((t) => t.trim()).filter(Boolean),
    ports: v.ports.map((p) => ({ port: p.port, label: p.label || undefined })),
    dnsChecks: v.dnsChecks.map((c) => ({ recordType: c.recordType, queryName: c.queryName || undefined, dnsServer: c.dnsServer || undefined })),
  };
}

function Err({ msg }: { msg?: string }) {
  return msg ? <p className="mt-1 text-xs text-status-down">{msg}</p> : null;
}

export function DeviceFormDialog({ open, device, onOpenChange }: { open: boolean; device?: Device | null; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const { register, control, handleSubmit, reset, setError, formState: { errors, isSubmitting } } =
    useForm<DeviceFormInput, unknown, DeviceFormValues>({ resolver: zodResolver(deviceSchema), defaultValues: toForm(device) });
  const ports = useFieldArray({ control, name: "ports" });
  const dns = useFieldArray({ control, name: "dnsChecks" });

  useEffect(() => { if (open) reset(toForm(device)); }, [open, device, reset]);

  const save = useMutation({
    mutationFn: (v: DeviceFormValues) => (device ? put<Device>(`/devices/${device.id}`, toRequest(v)) : post<Device>("/devices", toRequest(v))),
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: ["devices"] });
      void qc.invalidateQueries({ queryKey: ["dashboard"] });
      toast.success(device ? `Saved ${d.name}` : `Added ${d.name}. First check starts within a second.`);
      onOpenChange(false);
    },
    onError: (e: Error) => {
      if (e instanceof ApiError && e.fieldErrors) {
        Object.entries(e.fieldErrors).forEach(([f, m]) => setError(f as FieldPath<DeviceFormInput>, { message: m }));
      } else if (e instanceof ApiError && e.status === 409) {
        setError("name", { message: e.message });
      } else if (e instanceof ApiError && e.status === 400) {
        setError("host", { message: e.message });
      } else {
        toast.error(e.message);
      }
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{device ? `Edit ${device.name}` : "Add device"}</DialogTitle>
          <DialogDescription>Every enabled device is pinged on its interval. TCP and DNS checks are optional.</DialogDescription>
        </DialogHeader>
        <form id="device-form" noValidate className="space-y-4" onSubmit={handleSubmit((v) => save.mutateAsync(v).catch(() => undefined))}>
          <div className="grid gap-4 sm:grid-cols-2">
            <div><Label htmlFor="name" className="mb-1.5">Name</Label><Input id="name" aria-invalid={!!errors.name} {...register("name")} /><Err msg={errors.name?.message} /></div>
            <div><Label htmlFor="host" className="mb-1.5">IP or hostname</Label><Input id="host" className="font-mono" placeholder="192.168.1.1" aria-invalid={!!errors.host} {...register("host")} /><Err msg={errors.host?.message} /></div>
            <div>
              <Label className="mb-1.5">Type</Label>
              <Controller control={control} name="type" render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger aria-label="Type" className="w-full"><SelectValue /></SelectTrigger>
                  <SelectContent>{TYPES.map((t) => <SelectItem key={t} value={t}>{label(t)}</SelectItem>)}</SelectContent>
                </Select>)} />
            </div>
            <div><Label htmlFor="interval" className="mb-1.5">Check interval (seconds)</Label><Input id="interval" type="number" min={10} max={3600} aria-invalid={!!errors.checkIntervalSeconds} {...register("checkIntervalSeconds")} /><Err msg={errors.checkIntervalSeconds?.message} /></div>
            <div><Label htmlFor="location" className="mb-1.5">Location</Label><Input id="location" {...register("location")} /></div>
            <div><Label htmlFor="tags" className="mb-1.5">Tags (comma separated)</Label><Input id="tags" aria-invalid={!!errors.tags} {...register("tags")} /><Err msg={errors.tags?.message} /></div>
          </div>

          <fieldset>
            <div className="mb-2 flex items-center justify-between">
              <legend className="text-sm font-medium">TCP ports</legend>
              <Button type="button" variant="ghost" size="xs" onClick={() => ports.append({ port: "" as unknown as number, label: "" })}><Plus /> Add port</Button>
            </div>
            <div className="space-y-2">
              {ports.fields.map((f, i) => (
                <div key={f.id} className="flex items-start gap-2">
                  <div className="w-28"><Input aria-label="Port" type="number" className="font-mono" aria-invalid={!!errors.ports?.[i]?.port} {...register(`ports.${i}.port`)} /><Err msg={errors.ports?.[i]?.port?.message} /></div>
                  <Input aria-label="Label" placeholder="Label (optional)" {...register(`ports.${i}.label`)} />
                  <Button type="button" variant="ghost" size="icon-sm" aria-label="Remove port" onClick={() => ports.remove(i)}><X /></Button>
                </div>
              ))}
            </div>
          </fieldset>

          <fieldset>
            <div className="mb-2 flex items-center justify-between">
              <legend className="text-sm font-medium">DNS checks</legend>
              <Button type="button" variant="ghost" size="xs" onClick={() => dns.append({ recordType: "A", queryName: "", dnsServer: "" })}><Plus /> Add lookup</Button>
            </div>
            <div className="space-y-2">
              {dns.fields.map((f, i) => (
                <div key={f.id} className="flex items-start gap-2">
                  <Controller control={control} name={`dnsChecks.${i}.recordType`} render={({ field }) => (
                    <Select value={field.value} onValueChange={field.onChange}>
                      <SelectTrigger aria-label="Record type" className="w-24 shrink-0 font-mono"><SelectValue /></SelectTrigger>
                      <SelectContent>{RECORDS.map((r) => <SelectItem key={r} value={r}>{r}</SelectItem>)}</SelectContent>
                    </Select>)} />
                  <div className="flex-1"><Input aria-label="Query name" className="font-mono" placeholder="example.com (PTR: empty = device IP)" {...register(`dnsChecks.${i}.queryName`)} /><Err msg={errors.dnsChecks?.[i]?.queryName?.message} /></div>
                  <div className="w-36"><Input aria-label="DNS server" className="font-mono" placeholder="Server (optional)" {...register(`dnsChecks.${i}.dnsServer`)} /><Err msg={errors.dnsChecks?.[i]?.dnsServer?.message} /></div>
                  <Button type="button" variant="ghost" size="icon-sm" aria-label="Remove lookup" onClick={() => dns.remove(i)}><X /></Button>
                </div>
              ))}
            </div>
          </fieldset>

          <Controller control={control} name="enabled" render={({ field }) => (
            <label className="flex items-center gap-2.5 text-sm"><Switch checked={field.value} onCheckedChange={field.onChange} /> Monitoring enabled</label>)} />
        </form>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" form="device-form" disabled={isSubmitting}>{isSubmitting && <Loader2 className="animate-spin" />} {device ? "Save changes" : "Add device"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
