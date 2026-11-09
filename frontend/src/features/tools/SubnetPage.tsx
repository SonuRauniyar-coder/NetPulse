import { useState, type FormEvent, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import { Calculator, Loader2, Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { CopyButton, EmptyState, FormField, PageHeader, Panel } from "@/components/common";
import { api, post } from "@/lib/api";
import { fmtCount } from "@/lib/format";
import type { SubnetInfo, VlsmResult } from "@/lib/types";
import { isCidr } from "@/lib/validation";

function Result({ label, value, mono = true }: { label: string; value: ReactNode; mono?: boolean }) {
  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">{label}</p>
        {typeof value === "string" && value !== "—" && <CopyButton value={value} label={label} />}
      </div>
      <p className={`mt-0.5 text-sm [overflow-wrap:anywhere] ${mono ? "font-mono" : ""}`}>{value}</p>
    </div>
  );
}

/** Network bits and host bits are separated by position, not only by shade, so the split is readable without color. */
function BinaryRow({ label, value, prefix }: { label: string; value?: string; prefix: number }) {
  if (!value) return null;
  let seen = 0;
  const chars = [...value].map((c, i) => {
    if (c === ".") return <span key={i} className="text-muted-foreground">.</span>;
    seen += 1;
    const network = seen <= prefix;
    return <span key={i} className={network ? "text-foreground" : "text-muted-foreground"}>{c}{seen === prefix && prefix < 32 ? <span aria-hidden className="mx-0.5" style={{ color: "var(--chart-line)" }}>|</span> : null}</span>;
  });
  return <tr><th scope="row" className="py-1 pr-4 text-left text-xs font-normal text-muted-foreground">{label}</th><td className="py-1 font-mono text-sm whitespace-nowrap">{chars}</td></tr>;
}

function Calculate() {
  const [cidr, setCidr] = useState("192.168.10.77/26");
  const [error, setError] = useState<string>();
  const calc = useMutation({ mutationFn: (c: string) => api<SubnetInfo>(`/subnet/calculate?cidr=${encodeURIComponent(c)}`), onError: (e: Error) => setError(e.message) });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!isCidr(cidr)) { setError("Enter an IPv4 address and prefix, like 192.168.1.0/24"); return; }
    setError(undefined);
    calc.mutate(cidr.trim());
  };
  const r = calc.data;
  return (
    <div className="space-y-4">
      <Panel title="Calculate a subnet">
        <form onSubmit={submit} className="flex flex-wrap items-start gap-3 p-4">
          <div className="min-w-64 flex-1">
            <FormField id="cidr" label="IPv4 address / prefix" error={error}>
              <Input id="cidr" className="font-mono" value={cidr} onChange={(e) => setCidr(e.target.value)} aria-invalid={!!error} />
            </FormField>
          </div>
          <Button type="submit" size="sm" className="mt-[26px]" disabled={calc.isPending}>{calc.isPending && <Loader2 className="animate-spin" />} Calculate</Button>
        </form>
      </Panel>
      {r ? (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Result label="Network" value={r.cidr} />
            <Result label="Broadcast" value={r.broadcast ?? "None"} />
            <Result label="First host" value={r.firstHost} />
            <Result label="Last host" value={r.lastHost} />
            <Result label="Usable hosts" value={fmtCount(r.usableHosts)} />
            <Result label="Total addresses" value={fmtCount(r.totalAddresses)} />
            <Result label="Subnet mask" value={r.subnetMask} />
            <Result label="Wildcard mask" value={r.wildcardMask} />
            <Result label="Scope" value={r.addressScope} mono={false} />
            <Result label="Class (historical)" value={<span title="Classful addressing was replaced by CIDR in 1993. Shown for education only.">{r.historicalClass}</span>} />
          </div>
          {r.note && <p className="text-sm text-muted-foreground">{r.note}</p>}
          <Panel title="Binary view">
            <div className="overflow-x-auto p-4">
              <table><tbody>
                <BinaryRow label="Address" value={r.binary.address} prefix={r.prefix} />
                <BinaryRow label="Mask" value={r.binary.mask} prefix={r.prefix} />
                <BinaryRow label="Network" value={r.binary.network} prefix={r.prefix} />
                <BinaryRow label="Broadcast" value={r.binary.broadcast} prefix={r.prefix} />
              </tbody></table>
              <p className="mt-2 text-xs text-muted-foreground">The bar marks the /{r.prefix} boundary: network bits to its left, host bits to its right.</p>
            </div>
          </Panel>
        </>
      ) : (
        <Panel><EmptyState icon={Calculator} title="Enter a network" description="See its range, masks and binary form. Classless (CIDR) addressing is the model used throughout." /></Panel>
      )}
    </div>
  );
}

