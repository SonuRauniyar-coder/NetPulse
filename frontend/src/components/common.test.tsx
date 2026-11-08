import { render, screen } from "@testing-library/react";
import { EmptyState, ErrorState, StatusBadge } from "./common";
import { Server } from "lucide-react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import type { DeviceStatus } from "@/lib/types";

describe("StatusBadge", () => {
  it.each<[DeviceStatus, string]>([["UP", "Up"], ["DEGRADED", "Degraded"], ["DOWN", "Down"], ["UNKNOWN", "Unknown"], ["PAUSED", "Paused"]])(
    "renders text and an icon for %s, never color alone", (status, text) => {
      const { container } = render(<StatusBadge status={status} />);
      expect(screen.getByText(text)).toBeInTheDocument();
      expect(container.querySelector("svg")).not.toBeNull();
    });
});

describe("states", () => {
  it("empty state shows its call to action", () => {
    render(<EmptyState icon={Server} title="No devices" description="Add one" action={<button>Add</button>} />);
    expect(screen.getByRole("button", { name: "Add" })).toBeInTheDocument();
  });
  it("error state retries", async () => {
    const retry = vi.fn();
    render(<ErrorState message="Boom" onRetry={retry} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Boom");
    await userEvent.click(screen.getByRole("button", { name: /retry/i }));
    expect(retry).toHaveBeenCalledOnce();
  });
});
