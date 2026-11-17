# TCP port checks

## 1. The concept
A ping shows the host is reachable. A TCP check shows whether a **specific service** accepts connections. NetPulse performs a complete connection and classifies what happened.

| Result | What was observed | What it proves |
|---|---|---|
| `OPEN` | Handshake completed | Something is listening |
| `CLOSED` | Connection refused (RST) | The host (or a device acting for it) answered; nothing is listening |
| `FILTERED_OR_UNKNOWN` | Timeout or an ambiguous error | Nothing conclusive |

A timeout is **not** proof of a firewall: a powered-off host or a broken path looks the same from here.

## 2. Layers
TCP is **OSI layer 4 / the TCP/IP Transport layer**. Ports identify the application on a host, and a connection is identified by the 4-tuple of source IP, source port, destination IP and destination port.

## 3. The protocol
Connections start with the three-way handshake using these flags: **SYN** (start, with an initial sequence number), **SYN-ACK** (accept), **ACK** (confirm). **RST** aborts or refuses; **FIN** closes gracefully.

## 4. Flow
```mermaid
sequenceDiagram
    participant N as NetPulse
    participant T as Target
    Note over N,T: OPEN
    N->>T: SYN
    T->>N: SYN-ACK
    N->>T: ACK (connect returns; latency measured)
    N->>T: FIN (socket closed)
    Note over N,T: CLOSED
    N->>T: SYN
    T->>N: RST
    Note over N,T: FILTERED_OR_UNKNOWN
    N--xT: SYN (dropped, no response before the timeout)
```

## 5. The Java implementation
`TcpChecker` calls `Socket.connect(address, timeoutMs)`. Returning normally means the handshake finished, and the elapsed time is roughly one round trip.

`TcpChecker.classify` treats only a `ConnectException` whose message says "refused" as `CLOSED`. `ConnectException` can also mean a kernel-level timeout, which is why the type alone isn't enough.

The check connects to the address resolved and validated at the start of the cycle, never to the hostname again. That closes the DNS-rebinding window.

Connect scans complete the handshake and need no privileges. SYN ("half-open") scans need raw sockets, and NetPulse doesn't use them.

## 6. Verify it yourself
```bash
nc -vz -w 3 example.com 443
sudo tcpdump -ni any 'tcp port 443 and host example.com'
```
Wireshark filters: `tcp.flags.syn == 1 && tcp.flags.ack == 0` (connection attempts), `tcp.flags.reset == 1` (refusals), `tcp.port == 443`. An attempt with only retransmitted SYNs and no answer looks like `FILTERED_OR_UNKNOWN`.

## 7. Troubleshooting
| Symptom | Likely cause |
|---|---|
| `CLOSED` on a service that should run | Service is stopped, or bound only to `127.0.0.1` |
| Open locally, `FILTERED_OR_UNKNOWN` remotely | Host firewall or security group |
| Connect time much higher than ping RTT | Busy server: full accept queue or SYN backlog |
| Port flaps | Service restarts, or a connection limit is being hit |

## 8. Interview questions
1. **Walk through the three-way handshake.** Client sends SYN, server replies SYN-ACK, client sends ACK. Each side has now acknowledged the other's initial sequence number.
2. **Closed versus filtered?** Closed gets an active refusal (RST). Filtered usually means silence or an ICMP error, because something dropped the packet.
3. **Can a firewall send the RST?** Yes, some reject with a spoofed RST. So `CLOSED` proves something answered, strictly not that the target itself did.
4. **Connect scan versus SYN scan?** A connect scan finishes the handshake using normal sockets. A SYN scan sends only the SYN, needs raw sockets, and tears down before the connection is established.
5. **Why does a server listening on 127.0.0.1 refuse remote clients?** It's bound only to the loopback interface, so nothing listens on the external address.
6. **What's the 4-tuple?** Source IP, source port, destination IP, destination port. It's how one server port handles many clients at once.
