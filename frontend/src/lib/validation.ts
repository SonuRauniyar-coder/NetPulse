import { z } from "zod";

const OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
const IPV4 = new RegExp(`^${OCTET}(\\.${OCTET}){3}$`);
const LABEL = /^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/;

export const isIpv4 = (s: string) => IPV4.test(s);

export function isIpv6(s: string): boolean {
  if (!s.includes(":") || !/^[0-9A-Fa-f:.]+$/.test(s)) return false;
  const dbl = s.split("::").length - 1;
  if (dbl > 1 || s.includes(":::")) return false;
  if ((s.startsWith(":") && !s.startsWith("::")) || (s.endsWith(":") && !s.endsWith("::"))) return false;
  let groups = 0;
  const parts = s.split(":").filter(Boolean);
  for (const [i, p] of parts.entries()) {
    if (i === parts.length - 1 && p.includes(".")) {
      if (!isIpv4(p)) return false;
      groups += 2;
    } else if (/^[0-9A-Fa-f]{1,4}$/.test(p)) {
      groups += 1;
    } else return false;
  }
  return dbl === 1 ? groups <= 7 : groups === 8;
}

export function isHostname(s: string): boolean {
  const h = s.endsWith(".") ? s.slice(0, -1) : s;
  if (!h || h.length > 253) return false;
  const labels = h.split(".");
  return labels.every((l) => LABEL.test(l)) && !/^\d+$/.test(labels[labels.length - 1]);
}

/** Instant feedback only. The server re-validates everything and has the final say. */
export const isValidHost = (s: string) => isIpv4(s) || isIpv6(s) || isHostname(s);

export const deviceSchema = z.object({
  name: z.string().trim().min(1, "Name is required").max(100),
  host: z.string().trim().min(1, "IP or hostname is required").refine(isValidHost, "Enter a valid IPv4, IPv6 or hostname"),
  type: z.enum(["ROUTER", "SWITCH", "FIREWALL", "SERVER", "WEBSITE"]),
  location: z.string().max(100).optional(),
  tags: z.string().refine(
    (v) => v.split(",").map((t) => t.trim()).filter(Boolean).every((t) => /^[A-Za-z0-9._-]{1,32}$/.test(t)),
    "Tags: letters, digits, . _ - separated by commas",
  ),
  checkIntervalSeconds: z.coerce.number({ invalid_type_error: "Enter a number" }).int().min(10, "Minimum is 10 seconds").max(3600, "Maximum is 3600 seconds"),
  enabled: z.boolean(),
  ports: z.array(z.object({
    port: z.coerce.number({ invalid_type_error: "Enter a port" }).int().min(1, "1-65535").max(65535, "1-65535"),
    label: z.string().max(32).optional(),
  })).superRefine((ports, ctx) => {
    const seen = new Set<number>();
    ports.forEach((p, i) => {
      if (seen.has(p.port)) ctx.addIssue({ code: "custom", message: "Duplicate port", path: [i, "port"] });
      seen.add(p.port);
    });
  }),
  dnsChecks: z.array(z.object({
    recordType: z.enum(["A", "AAAA", "MX", "NS", "TXT", "PTR"]),
    queryName: z.string().trim().optional(),
    dnsServer: z.string().trim().optional().refine((v) => !v || isValidHost(v), "Invalid DNS server"),
  }).superRefine((c, ctx) => {
    if (c.recordType !== "PTR" && !c.queryName) ctx.addIssue({ code: "custom", message: "Domain required", path: ["queryName"] });
    if (c.recordType !== "PTR" && c.queryName && !isHostname(c.queryName)) ctx.addIssue({ code: "custom", message: "Invalid domain", path: ["queryName"] });
    if (c.recordType === "PTR" && c.queryName && !isIpv4(c.queryName) && !isIpv6(c.queryName)) ctx.addIssue({ code: "custom", message: "Must be an IP, or empty", path: ["queryName"] });
  })),
});

export type DeviceFormInput = z.input<typeof deviceSchema>;
export type DeviceFormValues = z.output<typeof deviceSchema>;
