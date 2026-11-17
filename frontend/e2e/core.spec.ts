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

  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name", { exact: true }).fill(name);
  await dialog.getByLabel("IP or hostname").fill("not a host!");
  await dialog.getByRole("button", { name: "Add device" }).click();
  await expect(page.getByText("Enter a valid IPv4, IPv6 or hostname")).toBeVisible();

  await dialog.getByLabel("IP or hostname").fill("127.0.0.1");
  await dialog.getByRole("button", { name: "Add device" }).click();

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

test("an alert fires from a real failed port check and can be acknowledged", async ({ page }) => {
  const stamp = Date.now();
  const device = `e2e-alert-${stamp}`;

  await page.goto("/login");
  await page.getByLabel("Username").fill(USER);
  await page.getByLabel("Password").fill(PASS);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();

  await page.getByRole("link", { name: "Alerts" }).first().click();
  await page.getByRole("tab", { name: "Rules" }).click();
  await page.getByRole("button", { name: "New rule" }).first().click();
  let dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(`e2e-rule-${stamp}`);
  await dialog.getByRole("combobox", { name: "Condition" }).click();
  await page.getByRole("option", { name: "Port closed" }).click();
  await dialog.getByLabel("For (minutes)").fill("0");
  await dialog.getByRole("button", { name: "Save rule" }).click();
  await expect(page.getByRole("cell", { name: `e2e-rule-${stamp}`, exact: true })).toBeVisible();

  await page.getByRole("link", { name: "Devices" }).first().click();
  await page.getByRole("button", { name: "Add device" }).click();
  dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name", { exact: true }).fill(device);
  await dialog.getByLabel("IP or hostname").fill("127.0.0.1");
  await dialog.getByRole("button", { name: /Add port/ }).click();
  await dialog.getByLabel("Port", { exact: true }).fill("1");   // nothing listens on TCP port 1, so the connection is refused
  await dialog.getByRole("button", { name: "Add device" }).click();

  // The bell and the table update through the WebSocket, with no reload.
  await page.getByRole("link", { name: /^Alerts/ }).first().click();
  const row = page.getByRole("row", { name: new RegExp(`${device} port 1.*e2e-rule-${stamp}`) });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByRole("button", { name: "Acknowledge" }).click();
  await expect(page.getByText("Alert acknowledged")).toBeVisible();
  await page.getByRole("tab", { name: "Acknowledged" }).click();
  await expect(page.getByRole("row", { name: new RegExp(`${device} port 1.*e2e-rule-${stamp}`) })).toContainText(/Acknowledged by/);
});
