export type DeviceStatus = "UP" | "DEGRADED" | "DOWN" | "UNKNOWN" | "PAUSED";
export type DeviceType = "ROUTER" | "SWITCH" | "FIREWALL" | "SERVER" | "WEBSITE";
export type DnsRecordType = "A" | "AAAA" | "MX" | "NS" | "TXT" | "PTR";
export type Role = "ADMIN" | "OPERATOR" | "VIEWER";
export type Range = "1h" | "6h" | "24h" | "7d";

export interface UserInfo { id: string; username: string; role: Role }
export interface TokenResponse { accessToken: string; expiresAt: string; user: UserInfo }
export interface Port { port: number; label?: string }
export interface DnsCheck { recordType: DnsRecordType; queryName?: string; dnsServer?: string }

export interface Device {
  id: string; name: string; host: string; type: DeviceType; location?: string; description?: string;
  tags: string[]; checkIntervalSeconds: number; enabled: boolean; status: DeviceStatus; icmpBlocked: boolean;
  resolvedIp?: string; lastRttMs?: number; lastLossPct?: number; lastCheckedAt?: string; nextCheckAt?: string;
  statusChangedAt?: string; createdAt: string; updatedAt: string; ports: Port[]; dnsChecks: DnsCheck[];
}

export interface DeviceRequest {
  name: string; host: string; type: DeviceType; location?: string; description?: string; tags: string[];
  checkIntervalSeconds: number; enabled: boolean; ports: Port[]; dnsChecks: DnsCheck[];
}

export interface Page<T> { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number } }
export interface MetricPoint { t: string; rttAvg?: number; rttMin?: number; rttMax?: number; lossPct?: number; jitter?: number }
export interface PingDetail { sent: number; received: number; lossPct: number; rttMin?: number; rttAvg?: number; rttMax?: number; jitter?: number; errorCategory?: string; errorDetail?: string }
export interface CheckSummary { id: number; checkedAt: string; verdict: string; status: DeviceStatus; icmpBlocked: boolean; resolvedIp?: string; reason?: string; durationMs: number; ping?: PingDetail }
export interface TcpDetail { port: number; state: "OPEN" | "CLOSED" | "FILTERED_OR_UNKNOWN"; connectMs?: number; error?: string }
export interface DnsDetail { recordType: string; queryName: string; dnsServer?: string; outcome: string; resolveMs?: number; answers: string[]; error?: string }
export interface CheckDetail { check: CheckSummary; tcp: TcpDetail[]; dns: DnsDetail[] }
export interface UptimeSegment { start: string; end: string; status?: DeviceStatus; checks: number; up: number; degraded: number; down: number }
export interface UptimeResponse { uptimePct?: number; segments: UptimeSegment[] }
export interface StatusEvent { id: number; deviceId: string; deviceName: string; fromStatus: DeviceStatus; toStatus: DeviceStatus; reason?: string; occurredAt: string }
export interface SlowDevice { id: string; name: string; host: string; status: DeviceStatus; rttMs: number }
export interface DashboardSummary { total: number; up: number; degraded: number; down: number; unknown: number; paused: number; avgLatencyMs?: number; networkUptimePct?: number; slowest: SlowDevice[]; recentChanges: StatusEvent[] }
export interface SystemInfo { demoMode: boolean; monitoringEnabled: boolean }
