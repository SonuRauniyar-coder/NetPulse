import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ErrorBoundary, RowsSkeleton } from "@/components/common";
import { AppShell } from "@/components/layout/AppShell";
import { AuthProvider, useAuth } from "@/features/auth/AuthContext";
import { LoginPage } from "@/features/auth/LoginPage";
import { DashboardPage } from "@/features/dashboard/DashboardPage";
import { DevicesPage } from "@/features/devices/DevicesPage";
import { DeviceDetailPage } from "@/features/devices/DeviceDetailPage";
import { ComingSoon, NotFound } from "@/pages/misc";
import { ThemeProvider } from "@/lib/theme";

const queryClient = new QueryClient({
  queryCache: new QueryCache(),
  mutationCache: new MutationCache(),
  defaultOptions: { queries: { staleTime: 5_000, retry: 1, refetchOnWindowFocus: false } },
});

function RequireAuth() {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <RowsSkeleton rows={8} />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}

export default function App() {
  return (
    <ErrorBoundary>
      <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          <TooltipProvider delayDuration={200}>
            <BrowserRouter>
              <AuthProvider>
                <Routes>
                  <Route path="/login" element={<LoginPage />} />
                  <Route element={<RequireAuth />}>
                    <Route element={<AppShell />}>
                      <Route index element={<DashboardPage />} />
                      <Route path="devices" element={<DevicesPage />} />
                      <Route path="devices/:id" element={<DeviceDetailPage />} />
                      <Route path="discovery" element={<ComingSoon title="Discovery" phase={2} summary="Authorized LAN sweeps with ARP, reverse DNS and vendor lookup." />} />
                      <Route path="traceroute" element={<ComingSoon title="Traceroute" phase={2} summary="Live hop-by-hop path tracing streamed over WebSocket." />} />
                      <Route path="tools/subnet" element={<ComingSoon title="Subnet Calculator" phase={2} summary="CIDR breakdown, binary mask view and VLSM allocation." />} />
                      <Route path="alerts" element={<ComingSoon title="Alerts" phase={3} summary="Rules, severities and the triggered, acknowledged, resolved lifecycle." />} />
                      <Route path="reports" element={<ComingSoon title="Reports" phase={3} summary="Per-device uptime and SLA reports with CSV export." />} />
                      <Route path="events" element={<ComingSoon title="Event Log" phase={3} summary="Filterable, exportable history of every status change." />} />
                      <Route path="settings" element={<ComingSoon title="Settings" phase={3} summary="Notification channels, retention, thresholds and scan CIDRs." />} />
                      <Route path="users" element={<ComingSoon title="Users" phase={3} summary="User management with Admin, Operator and Viewer roles." />} />
                      <Route path="*" element={<NotFound />} />
                    </Route>
                  </Route>
                </Routes>
              </AuthProvider>
            </BrowserRouter>
            <Toaster />
          </TooltipProvider>
        </QueryClientProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}
