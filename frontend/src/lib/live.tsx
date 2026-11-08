import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { Client } from "@stomp/stompjs";
import { useQueryClient } from "@tanstack/react-query";
import { freshAccessToken } from "./api";

const Ctx = createContext(false);
export const useLiveConnected = () => useContext(Ctx);

/**
 * STOMP over WebSocket. Each check result marks cached queries stale, debounced so a burst of results
 * causes one refetch. The token is re-read before every (re)connect because access tokens are short-lived.
 */
export function LiveProvider({ children }: { children: ReactNode }) {
  const [connected, setConnected] = useState(false);
  const qc = useQueryClient();
  const timer = useRef<number | undefined>(undefined);

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
        client.subscribe("/topic/monitoring", () => {
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => {
            qc.invalidateQueries({ queryKey: ["devices"] });
            qc.invalidateQueries({ queryKey: ["dashboard"] });
          }, 800);
        });
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    });
    client.activate();
    return () => {
      window.clearTimeout(timer.current);
      void client.deactivate();
    };
  }, [qc]);

  return <Ctx.Provider value={connected}>{children}</Ctx.Provider>;
}
