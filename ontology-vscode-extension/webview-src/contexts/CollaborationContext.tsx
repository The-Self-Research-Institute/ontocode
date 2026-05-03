import React, { createContext, useState, useEffect, useCallback, useMemo, useRef, ReactNode } from "react";
import { Client, StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { getBaseUrl } from "../services/apiClient";
import { useAuth } from "../custom-hook/useAuth";

// Types matching the collaboration types from extension
export interface ActiveUser {
  userId: string;
  username: string;
  color: string;
  lastActivity: number;
  projectId?: string; // Track which project/file the user is viewing
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
  currentProjectId: string | null; // Track the current project being viewed
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
}

export const CollaborationContext = createContext<CollaborationContextType | undefined>(undefined);

// Detect browser mode (not running inside VS Code webview)
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

  const { user } = useAuth();
  const stompClientRef = useRef<Client | null>(null);
  const subscriptionsRef = useRef<Map<string, StompSubscription>>(new Map());
  const currentProjectRef = useRef<string | null>(null);
  // Ref so handlePresenceUpdate can call addNotification before its declaration
  const addNotificationRef = useRef<((n: Omit<EditNotification, "id">) => void) | null>(null);
  //   const activeUsersIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Standalone REST fetch for active users — works regardless of WebSocket status
  //   const fetchActiveUsers = useCallback(
  //     (projectId: string) => {
  //       if (!isBrowserMode()) return;
  //       const userId = user?.userId || user?.username || "";
  //       const baseUrl = getBaseUrl();
  //       fetch(`${baseUrl}/api/collab-graph/${projectId}/active-users`)
  //         .then((res) => (res.ok ? res.json() : null))
  //         .then((data) => {
  //           if (data?.users) {
  //             setState((prev) => {
  //               const newUsers = new Map(prev.activeUsers);
  //               data.users.forEach((u: any) => {
  //                 if (u.userId !== userId) {
  //                   newUsers.set(u.userId, {
  //                     userId: u.userId,
  //                     username: u.username,
  //                     color: u.color || "#888888",
  //                     lastActivity: u.lastActivity,
  //                     projectId,
  //                   });
  //                 }
  //               });
  //               return { ...prev, activeUsers: newUsers };
  //             });
  //           }
  //         })
  //         .catch((e) => console.error("[CollaborationContext] Failed to fetch active users:", e));
  //     },
  //     [user],
  //   );

  // Periodically refresh active users via REST while a project is open (browser mode)
  //   useEffect(() => {
  //     if (!isBrowserMode()) return;
  //     // Clear previous interval
  //     if (activeUsersIntervalRef.current) {
  //       clearInterval(activeUsersIntervalRef.current);
  //       activeUsersIntervalRef.current = null;
  //     }
  //     const projectId = state.currentProjectId;
  //     if (!projectId || !user?.token) return;

  //     // Immediate fetch when project changes
  //     fetchActiveUsers(projectId);

  //     // Refresh every 15 seconds
  //     activeUsersIntervalRef.current = setInterval(() => {
  //       fetchActiveUsers(projectId);
  //     }, 15000);

  //     return () => {
  //       if (activeUsersIntervalRef.current) {
  //         clearInterval(activeUsersIntervalRef.current);
  //         activeUsersIntervalRef.current = null;
  //       }
  //     };
  //   }, [state.currentProjectId, user?.token, fetchActiveUsers]);

  // ─── Browser-mode: direct STOMP/WebSocket connection ───
  useEffect(() => {
    if (!isBrowserMode() || !user?.token) return;

    const baseUrl = getBaseUrl() || window.location.origin;
    const sockJsUrl = new URL("/ws", baseUrl).toString();
    console.log("[CollaborationContext] 🌐 Browser mode — connecting via SockJS:", sockJsUrl);

    const client = new Client({
      webSocketFactory: () => new SockJS(sockJsUrl) as any,
      connectHeaders: { Authorization: `Bearer ${user.token}` },
      debug: () => {},
      reconnectDelay: 2000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        console.log("[CollaborationContext] ✅ WebSocket connected");
        setState((prev) => ({ ...prev, connected: true }));

        // Subscribe to share notifications for this user
        if (user.email) {
          const sub = client.subscribe(`/topic/shares/${user.email}`, (msg) => {
            try {
              const notification = JSON.parse(msg.body);
              console.log("[CollaborationContext] 📨 Share notification:", notification);
              window.dispatchEvent(new CustomEvent("fileShared", { detail: notification }));
            } catch (e) {
              console.error("[CollaborationContext] Share parse error:", e);
            }
          });
          subscriptionsRef.current.set("shares", sub);
        }

        // Subscribe to workspace-level events (project created/deleted by others)
        if (user.workspaceId) {
          const wsSub = client.subscribe(`/topic/workspace/${user.workspaceId}`, (msg) => {
            try {
              const event = JSON.parse(msg.body);
              console.log("[CollaborationContext] 🏢 Workspace event:", event);
              window.dispatchEvent(new CustomEvent("workspaceEvent", { detail: event }));
            } catch (e) {
              console.error("[CollaborationContext] Workspace event parse error:", e);
            }
          });
          subscriptionsRef.current.set("workspace", wsSub);
        }

        // If we already have a project selected, join it
        if (currentProjectRef.current) {
          joinProjectTopics(client, currentProjectRef.current);
        }
      },
      onDisconnect: () => {
        console.log("[CollaborationContext] ❌ WebSocket disconnected");
        setState((prev) => ({ ...prev, connected: false }));
      },
      onWebSocketError: (e) => {
        console.error("[CollaborationContext] WebSocket error:", e);
      },
    });

    stompClientRef.current = client;
    client.activate();

    return () => {
      // Send USER_LEFT if in a project
      if (currentProjectRef.current && client.connected) {
        client.publish({
          destination: `/app/collab/${currentProjectRef.current}/presence`,
          body: JSON.stringify({
            type: "USER_LEFT",
            projectId: currentProjectRef.current,
            userId: user.userId || user.username,
            username: user.username,
            timestamp: Date.now(),
          }),
        });
      }
      subscriptionsRef.current.forEach((sub) => sub.unsubscribe());
      subscriptionsRef.current.clear();
      client.deactivate();
      stompClientRef.current = null;
    };
  }, [user?.token]);

  // Helper: subscribe to project-specific STOMP topics
  const joinProjectTopics = useCallback(
    (client: Client, projectId: string) => {
      // Clear previous project subscriptions (keep shares)
      subscriptionsRef.current.forEach((sub, key) => {
        if (key !== "shares") {
          sub.unsubscribe();
          subscriptionsRef.current.delete(key);
        }
      });

      const userId = user?.userId || user?.username || "";
      const username = user?.username || "";

      // Metadata events must sync across all devices for the same user
      const METADATA_EVENT_TYPES = new Set([
        "ONTOLOGY_ANNOTATION_ADDED", "ONTOLOGY_ANNOTATION_MODIFIED", "ONTOLOGY_ANNOTATION_DELETED",
        "IMPORT_ADDED", "IMPORT_REMOVED", "GCI_ADDED", "GCI_REMOVED",
      ]);

      // Edits
      const editSub = client.subscribe(`/topic/ontology/${projectId}`, (msg) => {
        try {
          const edit = JSON.parse(msg.body);
          // Skip own non-metadata edits (avoid echo). Metadata events always propagate
          // so that the same user on a second device sees annotation/import/GCI changes.
          if (edit.userId === userId && !METADATA_EVENT_TYPES.has(edit.type)) return;
          console.log("[CollaborationContext] 📝 Remote edit:", edit);
          handleRemoteEdit(edit);
        } catch (e) {
          console.error("[CollaborationContext] Edit parse error:", e);
        }
      });
      subscriptionsRef.current.set("edit", editSub);

      // Presence
      const presenceSub = client.subscribe(`/topic/presence/${projectId}`, (msg) => {
        try {
          const presence = JSON.parse(msg.body);
          console.log("[CollaborationContext] 👥 Presence:", presence);
          handlePresenceUpdate(presence);
        } catch (e) {
          console.error("[CollaborationContext] Presence parse error:", e);
        }
      });
      subscriptionsRef.current.set("presence", presenceSub);

      // Locks
      const lockSub = client.subscribe(`/topic/locks/${projectId}`, (msg) => {
        try {
          const lock = JSON.parse(msg.body);
          console.log("[CollaborationContext] 🔒 Lock:", lock);
          handleLockUpdate(lock);
        } catch (e) {
          console.error("[CollaborationContext] Lock parse error:", e);
        }
      });
      subscriptionsRef.current.set("locks", lockSub);

      // Import status
      const importSub = client.subscribe(`/topic/import/${projectId}`, (msg) => {
        try {
          const status = JSON.parse(msg.body);
          console.log("[CollaborationContext] 📦 Import status:", status);
          window.dispatchEvent(new CustomEvent("importStatusUpdate", { detail: status }));
        } catch (e) {
          console.error("[CollaborationContext] Import parse error:", e);
        }
      });
      subscriptionsRef.current.set("import", importSub);

      // Cursors
      const cursorSub = client.subscribe(`/topic/cursor/${projectId}`, (msg) => {
        try {
          const cursor = JSON.parse(msg.body);
          if (cursor.userId === userId) return;
          window.dispatchEvent(new CustomEvent("remoteCursorUpdate", { detail: cursor }));
        } catch (e) {
          console.error("[CollaborationContext] Cursor parse error:", e);
        }
      });
      subscriptionsRef.current.set("cursor", cursorSub);

      // Send USER_JOINED presence
      client.publish({
        destination: `/app/collab/${projectId}/presence`,
        body: JSON.stringify({
          type: "USER_JOINED",
          projectId,
          userId,
          username,
          timestamp: Date.now(),
        }),
      });

      // Fetch existing active users
      const baseUrl = getBaseUrl();
      fetch(`${baseUrl}/api/collab-graph/${projectId}/active-users`)
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

      console.log(`[CollaborationContext] 🔗 Joined project topics: ${projectId}`);
    },
    [user],
  );

  // ─── VS Code mode: message bridge ───
  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;

      console.log("[CollaborationContext] 📨 Received message:", message.type, message);

      switch (message.type) {
        case "collaborationStatus":
          console.log("[CollaborationContext] ✅ Updating connection status to:", message.connected);
          setState((prev) => {
            const wasDisconnected = !prev.connected;
            const isNowConnected = message.connected;

            // If reconnecting after a disconnection, dispatch event to refresh data
            if (wasDisconnected && isNowConnected) {
              console.log("[CollaborationContext] 🔄 Reconnected! Dispatching refresh event...");
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
          console.log("[CollaborationContext] 🔄 Rollback event received:", message);
          // Dispatch a custom event that Dashboard can listen to
          const rollbackEvent = new CustomEvent("ontologyRollback", {
            detail: message,
          });
          window.dispatchEvent(rollbackEvent);
          break;

        case "shareNotification":
          console.log("[CollaborationContext] 📨 Share notification received:", message.notification);
          // Dispatch a custom event that Dashboard can listen to refresh file list
          const shareEvent = new CustomEvent("fileShared", {
            detail: message.notification,
          });
          window.dispatchEvent(shareEvent);
          break;
      }
    };

    window.addEventListener("message", handleMessage);

    console.log("[CollaborationContext] 🚀 Component mounted, requesting collaboration status...");

    // Request current collaboration status when component mounts
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

    // Notify on join/leave — skip for current user
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
          newLocks.delete(lock.nodeId);
          break;
      }

      return {
        ...prev,
        locks: newLocks,
      };
    });
  }, []);

  const getEditActionDescription = (operationType: string): string => {
    const actionMap: Record<string, string> = {
      CLASS_ADDED: "added a class",
      CLASS_MODIFIED: "modified a class",
      CLASS_DELETED: "deleted a class",
      CLASS_RENAMED: "renamed a class",
      PROPERTY_ADDED: "added a property",
      PROPERTY_MODIFIED: "modified a property",
      PROPERTY_DELETED: "deleted a property",
      ANNOTATION_ADDED: "added an annotation",
      ANNOTATION_MODIFIED: "modified an annotation",
      ANNOTATION_DELETED: "deleted an annotation",
      SUBCLASS_ADDED: "added a subclass relationship",
      SUBCLASS_REMOVED: "removed a subclass relationship",
      INDIVIDUAL_ADDED: "added an individual",
      INDIVIDUAL_MODIFIED: "modified an individual",
      INDIVIDUAL_DELETED: "deleted an individual",
      // New types for axioms
      DISJOINT_ADDED: "made classes disjoint",
      DISJOINT_REMOVED: "removed disjoint axiom",
      EQUIVALENT_ADDED: "added equivalent class",
      EQUIVALENT_REMOVED: "removed equivalent class",
      // Metadata notifications
      IMPORT_ADDED: "added an import",
      IMPORT_REMOVED: "removed an import",
      ONTOLOGY_ANNOTATION_ADDED: "added an ontology annotation",
      ONTOLOGY_ANNOTATION_MODIFIED: "modified an ontology annotation",
      ONTOLOGY_ANNOTATION_DELETED: "deleted an ontology annotation",
      GCI_ADDED: "added a general class axiom",
      GCI_REMOVED: "removed a general class axiom",
      // SPARQL and revert notifications
      SPARQL_UPDATE: "executed a SPARQL update",
      CHANGE_REVERTED: "reverted a change",
      PROJECT_SAVED: "saved the project",
      // SWRL rule notifications
      SWRL_RULE_ADDED: "added a SWRL rule",
      SWRL_RULE_MODIFIED: "modified a SWRL rule",
      SWRL_RULE_DELETED: "deleted a SWRL rule",
    };
    return actionMap[operationType] || "made a change";
  };

  const addNotification = useCallback((notification: Omit<EditNotification, "id">) => {
    const id = `notif-${Date.now()}-${Math.random()}`;
    setState((prev) => {
      // Get user color from activeUsers
      const user = prev.activeUsers.get(notification.userId);
      const userColor = user?.color || notification.userColor;

      return {
        ...prev,
        notifications: [...prev.notifications, { ...notification, id, userColor }],
      };
    });

    // Auto-remove after 5 seconds
    setTimeout(() => {
      removeNotification(id);
    }, 5000);
  }, []);
  // Keep the ref current so handlePresenceUpdate (declared above) can call it
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

  const handleRemoteEdit = useCallback((edit: any) => {
    console.log("[CollaborationContext] 📝 Processing remote edit:", edit);

    // CRITICAL: Dispatch a custom event that the Dashboard/ontology tree can listen to
    // This ensures the UI updates instantly when receiving annotations or other changes
    const remoteEditEvent = new CustomEvent("remoteEditReceived", {
      detail: edit,
    });

    window.dispatchEvent(remoteEditEvent);
    console.log("[CollaborationContext] ✅ Dispatched remoteEditReceived event");

    // Add notification for remote edits and remove user from active users
    setState((prev) => {
      const id = `notif-${Date.now()}-${Math.random()}`;
      const notification: Omit<EditNotification, "id"> = {
        type: "info",
        message: `${edit.username} ${getEditActionDescription(edit.type)}`,
        userId: edit.userId,
        username: edit.username,
        userColor: "#888888",
        timestamp: edit.timestamp,
      };

      // Get user color from activeUsers
      const user = prev.activeUsers.get(notification.userId);
      const userColor = user?.color || notification.userColor;

      // Update last activity for the editing user (keep them in active users)
      const newUsers = new Map(prev.activeUsers);
      if (user) {
        newUsers.set(edit.userId, { ...user, lastActivity: edit.timestamp || Date.now() });
      }

      const newState = {
        ...prev,
        activeUsers: newUsers,
        notifications: [...prev.notifications, { ...notification, id, userColor }],
      };

      // Auto-remove after 5 seconds
      setTimeout(() => {
        setState((s) => ({
          ...s,
          notifications: s.notifications.filter((n) => n.id !== id),
        }));
      }, 5000);

      return newState;
    });

    console.log("[CollaborationContext] 📢 Added notification for remote edit");
  }, []);

  const setCurrentProject = useCallback(
    (projectId: string | null) => {
      currentProjectRef.current = projectId;
      setState((prev) => ({
        ...prev,
        currentProjectId: projectId,
      }));

      // Browser mode: join/leave project topics via direct WebSocket
      if (isBrowserMode() && stompClientRef.current?.connected) {
        if (projectId) {
          joinProjectTopics(stompClientRef.current, projectId);
        } else {
          // Leave: unsubscribe project topics (keep shares)
          subscriptionsRef.current.forEach((sub, key) => {
            if (key !== "shares") {
              sub.unsubscribe();
              subscriptionsRef.current.delete(key);
            }
          });
        }
      }
    },
    [joinProjectTopics],
  );

  const value: CollaborationContextType = useMemo(
    () => ({
      state,
      setCurrentProject,
      addNotification,
      removeNotification,
      clearNotifications,
    }),
    [state, setCurrentProject, addNotification, removeNotification, clearNotifications],
  );

  return <CollaborationContext.Provider value={value}>{children}</CollaborationContext.Provider>;
};

// Custom hook for using collaboration context
export const useCollaboration = (): CollaborationContextType => {
  const context = React.useContext(CollaborationContext);
  if (!context) {
    throw new Error("useCollaboration must be used within a CollaborationProvider");
  }
  return context;
};
