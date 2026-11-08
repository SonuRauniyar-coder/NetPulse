# NetPulse

Real-time network monitoring: ICMP, TCP and DNS checks with live status, latency history and a NOC-style interface.

![Dashboard](docs/screenshots/dashboard.png)

NetPulse measures real networks. Status comes from real `ping` processes, TCP handshakes and DNS queries. Synthetic data exists only in the separate, permanently labelled demo stack.

## Features (Phase 1)

- **Device inventory** with validation of IPv4, IPv6 and hostnames, tags, per-device intervals, bulk actions and server-side search, filter, sort and paging
- **ICMP monitoring** through the OS `ping`, with per-OS parsers (Linux, macOS, Windows): RTT min/avg/max, packet loss, jitter, and categorized errors
- **TCP port checks** classified as `OPEN`, `CLOSED` or `FILTERED_OR_UNKNOWN`
- **DNS checks** for A, AAAA, MX, NS, TXT and PTR against any server, keeping NXDOMAIN, SERVFAIL, NODATA and timeouts distinct
- **Status logic that avoids false alarms:** an ICMP failure alone never means DOWN, and configurable thresholds prevent flapping
- **Live dashboard** over authenticated WebSockets, with latency trends and a 24h uptime bar
- **Hourly rollups and retention** (7 days raw, 90 days rolled up)
- **Security:** short-lived JWT, rotating hashed refresh tokens with reuse detection, CSRF protection, login lockout and rate limiting, and a DNS-rebinding guard

Planned: traceroute, subnet calculator, LAN discovery and SNMP (Phase 2); alerts, notifications, RBAC, audit log and reports (Phase 3).

## Quick start

**Demo (synthetic data, no configuration):**

```bash
docker compose -f docker-compose.demo.yml up --build
```

Open <http://localhost:8089> and sign in with `demo` / `NetPulse-Demo-2026!`.

**Real monitoring:**

```bash
cp .env.example .env     # then set every value; use COOKIE_SECURE=false for plain http on localhost
docker compose up --build
```

Open <http://localhost:8088>.

**Development with hot reload:**

```bash
docker compose -f docker-compose.dev.yml up -d
cd backend  && ./mvnw spring-boot:run     # dev login: admin / NetPulse-Dev-2026!
cd frontend && npm install && npm run dev  # http://localhost:5173
```

Build with JDK 21 to 25. If your default JDK is newer (Homebrew's Maven can pick up a newer one), set `JAVA_HOME` first, because JaCoCo doesn't support later class-file versions yet.

## Architecture

```mermaid
flowchart LR
    B[Browser] -->|HTTPS, WSS| N[Nginx]
    N -->|/api, /ws| A[Spring Boot monolith]
    A --> P[(PostgreSQL)]
    A -->|ping process| T[Monitored devices]
    A -->|TCP connect, DNS query| T
```

A modular monolith with no microservices or message broker. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

**Stack:** Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, dnsjava, MapStruct, React 18, TypeScript, Vite, Tailwind, shadcn/ui, TanStack Query, Recharts, Docker, Nginx, GitHub Actions.

## Testing

```bash
cd backend  && ./mvnw verify      # unit + Testcontainers integration tests (Docker required)
cd frontend && npm test           # component and validation tests
cd frontend && E2E_PASS=<admin password> npm run e2e   # against a running stack
```

## Try it by hand

1. Add `8.8.8.8` with TCP port `443`. It should turn **Up** without reloading the page.
2. Add a TCP port nothing listens on, such as `81`. The device shows **Degraded**, and the Ports tab explains why.
3. Add `192.0.2.1`, a reserved address that never answers. It stays **Unknown**, then turns **Down** after 3 failed cycles.
4. Compare the numbers with `ping -c 4 8.8.8.8` and `dig @8.8.8.8 example.com`.

Note: on macOS and Windows, Docker runs in a VM. Latency from the containerized backend includes that VM's NAT, and only the native backend can see your LAN directly.

## Learning docs

[ICMP and ping](docs/learning/icmp-ping.md) · [TCP ports](docs/learning/tcp-ports.md) · [DNS](docs/learning/dns.md) · [Device status](docs/learning/device-status.md)
