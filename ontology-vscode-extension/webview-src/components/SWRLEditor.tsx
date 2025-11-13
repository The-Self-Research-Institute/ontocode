import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Play, Save, Check, X, AlertCircle, Loader2, Eye, EyeOff, ChevronDown, ChevronRight } from 'lucide-react';
import apiClient from '../services/apiClient';
import type { SwrlRule, ValidationResult as SwrlValidationResult, ExecutionResponse } from '../types';

interface SWRLEditorProps {
  projectId: string;
}

const SWRLEditor: React.FC<SWRLEditorProps> = ({ projectId }) => {
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
  const [showInferredAxioms, setShowInferredAxioms] = useState(false);

  // Load rules on mount
  useEffect(() => {
    loadRules();
  }, [projectId]);

  const loadRules = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get<SwrlRule[]>(`/api/swrl/${projectId}/rules`);
      setRules(response.data);
    } catch (error) {
      console.error('Failed to load SWRL rules:', error);
    } finally {
      setIsLoading(false);
    }
  };

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

  const handleValidate = async () => {
    if (!editForm.ruleText.trim()) return;
    
    try {
      const response = await apiClient.post<SwrlValidationResult>(
        `/api/swrl/${projectId}/validate`,
        { ruleText: editForm.ruleText }
      );
      setValidationResult(response.data);
    } catch (error) {
      console.error('Validation failed:', error);
    }
  };

  const handleSave = async () => {
    try {
      if (selectedRule) {
        // Update existing rule
        const response = await apiClient.put<SwrlRule>(
          `/api/swrl/${projectId}/rules/${selectedRule.id}`,
          {
            ruleName: editForm.ruleName,
            ruleText: editForm.ruleText,
            comment: editForm.comment,
            category: editForm.category,
            enabled: editForm.enabled
          }
        );
        
        setRules(rules.map(r => r.id === selectedRule.id ? response.data : r));
        setSelectedRule(response.data);
      } else {
        // Create new rule
        const response = await apiClient.post<SwrlRule>(
          `/api/swrl/${projectId}/rules`,
          {
            ruleName: editForm.ruleName,
            ruleText: editForm.ruleText,
            comment: editForm.comment,
            category: editForm.category,
            enabled: editForm.enabled
          }
        );
        
        setRules([...rules, response.data]);
        setSelectedRule(response.data);
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
      await apiClient.delete(`/api/swrl/${projectId}/rules/${selectedRule.id}`);
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
        `/api/swrl/${projectId}/rules/${rule.id}`,
        { enabled: !rule.enabled }
      );
      
      setRules(rules.map(r => r.id === rule.id ? response.data : r));
      
      if (selectedRule?.id === rule.id) {
        setSelectedRule(response.data);
        setEditForm(prev => ({ ...prev, enabled: response.data.enabled }));
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
        `/api/swrl/${projectId}/execute`
      );
      setExecutionResult(response.data);
      setShowInferredAxioms(response.data.inferredAxiomsCount > 0);
    } catch (error) {
      console.error('Rule execution failed:', error);
      alert('Failed to execute rules');
    } finally {
      setIsExecuting(false);
    }
  };

  const handleClearCache = async () => {
    try {
      await apiClient.post(`/api/swrl/${projectId}/cache/clear`);
      console.log('Cache cleared successfully');
    } catch (error) {
      console.error('Failed to clear cache:', error);
    }
  };

  const categories = Array.from(new Set(rules.map(r => r.category).filter(Boolean)));
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

        {executionResult && (
          <div className={`mt-4 p-3 rounded-lg ${
            executionResult.success ? 'bg-green-50 border border-green-200' : 'bg-red-50 border border-red-200'
          }`}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                {executionResult.success ? (
                  <Check className="text-green-600" size={20} />
                ) : (
                  <AlertCircle className="text-red-600" size={20} />
                )}
                <span className={`font-medium ${
                  executionResult.success ? 'text-green-800' : 'text-red-800'
                }`}>
                  {executionResult.success ? 'Execution Successful' : 'Execution Failed'}
                </span>
              </div>
              <div className="flex items-center gap-4 text-sm">
                <span className="text-gray-700">
                  {executionResult.totalRulesExecuted} rules executed
                </span>
                <span className="text-gray-700">
                  {executionResult.inferredAxiomsCount} axioms inferred
                </span>
                <span className="text-gray-700">
                  {executionResult.executionTimeMs}ms
                </span>
              </div>
            </div>
            
            {executionResult.errorMessage && (
              <p className="mt-2 text-sm text-red-700">{executionResult.errorMessage}</p>
            )}
            
            {executionResult.inferredAxiomsCount > 0 && (
              <button
                onClick={() => setShowInferredAxioms(!showInferredAxioms)}
                className="mt-2 text-sm text-purple-600 hover:underline flex items-center gap-1"
              >
                {showInferredAxioms ? <EyeOff size={14} /> : <Eye size={14} />}
                {showInferredAxioms ? 'Hide' : 'Show'} inferred axioms
              </button>
            )}
          </div>
        )}

        {showInferredAxioms && executionResult?.inferredAxioms && (
          <div className="mt-2 p-3 bg-purple-50 border border-purple-200 rounded-lg max-h-48 overflow-y-auto">
            <h3 className="text-sm font-semibold text-purple-800 mb-2">Inferred Axioms:</h3>
            <div className="space-y-1">
              {executionResult.inferredAxioms.map((axiom, idx) => (
                <div key={idx} className="text-xs font-mono bg-white p-2 rounded border border-purple-100">
                  <span className="text-purple-600 font-semibold">{axiom.axiomType}</span>
                  <span className="text-gray-600 ml-2">{axiom.readable}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Rules List */}
        <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
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
                  {rule.comment && (
                    <p className="text-xs text-gray-400 mt-1 truncate">{rule.comment}</p>
                  )}
                </div>
              ))
            )}
          </div>
        </aside>

        {/* Rule Editor */}
        <main className="flex-1 flex flex-col p-4 gap-4 overflow-hidden">
          {!selectedRule && !isEditing ? (
            <div className="flex items-center justify-center h-full text-gray-400">
              <div className="text-center">
                <AlertCircle size={64} className="mx-auto mb-4 opacity-20" />
                <p className="text-lg font-medium">No rule selected</p>
                <p className="text-sm mt-2">Select a rule or create a new one</p>
              </div>
            </div>
          ) : (
            <>
              <div className="bg-white rounded-lg border border-gray-200 p-4 space-y-3 flex-shrink-0">
                <div className="flex items-center justify-between">
                  <input
                    type="text"
                    value={editForm.ruleName}
                    onChange={(e) => setEditForm({ ...editForm, ruleName: e.target.value })}
                    disabled={!isEditing}
                    className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-lg font-semibold focus:ring-2 focus:ring-purple-500 focus:border-transparent disabled:bg-gray-50"
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
                    className="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent disabled:bg-gray-50"
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
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent disabled:bg-gray-50"
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
                    className="w-full h-32 p-3 font-mono text-sm border border-gray-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent disabled:bg-gray-50"
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
                      {validationResult.valid ? (
                        <Check className="text-green-600" size={20} />
                      ) : (
                        <AlertCircle className="text-red-600" size={20} />
                      )}
                      <span className={`text-sm font-medium ${
                        validationResult.valid ? 'text-green-800' : 'text-red-800'
                      }`}>
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
                      onClick={handleValidate}
                      className="px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg"
                    >
                      Validate
                    </button>
                    
                    {selectedRule && (
                      <button
                        onClick={handleDelete}
                        className="px-4 py-2 text-sm text-red-600 bg-red-50 hover:bg-red-100 rounded-lg"
                      >
                        <Trash2 size={16} className="inline mr-1" />
                        Delete
                      </button>
                    )}
                    
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

              {/* SWRL Syntax Help */}
              <div className="bg-white rounded-lg border border-gray-200 p-4 overflow-y-auto">
                <h3 className="font-semibold text-gray-800 mb-3">SWRL Syntax Reference</h3>
                
                <div className="space-y-3 text-sm">
                  <div>
                    <h4 className="font-medium text-gray-700 mb-1">Basic Structure</h4>
                    <code className="block bg-gray-100 p-2 rounded font-mono text-xs">
                      antecedent -&gt; consequent
                    </code>
                  </div>

                  <div>
                    <h4 className="font-medium text-gray-700 mb-1">Examples</h4>
                    <div className="space-y-2">
                      <div>
                        <p className="text-gray-600 text-xs mb-1">All parents are adults:</p>
                        <code className="block bg-gray-100 p-2 rounded font-mono text-xs">
                          Person(?x) ^ hasChild(?x, ?y) -&gt; Adult(?x)
                        </code>
                      </div>
                      <div>
                        <p className="text-gray-600 text-xs mb-1">People over 18 are adults:</p>
                        <code className="block bg-gray-100 p-2 rounded font-mono text-xs">
                          Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -&gt; Adult(?p)
                        </code>
                      </div>
                    </div>
                  </div>

                  <div>
                    <h4 className="font-medium text-gray-700 mb-1">Operators</h4>
                    <ul className="list-disc list-inside text-gray-600 text-xs space-y-1">
                      <li>^ - AND (conjunction)</li>
                      <li>-&gt; - implies</li>
                      <li>?x, ?y - variables</li>
                    </ul>
                  </div>

                  <div>
                    <h4 className="font-medium text-gray-700 mb-1">Built-ins (swrlb:)</h4>
                    <ul className="list-disc list-inside text-gray-600 text-xs space-y-1">
                      <li>greaterThan, lessThan, equal</li>
                      <li>add, subtract, multiply, divide</li>
                      <li>stringConcat, stringLength</li>
                    </ul>
                  </div>
                </div>
              </div>
            </>
          )}
        </main>
      </div>
    </div>
  );
};

export default SWRLEditor;