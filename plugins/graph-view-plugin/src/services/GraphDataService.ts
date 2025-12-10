/**
 * Graph Data Service
 * Handles all data fetching, caching, and synchronization
 */

import type { OntologyNode, OntologyEdge, GraphFilters, GraphQuery, ReasoningResult } from '../types';

export class GraphDataService {
  private cache: Map<string, { data: any; timestamp: number }> = new Map();
  private readonly CACHE_TTL = 5 * 60 * 1000; // 5 minutes
  private abortController: AbortController | null = null;

  constructor(private baseUrl: string = `${(window as any).API_BASE_URL || 'http://localhost:8082'}/api/ontology`) {}

  /**
   * Fetch graph data with caching and performance optimization
   */
  async fetchGraphData(projectId: string, filters?: GraphFilters): Promise<{
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }> {
    const cacheKey = `graph-${projectId}-${JSON.stringify(filters || {})}`;
    const cached = this.getFromCache(cacheKey);
    if (cached) {
      console.log('[GraphDataService] Using cached data');
      return cached;
    }

    // Cancel previous request if still pending
    this.abortController?.abort();
    this.abortController = new AbortController();

    try {
      const url = new URL(`${this.baseUrl}/${projectId}/graph`, window.location.origin);
      
      // Add filter parameters
      if (filters) {
        if (filters.nodeTypes && filters.nodeTypes.size > 0) {
          url.searchParams.append('nodeTypes', Array.from(filters.nodeTypes).join(','));
        }
        if (filters.edgeTypes && filters.edgeTypes.size > 0) {
          url.searchParams.append('edgeTypes', Array.from(filters.edgeTypes).join(','));
        }
        if (filters.searchQuery) {
          url.searchParams.append('search', filters.searchQuery);
        }
        if (filters.confidenceMin !== undefined) {
          url.searchParams.append('confidenceMin', filters.confidenceMin.toString());
        }
      }

      const response = await fetch(url.toString(), {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        signal: this.abortController.signal
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch graph data: ${response.statusText}`);
      }

      const data = await response.json();
      this.saveToCache(cacheKey, data);
      
      return {
        nodes: data.nodes || [],
        edges: data.edges || []
      };
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        console.log('[GraphDataService] Request aborted');
      } else {
        console.error('[GraphDataService] Error fetching graph data:', error);
      }
      throw error;
    }
  }

  /**
   * Execute graph query (pattern matching, path finding, etc.)
   */
  async executeQuery(projectId: string, query: GraphQuery): Promise<{
    nodes: OntologyNode[];
    edges: OntologyEdge[];
    metadata?: any;
  }> {
    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/graph/query`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify(query)
      });

      if (!response.ok) {
        throw new Error(`Query execution failed: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[GraphDataService] Query execution error:', error);
      throw error;
    }
  }

  /**
   * Perform reasoning and inference
   */
  async performReasoning(projectId: string, options?: {
    type?: 'consistency' | 'classification' | 'realization' | 'all';
    includeExplanations?: boolean;
  }): Promise<ReasoningResult> {
    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/reasoning`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify(options || {})
      });

      if (!response.ok) {
        throw new Error(`Reasoning failed: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[GraphDataService] Reasoning error:', error);
      throw error;
    }
  }

  /**
   * Get node neighbors (1-hop connections)
   */
  async getNodeNeighbors(projectId: string, nodeId: string, depth: number = 1): Promise<{
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }> {
    try {
      const response = await fetch(
        `${this.baseUrl}/${projectId}/graph/nodes/${encodeURIComponent(nodeId)}/neighbors?depth=${depth}`,
        {
          method: 'GET',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );

      if (!response.ok) {
        throw new Error(`Failed to fetch neighbors: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[GraphDataService] Error fetching neighbors:', error);
      throw error;
    }
  }

  /**
   * Find shortest path between two nodes
   */
  async findPath(
    projectId: string,
    fromNodeId: string,
    toNodeId: string,
    options?: { maxDepth?: number; algorithm?: 'dijkstra' | 'bfs' | 'astar' }
  ): Promise<{
    nodes: OntologyNode[];
    edges: OntologyEdge[];
    length: number;
  }> {
    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/graph/path`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify({
          from: fromNodeId,
          to: toNodeId,
          ...options
        })
      });

      if (!response.ok) {
        throw new Error(`Path finding failed: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[GraphDataService] Path finding error:', error);
      throw error;
    }
  }

  /**
   * Get semantic suggestions (AI-powered)
   */
  async getSuggestions(projectId: string, context: {
    nodeId?: string;
    query?: string;
    type?: 'synonym' | 'relation' | 'class' | 'property';
  }): Promise<Array<{
    type: string;
    suggestion: string;
    confidence: number;
    explanation: string;
  }>> {
    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/graph/suggestions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify(context)
      });

      if (!response.ok) {
        throw new Error(`Failed to get suggestions: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[GraphDataService] Suggestions error:', error);
      throw error;
    }
  }

  /**
   * Detect conflicts and duplicates
   */
  async detectConflicts(projectId: string): Promise<Array<{
    type: 'duplicate' | 'inconsistency' | 'redundancy';
    nodes: string[];
    description: string;
    severity: 'high' | 'medium' | 'low';
    autoFixAvailable: boolean;
  }>> {
    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/graph/conflicts`, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        }
      });

      if (!response.ok) {
        throw new Error(`Conflict detection failed: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[GraphDataService] Conflict detection error:', error);
      throw error;
    }
  }

  /**
   * Cache management
   */
  private getFromCache(key: string): any | null {
    const cached = this.cache.get(key);
    if (cached && Date.now() - cached.timestamp < this.CACHE_TTL) {
      return cached.data;
    }
    return null;
  }

  private saveToCache(key: string, data: any): void {
    this.cache.set(key, { data, timestamp: Date.now() });
  }

  clearCache(): void {
    this.cache.clear();
  }

  /**
   * Cancel ongoing requests
   */
  cancelRequests(): void {
    this.abortController?.abort();
    this.abortController = null;
  }
}

export const graphDataService = new GraphDataService();
