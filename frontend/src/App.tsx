import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ErrorBoundary, RowsSkeleton } from "@/components/common";
import { AppShell } from "@/components/layout/AppShell";
import { AuthProvider, useAuth, useCan } from "@/features/auth/AuthContext";
import { LoginPage } from "@/features/auth/LoginPage";
import { DashboardPage } from "@/features/dashboard/DashboardPage";
import { DevicesPage } from "@/features/devices/DevicesPage";
import { DeviceDetailPage } from "@/features/devices/DeviceDetailPage";
import { NotFound } from "@/pages/misc";
import { SubnetPage } from "@/features/tools/SubnetPage";
import { TraceroutePage } from "@/features/traceroute/TraceroutePage";
import { DiscoveryPage } from "@/features/discovery/DiscoveryPage";
import { AlertsPage } from "@/features/alerts/AlertsPage";
import { ReportsPage } from "@/features/reports/ReportsPage";
import { EventLogPage } from "@/features/events/EventLogPage";
import { SettingsPage } from "@/features/settings/SettingsPage";
import { UsersPage } from "@/features/users/UsersPage";
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

function RequireAdmin() {
  return useCan().admin ? <Outlet /> : <NotFound />;
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
                      <Route path="discovery" element={<DiscoveryPage />} />
                      <Route path="traceroute" element={<TraceroutePage />} />
                      <Route path="tools/subnet" element={<SubnetPage />} />
                      <Route path="alerts" element={<AlertsPage />} />
                      <Route path="reports" element={<ReportsPage />} />
                      <Route path="events" element={<EventLogPage />} />
                      <Route element={<RequireAdmin />}>
                        <Route path="settings" element={<SettingsPage />} />
                        <Route path="users" element={<UsersPage />} />
                      </Route>
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
