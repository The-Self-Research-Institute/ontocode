// hooks/useSwrlRules.ts

import { useState, useCallback, useEffect } from 'react';
import swrlApiService, {
  SwrlRule,
  CreateRuleRequest,
  UpdateRuleRequest,
  ExecutionResponse,
  ValidationResult,
} from '../services/swrlApiService';

interface UseSwrlRulesResult {
  rules: SwrlRule[];
  loading: boolean;
  error: string | null;
  validationResult: ValidationResult | null;
  executionResult: ExecutionResponse | null;
  
  loadRules: () => Promise<void>;
  validateRule: (ruleText: string) => Promise<ValidationResult>;
  createRule: (request: CreateRuleRequest) => Promise<SwrlRule>;
  updateRule: (ruleId: string, request: UpdateRuleRequest) => Promise<SwrlRule>;
  deleteRule: (ruleId: string) => Promise<void>;
  toggleRuleEnabled: (ruleId: string) => Promise<void>;
  executeRules: () => Promise<ExecutionResponse>;
  clearCache: () => Promise<void>;
  clearValidation: () => void;
  clearError: () => void;
}

export function useSwrlRules(projectId: string): UseSwrlRulesResult {
  const [rules, setRules] = useState<SwrlRule[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationResult, setValidationResult] = useState<ValidationResult | null>(null);
  const [executionResult, setExecutionResult] = useState<ExecutionResponse | null>(null);

  useEffect(() => {
    if (projectId) {
      loadRules();
    }
  }, [projectId]);

  const loadRules = useCallback(async () => {
    if (!projectId) return;
    
    setLoading(true);
    setError(null);
    
    try {
      const fetchedRules = await swrlApiService.getRules(projectId);
      setRules(fetchedRules);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to load rules';
      setError(errorMessage);
      console.error('Error loading rules:', err);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const validateRule = useCallback(async (ruleText: string): Promise<ValidationResult> => {
    if (!projectId) {
      throw new Error('Project ID is required');
    }

    setError(null);
    
    try {
      const result = await swrlApiService.validateRule(projectId, ruleText);
      setValidationResult(result);
      return result;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Validation failed';
      setError(errorMessage);
      throw err;
    }
  }, [projectId]);

  const createRule = useCallback(async (request: CreateRuleRequest): Promise<SwrlRule> => {
    if (!projectId) {
      throw new Error('Project ID is required');
    }

    setLoading(true);
    setError(null);
    
    try {
      const newRule = await swrlApiService.createRule(projectId, request);
      setRules(prev => [...prev, newRule]);
      return newRule;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to create rule';
      setError(errorMessage);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const updateRule = useCallback(async (
    ruleId: string,
    request: UpdateRuleRequest
  ): Promise<SwrlRule> => {
    if (!projectId) {
      throw new Error('Project ID is required');
    }

    setLoading(true);
    setError(null);
    
    try {
      const updatedRule = await swrlApiService.updateRule(projectId, ruleId, request);
      setRules(prev => prev.map(rule => rule.id === ruleId ? updatedRule : rule));
      return updatedRule;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to update rule';
      setError(errorMessage);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const deleteRule = useCallback(async (ruleId: string): Promise<void> => {
    if (!projectId) {
      throw new Error('Project ID is required');
    }

    setLoading(true);
    setError(null);
    
    try {
      await swrlApiService.deleteRule(projectId, ruleId);
      setRules(prev => prev.filter(rule => rule.id !== ruleId));
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to delete rule';
      setError(errorMessage);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const toggleRuleEnabled = useCallback(async (ruleId: string): Promise<void> => {
    const rule = rules.find(r => r.id === ruleId);
    if (!rule) {
      throw new Error('Rule not found');
    }

    await updateRule(ruleId, { enabled: !rule.enabled });
  }, [rules, updateRule]);

  const executeRules = useCallback(async (): Promise<ExecutionResponse> => {
    if (!projectId) {
      throw new Error('Project ID is required');
    }

    setLoading(true);
    setError(null);
    
    try {
      const result = await swrlApiService.executeRules(projectId);
      setExecutionResult(result);
      return result;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to execute rules';
      setError(errorMessage);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const clearCache = useCallback(async (): Promise<void> => {
    if (!projectId) {
      throw new Error('Project ID is required');
    }

    try {
      await swrlApiService.clearCache(projectId);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to clear cache';
      setError(errorMessage);
      throw err;
    }
  }, [projectId]);

  const clearValidation = useCallback(() => {
    setValidationResult(null);
  }, []);

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  return {
    rules,
    loading,
    error,
    validationResult,
    executionResult,
    loadRules,
    validateRule,
    createRule,
    updateRule,
    deleteRule,
    toggleRuleEnabled,
    executeRules,
    clearCache,
    clearValidation,
    clearError,
  };
}