# Ping output fixtures

| Files | Provenance |
|---|---|
| `macos/success.txt`, `macos/timeout.txt`, `macos/unknown-host.txt`, `macos/ping6-success.txt` | Captured from a real macOS machine |
| `linux/success.txt`, `linux/timeout.txt`, `linux/unknown-host.txt` | Captured from iputils-ping in a Debian container |
| `*/partial-loss.txt`, `linux/unreachable.txt`, `macos/unreachable.txt`, `linux/permission-denied.txt` | Written by hand from the tool's documented format |
| `windows/*` | Written by hand from the documented format. **Not captured on a live Windows machine.** Replace with real captures if you run on Windows. |
