import apiClient from './apiClient';
import { MutationOp } from './ontologyMutationService';

export interface DraftChange {
  id: string;
  projectId: string;
  userId: string;
  username: string;
  operationType: string;
  operationData: Record<string, any>;
  timestamp: string;
  sessionId: string;
  applied: boolean;
}

export interface DraftStatistics {
  totalDrafts: number;
  unappliedDrafts: number;
  appliedDrafts: number;
  operationTypeCounts: Record<string, number>;
  oldestDraft?: string;
  newestDraft?: string;
}

export const draftTrackingService = {

  async recordDrafts(projectId: string, operations: MutationOp[], userId?: string, username?: string, sessionId?: string): Promise<void> {
    await apiClient.post(`/api/ontology/${projectId}/drafts`, {
      ops: operations,
      userId: userId || 'anonymous',
      username: username || 'Anonymous',
      sessionId: sessionId || this.generateSessionId()
    });
  },

  async getDrafts(projectId: string, userId?: string): Promise<DraftChange[]> {
    const params = userId ? { userId, _cb: Date.now() } : { _cb: Date.now() };
    const response = await apiClient.get(`/api/ontology/${projectId}/drafts`, params);
    const data = (response as any)?.data ?? response;
    return (data as any)?.drafts ?? [];
  },

  async getDraftStats(projectId: string, userId?: string): Promise<DraftStatistics> {
    try {

      const params: Record<string, any> = { _cb: Date.now() };
      if (userId) params.userId = userId;
      const response = await apiClient.get(`/api/ontology/${projectId}/drafts/stats`, params);

      const data: any = (response as any)?.data ?? response;

      if (!data) throw new Error('No response from server');

      const { success: _s, projectId: _pid, ...stats } = data;
      return stats as DraftStatistics;
    } catch (error) {
      console.error('[draftTrackingService] getDraftStats failed:', error);
      throw error;
    }
  },

  async getPublishPreview(projectId: string, userId: string): Promise<Record<string, unknown>> {
    const response = await apiClient.get(`/api/ontology/${projectId}/drafts/publish-preview`, { userId, _cb: Date.now() });
    return ((response as any)?.data ?? response) as Record<string, unknown>;
  },

  async applyDrafts(
    projectId: string,
    userId: string,
    force = false,
    merge = false,
    resolutions?: Record<string, { action: string; renameSuffix?: string }>,
  ): Promise<{ success: boolean; appliedCount: number; message: string }> {
    let url = `/api/ontology/${projectId}/drafts/apply?userId=${encodeURIComponent(userId)}`;
    if (force) url += '&force=true';
    if (merge) url += '&merge=true';
    const response = await apiClient.post(url, merge && resolutions ? resolutions : undefined);

    return ((response as any)?.data ?? response) as { success: boolean; appliedCount: number; message: string };
  },

  async discardDrafts(
    projectId: string,
    userId?: string,
  ): Promise<{ success: boolean; discardedCount: number; message: string }> {
    const url = userId
      ? `/api/ontology/${projectId}/drafts?userId=${encodeURIComponent(userId)}`
      : `/api/ontology/${projectId}/drafts`;
    const response = await apiClient.delete(url);
    return ((response as any)?.data ?? response) as { success: boolean; discardedCount: number; message: string };
  },

  async clearAppliedDrafts(projectId: string): Promise<void> {
    await apiClient.delete(`/api/ontology/${projectId}/drafts/applied`);
  },

  async initiateDraftCopy(projectId: string, userId: string): Promise<{ tripleCount: number; mainRevisionAtCopy: number }> {
    const response = await apiClient.post(`/api/ontology/${projectId}/draft/copy`, { userId });
    const data = response.data || response;
    if (!data.success) {
      throw new Error(data.reason || 'Failed to initiate draft copy');
    }
    return { tripleCount: data.tripleCount, mainRevisionAtCopy: data.mainRevisionAtCopy };
  },

  async getDraftCopyStatus(projectId: string, userId: string): Promise<'COPYING' | 'READY' | 'FAILED' | 'NOT_FOUND'> {
    const response = await apiClient.get(
      `/api/ontology/${projectId}/draft/copy/status`,
      { userId }
    );
    const data = response.data || response;
    return data.status as 'COPYING' | 'READY' | 'FAILED' | 'NOT_FOUND';
  },

  async getDraftSettings(projectId: string, userId: string): Promise<{
    requireDraftForMembers: boolean;
    isOwner: boolean;
  }> {
    const response = await apiClient.get(`/api/ontology/${projectId}/draft/settings`, { userId });
    const data = response.data || response;
    return {
      requireDraftForMembers: Boolean(data.requireDraftForMembers),
      isOwner: Boolean(data.isOwner),
    };
  },

  async setRequireDraftForMembers(projectId: string, userId: string, value: boolean): Promise<void> {
    await apiClient.put(`/api/ontology/${projectId}/draft/settings`, {
      userId,
      requireDraftForMembers: value,
    });
  },

  generateSessionId(): string {
    return `session_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }
};
