import apiClient from './apiClient';

export interface OntologyChange {
  id: string;
  projectId?: string;
  userId: string;
  username: string;
  timestamp: string;
  changeType: string;
  changeCategory?: string;
  entityType?: string;
  operationType?: string;
  entityIRI?: string;
  entityLabel?: string;
  oldValue?: string;
  newValue?: string;
  description?: string;
  comment?: string;
  sessionId?: string;
  reverted?: boolean;
  status?: string;
  hasConflict?: boolean;
  commentCount?: number;
}

function resolveActor(): { userId: string; username: string } {
  try {
    const token = localStorage.getItem('authToken');
    if (token) {
      const parts = token.split('.');
      if (parts.length === 3) {
        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        const userId = payload.userId || payload.id || payload.email || payload.sub || 'anonymous';
        const username = payload.sub || payload.email || 'Anonymous';
        return { userId, username };
      }
    }
  } catch {
    /* ignore */
  }
  return { userId: 'anonymous', username: 'Anonymous' };
}

/**
 * Service for accessing change history and tracking
 */
export const changeTrackingService = {
  
  /**
   * Get recent changes for a project
   */
  async getRecentChanges(projectId: string, count: number = 20): Promise<OntologyChange[]> {
    try {
      console.log(`[changeTrackingService] Getting recent changes for project: ${projectId}`);
      const response = await apiClient.get(`/api/ontology/${projectId}/changes/recent?count=${count}`);
      
      console.log('[changeTrackingService] Recent changes response:', response);
      
      // Handle both direct response and response.data (VS Code proxy vs direct HTTP)
      const data = response.data || response;
      
      if (!data || !data.success) {
        console.error('[changeTrackingService] Invalid response:', response);
        return [];
      }
      
      return data.changes || [];
    } catch (error) {
      console.error('[changeTrackingService] getRecentChanges failed:', error);
      return []; // Return empty array instead of throwing
    }
  },
  
  /**
   * Get full change history for a project
   */
  async getHistory(projectId: string, limit: number = 50): Promise<OntologyChange[]> {
    try {
      const response = await apiClient.get(`/api/ontology/${projectId}/changes/history?limit=${limit}`);
      const data = response.data || response;
      return data.changes || [];
    } catch (error) {
      console.error('[changeTrackingService] getHistory failed:', error);
      return [];
    }
  },
  
  /**
   * Get changes for a specific entity
   */
  async getEntityHistory(projectId: string, entityIRI: string): Promise<OntologyChange[]> {
    try {
      const response = await apiClient.get(`/api/ontology/${projectId}/changes/entity?entityIRI=${encodeURIComponent(entityIRI)}`);
      const data = response.data || response;
      return data.changes || [];
    } catch (error) {
      console.error('[changeTrackingService] getEntityHistory failed:', error);
      return [];
    }
  },
  
  /**
   * Get change statistics
   */
  async getStatistics(projectId: string): Promise<any> {
    try {
      const response = await apiClient.get(`/api/ontology/${projectId}/changes/stats`);
      const data = response.data || response;
      return data.statistics || {};
    } catch (error) {
      console.error('[changeTrackingService] getStatistics failed:', error);
      return {};
    }
  },

  async rollbackChange(
    projectId: string,
    payload: {
      changeId: string;
      changeType: string;
      action: string;
      entityIRI: string;
      entityLabel?: string;
      oldValue?: string;
      newValue?: string;
      userId?: string;
      username?: string;
    },
  ): Promise<{ success: boolean; error?: string; message?: string }> {
    try {
      const response = await apiClient.post(
        `/api/ontology/${encodeURIComponent(projectId)}/changes/rollback`,
        payload,
      );
      const data = response.data || response;
      return {
        success: data.success !== false,
        error: data.error,
        message: data.message,
      };
    } catch (error) {
      console.error('[changeTrackingService] rollbackChange failed:', error);
      return { success: false, error: 'Rollback request failed' };
    }
  },

  async approveChange(projectId: string, changeId: string): Promise<{ success: boolean; error?: string }> {
    try {
      const actor = resolveActor();
      const response = await apiClient.post(
        `/api/ontology/${encodeURIComponent(projectId)}/changes/${encodeURIComponent(changeId)}/approve`,
        { userId: actor.userId, username: actor.username },
      );
      const data = response.data || response;
      return { success: data.success !== false, error: data.error };
    } catch (error) {
      console.error('[changeTrackingService] approveChange failed:', error);
      return { success: false, error: 'Approve request failed' };
    }
  },

  async rejectChange(projectId: string, changeId: string): Promise<{ success: boolean; error?: string }> {
    try {
      const actor = resolveActor();
      const response = await apiClient.post(
        `/api/ontology/${encodeURIComponent(projectId)}/changes/${encodeURIComponent(changeId)}/reject`,
        { userId: actor.userId, username: actor.username },
      );
      const data = response.data || response;
      return { success: data.success !== false, error: data.error };
    } catch (error) {
      console.error('[changeTrackingService] rejectChange failed:', error);
      return { success: false, error: 'Reject request failed' };
    }
  },

  async revertChange(
    projectId: string,
    changeId: string,
  ): Promise<{ success: boolean; error?: string; message?: string }> {
    try {
      const actor = resolveActor();
      const response = await apiClient.post(
        `/api/ontology/${encodeURIComponent(projectId)}/changes/${encodeURIComponent(changeId)}/revert`,
        { userId: actor.userId, username: actor.username },
      );
      const data = response.data || response;
      return { success: data.success !== false, error: data.error, message: data.message };
    } catch (error) {
      console.error('[changeTrackingService] revertChange failed:', error);
      return { success: false, error: 'Revert request failed' };
    }
  },

  async addComment(
    projectId: string,
    changeId: string,
    text: string,
  ): Promise<{ success: boolean; error?: string }> {
    try {
      const actor = resolveActor();
      const response = await apiClient.post(
        `/api/ontology/${encodeURIComponent(projectId)}/changes/${encodeURIComponent(changeId)}/comments`,
        { text, userId: actor.userId, username: actor.username },
      );
      const data = response.data || response;
      return { success: data.success !== false, error: data.error };
    } catch (error) {
      console.error('[changeTrackingService] addComment failed:', error);
      return { success: false, error: 'Comment request failed' };
    }
  },

  async getChangeDetails(projectId: string, changeId: string): Promise<Record<string, unknown> | null> {
    try {
      const response = await apiClient.get(
        `/api/ontology/${encodeURIComponent(projectId)}/changes/${encodeURIComponent(changeId)}/details`,
      );
      const data = response.data || response;
      return data.success ? (data.change as Record<string, unknown>) : null;
    } catch (error) {
      console.error('[changeTrackingService] getChangeDetails failed:', error);
      return null;
    }
  },
};

export default changeTrackingService;
