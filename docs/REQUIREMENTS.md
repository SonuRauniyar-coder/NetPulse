# NetPulse — Production-Grade Network Monitoring Web App

## 0. Your role

Act as a **Senior Network Engineer, Senior Java Backend Developer and Technical Architect**.
Your job is not only to generate code but to **mentor me** throughout development.

I am a fresher transitioning from Spring Boot development to Network Engineering.
Prioritize practical networking knowledge, real implementations, production-quality code
and interview preparation.

**Before writing any code:**
1. Analyze the complete requirements in this document.
2. Identify technical challenges and dependencies.
3. Propose the project folder structure.
4. Explain the database schema (with a diagram).
5. Present the implementation roadmap.
6. Identify OS-specific requirements (Windows dev vs Linux/Docker prod).

**Do not start coding until I approve the architecture and the Phase 1 plan.**

During development, explain why we use each networking API, how the underlying protocol
works, and how I can verify the results independently using terminal commands and Wireshark.
Never claim a feature or test is complete without actually implementing and verifying it.
Work incrementally and wait for my approval between phases.

---

## 1. Context and goals

I'm a fresher (B.Tech CSE, 2026) with Spring Boot experience, switching my career to
Network Engineering. This project should:

1. Give me real hands-on practice with networking concepts: ICMP, TCP/UDP, DNS,
   CIDR/subnetting/VLSM, traceroute/TTL, ARP, SNMP, latency/jitter/packet loss.
2. Be a portfolio project built to production standards. It should look, behave and be
   engineered like a real commercial product (think Datadog / PRTG / Grafana).

Rules:
- I must be able to explain every feature in an interview. Explain the networking concept
  behind each feature in code comments and in the learning docs (section 13).
- Production quality without unnecessary complexity: **no microservices, no Kafka, no
  Kubernetes**. A well-built modular monolith is the right architecture here.
- All metrics must come from real network operations. **No fake or mocked metrics** outside
  the clearly labelled `demo` profile.

---

## 2. Tech stack

- **Backend:** Java 21, Spring Boot 3.x (Web, Data JPA, Validation, Security, Scheduling,
  WebSocket/STOMP, Mail, Actuator), Flyway, MapStruct (DTO mapping), Lombok optional
- **Networking libraries:** SNMP4J (SNMP), dnsjava (DNS record lookups)
- **Database:** PostgreSQL 16 (Testcontainers in tests; no H2)
- **Frontend:** React 18 + TypeScript + Vite, Tailwind CSS, shadcn/ui, lucide-react,
  TanStack Query, React Router, React Hook Form + Zod, Recharts
- **Infra:** Docker (multi-stage builds), docker-compose, Nginx reverse proxy
  (serves the frontend, proxies `/api` and `/ws`)
- **CI:** GitHub Actions
- **Platforms:** Windows (my dev machine) and Linux (production / Docker)

---

## 3. Architecture

- **Modular monolith.** Backend packages by feature:
  `device`, `monitoring` (ping/tcp/dns), `snmp`, `traceroute`, `discovery`, `subnet`,
  `alert`, `notification`, `auth`, `user`, `audit`, `common` (errors, config, utils).
  Inside each: `controller / service / repository / entity / dto / mapper`.
- **Monitoring engine:**
  - A scheduler dispatches checks per device based on its own interval.
  - Use Java 21 virtual threads for blocking network I/O, **but still enforce a bounded
    number of concurrent checks** (e.g. a semaphore). Every ping spawns an OS process,
    so unlimited concurrency would exhaust processes, sockets and memory.
  - Every check has a hard timeout. Slow or unreachable hosts never block other checks.
  - Prevent overlapping checks for the same device. If the previous check for a device
    is still running, skip and log; never run duplicates.
  - Results are saved to the DB and pushed to the UI over WebSocket.
- **Time-series data:** raw results kept 7 days; hourly rollups (avg/min/max RTT,
  loss %, uptime %) kept 90 days. Retention is configurable. Rollup and cleanup run
  as scheduled jobs and delete in batches. Index `(device_id, timestamp)`.
