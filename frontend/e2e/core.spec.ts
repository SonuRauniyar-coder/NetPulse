import { expect, test } from "@playwright/test";

const USER = process.env.E2E_USER ?? "admin";
const PASS = process.env.E2E_PASS ?? "";

test("sign in, add a device and see a real status", async ({ page }) => {
  const name = `e2e-loopback-${Date.now()}`;

  await page.goto("/");
  await expect(page).toHaveURL(/login/);
  await page.getByLabel("Username").fill(USER);
  await page.getByLabel("Password").fill("wrong-password-here");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert")).toContainText("Invalid username or password");

  await page.getByLabel("Password").fill(PASS);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  await expect(page.getByText("Live", { exact: true })).toBeVisible({ timeout: 15_000 });

  await page.getByRole("link", { name: "Devices" }).click();
  await page.getByRole("button", { name: "Add device" }).click();

  await page.getByLabel("Name").fill(name);
  await page.getByLabel("IP or hostname").fill("not a host!");
  await page.locator("#device-form").press("Enter").catch(() => undefined);
  await page.getByRole("dialog").getByRole("button", { name: "Add device" }).click();
  await expect(page.getByText("Enter a valid IPv4, IPv6 or hostname")).toBeVisible();

  await page.getByLabel("IP or hostname").fill("127.0.0.1");
  await page.getByRole("dialog").getByRole("button", { name: "Add device" }).click();

  // No reload: the row must turn Up through the WebSocket push after a real ping.
  const row = page.getByRole("row", { name: new RegExp(name) });
  await expect(row).toBeVisible();
  await expect(row.getByText("Up", { exact: true })).toBeVisible({ timeout: 30_000 });

  await row.getByRole("link", { name }).click();
  await expect(page.getByText("4 of 4 replied")).toBeVisible({ timeout: 15_000 });

  // A reload restores the session from the refresh cookie.
  await page.reload();
  await expect(page.getByRole("heading", { name })).toBeVisible();
});
