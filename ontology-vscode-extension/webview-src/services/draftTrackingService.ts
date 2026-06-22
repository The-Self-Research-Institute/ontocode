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

/**
 * Service for managing draft changes in the ontology editor.
 * Drafts are tracked but not applied to GraphDB until explicitly saved.
 */
export const draftTrackingService = {
  
  /**
   * Record draft operations (doesn't apply to GraphDB)
   */
  async recordDrafts(projectId: string, operations: MutationOp[], userId?: string, username?: string, sessionId?: string): Promise<void> {
    await apiClient.post(`/api/ontology/${projectId}/drafts`, {
      ops: operations,
      userId: userId || 'anonymous',
      username: username || 'Anonymous',
      sessionId: sessionId || this.generateSessionId()
    });
  },
  
  /**
   * Get all unapplied drafts for a project
   */
  async getDrafts(projectId: string): Promise<DraftChange[]> {
    const response = await apiClient.get(`/api/ontology/${projectId}/drafts`);
    return response.data.drafts;
  },
  
  /**
   * Get draft statistics (optionally scoped to one user's private draft)
   */
  async getDraftStats(projectId: string, userId?: string): Promise<DraftStatistics> {
    try {
      console.log(`[draftTrackingService] Getting draft stats for project: ${projectId}`, userId);
      const params = userId ? { userId } : undefined;
      const response = await apiClient.get(`/api/ontology/${projectId}/drafts/stats`, params);
      
      console.log('[draftTrackingService] getDraftStats response:', response);
      
      // Handle both direct response and response.data (VS Code proxy vs direct HTTP)
      const data = response.data || response;
      
      if (!data) {
        console.error('[draftTrackingService] No response data:', response);
        throw new Error('No response from server');
      }
      
      // Extract just the statistics, excluding wrapper fields
      const { success, projectId: pid, ...stats } = data;
      console.log('[draftTrackingService] Extracted stats:', stats);
      return stats as DraftStatistics;
    } catch (error) {
      console.error('[draftTrackingService] getDraftStats failed:', error);
      throw error;
    }
  },
  
  /**
   * Preview publish conflicts before save.
   */
  async getPublishPreview(projectId: string, userId: string): Promise<Record<string, unknown>> {
    const response = await apiClient.get(`/api/ontology/${projectId}/drafts/publish-preview`, { userId });
    return (response.data || response) as Record<string, unknown>;
  },

  /**
   * Apply drafts for one user to GraphDB (used during save).
   * Pass merge=true with per-conflict resolutions for three-way merge.
   */
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
    return response.data;
  },

  /**
   * Discard unapplied drafts (optionally for one user only)
   */
  async discardDrafts(
    projectId: string,
    userId?: string,
  ): Promise<{ success: boolean; discardedCount: number; message: string }> {
    const url = userId
      ? `/api/ontology/${projectId}/drafts?userId=${encodeURIComponent(userId)}`
      : `/api/ontology/${projectId}/drafts`;
    const response = await apiClient.delete(url);
    return response.data;
  },
  
  /**
   * Clear applied drafts (cleanup)
   */
  async clearAppliedDrafts(projectId: string): Promise<void> {
    await apiClient.delete(`/api/ontology/${projectId}/drafts/applied`);
  },

  /**
   * Initiate a copy-on-switch draft (copy main → draft graph asynchronously).
   * Returns immediately; poll getDraftCopyStatus until status === 'READY'.
   * Resolves with tripleCount + mainRevisionAtCopy on success.
   * Rejects if an import is in progress (409).
   */
  async initiateDraftCopy(projectId: string, userId: string): Promise<{ tripleCount: number; mainRevisionAtCopy: number }> {
    const response = await apiClient.post(`/api/ontology/${projectId}/draft/copy`, { userId });
    const data = response.data || response;
    if (!data.success) {
      throw new Error(data.reason || 'Failed to initiate draft copy');
    }
    return { tripleCount: data.tripleCount, mainRevisionAtCopy: data.mainRevisionAtCopy };
  },

  /**
   * Poll the status of an in-progress draft copy.
   * Returns 'COPYING' | 'READY' | 'FAILED' | 'NOT_FOUND'
   */
  async getDraftCopyStatus(projectId: string, userId: string): Promise<'COPYING' | 'READY' | 'FAILED' | 'NOT_FOUND'> {
    const response = await apiClient.get(
      `/api/ontology/${projectId}/draft/copy/status`,
      { userId }
    );
    const data = response.data || response;
    return data.status as 'COPYING' | 'READY' | 'FAILED' | 'NOT_FOUND';
  },
  
  /**
   * Fetch draft settings for a project (requireDraftForMembers, isOwner).
   */
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

  /**
   * Update requireDraftForMembers — owner only.
   */
  async setRequireDraftForMembers(projectId: string, userId: string, value: boolean): Promise<void> {
    await apiClient.put(`/api/ontology/${projectId}/draft/settings`, {
      userId,
      requireDraftForMembers: value,
    });
  },

  /**
   * Generate a unique session ID
   */
  generateSessionId(): string {
    return `session_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }
};
