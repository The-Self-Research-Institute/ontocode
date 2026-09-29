import React, { createContext, useState, useEffect, useCallback, useMemo, useRef, ReactNode } from "react";
import { Client, StompSubscription } from "@stomp/stompjs";
import { getBaseUrl } from "../services/apiClient";
import { getAuthHeaders } from "../utils/authenticatedFetch";
import { useAuth } from "../custom-hook/useAuth";
import { useCollabSocket } from "../hooks/useCollabSocket";
import { subscribeGlobalTopics } from "../utils/collabGlobalTopics";
import { isDesktop, getDesktopLicense, DESKTOP_LICENSE_UPDATED_EVENT } from "../utils/desktop";
import {
  OP_BATCH_MS,
  capNotifications,
  collaborationAllowed,
  createForbiddenFilter,
  createShareDeduper,
  summarizeRemoteOps,
} from "../utils/collabSocketPolicy";

export interface ActiveUser {
  userId: string;
  username: string;
  color: string;
  lastActivity: number;
  projectId?: string;
  cursorPosition?: string;
  selectedNodes?: string[];
}

export interface NodeLock {
  nodeId: string;
  userId: string;
  username: string;
  expiresAt: number;
  timestamp: number;
}

export interface EditNotification {
  id: string;
  type: "success" | "error" | "info" | "warning";
  message: string;
  userId: string;
  username: string;
  userColor: string;
  timestamp: number;
}

export interface CollaborationState {
  connected: boolean;
  currentProjectId: string | null;
  activeUsers: Map<string, ActiveUser>;
  locks: Map<string, NodeLock>;
  notifications: EditNotification[];
}

interface CollaborationContextType {
  state: CollaborationState;
  setCurrentProject: (projectId: string | null) => void;
  addNotification: (notification: Omit<EditNotification, "id">) => void;
  removeNotification: (id: string) => void;
  clearNotifications: () => void;
  publishCursor: (nodeId: string, nodeLabel: string) => void;
  requestLock: (nodeId: string) => void;
  releaseLock: (nodeId: string) => void;
}

export const CollaborationContext = createContext<CollaborationContextType | undefined>(undefined);

const PROJECT_TOPICS = ["edit", "presence", "locks", "import", "queue"];

const isBrowserMode = () => {
  return (
    typeof window !== "undefined" &&
    (!window.vscode ||
      (window as any).__ONTOCODE_CONFIG__?.IS_WEB_EXTENSION ||
      (window as any).__ONTOCODE_BROWSER_BRIDGE__)
  );
};

