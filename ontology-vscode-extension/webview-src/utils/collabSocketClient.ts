import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { backoffDelay, stompErrorAction } from "./collabSocketPolicy";

export interface CollabSocketHandlers {
  getToken: () => string | null | undefined;
  refreshToken: () => Promise<string | null | undefined>;
  onConnected: (client: Client, reconnect: boolean) => void;
  onClosing: (client: Client) => void;
  onDown: () => void;
  onGiveUp: (code: string) => void;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export function createCollabClient(url: string, handlers: () => CollabSocketHandlers): { client: Client; stop: () => void } {
  let attempt = 0;
  let everConnected = false;
  let outageLogged = false;
  let refreshTried = false;
  let stopped = false;
  let usedToken: string | null | undefined = null;
  let nextToken: string | null | undefined = null;

  const giveUp = (code: string) => {
    if (stopped) {
      return;
    }
    stopped = true;
    void client.deactivate();
    handlers().onGiveUp(code);
  };

  const refreshAndRetry = async (code: string) => {
    refreshTried = true;
    await client.deactivate();
    const fresh = await handlers().refreshToken().catch(() => null);
    if (stopped) {
      return;
    }
    if (!fresh || fresh === usedToken) {
      giveUp(code);
      return;
    }
    nextToken = fresh;
    attempt = 0;
    client.activate();
  };

  const client = new Client({
    webSocketFactory: () => new SockJS(url) as any,
    reconnectDelay: 100,
    connectionTimeout: 15_000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async (c) => {
      if (attempt > 0) {
        await sleep(backoffDelay(attempt - 1));
      }
      attempt += 1;
      usedToken = nextToken || handlers().getToken();
      nextToken = null;
      if (!usedToken) {
        giveUp("AUTH_MISSING");
        return;
      }
      c.connectHeaders = { Authorization: `Bearer ${usedToken}` };
    },
    onConnect: () => {
      const reconnect = everConnected;
      everConnected = true;
      outageLogged = false;
      refreshTried = false;
      attempt = 0;
      handlers().onConnected(client, reconnect);
    },
    onStompError: (frame) => {
      const code = frame.headers?.message || "";
      const action = stompErrorAction(code, refreshTried);
      if (action === "stop") {
        giveUp(code);
      } else if (action === "refresh") {
        void refreshAndRetry(code);
      }
    },
    onWebSocketClose: () => {
      handlers().onDown();
      if (!outageLogged && !stopped) {
        outageLogged = true;
        console.warn("[CollaborationContext] Live connection lost, retrying with backoff");
      }
    },
  });

  const stop = () => {
    stopped = true;
    handlers().onClosing(client);
    void client.deactivate();
  };

  return { client, stop };
}
