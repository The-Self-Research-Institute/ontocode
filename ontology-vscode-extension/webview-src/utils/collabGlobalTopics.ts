import { Client, IMessage, StompSubscription } from "@stomp/stompjs";

interface TopicFilters {
  shareIsNew: (n: any) => boolean;
  forbiddenDest: (payload: any) => string | null;
}

const relay = (event: string, pick: (payload: any) => any = (p) => p) => (msg: IMessage) => {
  try {
    const detail = pick(JSON.parse(msg.body));
    if (detail !== undefined) {
      window.dispatchEvent(new CustomEvent(event, { detail }));
    }
  } catch (e) {
    console.error(`[CollaborationContext] ${event} parse error:`, e);
  }
};

export function subscribeGlobalTopics(
  client: Client,
  subs: Map<string, StompSubscription>,
  user: { email?: string; workspaceId?: string } | null | undefined,
  filters: TopicFilters,
): void {
  const onShare = relay("fileShared", (n) => (filters.shareIsNew(n) ? n : undefined));
  if (user?.email) {
    subs.set("shares", client.subscribe(`/topic/shares/${user.email}`, onShare));
  }
  subs.set("userShares", client.subscribe("/user/queue/shares", onShare));
  subs.set("errors", client.subscribe("/user/queue/errors", (msg) => {
    try {
      const dest = filters.forbiddenDest(JSON.parse(msg.body));
      if (dest !== null) {
        console.warn("[CollaborationContext] Subscription not permitted:", dest);
      }
    } catch {
      return;
    }
  }));
  subs.set("queueStats", client.subscribe("/topic/queue/stats", relay("queueStatsUpdate", (p) => p?.queueStats || undefined)));
  if (user?.workspaceId) {
    subs.set("workspace", client.subscribe(`/topic/workspace/${user.workspaceId}`, relay("workspaceEvent")));
  }
}
