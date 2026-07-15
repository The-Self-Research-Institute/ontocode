// services/swrlApiService.ts
import apiClient from './apiClient';

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

  private path(suffix: string) {
    return `${this.baseUrl}${suffix}`;
  }

  // ===== CRUD & Actions (UNWRAPPED responses) =====

  async validateRule(projectId: string, ruleText: string): Promise<ValidationResult> {
    return apiClient.post<ValidationResult>(
      this.path(`/${encodeURIComponent(projectId)}/validate`),
      { ruleText }
    );
  }

  async createRule(projectId: string, req: CreateRuleRequest): Promise<SwrlRule> {
    return apiClient.post<SwrlRule>(
      this.path(`/${encodeURIComponent(projectId)}/rules`),
      req
    );
  }

  async getRules(projectId: string): Promise<SwrlRule[]> {
    return apiClient.get<SwrlRule[]>(
      this.path(`/${encodeURIComponent(projectId)}/rules`)
    );
  }

  async getRule(projectId: string, ruleId: string): Promise<SwrlRule> {
    return apiClient.get<SwrlRule>(
      this.path(`/${encodeURIComponent(projectId)}/rules/${encodeURIComponent(ruleId)}`)
    );
  }

  async updateRule(projectId: string, ruleId: string, req: UpdateRuleRequest): Promise<SwrlRule> {
    // If your extension proxy doesn’t have apiPut, your apiClient.put should tunnel via POST + X-HTTP-Method-Override.
    return apiClient.put<SwrlRule>(
      this.path(`/${encodeURIComponent(projectId)}/rules/${encodeURIComponent(ruleId)}`),
      req
    );
  }

  async deleteRule(projectId: string, ruleId: string): Promise<void> {
    await apiClient.delete<void>(
      this.path(`/${encodeURIComponent(projectId)}/rules/${encodeURIComponent(ruleId)}`)
    );
  }

  async executeRules(projectId: string): Promise<ExecutionResponse> {
    return apiClient.post<ExecutionResponse>(
      this.path(`/${encodeURIComponent(projectId)}/execute`)
    );
  }

  async clearCache(projectId: string): Promise<void> {
    await apiClient.post<void>(
      this.path(`/${encodeURIComponent(projectId)}/cache/clear`)
    );
  }
}

export const swrlApiService = new SwrlApiService();
export default swrlApiService;
