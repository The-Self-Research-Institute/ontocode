import React, { useState, useEffect } from 'react';
import { Play, Check, AlertCircle, Trash2, Copy, Save } from 'lucide-react';

interface SWRLRule {
  id: string;
  name: string;
  rule: string;
  enabled: boolean;
  valid?: boolean;
  errorMessage?: string;
}

interface SWRLEditorProps {
  projectId?: string;
}

export const SWRLEditor: React.FC<SWRLEditorProps> = ({ projectId }) => {
  const [rules, setRules] = useState<SWRLRule[]>([]);
  const [selectedRule, setSelectedRule] = useState<string | null>(null);
  const [ruleText, setRuleText] = useState('');
  const [validationResult, setValidationResult] = useState<{ valid: boolean; message?: string } | null>(null);
  const [isValidating, setIsValidating] = useState(false);
  const [isExecuting, setIsExecuting] = useState(false);

  const currentRule = selectedRule ? rules.find(r => r.id === selectedRule) : null;

  useEffect(() => {
    if (currentRule) {
      setRuleText(currentRule.rule);
    }
  }, [selectedRule, currentRule]);

  const addRule = () => {
    const newRule: SWRLRule = {
      id: Date.now().toString(),
      name: `Rule ${rules.length + 1}`,
      rule: '// Enter your SWRL rule here\n// Example: Person(?p) ∧ hasAge(?p, ?age) ∧ swrlb:greaterThan(?age, 18) → Adult(?p)',
      enabled: true
    };
    setRules([...rules, newRule]);
    setSelectedRule(newRule.id);
  };

  const deleteRule = (ruleId: string) => {
    setRules(rules.filter(r => r.id !== ruleId));
    if (selectedRule === ruleId) {
      setSelectedRule(null);
      setRuleText('');
    }
  };

  const saveRule = () => {
    if (!selectedRule) return;
    setRules(rules.map(r => r.id === selectedRule ? { ...r, rule: ruleText } : r));
  };

  const validateRule = async () => {
    if (!ruleText.trim()) return;
    
    setIsValidating(true);
    setValidationResult(null);

    try {
      const response = await fetch('/api/swrl/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          projectId,
          rule: ruleText 
        })
      });
      
      const result = await response.json();
      setValidationResult(result);
      
      if (selectedRule) {
        setRules(rules.map(r => 
          r.id === selectedRule 
            ? { ...r, valid: result.valid, errorMessage: result.message } 
            : r
        ));
      }
    } catch (error) {
      setValidationResult({ 
        valid: false, 
        message: 'Failed to validate rule: ' + (error as Error).message 
      });
    } finally {
      setIsValidating(false);
    }
  };

  const executeRule = async () => {
    if (!ruleText.trim() || !projectId) return;
    
    setIsExecuting(true);

    try {
      const response = await fetch('/api/swrl/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          projectId,
          rule: ruleText 
        })
      });
      
      const result = await response.json();
      alert(`Rule executed successfully. ${result.inferencesCount || 0} new inferences created.`);
    } catch (error) {
      alert('Failed to execute rule: ' + (error as Error).message);
    } finally {
      setIsExecuting(false);
    }
  };

  const duplicateRule = () => {
    if (!currentRule) return;
    const newRule: SWRLRule = {
      ...currentRule,
      id: Date.now().toString(),
      name: `${currentRule.name} (copy)`
    };
    setRules([...rules, newRule]);
    setSelectedRule(newRule.id);
  };

  return (
    <div className="h-full flex flex-col bg-white">
      {/* Header */}
      <div className="flex items-center justify-between p-4 border-b bg-gradient-to-r from-purple-50 to-blue-50">
        <div>
          <h2 className="text-xl font-bold text-gray-900">SWRL Rules Editor</h2>
          <p className="text-sm text-gray-600">Create and manage Semantic Web Rule Language rules</p>
        </div>
        <button
          onClick={addRule}
          className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors flex items-center gap-2"
        >
          <Check size={16} />
          New Rule
        </button>
      </div>

      <div className="flex gap-4 flex-1 overflow-hidden p-4">
        {/* Rules List */}
        <div className="w-1/3 border border-gray-200 rounded-lg flex flex-col overflow-hidden">
          <div className="p-3 border-b bg-gray-50">
            <h3 className="font-semibold text-gray-900">Rules ({rules.length})</h3>
          </div>
          <div className="flex-1 overflow-auto p-2">
            {rules.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full text-gray-500 text-sm">
                <AlertCircle size={32} className="mb-2" />
                <p>No rules yet</p>
                <p className="text-xs">Click "New Rule" to create one</p>
              </div>
            ) : (
              rules.map(rule => (
                <div
                  key={rule.id}
                  onClick={() => setSelectedRule(rule.id)}
                  className={`p-3 rounded-lg cursor-pointer mb-2 transition-colors ${
                    selectedRule === rule.id
                      ? 'bg-purple-100 border-2 border-purple-300'
                      : 'bg-gray-50 border border-gray-200 hover:bg-gray-100'
                  }`}
                >
                  <div className="flex items-center justify-between mb-1">
                    <span className="font-medium text-sm">{rule.name}</span>
                    <div className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={rule.enabled}
                        onChange={(e) => {
                          e.stopPropagation();
                          setRules(rules.map(r =>
                            r.id === rule.id ? { ...r, enabled: e.target.checked } : r
                          ));
                        }}
                        className="rounded"
                      />
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          deleteRule(rule.id);
                        }}
                        className="text-red-600 hover:text-red-700"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </div>
                  {rule.valid !== undefined && (
                    <div className={`text-xs flex items-center gap-1 ${rule.valid ? 'text-green-600' : 'text-red-600'}`}>
                      {rule.valid ? (
                        <>
                          <Check size={12} />
                          Valid
                        </>
                      ) : (
                        <>
                          <AlertCircle size={12} />
                          Invalid
                        </>
                      )}
                    </div>
                  )}
                  <div className="text-xs text-gray-500 mt-1 line-clamp-2">
                    {rule.rule || 'Empty rule'}
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Rule Editor */}
        <div className="flex-1 border border-gray-200 rounded-lg flex flex-col overflow-hidden">
          {selectedRule ? (
            <>
              <div className="p-3 border-b bg-gray-50 flex items-center justify-between">
                <input
                  type="text"
                  value={currentRule?.name || ''}
                  onChange={(e) => {
                    setRules(rules.map(r =>
                      r.id === selectedRule ? { ...r, name: e.target.value } : r
                    ));
                  }}
                  className="font-semibold text-gray-900 bg-transparent border-none focus:outline-none"
                />
                <div className="flex items-center gap-2">
                  <button
                    onClick={duplicateRule}
                    className="p-2 text-gray-600 hover:bg-gray-200 rounded"
                    title="Duplicate rule"
                  >
                    <Copy size={16} />
                  </button>
                  <button
                    onClick={saveRule}
                    className="p-2 text-gray-600 hover:bg-gray-200 rounded"
                    title="Save rule"
                  >
                    <Save size={16} />
                  </button>
                </div>
              </div>
              
              <textarea
                value={ruleText}
                onChange={(e) => setRuleText(e.target.value)}
                placeholder="Enter SWRL rule..."
                className="flex-1 p-4 font-mono text-sm resize-none focus:outline-none"
                spellCheck={false}
              />

              {validationResult && (
                <div className={`p-3 border-t ${validationResult.valid ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'}`}>
                  <div className={`text-sm flex items-center gap-2 ${validationResult.valid ? 'text-green-700' : 'text-red-700'}`}>
                    {validationResult.valid ? <Check size={16} /> : <AlertCircle size={16} />}
                    <span className="font-medium">{validationResult.valid ? 'Valid SWRL Rule' : 'Invalid SWRL Rule'}</span>
                  </div>
                  {validationResult.message && (
                    <div className="text-xs text-gray-600 mt-1">{validationResult.message}</div>
                  )}
                </div>
              )}

              <div className="p-3 border-t bg-gray-50 flex gap-2">
                <button
                  onClick={validateRule}
                  disabled={isValidating}
                  className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors flex items-center gap-2 disabled:opacity-50"
                >
                  {isValidating ? (
                    <>
                      <div className="animate-spin rounded-full h-4 w-4 border-2 border-white border-t-transparent" />
                      Validating...
                    </>
                  ) : (
                    <>
                      <Check size={16} />
                      Validate
                    </>
                  )}
                </button>
                <button
                  onClick={executeRule}
                  disabled={isExecuting || !projectId}
                  className="px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors flex items-center gap-2 disabled:opacity-50"
                >
                  {isExecuting ? (
                    <>
                      <div className="animate-spin rounded-full h-4 w-4 border-2 border-white border-t-transparent" />
                      Executing...
                    </>
                  ) : (
                    <>
                      <Play size={16} />
                      Execute
                    </>
                  )}
                </button>
              </div>
            </>
          ) : (
            <div className="flex items-center justify-center h-full text-gray-500">
              <div className="text-center">
                <AlertCircle size={48} className="mx-auto mb-3" />
                <p>Select a rule to edit</p>
                <p className="text-sm mt-1">or create a new rule to get started</p>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Help Panel */}
      <div className="border-t bg-gray-50 p-3">
        <details className="text-sm">
          <summary className="cursor-pointer font-medium text-gray-700">SWRL Syntax Help</summary>
          <div className="mt-2 space-y-2 text-gray-600 text-xs">
            <p><strong>Basic Structure:</strong> Antecedent → Consequent</p>
            <p><strong>Example:</strong> Person(?p) ∧ hasAge(?p, ?age) ∧ swrlb:greaterThan(?age, 18) → Adult(?p)</p>
            <p><strong>Operators:</strong> ∧ (AND), ∨ (OR), → (implies)</p>
            <p><strong>Built-ins:</strong> swrlb:greaterThan, swrlb:lessThan, swrlb:equal, etc.</p>
          </div>
        </details>
      </div>
    </div>
  );
};