- **Configuration:** `application.yml` with profiles `dev`, `test`, `prod`, `demo`.
  All secrets come from environment variables. Provide `.env.example`; never commit secrets.

---

## 4. Implementation priority

Build progressively. Do not remove advanced features; implement them in this order.

| Priority | Features | Phase |
|---|---|---|
| P1 | Device inventory, Ping, TCP, DNS, Dashboard | Phase 1 |
| P1 | Subnet calculator, Traceroute | Phase 2 |
| P2 | LAN discovery, ARP, SNMP | Phase 2 |
| P2 | Alerts, notifications, reports | Phase 3 |
| P3 | RBAC, audit logs, CI/CD, deployment hardening | Phase 3 |

Basic login (a single admin account) is needed from Phase 1 so the app is never
exposed unauthenticated. Full RBAC comes in Phase 3.

---

## 5. Features

### Phase 1 — Core monitoring

**1. Device inventory**
CRUD: name, IP/hostname, type (router/switch/firewall/server/website), location, tags,
check interval, enabled/paused. Validate IPv4/IPv6/hostname.

**2. ICMP ping monitoring**
- Don't use `InetAddress.isReachable()`: it is unreliable and can silently fall back to
  a TCP echo (port 7) without root. Explain this in a comment.
- Use the OS `ping` command via `ProcessBuilder`:
  - Detect the OS at runtime. Use separate command builders and **separate output
    parsers for Windows and Linux**.
  - Pass validated arguments as a list; never build shell strings.
  - Enforce a process timeout, kill processes that exceed it, and always clean up.
  - Handle a missing `ping` utility and permission errors gracefully, with clear errors.
- Record: sent/received probe counts, RTT min/avg/max, packet loss %, jitter.
  - Packet loss = `(sent − received) / sent × 100`, from actual probe counts.
  - Jitter = mean absolute difference between consecutive RTT samples
    (document the formula in code and docs).
- Error categories: timeout, host unreachable, DNS resolution failure,
  permission denied, tool missing.

**3. Device status logic**
- States: `UP`, `DEGRADED`, `DOWN`, `UNKNOWN`, `PAUSED`, with configurable thresholds
  (e.g. RTT > X ms or loss > Y % → DEGRADED).
- **Never assume an ICMP failure means the device is offline.** Many hosts and firewalls
  block ICMP. If ping fails but a configured TCP check succeeds, the device is reachable
  (show "ICMP blocked"). DOWN means all configured checks failed.
- Flap prevention: DOWN only after N consecutive failures, back to UP only after M
  consecutive successes (both configurable).

**4. TCP port checks**
Configurable ports per device (22, 53, 80, 443, 3389, custom). Use `Socket.connect`
with a timeout. Classify results as follows:
- `OPEN`: TCP connection established.
- `CLOSED`: connection explicitly refused (RST).
- `FILTERED_OR_UNKNOWN`: timeout or ambiguous error. **Do not claim every timeout is a
  firewall.**

Record connect time.

**5. DNS checks**
A/AAAA/MX/NS/TXT lookups via dnsjava, resolution time, reverse DNS (PTR), and an option to
query a specific DNS server. Distinguish NXDOMAIN, SERVFAIL and timeout.

**6. Dashboard and device pages**
See section 6.

### Phase 2 — Network engineer tools

**1. Traceroute**
Run `traceroute` (Linux) or `tracert` (Windows) with separate parsers, a timeout and
cleanup. Parse hops (hop no., IP, hostname, RTTs, `*` for no reply) and stream them to the
UI live over WebSocket. Explain TTL and ICMP Time Exceeded.

**2. Subnet calculator** (pure Java, no library)
- **CIDR/classless addressing is the primary model.**
- Input `IP/prefix` → network address, broadcast, first/last usable host, usable host count,
  subnet mask, wildcard mask, private/public/special-purpose, binary view.
  Handle the /31 and /32 edge cases.
- Show the legacy IP class only as an educational note, labelled "historical".
- VLSM: split a network by a list of required host counts (largest first) and show the
  allocation table.
- Thorough JUnit tests with CCNA-style examples.

