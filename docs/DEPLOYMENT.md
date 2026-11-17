# Deployment

How to run NetPulse on a Linux VPS with Docker and HTTPS. These steps assume Ubuntu 24.04 and a domain pointing at the server.

## 1. Prepare the server

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo ufw allow 22,80,443/tcp
sudo ufw enable
git clone <your-repository> netpulse && cd netpulse
```

## 2. Configure

```bash
cp .env.example .env
chmod 600 .env
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 32   # CREDENTIAL_ENCRYPTION_KEY
```

| Variable | Set it to |
|---|---|
| `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD` | Long random values. The admin password needs 12 or more characters |
| `CREDENTIAL_ENCRYPTION_KEY` | The 32-byte value above. Without it, SNMP credentials and webhooks can't be saved |
| `COOKIE_SECURE` | `true` |
| `ALLOWED_ORIGINS` | `https://netpulse.example.com` |
| `ALLOW_LOCAL_TARGETS` | `false` |
| `HTTP_PORT` | `8088`, which only Caddy will reach |
| `SMTP_*`, `MAIL_FROM` | Your mail provider, for email alerts |

**Back up `CREDENTIAL_ENCRYPTION_KEY` separately from the database.** If it is lost, saved credentials have to be entered again.

## 3. Start

```bash
docker compose up -d --build
docker compose ps        # every service should become healthy
```

The backend container runs as a non-root user with every Linux capability dropped except `NET_RAW`, the one ping and traceroute need for raw ICMP sockets.

## 4. HTTPS with Caddy

Caddy obtains and renews Let's Encrypt certificates automatically.

```bash
sudo apt install -y caddy
sudo tee /etc/caddy/Caddyfile >/dev/null <<'CONF'
netpulse.example.com {
    reverse_proxy localhost:8088
    header Strict-Transport-Security "max-age=31536000; includeSubDomains"
}
CONF
sudo systemctl reload caddy
```

Keep port 8088 closed to the internet; the `ufw` rules above do that for host traffic, but Docker's published ports can bypass `ufw`. Bind it to localhost by changing the `web` service's port to `"127.0.0.1:${HTTP_PORT:-8088}:8080"`.

## 5. Backups

```bash
sudo mkdir -p /var/backups/netpulse
sudo tee /etc/cron.d/netpulse-backup >/dev/null <<'CONF'
15 2 * * * root cd /path/to/netpulse && docker compose exec -T postgres pg_dump -U netpulse -Fc netpulse > /var/backups/netpulse/netpulse-$(date +\%F).dump && find /var/backups/netpulse -name '*.dump' -mtime +14 -delete
CONF
```

Restore, and test this before you need it:

```bash
docker compose exec -T postgres pg_restore -U netpulse -d netpulse --clean --if-exists < netpulse-2026-01-15.dump
```

## 6. Updating

```bash
git pull
docker compose up -d --build
```

Flyway applies database migrations at startup. Take a backup first.

## 7. Operations

| Need | Where |
|---|---|
| Health | `/actuator/health`, also used by Docker healthchecks |
| Metrics | `/actuator/prometheus` on the internal Docker network only. Nginx doesn't proxy it |
| Logs | `docker compose logs -f backend`: JSON with a correlation id per request |
| Who did what | Event Log → Audit log |

## 8. Limits

- Run a single backend instance. Rate limits, the STOMP broker and the settings cache are in memory.
- Discovery from a VPS can only see that VPS's own network. To scan a LAN, run NetPulse inside it, using `network_mode: host` on Linux.
