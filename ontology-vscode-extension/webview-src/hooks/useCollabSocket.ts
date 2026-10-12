import { useEffect, useRef, useState, MutableRefObject } from "react";
import { Client } from "@stomp/stompjs";
import { getBaseUrl } from "../services/apiClient";
import { CollabSocketHandlers, createCollabClient } from "../utils/collabSocketClient";

export function useCollabSocket(
  active: boolean,
  identity: string,
  token: string | null | undefined,
  handlers: CollabSocketHandlers,
): MutableRefObject<Client | null> {
  const clientRef = useRef<Client | null>(null);
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;
  const gaveUpRef = useRef(false);
  const [revival, setRevival] = useState(0);

  useEffect(() => {
    if (gaveUpRef.current && token) {
      gaveUpRef.current = false;
      setRevival((count) => count + 1);
    }
  }, [token]);

  useEffect(() => {
    if (!active) {
      return;
    }
    gaveUpRef.current = false;
    const url = new URL("/ws", getBaseUrl() || window.location.origin).toString();
    const { client, stop } = createCollabClient(url, () => ({
      ...handlersRef.current,
      onGiveUp: (code) => {
        gaveUpRef.current = true;
        handlersRef.current.onGiveUp(code);
      },
    }));
    clientRef.current = client;
    client.activate();
    return () => {
      stop();
      clientRef.current = null;
    };
  }, [active, identity, revival]);

  return clientRef;
}
