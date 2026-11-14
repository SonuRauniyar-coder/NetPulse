# NetPulse: Run aur Test Manual

Is manual se aap NetPulse ko teen tareeke se chala sakte ho aur uski har feature test kar sakte ho.

> **Status:** Ye manual repo ki files se likha gaya hai. Iski commands aur expected results abhi end-to-end chala kar verify nahi kiye gaye hain. Jin results par **(andaza)** likha hai, wo README ki feature list se nikale gaye hain. Result alag aaye to wo bug bhi ho sakta hai, ya manual ki galti bhi.

## Contents

1. [Kaunsa mode chalayein](#1-kaunsa-mode-chalayein)
2. [Prerequisites](#2-prerequisites)
3. [Mode 1: Demo](#3-mode-1-demo)
4. [Mode 2: Real Docker stack](#4-mode-2-real-docker-stack)
5. [Mode 3: Development](#5-mode-3-development)
6. [Pehli baar use](#6-pehli-baar-use)
7. [SNMP lab](#7-snmp-lab)
8. [Automated tests](#8-automated-tests)
9. [Manual feature checklist](#9-manual-feature-checklist)
10. [Failure experiments](#10-failure-experiments)
11. [Kya test karna kitna aasan hai](#11-kya-test-karna-kitna-aasan-hai)
12. [Band karna aur reset](#12-band-karna-aur-reset)
13. [Troubleshooting](#13-troubleshooting)
14. [Aage padhne ke liye](#14-aage-padhne-ke-liye)

## 1. Kaunsa mode chalayein

Seekhne ke liye **Mode 3 (Development)** sabse sahi hai, kyunki sirf isme backend Mac par seedha chalta hai aur LAN dekh sakta hai.

| Mode | Kab use karein | Data | URL | Login |
|---|---|---|---|---|
| 1. Demo | Sirf UI dekhna ya kisi ko dikhana | Synthetic (nakli), monitoring engine off | <http://localhost:8089> | `demo` / `NetPulse-Demo-2026!` |
| 2. Real Docker | Production jaisa pura stack | Real, par Docker VM ke NAT ke peeche se | <http://localhost:8088> | `.env` me jo set kiya |
| 3. Development | Seekhna, code badalna, LAN discovery, SNMP lab | Real, Mac se seedha | <http://localhost:5173> | `admin` / `NetPulse-Dev-2026!` |

Teeno ka database alag hai, isliye ek mode ka data doosre me nahi dikhega.

## 2. Prerequisites

Aapko Docker Desktop, JDK 21 se 25, aur Node chahiye.

```bash
docker compose version
/usr/libexec/java_home -v 25
node --version
```

Mode 3 ko ports 8080 aur 5173 khaali chahiye. Pehle check karo kaun inhe use kar raha hai:

```bash
lsof -nP -iTCP:8080 -iTCP:5173 -sTCP:LISTEN
```

9 November 2026 ko is Mac par ye ports doosre projects ke paas the:

| Port | Kaun use kar raha tha |
|---|---|
| 8080 | `shop-backend-1` (Docker) aur `clinic-webapp/medibook` ka Java backend |
| 5173 | `clinic-webapp/medibook` ka Vite frontend |

Modes 1 aur 2 par iska asar nahi padta, kyunki wo 8089 aur 8088 use karte hain. Mode 3 ke liye un projects ko band karo, ya [alag ports](#agar-8080-ya-5173-busy-hain) use karo.

## 3. Mode 1: Demo

Demo ko koi configuration nahi chahiye.

```bash
cd ~/Desktop/network-webapp
docker compose -f docker-compose.demo.yml up --build
```

Pehli baar build me kuch minute lagte hain. Phir <http://localhost:8089> kholo aur `demo` / `NetPulse-Demo-2026!` se login karo.

Demo me monitoring engine band rehta hai, isliye yahan ke numbers asli network ke nahi hain.

## 4. Mode 2: Real Docker stack

Is mode me Postgres, backend aur Nginx web teeno Docker me chalte hain, aur sirf port 8088 bahar khulta hai.

**Step 1.** Env file banao. `.env` ko kabhi commit mat karna.

```bash
cd ~/Desktop/network-webapp
cp .env.example .env
chmod 600 .env
```

**Step 2.** Do secrets generate karke `.env` me paste karo.

```bash
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 32   # CREDENTIAL_ENCRYPTION_KEY
```

**Step 3.** `.env` ki values is table ke hisaab se bharo.

| Variable | Localhost par kya rakhein | Note |
|---|---|---|
| `DB_PASSWORD` | Koi lamba password | Sirf pehli baar volume bante waqt lagta hai |
| `JWT_SECRET` | `openssl` wali 48-byte value | Kam se kam 32 bytes |
| `ADMIN_USERNAME` | `admin` | Pehla admin user |
| `ADMIN_PASSWORD` | 12+ characters | Sirf tab use hota hai jab user table khaali ho |
| `COOKIE_SECURE` | `false` | Plain http par `true` rakhne se login session nahi tikega |
| `ALLOW_LOCAL_TARGETS` | `false` | E2E test ke liye `true` |
| `ALLOWED_ORIGINS` | `http://localhost:8088` | Browser wala exact origin |
| `HTTP_PORT` | `8088` | Badla to `ALLOWED_ORIGINS` bhi badlo |
| `CREDENTIAL_ENCRYPTION_KEY` | `openssl` wali 32-byte value | Khaali ho to SNMP credentials aur webhooks save nahi honge. Iska backup rakho |
| `SCAN_AUTHORIZED_CIDRS` | Default | Sirf apne ya authorized networks |
| `SMTP_HOST` | Khaali | Khaali rakhne se email band rehta hai |
| `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `MAIL_FROM` | Default | Sirf email chalu karne par |

**Step 4.** Stack chalao aur health check karo.

```bash
docker compose up --build -d
docker compose ps
```

**Step 5.** Sab services healthy hone par <http://localhost:8088> kholo aur `ADMIN_USERNAME` / `ADMIN_PASSWORD` se login karo.

Mac par Docker ek VM me chalta hai. Isliye latency me VM ka NAT bhi judta hai, aur Discovery aapka asli LAN nahi dekh payega.

VPS par HTTPS ke saath deploy karne ke liye [DEPLOYMENT.md](DEPLOYMENT.md) dekho.

## 5. Mode 3: Development

Is mode me sirf Postgres Docker me chalta hai; backend aur frontend Mac par native chalte hain. Teen alag terminals kholo.

**Terminal 1: database**

```bash
cd ~/Desktop/network-webapp
docker compose -f docker-compose.dev.yml up -d
```

**Terminal 2: backend.** `JAVA_HOME` zaroori hai, kyunki Homebrew Maven naya JDK utha leta hai aur JaCoCo toot jata hai.

```bash
cd ~/Desktop/network-webapp/backend
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./mvnw spring-boot:run
```

**Terminal 3: frontend**

```bash
cd ~/Desktop/network-webapp/frontend
npm install
npm run dev
```

Ab <http://localhost:5173> kholo aur `admin` / `NetPulse-Dev-2026!` se login karo.

Dev profile me local targets allowed hain aur encryption key pehle se set hai, isliye SNMP credentials bina extra setup ke save ho jate hain.

### Agar 8080 ya 5173 busy hain

Doosre projects band nahi karne, to alag ports use karo.

Terminal 2:

```bash
cd ~/Desktop/network-webapp/backend
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
SERVER_PORT=8081 ALLOWED_ORIGINS=http://localhost:5174 ./mvnw spring-boot:run
```

Terminal 3:

```bash
cd ~/Desktop/network-webapp/frontend
BACKEND_URL=http://localhost:8081 npm run dev -- --port 5174 --strictPort
```

Phir <http://localhost:5174> kholo. `ALLOWED_ORIGINS` ko browser wale exact origin se match karna zaroori hai.

## 6. Pehli baar use

Mode 3 chalu karke ye chhota round lagao. Default check interval 60 seconds hai, isliye status badalne me thoda waqt lagta hai.

1. Login karo aur Dashboard dekho.
2. Device add karo: `8.8.8.8`, TCP port `443`. Ye bina page reload ke **Up** hona chahiye.
3. Terminal me `ping -c 4 8.8.8.8` chalao aur RTT ko app ke number se compare karo.
4. **Traceroute** me `8.8.8.8` trace karo.
5. **Alerts → Rules** me ek rule banao, phir bell icon dekho.

## 7. SNMP lab

SNMP test karne ka sabse tez tareeka Docker me ek chhota `snmpd` agent hai. Ye Mode 3 ke saath chalta hai.

```bash
docker run -d --name lab-snmpd -p 11161:11161/udp alpine:3.20 sh -c '
  apk add --no-cache net-snmp net-snmp-tools >/dev/null
  mkdir -p /var/lib/net-snmp
  cat > /etc/snmp/snmpd.conf <<CONF
agentaddress udp:0.0.0.0:11161
rocommunity public
createUser labuser SHA authpass123 AES privpass123
rouser labuser priv
sysName lab-router
sysLocation my-desk
CONF
  exec snmpd -f -Lo -C -c /etc/snmp/snmpd.conf'
```

NetPulse me setup:

1. Device `127.0.0.1` add karo.
2. **SNMP Interfaces → Configure SNMP** kholo aur UDP port `11161` set karo.
3. v2c ke liye community `public` do.
4. v3 ke liye user `labuser`, SHA, AES, aur passphrases `authpass123` / `privpass123` do.

Agent ko app se alag verify karne ke liye:

```bash
docker exec lab-snmpd snmpwalk -v2c -c public 127.0.0.1:11161 sysName
docker exec lab-snmpd snmpwalk -v3 -l authPriv -u labuser -a SHA -A authpass123 -x AES -X privpass123 127.0.0.1:11161 sysName
```

Dono commands ko `lab-router` dikhana chahiye.

## 8. Automated tests

Pehle ye chalao, kyunki ye bahut kuch apne aap check kar lete hain.

```bash
# Backend unit + Testcontainers integration tests (Docker on hona chahiye)
cd ~/Desktop/network-webapp/backend
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./mvnw verify

# Frontend component aur validation tests
cd ../frontend
npm test

# TypeScript check
npm run lint
```

### E2E tests

E2E tests browser me do flows check karte hain:

- Sign in karke device add karna aur real status dekhna
- Failed port check se alert fire karna aur use acknowledge karna

Inke liye Mode 2 stack `ALLOW_LOCAL_TARGETS=true` ke saath port 8088 par chalna chahiye.

```bash
cd ~/Desktop/network-webapp/frontend
npx playwright install chromium
E2E_PASS='<aapka ADMIN_PASSWORD>' npm run e2e
```

Alag URL ya username ke liye `E2E_BASE_URL` aur `E2E_USER` set karo.

## 9. Manual feature checklist

Mode 3 me `admin` se login karke upar se neeche chalo, aur har result ko terminal se cross-check karo.

### Monitoring

| Done | Feature | Kya karo | Expected result | Verify |
|---|---|---|---|---|
| [ ] | Device validation | `999.1.1.1` add karo, phir IPv6 `2001:4860:4860::8888` aur hostname `example.com` | Galat IP reject, baaki accept | UI |
| [ ] | Inventory | Tags lagao, search, filter, sort, paging, bulk action | List sahi badle | UI |
| [ ] | ICMP | `8.8.8.8` add karo | RTT min/avg/max, loss, jitter dikhe | `ping -c 4 8.8.8.8` |
| [ ] | TCP open | Port `443` | `OPEN`, device **Up** | `nc -vz -G 3 8.8.8.8 443` |
| [ ] | TCP closed ya filtered | Port `81` | **Degraded**, Ports tab me reason | `nc -vz -G 3 8.8.8.8 81` |
| [ ] | Down logic | `192.0.2.1` add karo | **Unknown**, 3 failed cycles ke baad **Down** (default interval par lagbhag 3 minute) | `ping -c 4 192.0.2.1` |
| [ ] | DNS records | A, AAAA, MX, NS, TXT, PTR checks | Sahi records | `dig @8.8.8.8 example.com MX` |
| [ ] | DNS errors | Non-existent domain, aur band server | **NXDOMAIN** aur **TIMEOUT** alag dikhein | `dig` |
| [ ] | Live updates | Do browser tabs khol kar status badalne do | Bina reload update | UI |
| [ ] | Dashboard | Latency trend aur 24h uptime bar dekho | Data devices se match kare | UI |

### Network tools

| Done | Feature | Kya karo | Expected result | Verify |
|---|---|---|---|---|
| [ ] | Traceroute | `8.8.8.8` | Hops live aayein, path view bane | `traceroute -n 8.8.8.8` |
| [ ] | Subnet | `10.1.1.1/31`, `10.1.1.1/32`, `192.168.1.0/24` | /31 me 2 usable addresses, /32 ek host, /24 me 254 hosts | Copy par solve karo |
| [ ] | VLSM | `192.168.1.0/24` ko 100, 50, 10 hosts me baanto | Allocation overlap na ho | Copy par solve karo |
| [ ] | Discovery | Sirf apne ghar ka /24 scan karo, Mode 3 me | Hosts, MAC vendor, reverse DNS; one-click monitor | `arp -an` |
| [ ] | Discovery limit | Authorized list ke bahar ki koi range do, jaise documentation range `203.0.113.0/24` | Scan reject ho **(andaza)** | UI |
| [ ] | SNMP v2c | Lab agent, community `public` | System info aur interfaces | [SNMP lab](#7-snmp-lab) |
| [ ] | SNMP v3 | `labuser`, SHA/AES | Same data; galat passphrase par error | [SNMP lab](#7-snmp-lab) |

Discovery college ya office ke network par bina permission mat chalao.

### Alerting aur notifications

| Done | Feature | Kya karo | Expected result | Verify |
|---|---|---|---|---|
| [ ] | Port closed alert | Rule duration 0, closed port wala device | Bell apne aap update ho | Alerts page |
| [ ] | Alert lifecycle | Acknowledge karo, phir problem theek karo | Triggered, acknowledged, resolved; notification sirf state change par | Event Log |
| [ ] | RTT, loss, device down, interface alerts | Rules banao, [experiments](#10-failure-experiments) chalao | Sahi alert fire aur resolve | Alerts page |
| [ ] | Maintenance window | Window banao, device fail hone do | Alert nahi, time Reports se exclude | Reports |
| [ ] | Webhook | Apne Discord, Slack ya Telegram test channel ka URL do | Message aaye | Channel |
| [ ] | Email | Neeche wale Mailpit setup se | Mail Mailpit me dikhe | <http://localhost:8025> |

Email test karne ke liye backend aise chalao. Mailpit is Mac par `shop` project ke saath chalta hai, isliye wo band ho to pehle chalu karo.

```bash
SMTP_HOST=localhost SMTP_PORT=1025 SMTP_AUTH=false SMTP_STARTTLS=false ./mvnw spring-boot:run
```

Real Slack, Discord, Telegram aur real SMTP delivery abhi tak project me test nahi hui hai, isliye inme bug milna mumkin hai.

### Admin aur security

| Done | Feature | Kya karo | Expected result | Verify |
|---|---|---|---|---|
| [ ] | Reports | Uptime/SLA report, CSV export | CSV numbers UI se match | CSV file |
| [ ] | Event aur audit log | Filter lagao, CSV export | Har action user ke naam se dikhe | **Event Log → Audit log** |
| [ ] | Roles | Operator aur Viewer users banao, Viewer se device add karne ki koshish | Viewer ko write nahi, API par 403 | Browser Network tab |
| [ ] | Login lockout | Ek spare user par 5 baar galat password | 15 minute lock; login rate limit 10 per minute | Admin par mat karna |
| [ ] | Session | Login karke DevTools me cookies dekho, logout karo | Refresh cookie `HttpOnly`, logout ke baad API 401 **(andaza)** | Browser Network tab |
| [ ] | Encrypted secrets | SNMP credential save karo | UI me secret wapas plain text me na dikhe **(andaza)** | UI |
| [ ] | Rollups | App kuch ghante chalne do | Hourly charts bharein | Device history |

## 10. Failure experiments

Ye experiments alerts ko real failures se test karte hain. Pehle ye rules banao: Device down, RTT above 100 ms, Port closed, Interface down.

`tc` aur `iptables` commands Linux target par chalti hain, Mac par nahi. Router wale steps ke liye GNS3 ya EVE-NG chahiye; topology [LAB_SETUP.md](LAB_SETUP.md) me hai. Sirf apne equipment par test karo.

| Done | Kya karo | NetPulse me expected |
|---|---|---|
| [ ] | R1 par `interface g0/1`, phir `shutdown` | SNMP tab me **Admin down**. **Koi alert nahi**, kyunki shutdown jaan-boojh kar tha |
| [ ] | `no shutdown`, phir SW1 par **neighbor** ka port shut karo | R1 admin up, oper down. **Interface down** alert, restore par resolve |
| [ ] | Linux target par `sudo tc qdisc add dev eth0 root netem delay 200ms` | RTT badhe, device **Degraded**, RTT alert fire |
| [ ] | `sudo tc qdisc change dev eth0 root netem loss 30%` | Loss aur jitter dikhein, **Degraded** |
| [ ] | `sudo tc qdisc del dev eth0 root` | Wapas **Up**, alerts resolve |
| [ ] | TCP check open rakhte hue `sudo iptables -A INPUT -p icmp -j DROP` | "ICMP blocked" ke saath reachable. **Down nahi** |
| [ ] | Monitored port ki service band karo | Port **Closed (refused)**, Port closed alert |
| [ ] | `sudo iptables -A INPUT -p tcp --dport 443 -j DROP` | Port **Filtered or unknown** |
| [ ] | DNS check band server par, ya non-existent domain par | **TIMEOUT** ya **NXDOMAIN**, alag-alag |
| [ ] | R1 reboot karo | Device Down hokar wapas aaye. Rates ek poll tak "—", koi false spike nahi |
| [ ] | Maintenance schedule karke device band karo | Alert nahi, time Reports se exclude |

Undo ke liye wahi rule `sudo iptables -D INPUT ...` se hatao.

## 11. Kya test karna kitna aasan hai

Is manual se zyadatar features aasani se test ho jate hain, par sab nahi.

| Level | Kya test hoga | Kya chahiye |
|---|---|---|
| Aasan | Automated tests, inventory, ICMP, TCP, DNS, traceroute, subnet/VLSM, alert rules, reports, CSV export, roles, login lockout | Sirf Mode 3 aur internet |
| Thoda setup | SNMP v2c/v3, email, webhooks, Discovery | 5-10 minute ka setup |
| Mushkil | Interface down, RTT/loss alerts, ICMP block, router reboot | GNS3/EVE-NG, ya ek Linux target machine |
| Waqt lagega | Hourly rollups (kuch ghante), retention (raw 7 din, rollup 90 din) | App ko lamba chalana, ya backend ke automated tests |
| Is Mac par nahi | Windows ke ping/traceroute parsers | Windows machine |

## 12. Band karna aur reset

Normal stop se data bacha rehta hai. `-v` lagane par us mode ka database hamesha ke liye delete ho jata hai.

| Kaam | Command |
|---|---|
| Demo band | `docker compose -f docker-compose.demo.yml down` |
| Real stack band | `docker compose down` |
| Dev database band | `docker compose -f docker-compose.dev.yml down` |
| Native backend ya frontend band | Us terminal me `Ctrl+C` |
| SNMP lab hatana | `docker rm -f lab-snmpd` |
| Demo data delete | `docker compose -f docker-compose.demo.yml down -v` |
| Real stack data delete | `docker compose down -v` |
| Dev data delete | `docker compose -f docker-compose.dev.yml down -v` |

## 13. Troubleshooting

| Problem | Wajah | Fix |
|---|---|---|
| `Port 8080 was already in use` | Doosra project 8080 par hai | Use band karo, ya [alag ports](#agar-8080-ya-5173-busy-hain) |
| Vite 5174 jaise port par khul gaya, login fail | 5173 busy tha, origin match nahi hua | 5173 khaali karo, ya `ALLOWED_ORIGINS` set karo |
| Maven build me JaCoCo ya class-file error | JDK 25 se naya JDK | `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` |
| `Set DB_PASSWORD in .env` | `.env` nahi hai ya value khaali | `cp .env.example .env` karke values bharo |
| Real stack me login turant logout | `COOKIE_SECURE=true` plain http par | `COOKIE_SECURE=false`, phir `docker compose up -d` |
| Backend DB password error | `.env` ka password volume banne ke baad badla | Purana password wapas rakho, ya `docker compose down -v` (data delete hoga) |
| `ADMIN_PASSWORD` badalne ka asar nahi | Sirf khaali user table par use hota hai | App se password badlo, ya data reset karo |
| SNMP credentials save nahi hote | `CREDENTIAL_ENCRYPTION_KEY` khaali | Key set karke backend restart karo |
| Discovery LAN nahi dekh pata | Backend Docker VM ke andar hai | Mode 3 use karo |
| Real stack me `127.0.0.1` reject | `ALLOW_LOCAL_TARGETS=false` | Sirf local testing me `true` karo |

Logs ke liye `docker compose logs -f backend`, aur health ke liye `/actuator/health` dekho.

## 14. Aage padhne ke liye

Protocols is order me padho:

1. [ICMP and ping](learning/icmp-ping.md)
2. [TCP ports](learning/tcp-ports.md)
3. [DNS](learning/dns.md)
4. [Device status](learning/device-status.md)
5. [Traceroute](learning/traceroute.md)
6. [Subnetting](learning/subnetting.md)
7. [ARP and discovery](learning/arp-discovery.md)
8. [SNMP](learning/snmp.md)
9. [Alerting](learning/alerting.md)

Baaki docs: [ARCHITECTURE.md](ARCHITECTURE.md) · [LAB_SETUP.md](LAB_SETUP.md) · [DEPLOYMENT.md](DEPLOYMENT.md) · [REQUIREMENTS.md](REQUIREMENTS.md)
