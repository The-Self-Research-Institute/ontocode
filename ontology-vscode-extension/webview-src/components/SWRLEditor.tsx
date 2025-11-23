import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Play, Save, Check, X, AlertCircle, Loader2, Eye, EyeOff, ChevronDown, ChevronRight } from 'lucide-react';
import apiClient from '../services/apiClient';
import type { SwrlRule, ValidationResult as SwrlValidationResult, ExecutionResponse, PluginContext } from '../types'; //

// Debounce hook to prevent excessive validation API calls
function useDebounce(value: string, delay: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => { 
    const t = setTimeout(() => setDebounced(value), delay); 
    return () => clearTimeout(t); 
  }, [value, delay]);
  return debounced;
}

const SQWRLQueryPanel: React.FC<{ projectId: string; context: PluginContext }> = ({ projectId, context }) => {
  const [query, setQuery] = useState('Pizza(?p) ^ hasTopping(?p, ?t) -> sqWrl:select(?p, ?t)');
  const [results, setResults] = useState<{ columns: string[], rows: Record<string, any>[] } | null>(null);
  const [loading, setLoading] = useState(false);

  const execute = useCallback(async () => {
    setLoading(true);
    try {
      // This component is already wired to the correct API endpoint
      const res = await apiClient.post<{ columns: string[], rows: Record<string, any>[] }>(
        `/api/sqwrl/${projectId}/query`, 
        { query }
      );
      setResults(res);
      // context.notificationService.success('Query executed successfully');
    } catch (e) {
      console.error(e); 
      // context.notificationService.error('Failed to execute query');
    } finally { setLoading(false); }
  }, [projectId, query, context]);

  return (
    <div className="flex flex-col h-full bg-gray-50">
      <div className="p-4 border-b"><h3 className="text-lg font-semibold text-gray-800">SQWRL Query</h3></div>
      <div className="p-4 flex-grow flex flex-col gap-4">
        <textarea className="w-full h-32 p-3 font-mono text-sm border rounded-lg bg-white focus:ring-2 focus:ring-purple-500 text-black"
                  value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Enter SQWRL query…" />
        <button onClick={execute} disabled={loading} className="w-full flex items-center justify-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 text-sm">
          {loading ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />} Execute Query
        </button>
        <div className="flex-grow overflow-auto border rounded-lg bg-white">
          {!results ? <div className="h-full flex items-center justify-center text-gray-400">Query results will appear here.</div> : (
            <table className="min-w-full text-sm">
              <thead className="bg-gray-100"><tr>{results.columns.map(c => <th key={c} className="p-2 text-left font-semibold text-gray-600">{c}</th>)}</tr></thead>
              <tbody className="divide-y divide-gray-200">
              {results.rows.map((row, i) => (
                <tr key={i} className="hover:bg-gray-50">{results.columns.map(c => <td key={c} className="p-2 text-gray-700 whitespace-nowrap">{String(row[c] ?? '')}</td>)}</tr>
              ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
};

const ExecutionResultsPanel: React.FC<{ results: ExecutionResponse | null }> = ({ results }) => {
  const [showInferredAxioms, setShowInferredAxioms] = useState(true);

  if (!results) return (
    <div className="p-8 text-center text-gray-400">
      <AlertCircle size={48} className="mx-auto mb-4 opacity-50" />
      <p>Run rule execution to see results.</p>
    </div>
  );

  return (
    <div className="p-4 space-y-4">
      <div className="bg-white rounded-lg border p-4">
        <h3 className="font-semibold text-gray-800 mb-3">Execution Summary</h3>
        <div className="grid grid-cols-3 gap-4 text-sm">
          <div><div className="text-gray-500">Status</div><div className={`font-semibold ${results.success ? 'text-green-600' : 'text-red-600'}`}>{results.success ? '✓ Success' : '✗ Failed'}</div></div>
          <div><div className="text-gray-500">Execution Time</div><div className="font-semibold text-gray-800">{results.executionTimeMs}ms</div></div>
          <div><div className="text-gray-500">Rules Executed</div><div className="font-semibold text-gray-800">{results.totalRulesExecuted}</div></div>
        </div>
      </div>
      {!results.success && results.errorMessage && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4">
          <div className="flex items-start gap-2">
            <AlertCircle size={20} className="text-red-600 mt-0.5" />
            <div><div className="font-semibold text-red-800">Execution Failed</div>
              <div className="text-sm text-red-700 mt-1">{results.errorMessage}</div></div>
          </div>
        </div>
      )}
      {results.success && results.inferredAxioms.length > 0 && (
        <div className="bg-white rounded-lg border">
          <div className="p-4 border-b flex justify-between items-center">
            <h3 className="font-semibold text-gray-800">Inferred Axioms ({results.inferredAxiomsCount})</h3>
            <button
                onClick={() => setShowInferredAxioms(!showInferredAxioms)}
                className="text-sm text-purple-600 hover:underline flex items-center gap-1"
              >
                {showInferredAxioms ? <EyeOff size={14} /> : <Eye size={14} />}
                {showInferredAxioms ? 'Hide' : 'Show'}
              </button>
          </div>
          {showInferredAxioms && (
            <div className="max-h-96 overflow-y-auto">
              {results.inferredAxioms.map((ax, i) => (
                <div key={i} className="p-3 border-b border-gray-100 hover:bg-gray-50">
                  <span className="text-xs font-mono bg-purple-100 text-purple-800 px-2 py-1 rounded">{ax.axiomType}</span>
                  <p className="text-sm text-gray-800 font-mono mt-1">{ax.readable}</p>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
      {results.success && results.inferredAxiomsCount === 0 && (
        <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4 text-center">
          <p className="text-yellow-800">No new axioms were inferred from the rules.</p>
        </div>
      )}
    </div>
  );
};

const SWRLEditor: React.FC<{ projectId: string; context: PluginContext }> = ({ projectId, context }) => {
  const [rules, setRules] = useState<SwrlRule[]>([]);
  const [selectedRule, setSelectedRule] = useState<SwrlRule | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [editForm, setEditForm] = useState({
    ruleName: '',
    ruleText: '',
    comment: '',
    category: '',
    enabled: true
  });
  
  const [validationResult, setValidationResult] = useState<SwrlValidationResult | null>(null);
  const [executionResult, setExecutionResult] = useState<ExecutionResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isExecuting, setIsExecuting] = useState(false);
  const [activeTab, setActiveTab] = useState<'editor' | 'query' | 'results'>('editor');

  const debouncedRuleText = useDebounce(editForm.ruleText, 500);

  // Load rules on mount
  const loadRules = useCallback(async () => {
    if (!projectId) return;
    setIsLoading(true);
    try {
      const response = await apiClient.get<SwrlRule[]>(`/api/swrl/${projectId}/rules`); //
      setRules(response);
    } catch (error) {
      console.error('Failed to load SWRL rules:', error);
    } finally {
      setIsLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadRules();
  }, [loadRules]);

  // Validate debounced rule text
  useEffect(() => {
    const validate = async () => {
      if (!isEditing || !debouncedRuleText.trim()) {
        setValidationResult(null);
        return;
      }
      try {
        const response = await apiClient.post<SwrlValidationResult>(
          `/api/swrl/${projectId}/validate`, //
          { ruleText: debouncedRuleText }
        );
        setValidationResult(response);
      } catch (error) {
        console.error('Validation failed:', error);
      }
    };
    validate();
  }, [debouncedRuleText, projectId, isEditing]);

  const handleSelectRule = (rule: SwrlRule) => {
    setSelectedRule(rule);
    setEditForm({
      ruleName: rule.ruleName,
      ruleText: rule.ruleText,
      comment: rule.comment || '',
      category: rule.category || '',
      enabled: rule.enabled
    });
    setIsEditing(false);
    setValidationResult(null);
  };

  const handleNewRule = () => {
    setSelectedRule(null);
    setEditForm({
      ruleName: 'New Rule',
      ruleText: '',
      comment: '',
      category: '',
      enabled: true
    });
    setIsEditing(true);
    setValidationResult(null);
  };

  const handleSave = async () => {
    try {
      if (selectedRule) {
        // Update existing rule
        const response = await apiClient.put<SwrlRule>(
          `/api/swrl/${projectId}/rules/${selectedRule.id}`, //
          editForm
        );
        
        setRules(rules.map(r => r.id === selectedRule.id ? response : r));
        setSelectedRule(response);
      } else {
        // Create new rule
        const response = await apiClient.post<SwrlRule>(
          `/api/swrl/${projectId}/rules`, //
          editForm
        );
        
        setRules([...rules, response]);
        setSelectedRule(response);
      }
      
      setIsEditing(false);
      setValidationResult(null);
    } catch (error) {
      console.error('Failed to save rule:', error);
      alert('Failed to save rule. Please check the console for details.');
    }
  };

  const handleDelete = async () => {
    if (!selectedRule) return;
    
    if (!confirm(`Delete rule "${selectedRule.ruleName}"?`)) return;
    
    try {
      await apiClient.delete(`/api/swrl/${projectId}/rules/${selectedRule.id}`); //
      setRules(rules.filter(r => r.id !== selectedRule.id));
      setSelectedRule(null);
      setIsEditing(false);
    } catch (error) {
      console.error('Failed to delete rule:', error);
      alert('Failed to delete rule');
    }
  };

  const handleToggleEnabled = async (rule: SwrlRule) => {
    try {
      const response = await apiClient.put<SwrlRule>(
        `/api/swrl/${projectId}/rules/${rule.id}`, //
        { enabled: !rule.enabled } // Send only the changed field
      );
      
      setRules(rules.map(r => r.id === rule.id ? response : r));
      
      if (selectedRule?.id === rule.id) {
        setSelectedRule(response);
        setEditForm(prev => ({ ...prev, enabled: response.enabled }));
      }
    } catch (error) {
      console.error('Failed to toggle rule:', error);
    }
  };

  const handleExecuteRules = async () => {
    setIsExecuting(true);
    setExecutionResult(null);
    
    try {
      const response = await apiClient.post<ExecutionResponse>(
        `/api/swrl/${projectId}/execute` //
      );
      setExecutionResult(response);
      setActiveTab('results'); // Switch to results tab on execution
    } catch (error) {
      console.error('Rule execution failed:', error);
      alert('Failed to execute rules');
    } finally {
      setIsExecuting(false);
    }
  };

  const categories = Array.from(new Set(rules.map(r => r.category).filter((s): s is string => !!s)));
  const enabledCount = rules.filter(r => r.enabled).length;

  return (
    <div className="h-full flex flex-col bg-gray-100">
      <header className="bg-white p-4 border-b border-gray-200">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-gray-800">SWRL Rule Editor</h1>
            <p className="text-sm text-gray-500">
              Create and manage Semantic Web Rule Language (SWRL) rules
            </p>
          </div>
          <div className="flex items-center gap-2">
            <div className="text-right mr-4">
              <p className="text-sm text-gray-600">
                {enabledCount} of {rules.length} rules enabled
              </p>
            </div>
            <button
              onClick={handleExecuteRules}
              disabled={isExecuting || enabledCount === 0}
              className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 disabled:cursor-not-allowed transition-colors"
            >
              {isExecuting ? (
                <>
                  <Loader2 size={18} className="animate-spin" />
                  <span>Executing...</span>
                </>
              ) : (
                <>
                  <Play size={18} />
                  <span>Execute Rules</span>
                </>
              )}
            </button>
          </div>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden p-4 gap-4">
        {/* Rules List */}
        <aside className="w-80 bg-white border border-gray-200 rounded-lg flex flex-col">
          <div className="p-2 border-b border-gray-200">
            <button
              onClick={handleNewRule}
              className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-purple-600 text-white rounded-md hover:bg-purple-700 transition-colors text-sm"
            >
              <Plus size={16} />
              New Rule
            </button>
          </div>

          <div className="flex-1 overflow-y-auto">
            {isLoading ? (
              <div className="flex items-center justify-center p-8">
                <Loader2 className="animate-spin text-purple-600" size={32} />
              </div>
            ) : rules.length === 0 ? (
              <div className="p-4 text-center text-gray-400">
                <p className="mb-2">No rules yet</p>
                <button onClick={handleNewRule} className="text-sm text-purple-600 hover:underline">
                  Create your first rule
                </button>
              </div>
            ) : (
              rules.map(rule => (
                <div
                  key={rule.id}
                  onClick={() => handleSelectRule(rule)}
                  className={`p-3 cursor-pointer border-l-4 ${
                    selectedRule?.id === rule.id
                      ? 'bg-purple-50 border-purple-500'
                      : 'border-transparent hover:bg-gray-50'
                  }`}
                >
                  <div className="flex items-start justify-between mb-1">
                    <span className={`text-sm font-medium ${
                      selectedRule?.id === rule.id ? 'text-purple-800' : 'text-gray-800'
                    }`}>
                      {rule.ruleName}
                    </span>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleToggleEnabled(rule);
                      }}
                      className={`p-1 rounded ${
                        rule.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-400'
                      }`}
                      title={rule.enabled ? 'Enabled' : 'Disabled'}
                    >
                      {rule.enabled ? <Check size={14} /> : <X size={14} />}
                    </button>
                  </div>
                  {rule.category && (
                    <span className="inline-block px-2 py-0.5 text-xs bg-gray-200 text-gray-700 rounded mb-1">
                      {rule.category}
                    </span>
                  )}
                  <p className="text-xs text-gray-500 truncate font-mono">{rule.ruleText}</p>
                </div>
              ))
            )}
          </div>
        </aside>

        {/* Rule Editor & Panels */}
        <main className="flex-1 flex flex-col gap-4 overflow-hidden">
          {!selectedRule && !isEditing ? (
            <div className="flex items-center justify-center h-full text-gray-400 bg-white border border-gray-200 rounded-lg">
              <div className="text-center">
                <AlertCircle size={64} className="mx-auto mb-4 opacity-20" />
                <p className="text-lg font-medium">No rule selected</p>
                <p className="text-sm mt-2">Select a rule or create a new one</p>
              </div>
            </div>
          ) : (
            <div className="bg-white rounded-lg border border-gray-200 p-4 space-y-3 flex flex-col flex-1 overflow-hidden">
              <div className="flex items-center justify-between">
                <input
                  type="text"
                  value={editForm.ruleName}
                  onChange={(e) => setEditForm({ ...editForm, ruleName: e.target.value })}
                  disabled={!isEditing}
                  className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-lg font-semibold focus:ring-2 focus:ring-purple-500 focus:outline-none disabled:bg-gray-50 text-black"
                  placeholder="Rule Name"
                />
                {!isEditing && (
                  <button
                    onClick={() => setIsEditing(true)}
                    className="ml-2 px-4 py-2 text-sm bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200"
                  >
                    Edit
                  </button>
                )}
              </div>

              <div className="grid grid-cols-2 gap-3">
                <input
                  type="text"
                  value={editForm.category}
                  onChange={(e) => setEditForm({ ...editForm, category: e.target.value })}
                  disabled={!isEditing}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:outline-none disabled:bg-gray-50 text-black"
                  placeholder="Category (optional)"
                  list="categories"
                />
                <datalist id="categories">
                  {categories.map(cat => <option key={cat} value={cat} />)}
                </datalist>

                <label className="flex items-center gap-2 px-3 py-2 border border-gray-300 rounded-lg">
                  <input
                    type="checkbox"
                    checked={editForm.enabled}
                    onChange={(e) => setEditForm({ ...editForm, enabled: e.target.checked })}
                    disabled={!isEditing}
                    className="rounded"
                  />
                  <span className="text-sm">Enabled</span>
                </label>
              </div>

              <textarea
                value={editForm.comment}
                onChange={(e) => setEditForm({ ...editForm, comment: e.target.value })}
                disabled={!isEditing}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:outline-none disabled:bg-gray-50 text-black"
                placeholder="Comment (optional)"
                rows={2}
              />

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Rule Text (SWRL Syntax)
                </label>
                <textarea
                  value={editForm.ruleText}
                  onChange={(e) => setEditForm({ ...editForm, ruleText: e.target.value })}
                  disabled={!isEditing}
                  className="w-full h-32 p-3 font-mono text-sm border border-gray-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:outline-none disabled:bg-gray-50 text-black"
                  placeholder="Example: Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?p)"
                />
              </div>

              {validationResult && (
                <div className={`p-3 rounded-lg ${
                  validationResult.valid
                    ? 'bg-green-50 border border-green-200'
                    : 'bg-red-50 border border-red-200'
                }`}>
                  <div className="flex items-center gap-2">
                    {validationResult.valid ? <Check className="text-green-600" size={20} /> : <AlertCircle className="text-red-600" size={20} />}
                    <span className={`text-sm font-medium ${validationResult.valid ? 'text-green-800' : 'text-red-800'}`}>
                      {validationResult.valid ? 'Valid SWRL Rule' : 'Invalid SWRL Rule'}
                    </span>
                  </div>
                  {validationResult.errorMessage && (
                    <p className="mt-2 text-sm text-red-700">{validationResult.errorMessage}</p>
                  )}
                </div>
              )}

              {isEditing && (
                <div className="flex items-center justify-end gap-2 pt-3 border-t border-gray-200">
                  <button
                    onClick={handleDelete}
                    disabled={!selectedRule}
                    className="px-4 py-2 text-sm text-red-600 bg-red-50 hover:bg-red-100 rounded-lg disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <Trash2 size={16} className="inline mr-1" />
                    Delete
                  </button>
                  <div className="flex-grow" />
                  <button
                    onClick={() => {
                      setIsEditing(false);
                      if (selectedRule) {
                        handleSelectRule(selectedRule);
                      }
                    }}
                    className="px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleSave}
                    disabled={!editForm.ruleText.trim()}
                    className="px-4 py-2 text-sm text-white bg-purple-600 hover:bg-purple-700 rounded-lg disabled:bg-purple-300 disabled:cursor-not-allowed"
                  >
                    <Save size={16} className="inline mr-1" />
                    Save
                  </button>
                </div>
              )}
            </div>
          )}
          
          {/* Tabs for SQWRL/Results */}
          <div className="flex-1 flex flex-col bg-white border border-gray-200 rounded-lg overflow-hidden min-h-0">
            <div className="flex items-center gap-2 text-xs p-2 border-b bg-gray-50">
              <button onClick={() => setActiveTab('query')} className={`px-3 py-1 rounded ${activeTab === 'query' ? 'bg-purple-100 text-purple-700' : 'text-gray-600 hover:bg-gray-100'}`}>SQWRL Query</button>
              <button onClick={() => setActiveTab('results')} className={`px-3 py-1 rounded ${activeTab === 'results' ? 'bg-purple-100 text-purple-700' : 'text-gray-600 hover:bg-gray-100'}`}>Execution Results</button>
            </div>

            <div className="flex-1 overflow-auto">
              {activeTab === 'query'
                ? <SQWRLQueryPanel projectId={projectId} context={context} />
                : <ExecutionResultsPanel results={executionResult} />
              }
            </div>
          </div>
        </main>
      </div>
    </div>
  );
};

export default SWRLEditor;