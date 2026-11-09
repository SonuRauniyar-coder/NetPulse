import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Activity, Bell, Calculator, FileText, LayoutDashboard, ListTree, LogOut, Moon, PanelLeft, Radar, Route, Search, Server,
  Settings, Sun, Users, type LucideIcon,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { CommandDialog, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useAuth, useCan } from "@/features/auth/AuthContext";
import { api } from "@/lib/api";
import { LiveProvider, useLiveConnected } from "@/lib/live";
import { useTheme } from "@/lib/theme";
import type { AlertSummary, Device, Page, SystemInfo } from "@/lib/types";
import { cn } from "@/lib/utils";
import { StatusBadge } from "@/components/common";
import { LogoMark } from "./Logo";

interface NavItem { to: string; label: string; icon: LucideIcon; adminOnly?: boolean }
const NAV: NavItem[] = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard },
  { to: "/devices", label: "Devices", icon: Server },
  { to: "/discovery", label: "Discovery", icon: Radar },
  { to: "/traceroute", label: "Traceroute", icon: Route },
  { to: "/tools/subnet", label: "Subnet Calculator", icon: Calculator },
  { to: "/alerts", label: "Alerts", icon: Bell },
  { to: "/reports", label: "Reports", icon: FileText },
  { to: "/events", label: "Event Log", icon: ListTree },
  { to: "/settings", label: "Settings", icon: Settings, adminOnly: true },
  { to: "/users", label: "Users", icon: Users, adminOnly: true },
];

function useNav() {
  const { admin } = useCan();
  return NAV.filter((n) => admin || !n.adminOnly);
}

function readCollapsed() {
  try { return localStorage.getItem("np-sidebar") === "1"; } catch { return false; }
}

function Sidebar({ collapsed }: { collapsed: boolean }) {
  const nav = useNav();
  return (
    <aside className={cn("sticky top-0 hidden h-screen shrink-0 flex-col border-r bg-sidebar md:flex", collapsed ? "w-14" : "w-56")}>
      <Link to="/" className="flex h-14 items-center gap-2.5 border-b px-3.5">
        <LogoMark />
        {!collapsed && <span className="font-semibold tracking-tight">NetPulse</span>}
      </Link>
      <nav aria-label="Main" className="flex-1 space-y-0.5 overflow-y-auto p-2">
        {nav.map(({ to, label, icon: Icon }) => {
          const link = (
            <NavLink to={to} end={to === "/"} aria-label={label}
              className={({ isActive }) => cn("flex items-center gap-2.5 rounded-md px-2.5 py-2 text-sm",
                isActive ? "bg-accent font-medium text-accent-foreground" : "text-muted-foreground hover:bg-muted hover:text-foreground")}>
              <Icon aria-hidden className="size-4 shrink-0" />
              {!collapsed && label}
            </NavLink>
          );
          return collapsed
            ? <Tooltip key={to}><TooltipTrigger asChild>{link}</TooltipTrigger><TooltipContent side="right">{label}</TooltipContent></Tooltip>
            : <div key={to}>{link}</div>;
        })}
      </nav>
    </aside>
  );
}

function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const navigate = useNavigate();
  const nav = useNav();
  const [q, setQ] = useState("");
  const { data } = useQuery({
    queryKey: ["devices", "palette", q],
    queryFn: () => api<Page<Device>>(`/devices?size=8&q=${encodeURIComponent(q)}`),
    enabled: open,
  });
  const go = (to: string) => { onOpenChange(false); setQ(""); navigate(to); };
  return (
    <CommandDialog open={open} onOpenChange={onOpenChange} title="Search" description="Find devices and pages" >
      <CommandInput placeholder="Search devices and pages…" value={q} onValueChange={setQ} />
      <CommandList>
        <CommandEmpty>No results.</CommandEmpty>
        {!!data?.content.length && (
          <CommandGroup heading="Devices">
            {data.content.map((d) => (
              <CommandItem key={d.id} value={`${d.name} ${d.host} ${q}`} onSelect={() => go(`/devices/${d.id}`)}>
                <span className="flex-1 truncate">{d.name}</span>
                <span className="font-mono text-xs text-muted-foreground">{d.host}</span>
                <StatusBadge status={d.status} />
              </CommandItem>
            ))}
          </CommandGroup>
        )}
        <CommandGroup heading="Pages">
          {nav.map(({ to, label, icon: Icon }) => <CommandItem key={to} value={label} onSelect={() => go(to)}><Icon aria-hidden /> {label}</CommandItem>)}
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
}

