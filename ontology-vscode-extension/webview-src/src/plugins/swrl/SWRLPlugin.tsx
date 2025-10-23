import React, { useState, useEffect } from 'react';
import { Code, Play, Plus, Trash2, Edit2, CheckCircle, XCircle, AlertCircle, Loader2 } from 'lucide-react';
import apiClient from '../../services/apiClient';

interface SWRLRule {
  id: string;
  projectId: string;
  ruleName: string;
  ruleText: string;
  enabled: boolean;
  comment?: string;
  category?: string;
  createdAt: string;
  updatedAt: string;
}

interface ValidationResult {
  valid: boolean;
  errorMessage?: string;
  suggestions: string[];
}

interface ExecutionResult {
  success: boolean;
  executionTimeMs: number;
  inferredAxiomsCount: number;
  rulesExecuted: number;
  inferredAxioms: InferredAxiom[];
  message?: string;
}

interface InferredAxiom {
  axiomType: string;
  description: string;
  readable: string;
}

const SWRLEditor: React.FC<{ projectId: string }> = ({ projectId }) => {
  const [rules, setRules] = useState<SWRLRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [executing, setExecuting] = useState(false);
  const [validating, setValidating] = useState(false);
  
  // Current rule being edited
  const [currentRule, setCurrentRule] = useState({
    ruleName: '',
    ruleText: '',
    comment: '',
    category: 'inference'
  });
  
  const [editingRuleId, setEditingRuleId] = useState<string | null>(null);
  const [validationResult, setValidationResult] = useState<ValidationResult | null>(null);
  const [executionResult, setExecutionResult] = useState<ExecutionResult | null>(null);
  const [showResults, setShowResults] = useState(false);

  useEffect(() => {
    if (projectId) {
      fetchRules();
    }
  }, [projectId]);

  const fetchRules = async () => {
    try {
      setLoading(true);
      const response = await apiClient.get(`/api/swrl/${projectId}/rules`);
      setRules(response.data);
    } catch (error) {
      console.error('Failed to fetch rules:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleValidate = async () => {
    if (!currentRule.ruleText.trim()) return;
    
    try {
      setValidating(true);
      const response = await apiClient.post(`/api/swrl/${projectId}/validate`, {
        ruleText: currentRule.ruleText
      });
      setValidationResult(response.data);
    } catch (error) {
      console.error('Validation failed:', error);
      setValidationResult({
        valid: false,
        errorMessage: 'Failed to validate rule',
        suggestions: []
      });
    } finally {
      setValidating(false);
    }
  };

  const handleSaveRule = async () => {
    if (!currentRule.ruleName.trim() || !currentRule.ruleText.trim()) {
      alert('Please provide both rule name and rule text');
      return;
    }

    try {
      if (editingRuleId) {
        await apiClient.put(`/api/swrl/${projectId}/rules/${editingRuleId}`, {
          ruleText: currentRule.ruleText,
          comment: currentRule.comment,
          enabled: true,
          category: currentRule.category
        });
      } else {
        await apiClient.post(`/api/swrl/${projectId}/rules`, currentRule);
      }
      
      setCurrentRule({
        ruleName: '',
        ruleText: '',
        comment: '',
        category: 'inference'
      });
      setEditingRuleId(null);
      setValidationResult(null);
      
      // Refresh rules list
      await fetchRules();
    } catch (error: unknown) {
      console.error('Failed to save rule:', error);
      let message = 'Failed to save rule';
      if (typeof error === 'string') {
        message = error;
      } else if (error instanceof Error) {
        message = error.message;
      } else if ((error as { response?: { data?: { message?: unknown } } })?.response?.data?.message && typeof (error as { response: { data: { message: unknown } } }).response.data.message === 'string') {
        message = (error as { response: { data: { message: string } } }).response.data.message;
      }
      alert(message);
    }
  };

  const handleEditRule = (rule: SWRLRule) => {
    setCurrentRule({
      ruleName: rule.ruleName,
      ruleText: rule.ruleText,
      comment: rule.comment || '',
      category: rule.category || 'inference'
    });
    setEditingRuleId(rule.id);
    setValidationResult(null);
  };

  const handleDeleteRule = async (ruleId: string) => {
    if (!confirm('Are you sure you want to delete this rule?')) return;
    
    try {
      await apiClient.delete(`/api/swrl/${projectId}/rules/${ruleId}`);
      await fetchRules();
    } catch (error) {
      console.error('Failed to delete rule:', error);
      alert('Failed to delete rule');
    }
  };

  const handleToggleRule = async (ruleId: string, enabled: boolean) => {
    try {
      await apiClient.put(`/api/swrl/${projectId}/rules/${ruleId}`, {
        enabled: !enabled
      });
      await fetchRules();
    } catch (error) {
      console.error('Failed to toggle rule:', error);
    }
  };

  const handleExecuteRules = async () => {
    try {
      setExecuting(true);
      setShowResults(false);
      const response = await apiClient.post(`/api/swrl/${projectId}/execute`);
      setExecutionResult(response.data);
      setShowResults(true);
    } catch (error) {
      console.error('Failed to execute rules:', error);
      alert('Failed to execute rules');
    } finally {
      setExecuting(false);
    }
  };

  const handleCancelEdit = () => {
    setCurrentRule({
      ruleName: '',
      ruleText: '',
      comment: '',
      category: 'inference'
    });
    setEditingRuleId(null);
    setValidationResult(null);
  };

  return (
    <div className="h-full flex flex-col bg-gray-50">
      {/* Header */}
      <div className="bg-gradient-to-r from-purple-600 to-indigo-600 text-white p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-white/20 rounded-lg flex items-center justify-center backdrop-blur">
              <Code size={24} />
            </div>
            <div>
              <h2 className="text-2xl font-bold">SWRL Rules Editor</h2>
              <p className="text-purple-100 text-sm">Create and execute Semantic Web Rule Language rules</p>
            </div>
          </div>
          <button
            onClick={handleExecuteRules}
            disabled={executing || rules.filter(r => r.enabled).length === 0}
            className="flex items-center gap-2 px-6 py-3 bg-white text-purple-600 rounded-lg font-semibold hover:bg-purple-50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {executing ? (
              <>
                <Loader2 size={18} className="animate-spin" />
                Executing...
              </>
            ) : (
              <>
                <Play size={18} />
                Execute All Rules ({rules.filter(r => r.enabled).length})
              </>
            )}
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-6 space-y-6">
        {/* Rule Editor */}
        <div className="bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="p-4 border-b border-gray-200 bg-gray-50">
            <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
              <Edit2 size={18} />
              {editingRuleId ? 'Edit Rule' : 'Create New Rule'}
            </h3>
          </div>

          <div className="p-6 space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Rule Name *
                </label>
                <input
                  type="text"
                  value={currentRule.ruleName}
                  onChange={(e) => setCurrentRule({ ...currentRule, ruleName: e.target.value })}
                  disabled={!!editingRuleId}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent disabled:bg-gray-100"
                  placeholder="e.g., PersonWithParent"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Category
                </label>
                <select
                  value={currentRule.category}
                  onChange={(e) => setCurrentRule({ ...currentRule, category: e.target.value })}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                >
                  <option value="inference">Inference</option>
                  <option value="validation">Validation</option>
                  <option value="classification">Classification</option>
                  <option value="transformation">Transformation</option>
                </select>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                SWRL Rule *
              </label>
              <textarea
                value={currentRule.ruleText}
                onChange={(e) => {
                  setCurrentRule({ ...currentRule, ruleText: e.target.value });
                  setValidationResult(null);
                }}
                className="w-full h-32 px-3 py-2 border border-gray-300 rounded-lg font-mono text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                placeholder="Person(?p) ^ hasParent(?p, ?parent) -> hasAncestor(?p, ?parent)"
              />
              <p className="text-xs text-gray-500 mt-1">
                Use ^ for AND, -&gt; for implies, ?x for variables
              </p>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Comment
              </label>
              <input
                type="text"
                value={currentRule.comment}
                onChange={(e) => setCurrentRule({ ...currentRule, comment: e.target.value })}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                placeholder="Description of what this rule does"
              />
            </div>

            {/* Validation Result */}
            {validationResult && (
              <div className={`p-4 rounded-lg ${validationResult.valid ? 'bg-green-50 border border-green-200' : 'bg-red-50 border border-red-200'}`}>
                <div className="flex items-start gap-2">
                  {validationResult.valid ? (
                    <CheckCircle size={20} className="text-green-600 flex-shrink-0 mt-0.5" />
                  ) : (
                    <XCircle size={20} className="text-red-600 flex-shrink-0 mt-0.5" />
                  )}
                  <div className="flex-1">
                    <p className={`font-medium ${validationResult.valid ? 'text-green-900' : 'text-red-900'}`}>
                      {validationResult.valid ? 'Rule is valid!' : 'Validation Failed'}
                    </p>
                    {validationResult.errorMessage && (
                      <p className="text-sm text-red-700 mt-1">{validationResult.errorMessage}</p>
                    )}
                    {validationResult.suggestions.length > 0 && (
                      <ul className="text-sm text-red-700 mt-2 space-y-1">
                        {validationResult.suggestions.map((suggestion, idx) => (
                          <li key={idx} className="flex items-start gap-2">
                            <AlertCircle size={14} className="flex-shrink-0 mt-0.5" />
                            {suggestion}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                </div>
              </div>
            )}

            <div className="flex gap-2">
              <button
                onClick={handleValidate}
                disabled={validating || !currentRule.ruleText.trim()}
                className="px-4 py-2 border border-purple-600 text-purple-600 rounded-lg hover:bg-purple-50 font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {validating ? 'Validating...' : 'Validate'}
              </button>
              <button
                onClick={handleSaveRule}
                disabled={!currentRule.ruleName.trim() || !currentRule.ruleText.trim()}
                className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus size={16} />
                {editingRuleId ? 'Update Rule' : 'Add Rule'}
              </button>
              {editingRuleId && (
                <button
                  onClick={handleCancelEdit}
                  className="px-4 py-2 border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-50 font-medium transition-colors"
                >
                  Cancel
                </button>
              )}
            </div>
          </div>
        </div>

        {/* Execution Results */}
        {showResults && executionResult && (
          <div className="bg-white rounded-lg border border-gray-200 shadow-sm">
            <div className="p-4 border-b border-gray-200 bg-gray-50">
              <h3 className="text-lg font-bold text-gray-900">Execution Results</h3>
            </div>
            <div className="p-6">
              <div className="grid grid-cols-4 gap-4 mb-4">
                <div className="bg-blue-50 p-4 rounded-lg">
                  <p className="text-sm text-blue-600 font-medium">Status</p>
                  <p className="text-2xl font-bold text-blue-900">
                    {executionResult.success ? 'Success' : 'Failed'}
                  </p>
                </div>
                <div className="bg-purple-50 p-4 rounded-lg">
                  <p className="text-sm text-purple-600 font-medium">Execution Time</p>
                  <p className="text-2xl font-bold text-purple-900">
                    {executionResult.executionTimeMs}ms
                  </p>
                </div>
                <div className="bg-green-50 p-4 rounded-lg">
                  <p className="text-sm text-green-600 font-medium">Rules Executed</p>
                  <p className="text-2xl font-bold text-green-900">
                    {executionResult.rulesExecuted}
                  </p>
                </div>
                <div className="bg-orange-50 p-4 rounded-lg">
                  <p className="text-sm text-orange-600 font-medium">Inferred Axioms</p>
                  <p className="text-2xl font-bold text-orange-900">
                    {executionResult.inferredAxiomsCount}
                  </p>
                </div>
              </div>

              {executionResult.inferredAxioms.length > 0 && (
                <div>
                  <h4 className="font-semibold text-gray-900 mb-2">Inferred Axioms</h4>
                  <div className="space-y-2 max-h-64 overflow-y-auto">
                    {executionResult.inferredAxioms.slice(0, 20).map((axiom, idx) => (
                      <div key={idx} className="p-3 bg-gray-50 rounded border border-gray-200">
                        <div className="flex items-center gap-2 mb-1">
                          <span className="text-xs font-semibold text-purple-600 bg-purple-100 px-2 py-0.5 rounded">
                            {axiom.axiomType}
                          </span>
                        </div>
                        <p className="text-sm font-mono text-gray-700">{axiom.readable}</p>
                      </div>
                    ))}
                  </div>
                  {executionResult.inferredAxioms.length > 20 && (
                    <p className="text-sm text-gray-500 mt-2">
                      Showing 20 of {executionResult.inferredAxiomsCount} inferred axioms
                    </p>
                  )}
                </div>
              )}
            </div>
          </div>
        )}

        {/* Rules List */}
        <div className="bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="p-4 border-b border-gray-200 bg-gray-50">
            <h3 className="text-lg font-bold text-gray-900">Saved Rules ({rules.length})</h3>
          </div>

          {loading ? (
            <div className="p-8 flex items-center justify-center">
              <Loader2 size={32} className="animate-spin text-purple-600" />
            </div>
          ) : rules.length === 0 ? (
            <div className="p-8 text-center text-gray-500">
              <Code size={48} className="mx-auto mb-3 opacity-30" />
              <p>No rules created yet</p>
            </div>
          ) : (
            <div className="divide-y divide-gray-200">
              {rules.map((rule) => (
                <div
                  key={rule.id}
                  className={`p-4 hover:bg-gray-50 transition-colors ${!rule.enabled ? 'opacity-60' : ''}`}
                >
                  <div className="flex items-start justify-between mb-2">
                    <div className="flex-1">
                      <div className="flex items-center gap-2 mb-1">
                        <h4 className="font-semibold text-gray-900">{rule.ruleName}</h4>
                        {rule.category && (
                          <span className="text-xs bg-gray-100 text-gray-600 px-2 py-0.5 rounded">
                            {rule.category}
                          </span>
                        )}
                        {rule.enabled ? (
                          <CheckCircle size={16} className="text-green-600" />
                        ) : (
                          <XCircle size={16} className="text-gray-400" />
                        )}
                      </div>
                      <p className="text-sm font-mono text-gray-600 bg-gray-50 p-2 rounded">
                        {rule.ruleText}
                      </p>
                      {rule.comment && (
                        <p className="text-sm text-gray-500 mt-1 italic">{rule.comment}</p>
                      )}
                    </div>
                    <div className="flex items-center gap-1 ml-4">
                      <button
                        onClick={() => handleToggleRule(rule.id, rule.enabled)}
                        className="p-2 hover:bg-gray-100 rounded transition-colors"
                        title={rule.enabled ? 'Disable' : 'Enable'}
                      >
                        {rule.enabled ? (
                          <CheckCircle size={18} className="text-green-600" />
                        ) : (
                          <XCircle size={18} className="text-gray-400" />
                        )}
                      </button>
                      <button
                        onClick={() => handleEditRule(rule)}
                        className="p-2 hover:bg-gray-100 rounded transition-colors text-blue-600"
                        title="Edit"
                      >
                        <Edit2 size={18} />
                      </button>
                      <button
                        onClick={() => handleDeleteRule(rule.id)}
                        className="p-2 hover:bg-red-50 rounded transition-colors text-red-600"
                        title="Delete"
                      >
                        <Trash2 size={18} />
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default SWRLEditor;