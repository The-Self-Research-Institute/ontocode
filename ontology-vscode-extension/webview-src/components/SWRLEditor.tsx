import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Play, Save, CheckCircle, XCircle, FileCode, Search, BrainCircuit, Loader2 } from 'lucide-react';
import apiClient from '../services/apiClient';
import type { SWRLRule, ValidationResult, SQWRLResult, PluginContext } from '../types';

// Debounce hook for validation
function useDebounce(value: string, delay: number) {
  const [debouncedValue, setDebouncedValue] = useState(value);
  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);
    return () => {
      clearTimeout(handler);
    };
  }, [value, delay]);
  return debouncedValue;
}

interface SWRLEditorProps {
    projectId: string;
    context: PluginContext;
}

const SQWRLQueryPanel: React.FC<{ projectId: string }> = ({ projectId }) => {
    const [query, setQuery] = useState('Pizza(?p) ^ hasTopping(?p, ?t) -> sqwrl:select(?p, ?t)');
    const [results, setResults] = useState<SQWRLResult | null>(null);
    const [isLoading, setIsLoading] = useState(false);

    const executeQuery = useCallback(async () => {
        setIsLoading(true);
        try {
            const response = await apiClient.post<SQWRLResult>('/api/sqwrl/query', { projectId, query });
            setResults(response.data);
        } catch (error) {
            console.error('Failed to execute SQWRL query:', error);
        } finally {
            setIsLoading(false);
        }
    }, [projectId, query]);

    return (
        <div className="flex flex-col h-full bg-gray-50">
            <div className="p-4 border-b border-gray-200">
                <h3 className="text-lg font-semibold text-gray-800">SQWRL Query</h3>
                <p className="text-xs text-gray-500">Execute queries against the ontology.</p>
            </div>
            <div className="p-4 flex-grow flex flex-col gap-4">
                <div className="flex-shrink-0">
                    <textarea
                        className="w-full h-32 p-3 font-mono text-sm border border-gray-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        placeholder="Enter SQWRL query..."
                    />
                    <button
                        onClick={executeQuery}
                        disabled={isLoading}
                        className="mt-2 w-full flex items-center justify-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 text-sm font-semibold transition-colors"
                    >
                        {isLoading ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />}
                        Execute Query
                    </button>
                </div>
                <div className="flex-grow overflow-auto border border-gray-200 rounded-lg bg-white">
                    {results ? (
                        <table className="min-w-full text-sm">
                            <thead className="bg-gray-100">
                                <tr>
                                    {results.columns.map((col: string) => <th key={col} className="p-2 text-left font-semibold text-gray-600">{col}</th>)}
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-200">
                                {results.rows.map((row: Record<string, any>, rowIndex: number) => (
                                    <tr key={rowIndex} className="hover:bg-gray-50">
                                        {results.columns.map((col: string) => <td key={col} className="p-2 text-gray-700 whitespace-nowrap">{String(row[col])}</td>)}
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    ) : (
                        <div className="flex items-center justify-center h-full text-gray-400">
                            <p>Query results will appear here.</p>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};


const SWRLEditor: React.FC<SWRLEditorProps> = ({ projectId, context }) => {
    const [rules, setRules] = useState<SWRLRule[]>([]);
    const [selectedRule, setSelectedRule] = useState<SWRLRule | null>(null);
    const [ruleText, setRuleText] = useState('');
    const [ruleName, setRuleName] = useState('');
    const [validation, setValidation] = useState<ValidationResult | null>(null);
    const [activeTab, setActiveTab] = useState<'editor' | 'query'>('editor');
    const [isReasoning, setIsReasoning] = useState(false);
    const debouncedRuleText = useDebounce(ruleText, 500); // 500ms delay

    const fetchRules = useCallback(async () => {
        try {
            const response = await apiClient.get<SWRLRule[]>(`/api/swrl/rules?projectId=${projectId}`);
            setRules(response.data);
        } catch (_error) {
            context.notificationService.error('Failed to fetch SWRL rules.');
        }
    }, [projectId, context.notificationService]);

    useEffect(() => {
        fetchRules();
    }, [fetchRules]);
    
    // Effect for debounced validation
    useEffect(() => {
        const validate = async () => {
            if (debouncedRuleText.trim() === '') {
                setValidation(null);
                return;
            }
            try {
                const response = await apiClient.post<ValidationResult>('/api/swrl/validate', { ruleText: debouncedRuleText });
                setValidation(response.data);
            } catch (_error) {
                setValidation({ valid: false, message: 'Validation service unavailable' });
            }
        };
        validate();
    }, [debouncedRuleText]);


    const handleSelectRule = (rule: SWRLRule) => {
        setSelectedRule(rule);
        setRuleText(rule.ruleText);
        setRuleName(rule.name);
        setValidation(null);
    };

    const handleNewRule = () => {
        setSelectedRule(null);
        setRuleText('');
        setRuleName('NewRule');
        setValidation(null);
    };

    const handleRuleTextChange = (text: string) => {
        setRuleText(text);
        // Validation is now handled by the debounced effect
    };
    
    const handleSaveRule = async () => {
        if (!validation?.valid) {
            context.notificationService.error("Cannot save, rule has syntax errors.");
            return;
        }
        const ruleData = { name: ruleName, ruleText, enabled: selectedRule?.enabled ?? true };
        try {
            if (selectedRule) {
                // Update logic here (mocked as new save)
                await apiClient.post(`/api/swrl/rules`, { ...ruleData, id: selectedRule.id, projectId });
                context.notificationService.success(`Rule "${ruleName}" updated.`);
            } else {
                await apiClient.post('/api/swrl/rules', { ...ruleData, projectId });
                context.notificationService.success(`Rule "${ruleName}" created.`);
            }
            fetchRules();
        } catch (_error) {
            context.notificationService.error("Failed to save rule.");
        }
    };

    const handleDeleteRule = async () => {
        if (!selectedRule) return;

        if (window.confirm(`Are you sure you want to delete the rule "${selectedRule.name}"?`)) {
            try {
                await apiClient.delete(`/api/swrl/rules/${selectedRule.id}?projectId=${projectId}`);
                context.notificationService.success(`Rule "${selectedRule.name}" deleted.`);
                handleNewRule(); // Reset view to new rule state
                fetchRules(); // Refresh the list
            } catch (_error) {
                context.notificationService.error("Failed to delete rule.");
            }
        }
    };

    const handleRunReasoner = async () => {
        setIsReasoning(true);
        context.notificationService.info('Running reasoner...');
        try {
            // Simulate API call
            await new Promise(resolve => setTimeout(resolve, 2000));
            // In a real app, this would be a long-polling process or websocket.
            await apiClient.post(`/api/ontology/reasoner/run?projectId=${projectId}`);
            context.notificationService.success('Reasoner finished. Inferred axioms are now available.');
        } catch (error) {
            context.notificationService.error('Failed to run reasoner.');
        } finally {
            setIsReasoning(false);
        }
    };

    return (
        <div className="h-full flex flex-col bg-gray-100">
            <header className="bg-white p-4 border-b border-gray-200">
                <h1 className="text-xl font-bold text-gray-800">SWRL and SQWRL Editor</h1>
                <p className="text-sm text-gray-500">Manage rules and query your ontology.</p>
            </header>
            <div className="flex flex-1 overflow-hidden">
                <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
                    <div className="p-2 border-b border-gray-200 flex gap-2">
                        <button onClick={handleNewRule} className="flex-1 flex items-center justify-center gap-2 text-xs text-white bg-purple-600 hover:bg-purple-700 px-3 py-2 rounded-md transition-colors shadow-sm">
                            <Plus size={14} /> New Rule
                        </button>
                         <button onClick={handleRunReasoner} disabled={isReasoning} className="flex-1 flex items-center justify-center gap-2 text-xs text-gray-700 bg-gray-100 hover:bg-gray-200 px-3 py-2 rounded-md transition-colors disabled:opacity-50">
                            {isReasoning ? <Loader2 size={14} className="animate-spin" /> : <BrainCircuit size={14} />} 
                            {isReasoning ? 'Running...' : 'Run Reasoner'}
                        </button>
                    </div>
                    <div className="flex-1 overflow-y-auto">
                        {rules.map(rule => (
                            <div key={rule.id} onClick={() => handleSelectRule(rule)} className={`p-3 cursor-pointer border-l-4 ${selectedRule?.id === rule.id ? 'bg-purple-50 border-purple-500' : 'border-transparent hover:bg-gray-50'}`}>
                                <div className="flex justify-between items-center">
                                    <span className={`text-sm font-medium ${selectedRule?.id === rule.id ? 'text-purple-800' : 'text-gray-800'}`}>{rule.name}</span>
                                    <div className={`w-2 h-2 rounded-full ${rule.enabled ? 'bg-green-500' : 'bg-gray-400'}`}></div>
                                </div>
                                <p className="text-xs text-gray-500 truncate">{rule.ruleText}</p>
                            </div>
                        ))}
                    </div>
                </aside>
                <main className="flex-1 flex flex-col">
                    <div className="bg-gray-50 border-b border-gray-200 flex">
                        <button onClick={() => setActiveTab('editor')} className={`flex items-center gap-2 px-4 py-2 text-sm font-medium ${activeTab === 'editor' ? 'bg-white text-purple-600' : 'text-gray-600 hover:bg-gray-100'}`}>
                           <FileCode size={16} /> Rule Editor
                        </button>
                        <button onClick={() => setActiveTab('query')} className={`flex items-center gap-2 px-4 py-2 text-sm font-medium ${activeTab === 'query' ? 'bg-white text-purple-600' : 'text-gray-600 hover:bg-gray-100'}`}>
                           <Search size={16} /> SQWRL Query
                        </button>
                    </div>
                    <div className="flex-1 overflow-auto">
                        {activeTab === 'editor' ? (
                            <div className="p-4 space-y-4">
                                <input type="text" value={ruleName} onChange={(e) => setRuleName(e.target.value)} className="w-full px-3 py-2 border border-gray-300 rounded-lg text-lg font-semibold focus:ring-2 focus:ring-purple-500 focus:border-transparent" placeholder="Rule Name" />
                                <textarea
                                    className="w-full h-64 p-3 font-mono text-sm border border-gray-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                    value={ruleText}
                                    onChange={(e) => handleRuleTextChange(e.target.value)}
                                    placeholder="Enter SWRL rule... e.g., Pizza(?p) -> Food(?p)"
                                />
                                <div className="flex items-center justify-between">
                                    <div className="flex items-center gap-2 text-sm">
                                        {validation && (
                                            <>
                                                {validation.valid ? <CheckCircle size={16} className="text-green-600" /> : <XCircle size={16} className="text-red-600" />}
                                                <span className={validation.valid ? 'text-green-700' : 'text-red-700'}>{validation.message}</span>
                                            </>
                                        )}
                                    </div>
                                    <div className="flex gap-2">
                                        <button onClick={handleDeleteRule} disabled={!selectedRule} className="px-4 py-2 text-sm bg-gray-200 rounded-lg hover:bg-gray-300 disabled:opacity-50 disabled:cursor-not-allowed">
                                            <Trash2 size={16} className="text-gray-600" />
                                        </button>
                                        <button onClick={handleSaveRule} disabled={!validation?.valid} className="flex items-center gap-2 px-4 py-2 text-sm text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:bg-purple-300">
                                            <Save size={16} /> Save Rule
                                        </button>
                                    </div>
                                </div>
                            </div>
                        ) : (
                            <SQWRLQueryPanel projectId={projectId} />
                        )}
                    </div>
                </main>
            </div>
        </div>
    );
};

export default SWRLEditor;