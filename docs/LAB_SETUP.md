# Lab setup

Practice against real network behaviour for free. Only test equipment you own.

## Quickest option: Linux snmpd in Docker

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

In NetPulse, add device `127.0.0.1` (the dev profile allows local targets). Then open **SNMP Interfaces → Configure SNMP**, set UDP port `11161`, and use either community `public`, or v3 user `labuser` with SHA and the passphrases above.

## GNS3 or EVE-NG topology

```
 Your computer ---- Cloud ---- R1 ---- SW1 ---- PC1
 192.168.56.1                   |
                                +----- SW2 ---- PC2
```

Connect the Cloud node to a host-only adapter (VirtualBox on macOS and Windows, or a loopback adapter on Windows). Cisco Packet Tracer can practice the commands, but it can't exchange traffic with NetPulse on your real computer.

**Run the backend natively for this lab.** Under Docker Desktop, containers sit behind a VM's NAT and can't see the lab directly.

### Router configuration (Cisco IOS)

```
enable
configure terminal
hostname R1

interface GigabitEthernet0/0
 ip address 192.168.56.2 255.255.255.0
 no shutdown

interface GigabitEthernet0/1
 ip address 10.10.10.1 255.255.255.0
 no shutdown

ip route 0.0.0.0 0.0.0.0 192.168.56.1

! SNMP is limited to your computer by this ACL
ip access-list standard SNMP-MGMT
 permit host 192.168.56.1

snmp-server community lab-readonly RO SNMP-MGMT
snmp-server location Lab-Rack-1

! SNMPv3 authPriv
snmp-server group NP-GROUP v3 priv access SNMP-MGMT
snmp-server user npuser NP-GROUP v3 auth sha AuthPass123 priv aes 128 PrivPass123
end
write memory
```

So your computer can reach the far subnet, add a route **back** to it:

```bash
sudo ip route add 10.10.10.0/24 via 192.168.56.2       # Linux
sudo route -n add 10.10.10.0/24 192.168.56.2           # macOS
route add 10.10.10.0 mask 255.255.255.0 192.168.56.2   # Windows, as Administrator
```

Verify before opening NetPulse: `snmpwalk -v2c -c lab-readonly 192.168.56.2 ifDescr`.

## Experiments

Create these rules first: Device down, RTT above 100 ms, Port closed, Interface down.

| Do this | Expect in NetPulse |
|---|---|
| On R1: `interface g0/1`, then `shutdown` | SNMP tab shows **Admin down**. **No alert**, because the shutdown was deliberate |
| `no shutdown`, then shut the **neighbor's** port on SW1 | R1's interface is admin up, oper down. **Interface down** alert, resolved when restored |
| On a Linux target: `sudo tc qdisc add dev eth0 root netem delay 200ms` | RTT rises, device turns **Degraded**, RTT alert fires |
| `sudo tc qdisc change dev eth0 root netem loss 30%` | Loss and jitter appear. **Degraded** |
| `sudo tc qdisc del dev eth0 root` | Back to **Up**, alerts resolve |
| Block ICMP: `sudo iptables -A INPUT -p icmp -j DROP` while a TCP check is open | Stays reachable with "ICMP blocked". **Not Down** |
| Stop the service on a monitored port | Port shows **Closed (refused)**, Port closed alert |
| Drop instead: `sudo iptables -A INPUT -p tcp --dport 443 -j DROP` | Port shows **Filtered or unknown** |
| Point a DNS check at a server that isn't running, or query a domain that doesn't exist | **TIMEOUT** or **NXDOMAIN**, shown separately |
| Reboot R1 | Device goes Down and back. Rates show "—" for one poll, with no false spike |
| Schedule maintenance, then shut the device off | No alert, and the time is excluded from Reports |

Undo with `sudo iptables -D INPUT ...`, using the same rule.