**3. LAN discovery**
- Ping-sweep a subnet with bounded concurrency, a maximum size per scan (/22) and a
  maximum scan duration.
- Read MACs from the ARP/neighbor table (`arp -a` on Windows, `ip neigh` on Linux) and
  do reverse DNS.
- Look up the MAC vendor (OUI) from a bundled offline OUI file.
- Add an "Add to monitoring" action per discovered host.
- Show this UI warning: "Scan only networks you own or are authorized to scan."
- Only ADMIN/OPERATOR can run scans. Every scan is audit-logged.

**4. SNMP polling** (v2c first, then v3 authPriv)
- Poll sysName, sysDescr, sysUpTime, sysLocation and the interface table: ifDescr,
  ifAdminStatus, ifOperStatus, ifSpeed/ifHighSpeed, ifIn/OutOctets,
  ifHCIn/OutOctets, ifIn/OutErrors.
- Calculate bandwidth utilization from counter deltas. Prefer 64-bit HC counters and handle
  32-bit counter wrap and device reboots (sysUpTime reset). Explain this in comments.
- SNMP credentials are encrypted at rest (AES-GCM, key from an env var) and never returned
  by any API.

### Phase 3 — Alerting, users and production hardening

**1. Alert rules**
Rule types: device down, RTT > threshold, packet loss > %, port closed, SNMP interface
down, interface utilization > %. Each rule has a severity (critical/warning/info) and a
duration ("for X minutes"). Lifecycle: `TRIGGERED → ACKNOWLEDGED → RESOLVED`.
Notify only on state change. Maintenance windows mute a device for a time range.

**2. Notifications**
In-app alert center, email (SMTP via env vars), and an optional webhook (Slack/Discord/
Telegram-compatible JSON). Retry failed sends with backoff.

**3. Users and RBAC**
Roles: `ADMIN` (everything), `OPERATOR` (manage devices, run scans, ack alerts),
`VIEWER` (read-only). Enforce with `@PreAuthorize`.

**4. Audit log**
Records who changed what and when: device edits, rule changes, user changes, scans.

**5. Event log**
Every status change with a timestamp; filterable, exportable to CSV.

**6. Reports**
Per-device uptime/SLA report for a date range, exportable to CSV. Uptime is calculated from
the monitoring intervals within valid observation windows; paused or maintenance time is
excluded.

---

## 6. UI/UX — must look like a professional, industry-grade product

It should look like a real NOC (Network Operations Center) product, never like a
college project or a default template.

### Design system
- shadcn/ui on Tailwind + lucide-react only. Don't mix component libraries.
- Dark theme by default with a light theme toggle; persist the choice.
- One token set (CSS variables) for colors, spacing, radius, shadows and typography.
- Fonts: Inter for UI; JetBrains Mono for IPs, MACs, OIDs and CLI output.
- Status colors used identically everywhere: UP green, DEGRADED amber, DOWN red,
  UNKNOWN/PAUSED gray. Always pair color with an icon + text label.

### Layout
- App shell: collapsible sidebar (Dashboard, Devices, Discovery, Traceroute,
  Tools → Subnet Calculator, Alerts, Reports, Event Log, Settings, Users), a top bar with
  global search (Ctrl+K command palette for devices/pages), a "Live" WebSocket connection
  indicator, an alert bell with count, and a user menu.
- Breadcrumbs. A consistent page header: title, description, and the primary action on the
  right.

### Pages
- **Dashboard:** KPI cards (total devices, up, down, active alerts, avg latency,
  network uptime %), latency trend chart with a 1h/6h/24h/7d selector, top 5 slowest
  devices, recent alerts, recent status changes. Live updates with no page reloads.
- **Devices:** server-side sortable/filterable (status/type/tag)/searchable/paginated table,
  bulk actions, row actions, confirm dialogs for deletes.
- **Device detail** tabs: Overview, Latency & Loss, Ports, DNS, SNMP Interfaces, Alerts,
  History. A 24h uptime bar (status-page style segments). The SNMP interface table shows
  utilization bars + sparklines.
