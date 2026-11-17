import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { Client } from "@stomp/stompjs";
import { useQueryClient } from "@tanstack/react-query";
import { freshAccessToken } from "./api";

const STATUS_CHANGE_REFRESH_MS = 800;
const ROUTINE_REFRESH_MS = 10_000;

const Ctx = createContext(false);
const ClientCtx = createContext<Client | null>(null);
export const useLiveConnected = () => useContext(Ctx);

/** Subscribes while connected and re-subscribes after a reconnect. A null topic means "not yet". */
export function useTopic<T>(topic: string | null, onMessage: (body: T) => void) {
  const client = useContext(ClientCtx);
  const connected = useContext(Ctx);
  const latest = useRef(onMessage);
  latest.current = onMessage;
  useEffect(() => {
    if (!client || !connected || !topic) return;
    const sub = client.subscribe(topic, (m) => latest.current(JSON.parse(m.body) as T));
    return () => { try { sub.unsubscribe(); } catch { /* the connection is already gone */ } };
  }, [client, connected, topic]);
}

/**
 * STOMP over WebSocket. A status change refreshes cached queries almost at once; routine check results, which
 * arrive constantly, refresh them at most every 10 seconds. The server closes the socket when the access token
 * expires, and the token is re-read before every (re)connect.
 */
export function LiveProvider({ children }: { children: ReactNode }) {
  const [connected, setConnected] = useState(false);
  const qc = useQueryClient();
  const timer = useRef<number | undefined>(undefined);
  const refreshDue = useRef(0);
  const [stomp, setStomp] = useState<Client | null>(null);

  useEffect(() => {
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const client = new Client({
      brokerURL: `${scheme}://${location.host}/ws`,
      reconnectDelay: 3000,
      beforeConnect: async () => {
        const token = await freshAccessToken().catch(() => null);   // on failure the API layer handles expiry
        client.connectHeaders = { Authorization: `Bearer ${token ?? ""}` };
      },
      onConnect: () => {
        setConnected(true);
        client.subscribe("/topic/monitoring", (m) => {
          const wait = (JSON.parse(m.body) as { statusChanged?: boolean }).statusChanged ? STATUS_CHANGE_REFRESH_MS : ROUTINE_REFRESH_MS;
          const due = Date.now() + wait;
          if (refreshDue.current && refreshDue.current <= due) return;   // a refresh at least this soon is already planned
          refreshDue.current = due;
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => {
            refreshDue.current = 0;
            // The tag list only changes when a device is edited, and those paths refresh it themselves.
            qc.invalidateQueries({ queryKey: ["devices"], predicate: (q) => q.queryKey[1] !== "tags" });
            qc.invalidateQueries({ queryKey: ["dashboard"] });
          }, wait);
        });
        client.subscribe("/topic/alerts", () => {
          qc.invalidateQueries({ queryKey: ["alerts"] });
          qc.invalidateQueries({ queryKey: ["dashboard"] });
        });
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    });
    setStomp(client);
    client.activate();
    return () => {
      window.clearTimeout(timer.current);
      void client.deactivate();
    };
  }, [qc]);

  return <ClientCtx.Provider value={stomp}><Ctx.Provider value={connected}>{children}</Ctx.Provider></ClientCtx.Provider>;
}
