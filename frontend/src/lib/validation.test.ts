import { deviceSchema, isHostname, isIpv4, isIpv6, isValidHost } from "./validation";

const base = { name: "r1", host: "10.0.0.1", type: "ROUTER", tags: "", checkIntervalSeconds: 60, enabled: true, ports: [], dnsChecks: [] };

describe("target validation", () => {
  it.each(["8.8.8.8", "192.168.1.1", "255.255.255.255"])("accepts IPv4 %s", (v) => expect(isIpv4(v)).toBe(true));
  it.each(["999.1.1.1", "1.2.3", "010.1.1.1"])("rejects IPv4 %s", (v) => expect(isIpv4(v)).toBe(false));
  it.each(["::1", "2001:4860:4860::8888", "1:2:3:4:5:6:7:8", "::ffff:192.0.2.1"])("accepts IPv6 %s", (v) => expect(isIpv6(v)).toBe(true));
  it.each(["1::2::3", "1:2:3", "gggg::1", ":1:2"])("rejects IPv6 %s", (v) => expect(isIpv6(v)).toBe(false));
  it.each(["example.com", "router1"])("accepts hostname %s", (v) => expect(isHostname(v)).toBe(true));
  it.each(["-bad.com", "a..b", "1.2.3.999"])("rejects hostname %s", (v) => expect(isHostname(v)).toBe(false));
  it.each(["x; rm -rf /", "$(id)", "a|b"])("rejects injection %s", (v) => expect(isValidHost(v)).toBe(false));
});

describe("deviceSchema", () => {
  it("accepts a minimal device", () => expect(deviceSchema.safeParse(base).success).toBe(true));
  it("rejects a port out of range", () => expect(deviceSchema.safeParse({ ...base, ports: [{ port: 70000 }] }).success).toBe(false));
  it("rejects duplicate ports", () => expect(deviceSchema.safeParse({ ...base, ports: [{ port: 80 }, { port: 80 }] }).success).toBe(false));
  it("rejects too-short interval", () => expect(deviceSchema.safeParse({ ...base, checkIntervalSeconds: 5 }).success).toBe(false));
  it("needs a domain for non-PTR lookups", () => expect(deviceSchema.safeParse({ ...base, dnsChecks: [{ recordType: "A" }] }).success).toBe(false));
  it("explains a blank interval", () => {
    const r = deviceSchema.safeParse({ ...base, checkIntervalSeconds: "" });
    expect(r.error?.issues[0].message).toBe("Enter an interval in seconds");
  });
  it("limits the number of ports", () => expect(deviceSchema.safeParse({ ...base, ports: Array.from({ length: 33 }, (_, i) => ({ port: i + 1 })) }).success).toBe(false));
  it("allows PTR without a name", () => expect(deviceSchema.safeParse({ ...base, dnsChecks: [{ recordType: "PTR" }] }).success).toBe(true));
});
