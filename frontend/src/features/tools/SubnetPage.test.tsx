import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { isCidr } from "@/lib/validation";
import { SubnetPage } from "./SubnetPage";

const ui = () => render(<QueryClientProvider client={new QueryClient()}><TooltipProvider><SubnetPage /></TooltipProvider></QueryClientProvider>);

describe("isCidr", () => {
  it.each(["10.0.0.0/8", "192.168.1.1/32", "0.0.0.0/0"])("accepts %s", (v) => expect(isCidr(v)).toBe(true));
  it.each(["10.0.0.0", "10.0.0.0/33", "10.0.0/8", "10.0.0.0/8/1", "abc/8", "10.0.0.0/"])("rejects %s", (v) => expect(isCidr(v)).toBe(false));
});

describe("SubnetPage", () => {
  it("shows an inline error and doesn't call the server for an invalid network", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    ui();
    const input = screen.getByLabelText("IPv4 address / prefix");
    await userEvent.clear(input);
    await userEvent.type(input, "not-a-network");
    await userEvent.click(screen.getByRole("button", { name: "Calculate" }));
    expect(await screen.findByText(/Enter an IPv4 address and prefix/)).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it("renders the server's result, showing None for a /31's broadcast", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      input: "10.1.1.1/31", cidr: "10.1.1.0/31", address: "10.1.1.1", prefix: 31, network: "10.1.1.0", firstHost: "10.1.1.0", lastHost: "10.1.1.1", totalAddresses: 2, usableHosts: 2, subnetMask: "255.255.255.254", wildcardMask: "0.0.0.1", addressScope: "Private (RFC 1918)", historicalClass: "A",
      binary: { address: "1", mask: "1", network: "1" }, note: "/31 point-to-point link (RFC 3021)",
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    ui();
    await userEvent.click(screen.getByRole("button", { name: "Calculate" }));
    await waitFor(() => expect(screen.getByText("10.1.1.0/31")).toBeInTheDocument());
    expect(screen.getByText("None")).toBeInTheDocument();
    expect(screen.getByText(/RFC 3021/)).toBeInTheDocument();
    fetchSpy.mockRestore();
  });
});
