import type { TokenResponse } from "./types";

/**
 * The access token lives only in memory, so XSS-persisted storage can't leak it. A page reload restores the
 * session through the HttpOnly refresh cookie. Only cookie-authenticated auth endpoints need the CSRF header.
 */
export class ApiError extends Error {
  constructor(public status: number, message: string, public fieldErrors?: Record<string, string>) {
    super(message);
  }
}

let accessToken: string | null = null;
let expiresAt = 0;
let refreshing: Promise<TokenResponse> | null = null;
let onExpired: () => void = () => {};

export const setSessionExpiredHandler = (fn: () => void) => { onExpired = fn; };

function readCookie(name: string): string | null {
  const row = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
  return row ? decodeURIComponent(row.slice(name.length + 1)) : null;
}

async function toError(res: Response): Promise<ApiError> {
  let detail = `Request failed (${res.status})`;
  let errors: Record<string, string> | undefined;
  try {
    const body = await res.json();
    detail = body.detail ?? detail;
    errors = body.errors;
  } catch { /* non-JSON error, e.g. from a proxy */ }
  return new ApiError(res.status, detail, errors);
}

async function authPost<T>(path: string, body?: unknown): Promise<T> {
  // Always fetch first: Spring writes a fresh XSRF-TOKEN cookie, which we echo back in the header.
  const csrf = await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
  if (!csrf.ok) throw await toError(csrf);
  const res = await fetch(`/api/v1/auth/${path}`, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": readCookie("XSRF-TOKEN") ?? "" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw await toError(res);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

function remember(t: TokenResponse) {
  accessToken = t.accessToken;
  expiresAt = Date.parse(t.expiresAt);
}

export async function login(username: string, password: string) {
  const t = await authPost<TokenResponse>("login", { username, password });
  remember(t);
  return t;
}

/** Single-flight: refresh tokens rotate, so two parallel refreshes would look like token theft to the server. */
export function refreshSession(): Promise<TokenResponse> {
  if (refreshing) return refreshing;
  const doRefresh = () => authPost<TokenResponse>("refresh");
  // The Web Lock also serializes across browser tabs, which share the cookie but not this module's state.
  const serialized = navigator.locks ? navigator.locks.request("np-refresh", doRefresh) : doRefresh();
  refreshing = (serialized as Promise<TokenResponse>)
    .then((t) => { remember(t); return t; })
    .finally(() => { refreshing = null; });
  return refreshing;
}

/** Returns a token valid for at least 30 more seconds, refreshing only when needed. */
export async function freshAccessToken(): Promise<string | null> {
  if (accessToken && expiresAt - Date.now() > 30_000) return accessToken;
  await refreshSession();
  return accessToken;
}

export async function logout() {
  try { await authPost<void>("logout"); } finally { accessToken = null; }
}

export async function api<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  const res = await fetch(`/api/v1${path}`, { ...init, headers, credentials: "same-origin" });

  if (res.status === 401 && retry) {
    try {
      await refreshSession();
    } catch {
      accessToken = null;
      onExpired();
      throw new ApiError(401, "Your session has expired. Please sign in again.");
    }
    return api<T>(path, init, false);
  }
  if (!res.ok) throw await toError(res);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

export const post = <T,>(path: string, body?: unknown) =>
  api<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) });
export const put = <T,>(path: string, body: unknown) => api<T>(path, { method: "PUT", body: JSON.stringify(body) });
export const del = (path: string) => api<void>(path, { method: "DELETE" });

/** Fetches with the bearer token, since a plain link can't send it, and hands the result to the browser as a file. */
export async function downloadFile(path: string, filename: string): Promise<void> {
  const send = (t: string | null) => fetch(`/api/v1${path}`, { headers: t ? { Authorization: `Bearer ${t}` } : {}, credentials: "same-origin" });
  let res = await send(await freshAccessToken());
  if (res.status === 401) { await refreshSession(); res = await send(accessToken); }
  if (!res.ok) throw await toError(res);
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
