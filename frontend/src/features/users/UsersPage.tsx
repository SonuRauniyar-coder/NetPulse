import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { KeyRound, Loader2, Plus, Trash2, Users } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { EmptyState, ErrorState, FormField, PageHeader, Panel, RelativeTime, RowsSkeleton, useConfirm } from "@/components/common";
import { useAuth } from "@/features/auth/AuthContext";
import { ApiError, api, del, post, put } from "@/lib/api";
import type { ManagedUser, Role } from "@/lib/types";

const ROLES: { value: Role; label: string }[] = [{ value: "ADMIN", label: "Admin" }, { value: "OPERATOR", label: "Operator" }, { value: "VIEWER", label: "Viewer" }];
const password = z.string().min(12, "Use at least 12 characters").max(72, "Use at most 72 characters");
const createSchema = z.object({ username: z.string().regex(/^[A-Za-z0-9._-]{3,64}$/, "3-64 letters, digits, . _ -"), password, role: z.enum(["ADMIN", "OPERATOR", "VIEWER"]) });
type CreateForm = z.infer<typeof createSchema>;
const passwordSchema = z.object({ password });

function CreateDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const { register, control, handleSubmit, reset, setError, formState: { errors } } = useForm<CreateForm>({ resolver: zodResolver(createSchema), defaultValues: { username: "", password: "", role: "VIEWER" } });
  const save = useMutation({
    mutationFn: (v: CreateForm) => post("/users", v),
    onSuccess: () => { toast.success("User created"); void qc.invalidateQueries({ queryKey: ["users"] }); reset(); onOpenChange(false); },
    onError: (e: Error) => { if (e instanceof ApiError && e.status === 409) setError("username", { message: e.message }); else toast.error(e.message); },
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>New user</DialogTitle><DialogDescription>Operators manage devices, run scans and acknowledge alerts. Viewers are read-only.</DialogDescription></DialogHeader>
        <form id="user-form" className="space-y-3" onSubmit={handleSubmit((v) => save.mutate(v))}>
          <FormField id="u-name" label="Username" error={errors.username?.message}><Input id="u-name" autoComplete="off" {...register("username")} /></FormField>
          <FormField id="u-pass" label="Password" error={errors.password?.message}><Input id="u-pass" type="password" autoComplete="new-password" {...register("password")} /></FormField>
          <FormField label="Role"><Controller control={control} name="role" render={({ field }) => <Select value={field.value} onValueChange={field.onChange}><SelectTrigger aria-label="Role" className="w-full"><SelectValue /></SelectTrigger><SelectContent>{ROLES.map((r) => <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>)}</SelectContent></Select>} /></FormField>
        </form>
        <DialogFooter><Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button><Button type="submit" form="user-form" disabled={save.isPending}>{save.isPending && <Loader2 className="animate-spin" />} Create user</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PasswordDialog({ user, onClose }: { user: ManagedUser | null; onClose: () => void }) {
  const { register, handleSubmit, reset, formState: { errors } } = useForm<{ password: string }>({ resolver: zodResolver(passwordSchema), defaultValues: { password: "" } });
  const save = useMutation({
    mutationFn: (v: { password: string }) => post(`/users/${user?.id}/password`, v),
    onSuccess: () => { toast.success("Password reset. Their sessions were signed out."); reset(); onClose(); },
    onError: (e: Error) => toast.error(e.message),
  });
  return (
    <Dialog open={!!user} onOpenChange={(o) => { if (!o) { reset(); onClose(); } }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>Reset password for {user?.username}</DialogTitle><DialogDescription>This also clears any lockout and signs them out.</DialogDescription></DialogHeader>
        <form id="pw-form" onSubmit={handleSubmit((v) => save.mutate(v))}><FormField id="pw" label="New password" error={errors.password?.message}><Input id="pw" type="password" autoComplete="new-password" {...register("password")} /></FormField></form>
        <DialogFooter><Button variant="outline" onClick={() => { reset(); onClose(); }}>Cancel</Button><Button type="submit" form="pw-form" disabled={save.isPending}>{save.isPending && <Loader2 className="animate-spin" />} Reset password</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function UsersPage() {
  const me = useAuth().user;
  const qc = useQueryClient();
  const { dialog, confirm } = useConfirm();
  const [creating, setCreating] = useState(false);
  const [resetting, setResetting] = useState<ManagedUser | null>(null);
  const q = useQuery({ queryKey: ["users"], queryFn: () => api<ManagedUser[]>("/users") });
  const refresh = () => qc.invalidateQueries({ queryKey: ["users"] });
  const update = useMutation({ mutationFn: (v: { id: string; role: Role; enabled: boolean }) => put(`/users/${v.id}`, { role: v.role, enabled: v.enabled }), onSuccess: () => { toast.success("User updated"); void refresh(); }, onError: (e: Error) => { toast.error(e.message); void refresh(); } });
  const remove = useMutation({ mutationFn: (id: string) => del(`/users/${id}`), onSuccess: () => { toast.success("User deleted"); void refresh(); }, onError: (e: Error) => toast.error(e.message) });
  const add = <Button size="sm" onClick={() => setCreating(true)}><Plus /> New user</Button>;
  return (
    <>
      <PageHeader title="Users" description="Who can sign in, and what they can do." action={add} />
      <Panel>
        {q.isError ? <ErrorState message={q.error.message} onRetry={() => q.refetch()} />
          : !q.data ? <RowsSkeleton />
          : q.data.length === 0 ? <EmptyState icon={Users} title="No users" description="Create the first user." action={add} />
          : <div className="overflow-x-auto"><table className="w-full min-w-[640px] text-sm">
              <thead className="border-b text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">User</th><th className="px-3 py-2 font-medium">Role</th><th className="px-3 py-2 font-medium">Enabled</th><th className="px-3 py-2 font-medium">Last sign-in</th><th aria-label="Actions" className="px-4 py-2" /></tr></thead>
              <tbody className="divide-y">{q.data.map((u) => {
                const self = u.id === me?.id;
                return (
                  <tr key={u.id}>
                    <td className="px-4 py-2 font-mono text-xs">{u.username}{self && <span className="ml-2 font-sans text-muted-foreground">You</span>}{u.locked && <span className="ml-2 font-sans text-status-degraded">Locked</span>}</td>
                    <td className="px-3 py-2"><Select value={u.role} onValueChange={(r) => update.mutate({ id: u.id, role: r as Role, enabled: u.enabled })}><SelectTrigger size="sm" className="w-32" aria-label={`Role for ${u.username}`}><SelectValue /></SelectTrigger><SelectContent>{ROLES.map((r) => <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>)}</SelectContent></Select></td>
                    <td className="px-3 py-2"><Switch checked={u.enabled} disabled={self} aria-label={`${u.username} enabled`} onCheckedChange={(e) => update.mutate({ id: u.id, role: u.role, enabled: e })} /></td>
                    <td className="px-3 py-2 text-xs"><RelativeTime iso={u.lastLoginAt} /></td>
                    <td className="px-4 py-2 text-right"><span className="inline-flex gap-1">
                      <Button size="icon-xs" variant="ghost" aria-label={`Reset password for ${u.username}`} onClick={() => setResetting(u)}><KeyRound /></Button>
                      {!self && <Button size="icon-xs" variant="ghost" aria-label={`Delete ${u.username}`} onClick={() => confirm(`Delete ${u.username}?`, "They will lose access. Their audit history is kept.", () => remove.mutate(u.id))}><Trash2 /></Button>}
                    </span></td>
                  </tr>);
              })}</tbody>
            </table></div>}
      </Panel>
      {dialog}
      <CreateDialog open={creating} onOpenChange={setCreating} />
      <PasswordDialog user={resetting} onClose={() => setResetting(null)} />
    </>
  );
}
