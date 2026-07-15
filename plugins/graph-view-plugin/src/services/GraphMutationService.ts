/**
 * Graph Mutation Service
 * Handles all graph modifications with optimistic updates
 */

import type { OntologyNode, OntologyEdge } from '../types';

export class GraphMutationService {
  private pendingOperations: Set<string> = new Set();

  constructor(private baseUrl: string = `${(window as any).API_BASE_URL || 'http://localhost:8082'}/api/ontology`) {}

  /**
   * Create a new node
   */
  async createNode(projectId: string, node: Partial<OntologyNode>): Promise<OntologyNode> {
    const operationId = `create-node-${Date.now()}`;
    this.pendingOperations.add(operationId);

    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/graph/nodes`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify(node)
      });

      if (!response.ok) {
        throw new Error(`Failed to create node: ${response.statusText}`);
      }

      return await response.json();
    } finally {
      this.pendingOperations.delete(operationId);
    }
  }

  /**
   * Update an existing node
   */
  async updateNode(
    projectId: string,
    nodeId: string,
    updates: Partial<OntologyNode>
  ): Promise<OntologyNode> {
    const operationId = `update-node-${nodeId}-${Date.now()}`;
    this.pendingOperations.add(operationId);

    try {
      const response = await fetch(
        `${this.baseUrl}/${projectId}/graph/nodes/${encodeURIComponent(nodeId)}`,
        {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          },
          body: JSON.stringify(updates)
        }
      );

      if (!response.ok) {
        throw new Error(`Failed to update node: ${response.statusText}`);
      }

      return await response.json();
    } finally {
      this.pendingOperations.delete(operationId);
    }
  }

  /**
   * Delete a node
   */
  async deleteNode(projectId: string, nodeId: string, cascade: boolean = false): Promise<void> {
    const operationId = `delete-node-${nodeId}-${Date.now()}`;
    this.pendingOperations.add(operationId);

    try {
      const response = await fetch(
        `${this.baseUrl}/${projectId}/graph/nodes/${encodeURIComponent(nodeId)}?cascade=${cascade}`,
        {
          method: 'DELETE',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );

      if (!response.ok) {
        throw new Error(`Failed to delete node: ${response.statusText}`);
      }
    } finally {
      this.pendingOperations.delete(operationId);
    }
  }

  /**
   * Create a new edge
   */
  async createEdge(projectId: string, edge: Partial<OntologyEdge>): Promise<OntologyEdge> {
    const operationId = `create-edge-${Date.now()}`;
    this.pendingOperations.add(operationId);

    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/graph/edges`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify(edge)
      });

      if (!response.ok) {
        throw new Error(`Failed to create edge: ${response.statusText}`);
      }

      return await response.json();
    } finally {
      this.pendingOperations.delete(operationId);
    }
  }

  /**
   * Update an existing edge
   */
  async updateEdge(
    projectId: string,
    edgeId: string,
    updates: Partial<OntologyEdge>
  ): Promise<OntologyEdge> {
    const operationId = `update-edge-${edgeId}-${Date.now()}`;
    this.pendingOperations.add(operationId);

    try {
      const response = await fetch(
        `${this.baseUrl}/${projectId}/graph/edges/${encodeURIComponent(edgeId)}`,
        {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          },
          body: JSON.stringify(updates)
        }
      );

      if (!response.ok) {
        throw new Error(`Failed to update edge: ${response.statusText}`);
      }

      return await response.json();
    } finally {
      this.pendingOperations.delete(operationId);
    }
  }

  /**
   * Delete an edge
   */
  async deleteEdge(projectId: string, edgeId: string): Promise<void> {
    const operationId = `delete-edge-${edgeId}-${Date.now()}`;
    this.pendingOperations.add(operationId);

    try {
      const response = await fetch(
        `${this.baseUrl}/${projectId}/graph/edges/${encodeURIComponent(edgeId)}`,
        {
          method: 'DELETE',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );

      if (!response.ok) {
        throw new Error(`Failed to delete edge: ${response.statusText}`);
      }
    } finally {
      this.pendingOperations.delete(operationId);
    }
  }

  /**
   * Batch operations for better performance
   */
  async batchCreate(
    projectId: string,
    operations: Array<{
      type: 'node' | 'edge';
      data: Partial<OntologyNode> | Partial<OntologyEdge>;
    }>
  ): Promise<{
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }> {
    const operationId = `batch-create-${Date.now()}`;
    this.pendingOperations.add(operationId);

    try {
      const response = await fetch(`${this.baseUrl}/${projectId}/graph/batch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify({ operations })
      });

      if (!response.ok) {
        throw new Error(`Batch operation failed: ${response.statusText}`);
      }

      return await response.json();
    } finally {
      this.pendingOperations.delete(operationId);
    }
  }

  /**
   * Move nodes (update positions)
   */
  async updateNodePositions(
    projectId: string,
    positions: Array<{ nodeId: string; x: number; y: number }>
  ): Promise<void> {
    try {
      await fetch(`${this.baseUrl}/${projectId}/graph/positions`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('authToken')}`
        },
        body: JSON.stringify({ positions })
      });
    } catch (error) {
      console.error('[GraphMutationService] Failed to update positions:', error);
    }
  }

  /**
   * Check if operations are pending
   */
  hasPendingOperations(): boolean {
    return this.pendingOperations.size > 0;
  }
}

export const graphMutationService = new GraphMutationService();