- **Traceroute:** hops stream in live, with a node → node path visual and a table.
- **Subnet calculator:** form + results card grid, binary mask view, VLSM table,
  copy buttons.
- **Discovery:** scan progress bar, results table, "Add to monitoring" action.
- **Alerts:** active/acknowledged/resolved tabs, plus rule management.
- **Settings:** notification channels (with a "send test" button), retention, default
  intervals, thresholds, authorized scan CIDRs.
- **Login:** looks like a real product (logo mark, name, tagline
  "Real-time network monitoring").

### Required polish
- Skeleton loaders, helpful empty states with a CTA, error states with retry, toasts,
  a 404 page, and a global error boundary.
- Inline form validation (IP, CIDR, port range, hostname); disable submit while saving.
- Responsive for laptop and tablet. Keyboard accessible, visible focus rings, WCAG AA contrast.
- Relative timestamps ("2 min ago") with exact local time in a tooltip.
- No lorem ipsum. A `demo` Spring profile seeds realistic, clearly labelled demo data
  (core router, distribution/access switches, firewall, DNS servers, public websites,
  generated history) for screenshots. Never mix demo data into real monitoring.

---

## 7. API standards

- REST under `/api/v1`, plural nouns, correct HTTP status codes.
- One error format (RFC 7807 Problem Details) via `@RestControllerAdvice`.
- Bean Validation on all inputs. Use DTOs only; never expose entities.
- Server-side pagination, sorting and filtering.
- OpenAPI via springdoc at `/swagger-ui` (enabled in dev, protected in prod).

---

## 8. Security

### Authentication
- JWT access tokens (short-lived) + refresh tokens in cookies that are `HttpOnly`, `Secure`
  (in prod), with an appropriate `SameSite` setting.
- **CSRF protection** for the cookie-based endpoints.
- Refresh token rotation; refresh tokens revoked on logout (stored hashed in the DB).
- BCrypt passwords. The first admin is created from env vars on first startup.
- Rate limiting and temporary lockout after repeated failed logins.
- **WebSocket connections are authenticated**, and topic subscriptions are authorized by role.
- Never expose password hashes, refresh tokens or SNMP credentials through any API.

### Network security
1. Discovery, subnet sweeps and multi-port scans are restricted to configurable
   **authorized CIDRs** (default: localhost + RFC1918 private ranges). Public ranges are
   blocked for scans by default.
2. Single-target monitoring (ping/TCP/DNS/SNMP on a device added by ADMIN/OPERATOR)
   **may target public hosts**, such as websites and public DNS servers. This is a core
   feature and must not be blocked by the scan allowlist.
3. Validate hostnames, IPv4, IPv6, CIDR and port ranges. Reject multicast, broadcast,
   unspecified (`0.0.0.0`, `::`) and other special-purpose addresses where they make no sense.
4. Resolve hostnames and validate the resolved addresses before connecting (prevents DNS
   rebinding into blocked ranges).
5. Command injection safety: `ProcessBuilder` with individual validated arguments only;
   never build shell commands from user input.
6. Configurable concurrency, duration and timeout limits for all network operations.
7. Audit-log every discovery and scan operation (who, what range, when).

### Platform security
- CORS restricted to the frontend origin. Security headers (CSP, X-Frame-Options, HSTS over
  HTTPS) via Nginx/Spring.
- Rate limiting on heavy endpoints (discovery, traceroute).
- Dependency vulnerability scanning in CI (OWASP dependency-check or Trivy).

---

## 9. Observability and reliability

- Structured JSON logs in prod with request correlation IDs.
- Actuator endpoints: health (with a DB check), info and metrics. A Micrometer Prometheus
  endpoint exposing checks run, check failures, check duration, active alerts and the
  current concurrent-check count.
- Graceful shutdown. Scheduler failures are logged and never kill the engine.
- Docker healthchecks for every container; restart policies in compose.

---

## 10. Testing