interface Segment { name: string; hosts: string }

function Vlsm() {
  const [network, setNetwork] = useState("192.168.1.0/24");
  const [segments, setSegments] = useState<Segment[]>([{ name: "Sales", hosts: "50" }, { name: "IT", hosts: "20" }, { name: "WAN link", hosts: "2" }]);
  const [error, setError] = useState<string>();
  const run = useMutation({
    mutationFn: () => post<VlsmResult>("/subnet/vlsm", { network: network.trim(), segments: segments.map((s) => ({ name: s.name.trim(), hosts: Number(s.hosts) })) }),
    onError: (e: Error) => setError(e.message),
  });
  const update = (i: number, patch: Partial<Segment>) => setSegments(segments.map((s, n) => (n === i ? { ...s, ...patch } : s)));
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!isCidr(network)) return setError("Enter a base network, like 192.168.1.0/24");
    if (segments.length === 0) return setError("Add at least one segment");
    if (segments.some((s) => !s.name.trim() || !/^\d+$/.test(s.hosts) || Number(s.hosts) < 1)) return setError("Every segment needs a name and at least 1 host");
    setError(undefined);
    run.mutate();
  };
  const r = run.data;
  return (
    <Panel title="VLSM planner">
      <form onSubmit={submit} className="space-y-3 p-4">
        <div className="max-w-sm"><FormField id="vlsm-net" label="Base network"><Input id="vlsm-net" className="font-mono" value={network} onChange={(e) => setNetwork(e.target.value)} /></FormField></div>
        <div className="space-y-2">
          {segments.map((s, i) => (
            <div key={i} className="flex items-center gap-2">
              <Input aria-label={`Segment ${i + 1} name`} placeholder="Segment name" value={s.name} onChange={(e) => update(i, { name: e.target.value })} />
              <Input aria-label={`Segment ${i + 1} hosts`} placeholder="Hosts" inputMode="numeric" className="w-28 font-mono" value={s.hosts} onChange={(e) => update(i, { hosts: e.target.value })} />
              <Button type="button" variant="ghost" size="icon-sm" aria-label={`Remove segment ${i + 1}`} onClick={() => setSegments(segments.filter((_, n) => n !== i))}><X /></Button>
            </div>
          ))}
        </div>
        {error && <p role="alert" className="text-xs text-status-down">{error}</p>}
        <div className="flex gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => setSegments([...segments, { name: "", hosts: "" }])}><Plus /> Add segment</Button>
          <Button type="submit" size="sm" disabled={run.isPending}>{run.isPending && <Loader2 className="animate-spin" />} Allocate</Button>
        </div>
      </form>
      {r && (
        <div className="border-t">
          <p className="px-4 py-2.5 text-xs text-muted-foreground">Largest segment first. {fmtCount(r.allocatedAddresses)} of {fmtCount(r.totalAddresses)} addresses allocated, {fmtCount(r.freeAddresses)} free.</p>
          <div className="overflow-x-auto"><table className="w-full min-w-[720px] text-sm">
            <thead className="border-y text-left text-xs text-muted-foreground"><tr>
              <th className="px-4 py-2 font-medium">Segment</th><th className="px-3 py-2 text-right font-medium">Needed</th><th className="px-3 py-2 font-medium">Subnet</th><th className="px-3 py-2 font-medium">Mask</th><th className="px-3 py-2 font-medium">Host range</th><th className="px-3 py-2 font-medium">Broadcast</th><th className="px-4 py-2 text-right font-medium">Usable</th>
            </tr></thead>
            <tbody className="divide-y font-mono text-xs">{r.allocations.map((a) => (
              <tr key={a.cidr}>
                <td className="px-4 py-2 font-sans text-sm">{a.name}</td><td className="px-3 py-2 text-right">{fmtCount(a.requestedHosts)}</td>
                <td className="px-3 py-2"><span className="inline-flex items-center gap-1">{a.cidr}<CopyButton value={a.cidr} label={`${a.name} subnet`} /></span></td>
                <td className="px-3 py-2">{a.subnetMask}</td><td className="px-3 py-2 whitespace-nowrap">{a.firstHost} – {a.lastHost}</td><td className="px-3 py-2">{a.broadcast}</td><td className="px-4 py-2 text-right">{fmtCount(a.usableHosts)}</td>
              </tr>))}
            </tbody>
          </table></div>
        </div>
      )}
    </Panel>
  );
}

export function SubnetPage() {
  return (
    <>
      <PageHeader title="Subnet Calculator" description="CIDR breakdown, binary view and VLSM allocation for IPv4." />
      <div className="space-y-4"><Calculate /><Vlsm /></div>
    </>
  );
}
