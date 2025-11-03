// services/SwrlApiService.ts

export interface ValidationResult {
  valid: boolean;
  errorMessage: string | null;
  suggestions: string[];
}

export interface SwrlRule {
  id: string;
  projectId: string;
  ruleName: string;
  ruleText: string;
  comment?: string;
  category?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRuleRequest {
  ruleName: string;
  ruleText: string;
  comment?: string;
  category?: string;
}

export interface UpdateRuleRequest {
  ruleText?: string;
  comment?: string;
  enabled?: boolean;
  category?: string;
}

export interface InferredAxiom {
  axiomType: string;
  description: string;
  readable: string;
}

export interface ExecutionResponse {
  success: boolean;
  executionTimeMs: number;
  inferredAxiomsCount: number;
  totalRulesExecuted: number;
  inferredAxioms: InferredAxiom[];
  errorMessage: string | null;
}

class SwrlApiService {
  private baseUrl: string;

  constructor(baseUrl: string = '/api/swrl') {
    this.baseUrl = baseUrl;
  }

  /**
   * Validate a SWRL rule before creating it
   */
  async validateRule(projectId: string, ruleText: string): Promise<ValidationResult> {
    const response = await fetch(`${this.baseUrl}/${projectId}/validate`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ ruleText }),
    });

    if (!response.ok) {
      throw new Error(`Validation failed: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Create a new SWRL rule
   */
  async createRule(projectId: string, request: CreateRuleRequest): Promise<SwrlRule> {
    const response = await fetch(`${this.baseUrl}/${projectId}/rules`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`Failed to create rule: ${error}`);
    }

    return response.json();
  }

  /**
   * Get all rules for a project
   */
  async getRules(projectId: string): Promise<SwrlRule[]> {
    const response = await fetch(`${this.baseUrl}/${projectId}/rules`);

    if (!response.ok) {
      throw new Error(`Failed to fetch rules: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Get a specific rule by ID
   */
  async getRule(projectId: string, ruleId: string): Promise<SwrlRule> {
    const response = await fetch(`${this.baseUrl}/${projectId}/rules/${ruleId}`);

    if (!response.ok) {
      throw new Error(`Failed to fetch rule: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Update an existing rule
   */
  async updateRule(
    projectId: string,
    ruleId: string,
    request: UpdateRuleRequest
  ): Promise<SwrlRule> {
    const response = await fetch(`${this.baseUrl}/${projectId}/rules/${ruleId}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`Failed to update rule: ${error}`);
    }

    return response.json();
  }

  /**
   * Delete a rule
   */
  async deleteRule(projectId: string, ruleId: string): Promise<void> {
    const response = await fetch(`${this.baseUrl}/${projectId}/rules/${ruleId}`, {
      method: 'DELETE',
    });

    if (!response.ok) {
      throw new Error(`Failed to delete rule: ${response.statusText}`);
    }
  }

  /**
   * Execute all enabled rules for a project
   */
  async executeRules(projectId: string): Promise<ExecutionResponse> {
    const response = await fetch(`${this.baseUrl}/${projectId}/execute`, {
      method: 'POST',
    });

    if (!response.ok) {
      throw new Error(`Failed to execute rules: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Clear the SWRL engine cache for a project
   */
  async clearCache(projectId: string): Promise<void> {
    const response = await fetch(`${this.baseUrl}/${projectId}/cache/clear`, {
      method: 'POST',
    });

    if (!response.ok) {
      throw new Error(`Failed to clear cache: ${response.statusText}`);
    }
  }
}

// Export singleton instance
export const swrlApiService = new SwrlApiService();
export default swrlApiService;