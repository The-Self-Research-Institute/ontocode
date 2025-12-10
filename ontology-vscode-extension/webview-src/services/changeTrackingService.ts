import apiClient from './apiClient';

export interface OntologyChange {
  id: string;
  projectId: string;
  userId: string;
  username: string;
  timestamp: string;
  changeType: string;
  changeCategory: string;
  entityIRI?: string;
  entityLabel?: string;
  oldValue?: string;
  newValue?: string;
  description?: string;
  comment?: string;
  sessionId?: string;
  reverted: boolean;
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
  }
};

export default changeTrackingService;
