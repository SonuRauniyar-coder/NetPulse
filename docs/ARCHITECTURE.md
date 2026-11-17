# Architecture

NetPulse is a modular monolith: one deployable Spring Boot application, organized by feature. At this scale, microservices would add network failure modes and operational cost with no benefit.

## Modules

| Package | Responsibility |
|---|---|
| `common` | Errors (RFC 7807), `TargetValidator`, `ProcessRunner`, configuration |
| `auth`, `user` | Login, JWT, refresh rotation, lockout; users and first-admin bootstrap |
| `device` | Inventory CRUD, validation, search |
| `monitoring.engine` | Scheduler, concurrency limits, one check cycle |
| `monitoring.ping` / `tcp` / `dns` | The probes |
| `monitoring.status` | Pure status logic |
| `monitoring.result` | Time-series storage and queries |
| `monitoring.retention` | Hourly rollups and cleanup |
| `dashboard`, `realtime`, `system`, `demo` | KPIs, STOMP push, public info, synthetic seed |

## Data flow of a check

```mermaid
sequenceDiagram
    participant S as MonitoringScheduler (1s tick)
    participant R as CheckRunner (virtual thread)
    participant T as Target
    participant D as PostgreSQL
    participant W as Browser (STOMP)
    S->>D: find enabled devices with next_check_at <= now
    S->>D: claim: next_check_at = now + interval
    S->>R: start, if not already in flight
    R->>R: acquire semaphore, resolve host once, validate the address
    par probes share one deadline
        R->>T: ping process
        R->>T: TCP connects
        R->>T: DNS queries
    end
    R->>D: one transaction: lock device, evaluate, store results and event
    R->>W: publish after commit
```

In Phase 3 the alert engine attaches after the commit, next to the publish step.

## Database

```mermaid
erDiagram
    app_user ||--o{ refresh_token : has
    device ||--o{ device_tag : tagged
    device ||--o{ device_port : "ports to check"
    device ||--o{ device_dns_check : "lookups to run"
    device ||--o{ device_check : "one row per cycle"
    device_check ||--o| ping_result : ""
    device_check ||--o{ tcp_result : ""
    device_check ||--o{ dns_result : ""
    device ||--o{ status_event : ""
    device ||--o{ rollup_hourly : ""
```

The migration [V1__baseline.sql](../backend/src/main/resources/db/migration/V1__baseline.sql) is the full definition.

## Key decisions

| Decision | Reason |
|---|---|
| Polling tick on `next_check_at`, not one timer per device | Survives restarts; interval changes and "check now" are single column updates |
| Virtual threads plus a semaphore | Threads are cheap but each cycle starts a process, so concurrency still needs a limit |
| In-flight set | A slow device is skipped, never checked twice at once |
| No DB transaction during network I/O | A slow host must never hold a pooled connection or row lock |
| `device_check` parent row per cycle | Uptime depends on the combined result; ping alone would count ICMP-blocked hosts as down |
| JDBC for time-series, JPA for inventory | Append-only rows gain nothing from entity tracking; CRUD does |
| Engine updates device state with SQL | Monitoring writes don't conflict with the optimistic lock on user edits |
| Idempotent upsert rollups | The open hour can be recomputed, and a crash needs no cleanup |
| Resolve once per cycle, connect to that address | Closes the DNS-rebinding window |
| Monitor faults yield `UNKNOWN` | A broken monitor must not report the whole network as down |
| JWT in memory, refresh in `HttpOnly` cookie | XSS can't read the long-lived credential; only cookie endpoints need CSRF defense |
| No CSRF rotation on authentication | With stateless JWT, every request "authenticates", so rotation would break concurrent refreshes |
| No Lombok | Explicit code avoids coupling the build to compiler internals across JDK versions |

## Privileges and platforms

| Concern | Detail |
|---|---|
| Linux containers | Run as non-root with `cap_drop: ALL` and `cap_add: NET_RAW`, the minimum for ICMP |
| macOS | `ping` is privileged already; `ping6` handles IPv6 and uses a different output format |
| Windows | Output is localized, so the parser matches structure. Fixtures are written from the documented format and have **not** been verified on a live Windows machine |
| Docker Desktop | Containers run in a VM and can't see your LAN's ARP table. Phase 2 discovery needs the native backend, or `network_mode: host` on Linux |

## Current limits

- Thresholds are global and set in configuration. The Settings page arrives in Phase 3.
- Rate limiting and the STOMP broker are in-memory, so the backend runs as a single instance.