- **Backend unit tests (JUnit 5 + Mockito):** subnet calculator and VLSM; ping, traceroute
  and ARP parsers against **sample Windows and Linux outputs** (success, partial loss,
  timeout, unreachable); jitter/loss math; counter-wrap math; status flap logic;
  alert state machine; TCP result classification; target validation and allowlist rules.
- **Integration tests:** Testcontainers (PostgreSQL); security tests for role access, CSRF and
  WebSocket auth.
- **Frontend:** Vitest + React Testing Library for key components.
- **E2E:** Playwright for login, add device, see status, and acknowledge an alert.
- Target ≥ 70% coverage on service-layer code.

---

## 11. DevOps and deployment

- Multi-stage Dockerfiles: small images running as a non-root user. The backend image includes
  `iputils-ping`, `traceroute` and `iproute2`, plus only the minimum capability needed for
  ICMP (`NET_RAW`). Document why.
- `docker-compose.yml` for prod-like local runs (postgres, backend, nginx + frontend);
  `docker-compose.dev.yml` for development with hot reload.
- GitHub Actions: lint + test + build on every push/PR; build Docker images on `main`.
- `docs/DEPLOYMENT.md`: deploying on a Linux VPS with Docker, HTTPS via Let's Encrypt
  (Caddy, or Certbot + Nginx), env setup, Postgres backups (`pg_dump` cron).

---

## 12. Networking lab for testing

Write `docs/LAB_SETUP.md` explaining how to test against real network devices for free:
- A GNS3 / EVE-NG / Cisco Packet Tracer topology (e.g. 1 router, 2 switches, a few hosts),
  connected to my Windows host via a Cloud/loopback adapter.
- Exact Cisco IOS commands: interface IPs, static routes back to my laptop,
  `snmp-server community ... RO`, SNMPv3 user setup, and an ACL limiting SNMP access.
- Linux `snmpd` as an alternative SNMP target.
- Experiments, each noting what I should see in NetPulse:
  `shutdown` / `no shutdown` an interface, add latency/loss with `tc qdisc ... netem`,
  block ICMP but keep TCP open, close a port, break DNS.

---

## 13. Documentation and learning

### Learning docs (one per networking module, in `docs/learning/`)
Each document covers:
1. The networking concept.
2. The relevant OSI and TCP/IP layers.
3. The protocol involved.
4. The packet or connection flow (with a diagram).
5. The Java implementation explanation.
6. Commands to verify the behavior independently (CLI + what to filter in Wireshark).
7. Common troubleshooting scenarios.
8. At least five interview questions with answers.

### Project docs
- `README.md`: product overview, screenshots (dashboard, device detail, SNMP interfaces,
  subnet tool, alerts), feature list, architecture diagram (Mermaid), tech stack,
  one-command quick start, demo credentials for the `demo` profile.
- `docs/ARCHITECTURE.md`: modules, the data flow of a check (scheduler → check → DB →
  alert engine → WebSocket/notification), a DB schema diagram, and key design decisions with
  reasons.
- `docs/LAB_SETUP.md`, `docs/DEPLOYMENT.md`, and Swagger for the API.
- `docs/REQUIREMENTS.md`: a copy of this file. Re-read it at the start of every phase.
- Clean git history: small, meaningful commits (Conventional Commits).

---

## 14. Development discipline

1. Do not generate the entire project at once. Complete only the approved phase.
2. After each phase, summarize: what was built, which networking concepts it covers,
   how to test it manually, and what's left. Then wait for my approval.
3. Run tests before declaring anything complete. Never mark mock functionality as implemented.
4. Explain every major architectural decision.
5. Keep dependencies and infrastructure proportional to the feature being built.
   Prefer simple, maintainable code over unnecessary abstractions.
6. Tell me clearly whenever something needs admin/root privileges or OS-specific setup.

### Definition of Done
A feature is complete only when:
- The backend implementation is functional, using real network operations.
- The frontend is connected to the actual backend API.
- Input validation and exception handling work.
- Automated tests pass.
- Manual testing instructions are documented.
- The networking concepts are explained in the learning docs.
- No fake metrics are displayed outside the labelled demo profile.
- The UI matches section 6 (checked screen by screen).
- No unresolved critical security issues remain.
