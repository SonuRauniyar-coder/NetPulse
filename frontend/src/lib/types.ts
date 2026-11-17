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
  statusChangedAt?: string; createdAt: string; updatedAt: string; ports: Port[]; dnsChecks: DnsCheck[]; version: number;
}

export interface DeviceRequest {
  name: string; host: string; type: DeviceType; location?: string; description?: string; tags: string[];
  checkIntervalSeconds: number; enabled: boolean; ports: Port[]; dnsChecks: DnsCheck[]; version?: number;
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
export interface DashboardSummary { total: number; up: number; degraded: number; down: number; unknown: number; paused: number; avgLatencyMs?: number; networkUptimePct?: number; slowest: SlowDevice[]; recentChanges: StatusEvent[]; activeAlerts: number; recentAlerts: Alert[] }
export interface SystemInfo { demoMode: boolean; monitoringEnabled: boolean }

export interface SubnetBinary { address: string; mask: string; network: string; broadcast?: string }
export interface SubnetInfo { input: string; cidr: string; address: string; prefix: number; network: string; broadcast?: string; firstHost: string; lastHost: string; totalAddresses: number; usableHosts: number; subnetMask: string; wildcardMask: string; addressScope: string; historicalClass: string; binary: SubnetBinary; note?: string }
export interface VlsmAllocation { name: string; requestedHosts: number; cidr: string; network: string; firstHost: string; lastHost: string; broadcast: string; subnetMask: string; usableHosts: number; wastedHosts: number }
export interface VlsmResult { baseCidr: string; totalAddresses: number; allocatedAddresses: number; freeAddresses: number; allocations: VlsmAllocation[] }

export interface TraceHop { hop: number; ip?: string; hostname?: string; rttsMs: (number | null)[]; otherIps: string[]; note?: string }
export type TraceStatus = "RUNNING" | "COMPLETED" | "TIMED_OUT" | "FAILED";
export interface TraceRun { id: string; target: string; ip: string; status: TraceStatus; reachedTarget: boolean; error?: string; hops: TraceHop[]; startedAt: string; finishedAt?: string }

export type ScanStatus = "RUNNING" | "COMPLETED" | "PARTIAL" | "FAILED" | "INTERRUPTED";
export interface Scan { id: string; cidr: string; status: ScanStatus; requestedBy: string; total: number; scanned: number; found: number; error?: string; startedAt: string; finishedAt?: string }
export interface DiscoveredHost { ip: string; mac?: string; vendor?: string; hostname?: string; rttMs?: number; icmpResponded: boolean; monitored: boolean }
export interface ScanDetail { scan: Scan; hosts: DiscoveredHost[] }

export type SnmpVersion = "V2C" | "V3";
export interface SnmpConfig { enabled: boolean; version: SnmpVersion; port: number; intervalSeconds: number; sysName?: string; sysDescr?: string; sysLocation?: string; sysUptimeTicks?: number; lastPollAt?: string; lastSuccessAt?: string; lastError?: string }
export interface SnmpSpark { t: string; inBps: number; outBps: number }
export interface SnmpInterface { ifIndex: number; descr?: string; adminStatus?: number; operStatus?: number; speedBps?: number; inBps?: number; outBps?: number; utilizationPct?: number; inErrors?: number; outErrors?: number; counterBits?: number; samples: SnmpSpark[] }
export interface SnmpView { config?: SnmpConfig; interfaces: SnmpInterface[] }

export type Severity = "CRITICAL" | "WARNING" | "INFO";
export type AlertState = "TRIGGERED" | "ACKNOWLEDGED" | "RESOLVED";
export type RuleType = "DEVICE_DOWN" | "RTT_ABOVE" | "LOSS_ABOVE" | "PORT_CLOSED" | "INTERFACE_DOWN" | "INTERFACE_UTILIZATION";
export interface Alert { id: string; ruleId?: string; ruleName: string; deviceId: string; deviceName: string; subject: string; severity: Severity; state: AlertState; message: string; value?: number; triggeredAt: string; acknowledgedAt?: string; acknowledgedBy?: string; resolvedAt?: string; resolveReason?: string }
export interface AlertSummary { active: number; triggered: number; acknowledged: number; critical: number }
export interface AlertRule { id: string; name: string; type: RuleType; severity: Severity; deviceId?: string; deviceName?: string; threshold?: number; port?: number; interfaceName?: string; durationSeconds: number; enabled: boolean }
export interface MaintenanceWindow { id: string; deviceId: string; deviceName: string; startsAt: string; endsAt: string; reason?: string; createdBy: string }

export interface NotificationChannel { id: string; name: string; type: "EMAIL" | "WEBHOOK"; enabled: boolean; minSeverity: Severity; recipients?: string; webhookFormat?: WebhookFormat; webhookHost?: string; telegramChatId?: string }
export type WebhookFormat = "SLACK" | "DISCORD" | "TELEGRAM" | "GENERIC";
export interface NotificationDelivery { id: number; channelName: string; event: string; subject: string; status: "PENDING" | "SENT" | "FAILED"; attempts: number; lastError?: string; createdAt: string; sentAt?: string }
export interface AppSettings { degradedRttMs: number; degradedLossPct: number; downAfterFailures: number; upAfterSuccesses: number; defaultIntervalSeconds: number; rawDays: number; rollupDays: number; scanCidrs: string[] }
export interface ManagedUser { id: string; username: string; role: Role; enabled: boolean; locked: boolean; lastLoginAt?: string; createdAt: string }
export interface AuditEntry { id: number; actor: string; action: string; entityType: string; entityId?: string; summary: string; clientIp?: string; occurredAt: string }
export interface DeviceReport { deviceId: string; name: string; host: string; observedChecks: number; up: number; degraded: number; down: number; uptimePct?: number; observedSeconds: number; downtimeSeconds: number; incidents: number; slaMet?: boolean }
export interface UptimeReport { from: string; to: string; slaTargetPct: number; devices: DeviceReport[] }