export const CollaborationProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [state, setState] = useState<CollaborationState>({
    connected: false,
    currentProjectId: null,
    activeUsers: new Map(),
    locks: new Map(),
    notifications: [],
  });

  const { user, refreshPermissions } = useAuth();
  const subscriptionsRef = useRef<Map<string, StompSubscription>>(new Map());
  const currentProjectRef = useRef<string | null>(null);

  const addNotificationRef = useRef<((n: Omit<EditNotification, "id">) => void) | null>(null);

  const tokenRef = useRef(user?.token);
  tokenRef.current = user?.token;
  const userRef = useRef(user);
  userRef.current = user;
  const [collabAllowed, setCollabAllowed] = useState(!isDesktop());
  const shareIsNew = useRef(createShareDeduper()).current;
  const forbiddenDest = useRef(createForbiddenFilter()).current;
  const joinRef = useRef<(client: Client, projectId: string) => void>(() => {});
  const [connectionEpoch, setConnectionEpoch] = useState(0);
  const activeJobsRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    if (!isDesktop()) {
      return;
    }
    const check = () => {
      getDesktopLicense().then((lic) => setCollabAllowed(collaborationAllowed(true, lic)));
    };
    check();
    window.addEventListener(DESKTOP_LICENSE_UPDATED_EVENT, check);
    return () => window.removeEventListener(DESKTOP_LICENSE_UPDATED_EVENT, check);
  }, []);

  const hasToken = !!user?.token;
  const identity = `${user?.userId ?? ""}|${user?.email ?? ""}|${user?.workspaceId ?? ""}`;
  const stompClientRef = useCollabSocket(isBrowserMode() && hasToken && collabAllowed, identity, user?.token, {
    getToken: () => tokenRef.current,
    refreshToken: async () => {
      await refreshPermissions();
      return localStorage.getItem("authToken");
    },
    onConnected: (client, reconnect) => {
      subscriptionsRef.current.clear();
      setConnectionEpoch((epoch) => epoch + 1);
      setState((prev) => ({ ...prev, connected: true }));
      subscribeGlobalTopics(client, subscriptionsRef.current, userRef.current, { shareIsNew, forbiddenDest });
      if (currentProjectRef.current) {
        joinRef.current(client, currentProjectRef.current);
      }
      if (reconnect) {
        window.dispatchEvent(new CustomEvent("collaborationReconnected", { detail: { timestamp: Date.now() } }));
      }
    },
    onClosing: (client) => {
      const projectId = currentProjectRef.current;
      if (projectId && client.connected) {
        publishPresence(client, projectId, "USER_LEFT");
      }
      subscriptionsRef.current.forEach((sub) => sub.unsubscribe());
      subscriptionsRef.current.clear();
    },
    onDown: () => setState((prev) => (prev.connected ? { ...prev, connected: false } : prev)),
    onGiveUp: () => {
      addNotificationRef.current?.({
        type: "warning",
        message: "Live collaboration disconnected. Sign in again to reconnect.",
        userId: "system",
        username: "OntoCode",
        userColor: "#F59E0B",
        timestamp: Date.now(),
      });
    },
  });

  const publishPresence = (client: Client, projectId: string, type: string) => {
    const u = userRef.current;
    client.publish({
      destination: `/app/collab/${projectId}/presence`,
      body: JSON.stringify({
        type,
        projectId,
        userId: u?.userId || u?.username,
        username: u?.username,
        timestamp: Date.now(),
      }),
    });
  };

  useEffect(() => {
    const jobSubscriptions = new Map<string, StompSubscription>();

    const subscribeToJob = (jobId: string) => {
      const client = stompClientRef.current;
      if (!client?.connected || jobSubscriptions.has(jobId)) {
        return;
      }
      const sub = client.subscribe(`/topic/dlquery/${jobId}`, (msg) => {
        try {
          const payload = JSON.parse(msg.body);
          window.dispatchEvent(new CustomEvent("dlQueryJobUpdate", { detail: payload }));
          if (payload.status === "COMPLETED" || payload.status === "FAILED") {
            sub.unsubscribe();
            jobSubscriptions.delete(jobId);
            activeJobsRef.current.delete(jobId);
          }
        } catch (e) {
          console.error("[CollaborationContext] DL Query job parse error:", e);
        }
      });
      jobSubscriptions.set(jobId, sub);
    };

    activeJobsRef.current.forEach(subscribeToJob);

    const handleSubscribe = (event: Event) => {
      const jobId = (event as CustomEvent).detail?.jobId as string | undefined;
      if (!jobId) return;
      activeJobsRef.current.add(jobId);

      const trySubscribe = () => {
        if (stompClientRef.current?.connected) {
          subscribeToJob(jobId);
          return true;
        }
        return false;
      };

      if (!trySubscribe()) {
        const retryId = window.setInterval(() => {
          if (trySubscribe()) {
            window.clearInterval(retryId);
          }
        }, 500);
        window.setTimeout(() => window.clearInterval(retryId), 30_000);
      }
    };

    const handleUnsubscribe = (event: Event) => {
      const jobId = (event as CustomEvent).detail?.jobId as string | undefined;
      if (!jobId) return;
      activeJobsRef.current.delete(jobId);
      const sub = jobSubscriptions.get(jobId);
      if (sub) {
        sub.unsubscribe();
        jobSubscriptions.delete(jobId);
      }
    };

    window.addEventListener("dlQuerySubscribe", handleSubscribe);
    window.addEventListener("dlQueryUnsubscribe", handleUnsubscribe);

    return () => {
      window.removeEventListener("dlQuerySubscribe", handleSubscribe);
      window.removeEventListener("dlQueryUnsubscribe", handleUnsubscribe);
      jobSubscriptions.forEach((sub) => {
        try {
          sub.unsubscribe();
        } catch {
          return;
        }
      });
      jobSubscriptions.clear();
    };
  }, [stompClientRef, connectionEpoch]);

  const dropProjectSubscriptions = () => {
    PROJECT_TOPICS.forEach((key) => {
      subscriptionsRef.current.get(key)?.unsubscribe();
      subscriptionsRef.current.delete(key);
    });
  };

  const joinProjectTopics = useCallback(
    (client: Client, projectId: string) => {
      dropProjectSubscriptions();

      const userId = user?.userId || user?.username || "";
      const userEmail = user?.email || "";

      const METADATA_EVENT_TYPES = new Set([
        "ONTOLOGY_ANNOTATION_ADDED", "ONTOLOGY_ANNOTATION_MODIFIED", "ONTOLOGY_ANNOTATION_DELETED",
        "IMPORT_ADDED", "IMPORT_REMOVED", "GCI_ADDED", "GCI_REMOVED",
      ]);

      const editSub = client.subscribe(`/topic/ontology/${projectId}`, (msg) => {
        try {
          const edit = JSON.parse(msg.body);

          const ownEdit = edit.userId === userId || (!!userEmail && edit.userEmail === userEmail);
          if (ownEdit && !METADATA_EVENT_TYPES.has(edit.type)) {
            window.dispatchEvent(new CustomEvent("ownEditReceived", { detail: edit }));
            return;
          }
          handleRemoteEdit(edit);
        } catch (e) {
          console.error("[CollaborationContext] Edit parse error:", e);
        }
      });
      subscriptionsRef.current.set("edit", editSub);

      const presenceSub = client.subscribe(`/topic/presence/${projectId}`, (msg) => {
        try {
          const presence = JSON.parse(msg.body);
          handlePresenceUpdate(presence);
        } catch (e) {
          console.error("[CollaborationContext] Presence parse error:", e);
        }
      });
      subscriptionsRef.current.set("presence", presenceSub);

      const lockSub = client.subscribe(`/topic/locks/${projectId}`, (msg) => {
        try {
          const lock = JSON.parse(msg.body);
          handleLockUpdate(lock);
        } catch (e) {
          console.error("[CollaborationContext] Lock parse error:", e);
        }
      });
      subscriptionsRef.current.set("locks", lockSub);

      const importSub = client.subscribe(`/topic/import/${projectId}`, (msg) => {
        try {
          const status = JSON.parse(msg.body);
          window.dispatchEvent(new CustomEvent("importStatusUpdate", { detail: status }));
        } catch (e) {
          console.error("[CollaborationContext] Import parse error:", e);
        }
      });
      subscriptionsRef.current.set("import", importSub);

      const queueSub = client.subscribe(`/topic/queue/${projectId}`, (msg) => {
        try {
          const status = JSON.parse(msg.body);
          window.dispatchEvent(new CustomEvent("queueStatusUpdate", { detail: status }));
        } catch (e) {
          console.error("[CollaborationContext] Queue status parse error:", e);
        }
      });
      subscriptionsRef.current.set("queue", queueSub);

      publishPresence(client, projectId, "USER_JOINED");

      const baseUrl = getBaseUrl();
      fetch(`${baseUrl}/api/collab-graph/${projectId}/active-users`, {
        headers: getAuthHeaders(),
      })
        .then((res) => (res.ok ? res.json() : null))
        .then((data) => {
          if (data?.users) {
            data.users.forEach((u: any) => {
              if (u.userId !== userId) {
                handlePresenceUpdate({
                  type: "USER_ACTIVE",
                  userId: u.userId,
                  username: u.username,
                  color: u.color,
                  timestamp: u.lastActivity,
                  projectId,
                });
              }
            });
          }
        })
        .catch((e) => console.error("[CollaborationContext] Failed to fetch active users:", e));

    },
    [user],
  );
  joinRef.current = joinProjectTopics;

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;

      switch (message.type) {
        case "collaborationStatus":
          setState((prev) => {
            const wasDisconnected = !prev.connected;
            const isNowConnected = message.connected;

            if (wasDisconnected && isNowConnected) {
              const reconnectEvent = new CustomEvent("collaborationReconnected", {
                detail: { timestamp: Date.now() },
              });
              window.dispatchEvent(reconnectEvent);
            }

            return {
              ...prev,
              connected: message.connected,
            };
          });
          break;

        case "presenceUpdate":
          handlePresenceUpdate(message.presence);
          break;

        case "lockUpdate":
          handleLockUpdate(message.lock);
          break;

        case "remoteEdit":
          handleRemoteEdit(message.edit);
          break;

        case "ROLLBACK":

          const rollbackEvent = new CustomEvent("ontologyRollback", {
            detail: message,
          });
          window.dispatchEvent(rollbackEvent);
          break;

        case "shareNotification":
          if (!shareIsNew(message.notification)) {
            break;
          }
          const shareEvent = new CustomEvent("fileShared", {
            detail: message.notification,
          });
          window.dispatchEvent(shareEvent);
          break;
      }
    };

    window.addEventListener("message", handleMessage);

    if (window.vscode) {
      window.vscode.postMessage({ type: "requestCollaborationStatus" });
    }

    return () => {
      window.removeEventListener("message", handleMessage);
    };
  }, []);

  const handlePresenceUpdate = useCallback((presence: any) => {
    const currentUserId = user?.userId || user?.username;

    setState((prev) => {
      const newUsers = new Map(prev.activeUsers);

      switch (presence.type) {
        case "USER_JOINED":
        case "USER_ACTIVE":
        case "CURSOR_MOVED":
        case "SELECTION_CHANGED":
          newUsers.set(presence.userId, {
            userId: presence.userId,
            username: presence.username,
            color: presence.color || "#888888",
            lastActivity: presence.timestamp,
            projectId: presence.projectId,
            cursorPosition: presence.cursorPosition,
            selectedNodes: presence.selectedNodes,
          });
          break;

        case "USER_LEFT":
          newUsers.delete(presence.userId);
          break;
      }

      return { ...prev, activeUsers: newUsers };
    });

    if (presence.userId !== currentUserId && addNotificationRef.current) {
      if (presence.type === "USER_JOINED") {
        addNotificationRef.current({
          type: "info" as any,
          message: `${presence.username || "A collaborator"} joined the session`,
          userId: presence.userId,
          username: presence.username,
          userColor: presence.color || "#888888",
          timestamp: presence.timestamp,
        });
      } else if (presence.type === "USER_LEFT") {
        addNotificationRef.current({
          type: "info" as any,
          message: `${presence.username || "A collaborator"} left the session`,
          userId: presence.userId,
          username: presence.username,
          userColor: "#888888",
          timestamp: presence.timestamp,
        });
      }
    }
  }, [user?.userId, user?.username]);

  const handleLockUpdate = useCallback((lock: any) => {
    if (lock.type === "LOCK_DENIED") {
      const myUserId = user?.userId || user?.username;
      if (lock.userId === myUserId) {
        addNotification({
          type: "warning",
          message: lock.error || `Locked by ${lock.username}`,
          userId: lock.userId,
          username: lock.username,
          userColor: "#F59E0B",
          timestamp: lock.timestamp,
        });
      }
      return;
    }

    setState((prev) => {
      const newLocks = new Map(prev.locks);

      switch (lock.type) {
        case "LOCK_ACQUIRED":
          newLocks.set(lock.nodeId, {
            nodeId: lock.nodeId,
            userId: lock.userId,
            username: lock.username,
            expiresAt: lock.expiresAt,
            timestamp: lock.timestamp,
          });
          break;

        case "LOCK_RELEASED":
        case "LOCK_EXPIRED":
        case "LOCK_FORCE_RELEASE":
          newLocks.delete(lock.nodeId);
          break;
      }

      return {
        ...prev,
        locks: newLocks,
      };
    });
  }, []);

  const addNotification = useCallback((notification: Omit<EditNotification, "id">) => {
    const id = `notif-${Date.now()}-${Math.random()}`;
    setState((prev) => {

      const user = prev.activeUsers.get(notification.userId);
      const userColor = user?.color || notification.userColor;

      return {
        ...prev,
        notifications: capNotifications([...prev.notifications, { ...notification, id, userColor }]),
      };
    });

    setTimeout(() => {
      removeNotification(id);
    }, 5000);
  }, []);

  addNotificationRef.current = addNotification;

  const removeNotification = useCallback((id: string) => {
    setState((prev) => ({
      ...prev,
      notifications: prev.notifications.filter((n) => n.id !== id),
    }));
  }, []);

  const clearNotifications = useCallback(() => {
    setState((prev) => ({
      ...prev,
      notifications: [],
    }));
  }, []);

  const pendingOpsRef = useRef<any[]>([]);
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const expiryTimersRef = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());

  const flushRemoteOps = useCallback(() => {
    flushTimerRef.current = null;
    const ops = pendingOpsRef.current;
    pendingOpsRef.current = [];
    const stamp = Date.now();
    const added = summarizeRemoteOps(ops).map((summary, i) => ({ ...summary, id: `notif-${stamp}-${i}-${Math.random()}` }));
    if (!added.length) {
      return;
    }
    const ids = new Set(added.map((n) => n.id));
    setState((prev) => {
      const users = new Map(prev.activeUsers);
      const notes: EditNotification[] = added.map((n) => {
        const known = users.get(n.userId);
        if (known) {
          users.set(n.userId, { ...known, lastActivity: n.timestamp });
        }
        return { ...n, type: "info", userColor: known?.color || "#888888" };
      });
      return { ...prev, activeUsers: users, notifications: capNotifications([...prev.notifications, ...notes]) };
    });
    const expiry = setTimeout(() => {
      expiryTimersRef.current.delete(expiry);
      setState((s) => ({ ...s, notifications: s.notifications.filter((n) => !ids.has(n.id)) }));
    }, 5000);
    expiryTimersRef.current.add(expiry);
  }, []);

  const handleRemoteEdit = useCallback((edit: any) => {
    window.dispatchEvent(new CustomEvent("remoteEditReceived", { detail: edit }));
    pendingOpsRef.current.push(edit);
    if (!flushTimerRef.current) {
      flushTimerRef.current = setTimeout(flushRemoteOps, OP_BATCH_MS);
    }
  }, [flushRemoteOps]);

  useEffect(() => () => {
    if (flushTimerRef.current) {
      clearTimeout(flushTimerRef.current);
    }
    expiryTimersRef.current.forEach(clearTimeout);
    expiryTimersRef.current.clear();
  }, []);

  const publishCursor = useCallback(
    (nodeId: string, nodeLabel: string) => {
      const projectId = currentProjectRef.current;
      if (!projectId) return;

      const userId = user?.userId || user?.username || "";
      const username = user?.username || "";

      if (isBrowserMode()) {
        const client = stompClientRef.current;
        if (!client?.connected) return;
        client.publish({
          destination: `/app/collab/${projectId}/presence`,
          body: JSON.stringify({
            type: "CURSOR_MOVED",
            projectId,
            userId,
            username,
            cursorPosition: nodeId,
            selectedNodes: [nodeId],
            timestamp: Date.now(),
          }),
        });
      } else if (window.vscode) {
        window.vscode.postMessage({
          type: "cursorMoved",
          projectId,
          nodeId,
          nodeName: nodeLabel,
        });
      }
    },
    [user?.userId, user?.username],
  );

  const requestLock = useCallback(
    (nodeId: string) => {
      const projectId = currentProjectRef.current;
      if (!projectId) return;

      const userId = user?.userId || user?.username || "";
      const username = user?.username || "";

      if (isBrowserMode()) {
        const client = stompClientRef.current;
        if (!client?.connected) return;
        client.publish({
          destination: `/app/collab/${projectId}/lock`,
          body: JSON.stringify({
            type: "LOCK_REQUEST",
            projectId,
            nodeId,
            userId,
            username,
            timestamp: Date.now(),
          }),
        });
      } else if (window.vscode) {
        window.vscode.postMessage({
          type: "requestLock",
          projectId,
          nodeId,
        });
      }
    },
    [user?.userId, user?.username],
  );

  const releaseLock = useCallback(
    (nodeId: string) => {
      const projectId = currentProjectRef.current;
      if (!projectId) return;

      const userId = user?.userId || user?.username || "";
      const username = user?.username || "";

      if (isBrowserMode()) {
        const client = stompClientRef.current;
        if (!client?.connected) return;
        client.publish({
          destination: `/app/collab/${projectId}/lock`,
          body: JSON.stringify({
            type: "LOCK_RELEASED",
            projectId,
            nodeId,
            userId,
            username,
            timestamp: Date.now(),
          }),
        });
      } else if (window.vscode) {
        window.vscode.postMessage({
          type: "releaseLock",
          projectId,
          nodeId,
        });
      }

      setState((prev) => {
        const newLocks = new Map(prev.locks);
        newLocks.delete(nodeId);
        return { ...prev, locks: newLocks };
      });
    },
    [user?.userId, user?.username],
  );

  const setCurrentProject = useCallback(
    (projectId: string | null) => {
      const previous = currentProjectRef.current;
      currentProjectRef.current = projectId;
      setState((prev) => ({
        ...prev,
        currentProjectId: projectId,
      }));

      const client = stompClientRef.current;
      if (isBrowserMode() && client?.connected) {
        if (previous && previous !== projectId) {
          publishPresence(client, previous, "USER_LEFT");
        }
        if (projectId) {
          joinProjectTopics(client, projectId);
        } else {
          dropProjectSubscriptions();
        }
      }
    },
    [joinProjectTopics, stompClientRef],
  );

  const value: CollaborationContextType = useMemo(
    () => ({
      state,
      setCurrentProject,
      addNotification,
      removeNotification,
      clearNotifications,
      publishCursor,
      requestLock,
      releaseLock,
    }),
    [
      state,
      setCurrentProject,
      addNotification,
      removeNotification,
      clearNotifications,
      publishCursor,
      requestLock,
      releaseLock,
    ],
  );

  return <CollaborationContext.Provider value={value}>{children}</CollaborationContext.Provider>;
};

export const useCollaboration = (): CollaborationContextType => {
  const context = React.useContext(CollaborationContext);
  if (!context) {
    throw new Error("useCollaboration must be used within a CollaborationProvider");
  }
  return context;
};