function LiveIndicator() {
  const live = useLiveConnected();
  return (
    <span role="status" className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className={cn("size-2 rounded-full", live ? "bg-status-up" : "bg-status-neutral")} aria-hidden />
      {live ? "Live" : "Reconnecting"}
    </span>
  );
}

function AlertBell() {
  const { data } = useQuery({ queryKey: ["alerts", "summary"], queryFn: () => api<AlertSummary>("/alerts/summary"), refetchInterval: 30_000 });
  const count = data?.active ?? 0;
  return (
    <Button variant="ghost" size="icon-sm" asChild aria-label={`Alerts, ${count} active`} className="relative">
      <Link to="/alerts">
        <Bell />
        {count > 0 && <span className="absolute -top-1 -right-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-status-down px-1 font-mono text-[10px] font-semibold text-background">{count > 99 ? "99+" : count}</span>}
      </Link>
    </Button>
  );
}

function Breadcrumbs() {
  const { pathname } = useLocation();
  const parts = pathname.split("/").filter(Boolean);
  if (parts.length === 0) return <span className="text-sm font-medium">Dashboard</span>;
  const isUuid = (s: string) => /^[0-9a-f-]{36}$/.test(s);
  const label = (s: string) => (isUuid(s) ? "Details" : s[0].toUpperCase() + s.slice(1));
  return (
    <nav aria-label="Breadcrumb" className="flex min-w-0 items-center gap-1.5 text-sm">
      <Link to="/" className="text-muted-foreground hover:text-foreground">Home</Link>
      {parts.map((p, i) => (
        <span key={i} className="flex items-center gap-1.5 truncate">
          <span aria-hidden className="text-muted-foreground">/</span>
          {i === parts.length - 1
            ? <span aria-current="page" className="truncate font-medium">{label(p)}</span>
            : <Link className="text-muted-foreground hover:text-foreground" to={`/${parts.slice(0, i + 1).join("/")}`}>{label(p)}</Link>}
        </span>
      ))}
    </nav>
  );
}

export function AppShell() {
  const { user, signOut } = useAuth();
  const { theme, toggle } = useTheme();
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const [palette, setPalette] = useState(false);
  const { data: system } = useQuery({ queryKey: ["system"], queryFn: () => api<SystemInfo>("/system/info"), staleTime: Infinity });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); setPalette((o) => !o); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const toggleSidebar = () => setCollapsed((c) => {
    try { localStorage.setItem("np-sidebar", c ? "0" : "1"); } catch { /* not persisted */ }
    return !c;
  });

  return (
    <LiveProvider>
      <div className="flex min-h-screen">
        <Sidebar collapsed={collapsed} />
        <div className="flex min-w-0 flex-1 flex-col">
          {system?.demoMode && (
            <div role="note" className="bg-status-degraded/15 px-4 py-1.5 text-center text-xs font-medium text-status-degraded">
              Demo mode: all data on this instance is synthetic. No real network is being monitored.
            </div>
          )}
          <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b bg-background/95 px-4 backdrop-blur">
            <Button variant="ghost" size="icon-sm" aria-label="Toggle sidebar" className="hidden md:inline-flex" onClick={toggleSidebar}><PanelLeft /></Button>
            <Link to="/" className="md:hidden" aria-label="NetPulse home"><LogoMark /></Link>
            <div className="min-w-0 flex-1"><Breadcrumbs /></div>
            <button type="button" onClick={() => setPalette(true)} className="hidden h-8 w-56 items-center gap-2 rounded-md border px-2.5 text-sm text-muted-foreground hover:text-foreground lg:flex">
              <Search aria-hidden className="size-3.5" /> Search
              <kbd className="ml-auto font-mono text-[10px]">Ctrl K</kbd>
            </button>
            <Button variant="ghost" size="icon-sm" aria-label="Search" className="lg:hidden" onClick={() => setPalette(true)}><Search /></Button>
            <LiveIndicator />
            <AlertBell />
            <Button variant="ghost" size="icon-sm" aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`} onClick={toggle}>
              {theme === "dark" ? <Sun /> : <Moon />}
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="sm" aria-label="User menu" className="font-mono text-xs"><Activity aria-hidden /> {user?.username}</Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuLabel>{user?.role}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => void signOut()}><LogOut /> Sign out</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </header>
          <main className="mx-auto w-full max-w-[1400px] flex-1 p-4 md:p-6"><Outlet /></main>
        </div>
        <CommandPalette open={palette} onOpenChange={setPalette} />
      </div>
    </LiveProvider>
  );
}
