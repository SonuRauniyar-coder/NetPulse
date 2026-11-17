import { Component, useEffect, useState, type ErrorInfo, type ReactNode } from "react";
import { formatDistanceToNowStrict } from "date-fns";
import { AlertTriangle, CheckCircle2, CircleHelp, CircleX, PauseCircle, RefreshCw, TriangleAlert, type LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { fmtDateTime } from "@/lib/format";
import type { DeviceStatus } from "@/lib/types";
import { cn } from "@/lib/utils";

const STATUS: Record<DeviceStatus, { label: string; icon: LucideIcon; cls: string }> = {
  UP: { label: "Up", icon: CheckCircle2, cls: "text-status-up border-status-up/30 bg-status-up/10" },
  DEGRADED: { label: "Degraded", icon: TriangleAlert, cls: "text-status-degraded border-status-degraded/30 bg-status-degraded/10" },
  DOWN: { label: "Down", icon: CircleX, cls: "text-status-down border-status-down/30 bg-status-down/10" },
  UNKNOWN: { label: "Unknown", icon: CircleHelp, cls: "text-status-neutral border-status-neutral/30 bg-status-neutral/10" },
  PAUSED: { label: "Paused", icon: PauseCircle, cls: "text-status-neutral border-status-neutral/30 bg-status-neutral/10" },
};

/** The only place status is rendered: always icon + text, never color alone. */
export function StatusBadge({ status, className }: { status: DeviceStatus; className?: string }) {
  const s = STATUS[status];
  const Icon = s.icon;
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium whitespace-nowrap", s.cls, className)}>
      <Icon aria-hidden className="size-3.5" />
      {s.label}
    </span>
  );
}

function useTick(ms = 30_000) {
  const [, set] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => set((n) => n + 1), ms);
    return () => window.clearInterval(id);
  }, [ms]);
}

export function RelativeTime({ iso, fallback = "Never" }: { iso?: string | null; fallback?: string }) {
  useTick();
  if (!iso) return <span className="text-muted-foreground">{fallback}</span>;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <time dateTime={iso} tabIndex={0} className="whitespace-nowrap">{formatDistanceToNowStrict(new Date(iso), { addSuffix: true })}</time>
      </TooltipTrigger>
      <TooltipContent>{fmtDateTime(iso)}</TooltipContent>
    </Tooltip>
  );
}

export function PageHeader({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {action}
    </div>
  );
}

export function EmptyState({ icon: Icon, title, description, action }: { icon: LucideIcon; title: string; description: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
      <div className="mb-3 rounded-lg border bg-muted p-2.5"><Icon aria-hidden className="size-5 text-muted-foreground" /></div>
      <p className="text-sm font-medium">{title}</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message?: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center justify-center px-6 py-12 text-center">
      <AlertTriangle aria-hidden className="mb-3 size-6 text-status-down" />
      <p className="text-sm font-medium">Couldn't load this data</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">{message ?? "Something went wrong."}</p>
      {onRetry && <Button variant="outline" size="sm" className="mt-4" onClick={onRetry}><RefreshCw /> Retry</Button>}
    </div>
  );
}

export function RowsSkeleton({ rows = 5 }: { rows?: number }) {
  return <div className="space-y-2 p-4">{Array.from({ length: rows }, (_, i) => <Skeleton key={i} className="h-9 w-full" />)}</div>;
}

export function Panel({ title, action, children, className }: { title?: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-lg border bg-card", className)}>
      {title && (
        <header className="flex items-center justify-between gap-3 border-b px-4 py-3">
          <h2 className="text-sm font-medium">{title}</h2>
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error("UI crash", error, info); }
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="flex min-h-screen items-center justify-center">
        <ErrorState message="The interface hit an unexpected error." onRetry={() => location.reload()} />
      </div>
    );
  }
}
