import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, MoreHorizontal, Pause, Pencil, Play, Plus, RefreshCw, Search, Server, ShieldAlert, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { EmptyState, ErrorState, PageHeader, Panel, RelativeTime, RowsSkeleton, StatusBadge } from "@/components/common";
import { useAuth } from "@/features/auth/AuthContext";
import { api, del, post } from "@/lib/api";
import { fmtMs, fmtPct } from "@/lib/format";
import type { Device, Page } from "@/lib/types";
import { DeviceFormDialog } from "./DeviceFormDialog";

type SortDir = "asc" | "desc";
const ALL = "all";
const STATUSES = ["UP", "DEGRADED", "DOWN", "UNKNOWN", "PAUSED"];
const TYPES = ["ROUTER", "SWITCH", "FIREWALL", "SERVER", "WEBSITE"];
const title = (s: string) => s[0] + s.slice(1).toLowerCase();

function useDebounced(value: string, ms = 250) {
  const [v, set] = useState(value);
  useEffect(() => { const t = setTimeout(() => set(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

function Filter({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: string[] }) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger size="sm" aria-label={label} className="w-36"><SelectValue /></SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL}>All {label.toLowerCase()}</SelectItem>
        {options.map((o) => <SelectItem key={o} value={o}>{title(o)}</SelectItem>)}
      </SelectContent>
    </Select>
  );
}

type Confirm = { title: string; description: string; run: () => void };

export function DevicesPage() {
  const qc = useQueryClient();
  const canWrite = useAuth().user?.role !== "VIEWER";
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState("");
  const search = useDebounced(q);
  const [status, setStatus] = useState(ALL);
  const [type, setType] = useState(ALL);
  const [tag, setTag] = useState(ALL);
  const [sort, setSort] = useState<{ field: string; dir: SortDir }>({ field: "name", dir: "asc" });
  // Kept in the URL (1-based) so reload and the back button return to the same page.
  const page = Math.max(0, Math.floor(Number(params.get("page")) || 1) - 1);
  const setPage = useCallback((next: number, replace = false) => setParams((current) => {
    const updated = new URLSearchParams(current);
    if (next > 0) updated.set("page", String(next + 1)); else updated.delete("page");
    return updated;
  }, { replace }), [setParams]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [form, setForm] = useState<{ open: boolean; device?: Device | null }>({ open: false });
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  useEffect(() => {
    if (params.get("new")) { setForm({ open: true }); setParams({}, { replace: true }); }
  }, [params, setParams]);
  const filters = JSON.stringify([search, status, type, tag]);
  const appliedFilters = useRef(filters);
  useEffect(() => {
    if (appliedFilters.current === filters) return;   // not on first render, or a reload would lose the page
    appliedFilters.current = filters;
    setPage(0, true);
  }, [filters, setPage]);
  // Bulk actions must only ever apply to rows the user can currently see.
  useEffect(() => { setSelected(new Set()); }, [search, status, type, tag, page, sort]);

  const query = new URLSearchParams({ page: String(page), size: "25", sort: `${sort.field},${sort.dir}` });
  if (search) query.set("q", search);
  if (status !== ALL) query.set("status", status);
  if (type !== ALL) query.set("type", type);
  if (tag !== ALL) query.set("tag", tag);

  const list = useQuery({ queryKey: ["devices", "list", query.toString()], queryFn: () => api<Page<Device>>(`/devices?${query}`), placeholderData: keepPreviousData, refetchInterval: 30_000 });
  const totalPages = list.data?.page.totalPages;
  useEffect(() => {
    if (!list.isPlaceholderData && totalPages !== undefined && page > 0 && page >= totalPages) setPage(Math.max(totalPages - 1, 0), true);
  }, [list.isPlaceholderData, totalPages, page, setPage]);
  const tags = useQuery({ queryKey: ["devices", "tags"], queryFn: () => api<string[]>("/devices/tags") });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ["devices"] }); void qc.invalidateQueries({ queryKey: ["dashboard"] }); };

  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: refresh,
    onError: (e: Error) => toast.error(e.message),
  });

  const rows = list.data?.content ?? [];
  const filtered = search || status !== ALL || type !== ALL || tag !== ALL;
  const allOnPage = rows.length > 0 && rows.every((d) => selected.has(d.id));

  const toggleSort = (field: string) => setSort((s) => ({ field, dir: s.field === field && s.dir === "asc" ? "desc" : "asc" }));
  const toggleRow = (id: string) => setSelected((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const togglePage = () => setSelected(allOnPage ? new Set() : new Set(rows.map((d) => d.id)));

  const bulk = (action: "PAUSE" | "RESUME" | "DELETE") => act.mutate(async () => {
    const r = await post<{ affected: number }>("/devices/bulk", { ids: [...selected], action });
    const verb = { PAUSE: "Paused", RESUME: "Resumed", DELETE: "Deleted" }[action];
    toast.success(`${verb} ${r.affected} device${r.affected === 1 ? "" : "s"}`);
    setSelected(new Set());
  });

  const Th = ({ field, children, right }: { field: string; children: ReactNode; right?: boolean }) => (
    <th scope="col" aria-sort={sort.field === field ? (sort.dir === "asc" ? "ascending" : "descending") : "none"} className={`px-3 py-2.5 font-medium ${right ? "text-right" : "text-left"}`}>
      <button type="button" onClick={() => toggleSort(field)} className="inline-flex items-center gap-1 hover:text-foreground">
        {children}{sort.field === field && (sort.dir === "asc" ? <ArrowUp aria-hidden className="size-3" /> : <ArrowDown aria-hidden className="size-3" />)}
      </button>
    </th>
  );

  return (
    <>
      <PageHeader title="Devices" description="Everything NetPulse checks, and how each one is doing."
        action={canWrite ? <Button size="sm" onClick={() => setForm({ open: true })}><Plus /> Add device</Button> : undefined} />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div className="relative w-full sm:w-64">
          <Search aria-hidden className="absolute top-2 left-2.5 size-4 text-muted-foreground" />
          <Input aria-label="Search devices" placeholder="Name, IP, location" value={q} onChange={(e) => setQ(e.target.value)} className="h-8 pl-8" />
        </div>
        <Filter label="Statuses" value={status} onChange={setStatus} options={STATUSES} />
        <Filter label="Types" value={type} onChange={setType} options={TYPES} />
        <Select value={tag} onValueChange={setTag}>
          <SelectTrigger size="sm" aria-label="Tags" className="w-36"><SelectValue /></SelectTrigger>
          <SelectContent><SelectItem value={ALL}>All tags</SelectItem>{tags.data?.map((t) => <SelectItem key={t} value={t}>{t}</SelectItem>)}</SelectContent>
        </Select>
        {selected.size > 0 && (
          <div className="ml-auto flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">{selected.size} selected</span>
            <Button size="sm" variant="outline" onClick={() => bulk("PAUSE")}><Pause /> Pause</Button>
            <Button size="sm" variant="outline" onClick={() => bulk("RESUME")}><Play /> Resume</Button>
            <Button size="sm" variant="outline" onClick={() => setConfirm({ title: `Delete ${selected.size} device${selected.size === 1 ? "" : "s"}?`, description: "All monitoring history for these devices is permanently removed.", run: () => bulk("DELETE") })}><Trash2 /> Delete</Button>
          </div>
        )}
      </div>

      <Panel>
        {list.isError ? <ErrorState message={list.error.message} onRetry={() => list.refetch()} />
          : list.isPending || (rows.length === 0 && page > 0) ? <RowsSkeleton rows={8} />
          : rows.length === 0 ? (filtered
              ? <EmptyState icon={Search} title="No devices match" description="Try a different search or clear the filters." />
              : <EmptyState icon={Server} title="No devices yet" description="Add a router, server or website to start monitoring it."
                  action={canWrite ? <Button size="sm" onClick={() => setForm({ open: true })}><Plus /> Add your first device</Button> : undefined} />)
          : (
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[880px] text-sm">
              <thead className="border-b text-xs text-muted-foreground">
                <tr>
                  {canWrite && <th scope="col" className="w-10 px-3 py-2.5"><Checkbox aria-label="Select all on this page" checked={allOnPage} onCheckedChange={togglePage} /></th>}
                  <Th field="name">Name</Th><Th field="host">Host</Th><Th field="status">Status</Th><Th field="type">Type</Th>
                  <Th field="lastRttMs" right>Latency</Th><Th field="lastLossPct" right>Loss</Th><Th field="lastCheckedAt">Last check</Th>
                  {canWrite && <th scope="col" className="w-10"><span className="sr-only">Actions</span></th>}
                </tr>
              </thead>
              <tbody className="divide-y">
                {rows.map((d) => (
                  <tr key={d.id} className="hover:bg-muted/50">
                    {canWrite && <td className="px-3 py-2"><Checkbox aria-label={`Select ${d.name}`} checked={selected.has(d.id)} onCheckedChange={() => toggleRow(d.id)} /></td>}
                    <td className="px-3 py-2">
                      <Link to={`/devices/${d.id}`} className="font-medium hover:underline">{d.name}</Link>
                      {d.location && <p className="text-xs text-muted-foreground">{d.location}</p>}
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">{d.host}</td>
                    <td className="px-3 py-2">
                      <div className="flex items-center gap-1.5"><StatusBadge status={d.status} />
                        {d.icmpBlocked && <span title="Ping got no reply but TCP connected" className="inline-flex items-center gap-1 text-xs text-muted-foreground"><ShieldAlert aria-hidden className="size-3.5" /> ICMP blocked</span>}
                      </div>
                    </td>
                    <td className="px-3 py-2 text-muted-foreground">{title(d.type)}</td>
                    <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtMs(d.lastRttMs)}</td>
                    <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">{fmtPct(d.lastLossPct, 0)}</td>
                    <td className="px-3 py-2 text-xs"><RelativeTime iso={d.lastCheckedAt} /></td>
                    {canWrite && <td className="px-2 py-2">
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild><Button variant="ghost" size="icon-sm" aria-label={`Actions for ${d.name}`}><MoreHorizontal /></Button></DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem disabled={!d.enabled} onSelect={() => act.mutate(async () => { await post(`/devices/${d.id}/check-now`); toast.success(`Checking ${d.name}`); })}><RefreshCw /> Check now</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setForm({ open: true, device: d })}><Pencil /> Edit</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => act.mutate(() => post(`/devices/${d.id}/${d.enabled ? "pause" : "resume"}`))}>{d.enabled ? <><Pause /> Pause</> : <><Play /> Resume</>}</DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem variant="destructive" onSelect={() => setConfirm({ title: `Delete ${d.name}?`, description: "Its monitoring history is permanently removed.", run: () => act.mutate(async () => { await del(`/devices/${d.id}`); toast.success(`Deleted ${d.name}`); }) })}><Trash2 /> Delete</DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {list.data && list.data.page.totalPages > 1 && (
          <footer className="flex items-center justify-between border-t px-4 py-2.5 text-xs text-muted-foreground">
            <span>{list.data.page.totalElements} devices</span>
            <div className="flex items-center gap-2">
              <span>Page {list.data.page.number + 1} of {list.data.page.totalPages}</span>
              <Button variant="outline" size="icon-xs" aria-label="Previous page" disabled={page === 0} onClick={() => setPage(page - 1)}><ChevronLeft /></Button>
              <Button variant="outline" size="icon-xs" aria-label="Next page" disabled={page + 1 >= list.data.page.totalPages} onClick={() => setPage(page + 1)}><ChevronRight /></Button>
            </div>
          </footer>
        )}
      </Panel>

      <DeviceFormDialog open={form.open} device={form.device} onOpenChange={(open) => setForm((f) => ({ ...f, open }))} />
      <AlertDialog open={!!confirm} onOpenChange={(o) => !o && setConfirm(null)}>
        <AlertDialogContent>
          <AlertDialogHeader><AlertDialogTitle>{confirm?.title}</AlertDialogTitle><AlertDialogDescription>{confirm?.description}</AlertDialogDescription></AlertDialogHeader>
          <AlertDialogFooter><AlertDialogCancel>Cancel</AlertDialogCancel><AlertDialogAction onClick={() => confirm?.run()}>Delete</AlertDialogAction></AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
