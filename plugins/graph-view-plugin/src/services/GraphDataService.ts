/**
 * Graph Data Service
 * Handles all data fetching, caching, and synchronization
 */

import type { OntologyNode, OntologyEdge, GraphFilters, GraphQuery, ReasoningResult } from '../types';

export class GraphDataService {
  private cache: Map<string, { data: any; timestamp: number }> = new Map();
  private readonly CACHE_TTL = 5 * 60 * 1000; // 5 minutes
  private abortController: AbortController | null = null;
  private baseUrlOverride?: string;

  constructor(baseUrl?: string) {
    this.baseUrlOverride = baseUrl;
  }

  // Resolved per request, never captured at construction: the singleton is
  // created at module evaluation, before the desktop shell injects
  // __DESKTOP_API_URL__ (did-finish-load) — and the desktop proxy may bind a
  // non-default port, so the localhost fallback is only a last resort.
  private get apiRoot(): string {
    return (window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL || 'http://localhost:18085';
  }

  private get baseUrl(): string {
    return this.baseUrlOverride ?? `${this.apiRoot}/api/ontology`;
  }

  /**
   * Clear all caches
   */

  /**
   * Clear cache for specific project and force backend cache clear
   */
  async clearProjectCache(projectId: string): Promise<void> {
    // Clear local cache
    for (const key of Array.from(this.cache.keys())) {
      if (key.includes(projectId)) {
        this.cache.delete(key);
      }
    }

    // Clear backend cache
    try {
      const response = await fetch(`${this.apiRoot}/api/collab-graph/${projectId}/clear-cache`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        }
      });

      if (!response.ok) {
        console.error('[GraphDataService] Failed to clear backend cache:', response.statusText);
      } else {
        console.log('[GraphDataService] Backend cache cleared successfully');
      }
    } catch (error) {
      console.error('[GraphDataService] Error clearing backend cache:', error);
    }
  }

  /**
   * Fetch graph data with caching and performance optimization
   */
  async fetchGraphData(projectId: string, filters?: GraphFilters, forceReload: boolean = false): Promise<{
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }> {
    const cacheKey = `graph-${projectId}-${JSON.stringify(filters || {})}`;
    
    // Skip cache if forceReload
    if (!forceReload) {
      const cached = this.getFromCache(cacheKey);
      if (cached) {
        console.log('[GraphDataService] Using cached data');
        return cached;
      }
    } else {
      console.log('[GraphDataService] Force reload - bypassing cache');
      // Clear cache for this project
      await this.clearProjectCache(projectId);
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
