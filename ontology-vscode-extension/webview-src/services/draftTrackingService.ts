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
   * Get draft statistics
   */
  async getDraftStats(projectId: string): Promise<DraftStatistics> {
    try {
      console.log(`[draftTrackingService] Getting draft stats for project: ${projectId}`);
      const response = await apiClient.get(`/api/ontology/${projectId}/drafts/stats`);
      
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
   * Apply all drafts to GraphDB (used during save)
   */
  async applyDrafts(projectId: string): Promise<{ success: boolean; appliedCount: number; message: string }> {
    const response = await apiClient.post(`/api/ontology/${projectId}/drafts/apply`);
    return response.data;
  },
  
  /**
   * Discard all unapplied drafts
   */
  async discardDrafts(projectId: string): Promise<{ success: boolean; discardedCount: number; message: string }> {
    const response = await apiClient.delete(`/api/ontology/${projectId}/drafts`);
    return response.data;
  },
  
  /**
   * Clear applied drafts (cleanup)
   */
  async clearAppliedDrafts(projectId: string): Promise<void> {
    await apiClient.delete(`/api/ontology/${projectId}/drafts/applied`);
  },
  
  /**
   * Generate a unique session ID
   */
  generateSessionId(): string {
    return `session_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }
};
