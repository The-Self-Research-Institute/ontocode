import React, { useState, useEffect, useCallback } from 'react';
import { 
    Plus, Trash2, Play, Save, CheckCircle, XCircle, FileCode, Search, 
    BrainCircuit, Loader2, Filter, Download, Upload, BarChart3, RefreshCw,
    AlertCircle
} from 'lucide-react';
import apiClient from '../services/apiClient';
import type { SWRLRule, ValidationResult, SQWRLResult, PluginContext, ExecutionResponse } from '../types';

// ============================================================================
// UTILITY HOOKS
// ============================================================================

/**
 * Debounce hook for validation - prevents API spam
 */
function useDebounce(value: string, delay: number) {
    const [debouncedValue, setDebouncedValue] = useState(value);
    useEffect(() => {
        const handler = setTimeout(() => {
            setDebouncedValue(value);
        }, delay);
        return () => clearTimeout(handler);
    }, [value, delay]);
    return debouncedValue;
}

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

interface SWRLEditorProps {
    projectId: string;
    context: PluginContext;
}

interface InferredAxiom {
    axiomType: string;
    description: string;
    readable: string;
}

// ============================================================================
// SQWRL QUERY PANEL COMPONENT
// ============================================================================

const SQWRLQueryPanel: React.FC<{ projectId: string; context: PluginContext }> = ({ projectId, context }) => {
    const [query, setQuery] = useState('Pizza(?p) ^ hasTopping(?p, ?t) -> sqwrl:select(?p, ?t)');
    const [results, setResults] = useState<SQWRLResult | null>(null);
    const [isLoading, setIsLoading] = useState(false);

    const executeQuery = useCallback(async () => {
        setIsLoading(true);
        try {
            // ✅ FIXED: Correct endpoint path
            const response = await apiClient.post<SQWRLResult>(
                `/api/sqwrl/${projectId}/query`, 
                { query }
            );
            setResults(response.data);
            context.notificationService.success('Query executed successfully');
        } catch (error) {
            console.error('Failed to execute SQWRL query:', error);
            context.notificationService.error('Failed to execute query');
        } finally {
            setIsLoading(false);
        }
    }, [projectId, query, context.notificationService]);

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
                                    {results.columns.map((col: string) => (
                                        <th key={col} className="p-2 text-left font-semibold text-gray-600">{col}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-200">
                                {results.rows.map((row: Record<string, any>, rowIndex: number) => (
                                    <tr key={rowIndex} className="hover:bg-gray-50">
                                        {results.columns.map((col: string) => (
                                            <td key={col} className="p-2 text-gray-700 whitespace-nowrap">
                                                {String(row[col])}
                                            </td>
                                        ))}
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

// ============================================================================
// EXECUTION RESULTS PANEL
// ============================================================================

const ExecutionResultsPanel: React.FC<{ results: ExecutionResponse | null }> = ({ results }) => {
    if (!results) {
        return (
            <div className="p-8 text-center text-gray-400">
                <BrainCircuit size={48} className="mx-auto mb-4 opacity-50" />
                <p>Execute rules to see inferred axioms</p>
            </div>
        );
    }

    return (
        <div className="p-4 space-y-4">
            {/* Execution Summary */}
            <div className="bg-white rounded-lg border border-gray-200 p-4">
                <h3 className="font-semibold text-gray-800 mb-3">Execution Summary</h3>
                <div className="grid grid-cols-3 gap-4 text-sm">
                    <div>
                        <div className="text-gray-500">Status</div>
                        <div className={`font-semibold ${results.success ? 'text-green-600' : 'text-red-600'}`}>
                            {results.success ? '✓ Success' : '✗ Failed'}
                        </div>
                    </div>
                    <div>
                        <div className="text-gray-500">Execution Time</div>
                        <div className="font-semibold text-gray-800">{results.executionTimeMs}ms</div>
                    </div>
                    <div>
                        <div className="text-gray-500">Rules Executed</div>
                        <div className="font-semibold text-gray-800">{results.totalRulesExecuted}</div>
                    </div>
                </div>
            </div>

            {/* Error Message */}
            {!results.success && results.errorMessage && (
                <div className="bg-red-50 border border-red-200 rounded-lg p-4">
                    <div className="flex items-start gap-2">
                        <AlertCircle size={20} className="text-red-600 flex-shrink-0 mt-0.5" />
                        <div>
                            <div className="font-semibold text-red-800">Execution Failed</div>
                            <div className="text-sm text-red-700 mt-1">{results.errorMessage}</div>
                        </div>
                    </div>
                </div>
            )}

            {/* Inferred Axioms */}
            {results.success && results.inferredAxioms.length > 0 && (
                <div className="bg-white rounded-lg border border-gray-200">
                    <div className="p-4 border-b border-gray-200 flex justify-between items-center">
                        <h3 className="font-semibold text-gray-800">
                            Inferred Axioms ({results.inferredAxiomsCount})
                        </h3>
                        {results.inferredAxiomsCount > results.inferredAxioms.length && (
                            <span className="text-xs text-gray-500">
                                Showing first {results.inferredAxioms.length}
                            </span>
                        )}
                    </div>
                    <div className="max-h-96 overflow-y-auto">
                        {results.inferredAxioms.map((axiom: InferredAxiom, idx: number) => (
                            <div key={idx} className="p-3 border-b border-gray-100 hover:bg-gray-50">
                                <div className="flex items-start gap-2">
                                    <span className="text-xs font-mono bg-purple-100 text-purple-800 px-2 py-1 rounded">
                                        {axiom.axiomType}
                                    </span>
                                    <div className="flex-1">
                                        <p className="text-sm text-gray-800 font-mono">{axiom.readable}</p>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* No Inferences */}
            {results.success && results.inferredAxiomsCount === 0 && (
                <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4 text-center">
                    <p className="text-yellow-800">No new axioms were inferred from the rules.</p>
                    <p className="text-xs text-yellow-600 mt-1">
                        This could mean rules are not applicable to current data or all inferences already exist.
                    </p>
                </div>
            )}
        </div>
    );
};

// ============================================================================
// MAIN SWRL EDITOR COMPONENT
// ============================================================================

const SWRLEditor: React.FC<SWRLEditorProps> = ({ projectId, context }) => {
    const [rules, setRules] = useState<SWRLRule[]>([]);
    const [selectedRule, setSelectedRule] = useState<SWRLRule | null>(null);
    const [ruleText, setRuleText] = useState('');
    const [ruleName, setRuleName] = useState('');
    const [ruleCategory, setRuleCategory] = useState('');
    const [ruleComment, setRuleComment] = useState('');
    const [validation, setValidation] = useState<ValidationResult | null>(null);
    const [activeTab, setActiveTab] = useState<'editor' | 'query' | 'results'>('editor');
    const [isReasoning, setIsReasoning] = useState(false);
    const [isExecuting, setIsExecuting] = useState(false);
    const [executionResults, setExecutionResults] = useState<ExecutionResponse | null>(null);
    const [searchTerm, setSearchTerm] = useState('');
    const [filterEnabled, setFilterEnabled] = useState<boolean | null>(null);
    
    const debouncedRuleText = useDebounce(ruleText, 500);

    // ========================================================================
    // FETCH RULES
    // ========================================================================
    
const fetchRules = useCallback(async () => {
    try {
        // Fetch rules from API
        const response = await apiClient.get<any>(`/api/swrl/${projectId}/rules`);
        
        // Handle both paginated (Spring Data Page) and simple array responses
        if (response.data.content) {
            // Paginated response
            setRules(response.data.content);
        } else if (Array.isArray(response.data)) {
            // Simple array response
            setRules(response.data);
        } else {
            // Fallback
            setRules([]);
        }
    } catch (error) {
        console.error('Failed to fetch rules:', error);
        context.notificationService.error('Failed to fetch SWRL rules.');
        setRules([]);  // Set empty array on error
    }
}, [projectId, context.notificationService]);

    useEffect(() => {
        fetchRules();
    }, [fetchRules]);

    // ========================================================================
    // DEBOUNCED VALIDATION
    // ========================================================================
    
    useEffect(() => {
        const validate = async () => {
            if (debouncedRuleText.trim() === '') {
                setValidation(null);
                return;
            }
            try {
                // ✅ FIXED: Correct endpoint path
                const response = await apiClient.post<ValidationResult>(
                    `/api/swrl/${projectId}/validate`,
                    { ruleText: debouncedRuleText }
                );
                setValidation(response.data);
            } catch (error) {
                console.error('Validation error:', error);
                setValidation({ 
                    valid: false, 
                    errorMessage: 'Validation service unavailable',
                    suggestions: []
                });
            }
        };
        validate();
    }, [debouncedRuleText, projectId]);

    // ========================================================================
    // RULE HANDLERS
    // ========================================================================

    const handleSelectRule = (rule: SWRLRule) => {
        setSelectedRule(rule);
        setRuleText(rule.ruleText);
        setRuleName(rule.ruleName);
        setRuleCategory(rule.category || '');
        setRuleComment(rule.comment || '');
        setValidation(null);
    };

    const handleNewRule = () => {
        setSelectedRule(null);
        setRuleText('');
        setRuleName('NewRule');
        setRuleCategory('');
        setRuleComment('');
        setValidation(null);
    };

    const handleSaveRule = async () => {
        if (!validation?.valid) {
            context.notificationService.error("Cannot save, rule has syntax errors.");
            return;
        }

        const ruleData = {
            ruleName,
            ruleText,
            category: ruleCategory || undefined,
            comment: ruleComment || undefined,
            enabled: selectedRule?.enabled ?? true
        };

        try {
            if (selectedRule) {
                // ✅ FIXED: Use PUT for updates with correct path
                await apiClient.put(
                    `/api/swrl/${projectId}/rules/${selectedRule.id}`,
                    ruleData
                );
                context.notificationService.success(`Rule "${ruleName}" updated.`);
            } else {
                // ✅ FIXED: Correct POST endpoint
                await apiClient.post(`/api/swrl/${projectId}/rules`, ruleData);
                context.notificationService.success(`Rule "${ruleName}" created.`);
            }
            fetchRules();
        } catch (error) {
            console.error('Save error:', error);
            context.notificationService.error("Failed to save rule.");
        }
    };

    const handleDeleteRule = async () => {
        if (!selectedRule) return;

        if (window.confirm(`Are you sure you want to delete the rule "${selectedRule.ruleName}"?`)) {
            try {
                // ✅ FIXED: Correct DELETE endpoint
                await apiClient.delete(`/api/swrl/${projectId}/rules/${selectedRule.id}`);
                context.notificationService.success(`Rule "${selectedRule.ruleName}" deleted.`);
                handleNewRule();
                fetchRules();
            } catch (error) {
                console.error('Delete error:', error);
                context.notificationService.error("Failed to delete rule.");
            }
        }
    };

    const handleToggleEnabled = async (rule: SWRLRule) => {
        try {
            // ✅ FIXED: Use PUT to toggle enabled state
            await apiClient.put(`/api/swrl/${projectId}/rules/${rule.id}`, {
                enabled: !rule.enabled
            });
            fetchRules();
            context.notificationService.success(
                `Rule "${rule.ruleName}" ${!rule.enabled ? 'enabled' : 'disabled'}.`
            );
        } catch (error) {
            console.error('Toggle error:', error);
            context.notificationService.error("Failed to toggle rule.");
        }
    };

    // ========================================================================
    // EXECUTION HANDLERS
    // ========================================================================

    const handleExecuteRules = async () => {
        setIsExecuting(true);
        context.notificationService.info('Executing SWRL rules...');
        
        try {
            // ✅ NEW: Execute rules and get results
            const response = await apiClient.post<ExecutionResponse>(
                `/api/swrl/${projectId}/execute`
            );
            setExecutionResults(response.data);
            setActiveTab('results');
            
            if (response.data.success) {
                context.notificationService.success(
                    `Executed ${response.data.totalRulesExecuted} rules in ${response.data.executionTimeMs}ms. ` +
                    `Inferred ${response.data.inferredAxiomsCount} axioms.`
                );
            } else {
                context.notificationService.error('Rule execution failed: ' + response.data.errorMessage);
            }
        } catch (error) {
            console.error('Execution error:', error);
            context.notificationService.error('Failed to execute rules.');
        } finally {
            setIsExecuting(false);
        }
    };

    const handleRunReasoner = async () => {
        setIsReasoning(true);
        context.notificationService.info('Running reasoner...');
        
        try {
            await apiClient.post(`/api/ontology/${projectId}/reasoner/run`);
            context.notificationService.success('Reasoner finished. Inferred axioms are now available.');
        } catch (error) {
            console.error('Reasoner error:', error);
            context.notificationService.error('Failed to run reasoner.');
        } finally {
            setIsReasoning(false);
        }
    };

    const handleClearCache = async () => {
        try {
            await apiClient.post(`/api/swrl/${projectId}/cache/clear`);
            context.notificationService.success('Cache cleared successfully.');
        } catch (error) {
            console.error('Clear cache error:', error);
            context.notificationService.error('Failed to clear cache.');
        }
    };

    // ========================================================================
    // IMPORT/EXPORT
    // ========================================================================

    const handleExportRules = async () => {
        try {
            const response = await apiClient.get(`/api/swrl/${projectId}/rules/export`);
            const blob = new Blob([JSON.stringify(response.data, null, 2)], { 
                type: 'application/json' 
            });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `swrl-rules-${projectId}-${Date.now()}.json`;
            a.click();
            URL.revokeObjectURL(url);
            context.notificationService.success('Rules exported successfully.');
        } catch (error) {
            console.error('Export error:', error);
            context.notificationService.error('Failed to export rules.');
        }
    };

    // ========================================================================
    // FILTERING
    // ========================================================================

    const filteredRules = rules.filter(rule => {
        const matchesSearch = searchTerm === '' || 
            rule.ruleName.toLowerCase().includes(searchTerm.toLowerCase()) ||
            rule.ruleText.toLowerCase().includes(searchTerm.toLowerCase());
        
        const matchesEnabled = filterEnabled === null || rule.enabled === filterEnabled;
        
        return matchesSearch && matchesEnabled;
    });

    // ========================================================================
    // RENDER
    // ========================================================================

    return (
        <div className="h-full flex flex-col bg-gray-100">
            {/* Header */}
            <header className="bg-white p-4 border-b border-gray-200">
                <div className="flex justify-between items-center">
                    <div>
                        <h1 className="text-xl font-bold text-gray-800">SWRL and SQWRL Editor</h1>
                        <p className="text-sm text-gray-500">Manage rules and query your ontology.</p>
                    </div>
                    <div className="flex gap-2">
                        <button 
                            onClick={handleClearCache}
                            className="flex items-center gap-2 px-3 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-md transition-colors"
                        >
                            <RefreshCw size={14} /> Clear Cache
                        </button>
                        <button 
                            onClick={handleExportRules}
                            className="flex items-center gap-2 px-3 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-md transition-colors"
                        >
                            <Download size={14} /> Export
                        </button>
                    </div>
                </div>
            </header>

            <div className="flex flex-1 overflow-hidden">
                {/* Sidebar */}
                <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
                    {/* Action Buttons */}
                    <div className="p-2 border-b border-gray-200 flex flex-col gap-2">
                        <button 
                            onClick={handleNewRule} 
                            className="flex items-center justify-center gap-2 text-xs text-white bg-purple-600 hover:bg-purple-700 px-3 py-2 rounded-md transition-colors shadow-sm"
                        >
                            <Plus size={14} /> New Rule
                        </button>
                        <div className="flex gap-2">
                            <button 
                                onClick={handleExecuteRules}
                                disabled={isExecuting}
                                className="flex-1 flex items-center justify-center gap-2 text-xs text-white bg-green-600 hover:bg-green-700 px-3 py-2 rounded-md transition-colors disabled:opacity-50"
                            >
                                {isExecuting ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
                                {isExecuting ? 'Executing...' : 'Execute Rules'}
                            </button>
                            <button 
                                onClick={handleRunReasoner}
                                disabled={isReasoning}
                                className="flex-1 flex items-center justify-center gap-2 text-xs text-gray-700 bg-gray-100 hover:bg-gray-200 px-3 py-2 rounded-md transition-colors disabled:opacity-50"
                            >
                                {isReasoning ? <Loader2 size={14} className="animate-spin" /> : <BrainCircuit size={14} />}
                                {isReasoning ? 'Running...' : 'Reasoner'}
                            </button>
                        </div>
                    </div>

                    {/* Search & Filter */}
                    <div className="p-2 border-b border-gray-200 space-y-2">
                        <input
                            type="text"
                            placeholder="Search rules..."
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                            className="w-full px-3 py-2 text-xs border border-gray-300 rounded-md focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                        />
                        <div className="flex gap-1">
                            <button
                                onClick={() => setFilterEnabled(null)}
                                className={`flex-1 text-xs px-2 py-1 rounded ${filterEnabled === null ? 'bg-purple-100 text-purple-700' : 'bg-gray-100 text-gray-600'}`}
                            >
                                All ({rules.length})
                            </button>
                            <button
                                onClick={() => setFilterEnabled(true)}
                                className={`flex-1 text-xs px-2 py-1 rounded ${filterEnabled === true ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}
                            >
                                Enabled ({rules.filter(r => r.enabled).length})
                            </button>
                            <button
                                onClick={() => setFilterEnabled(false)}
                                className={`flex-1 text-xs px-2 py-1 rounded ${filterEnabled === false ? 'bg-gray-300 text-gray-700' : 'bg-gray-100 text-gray-600'}`}
                            >
                                Disabled ({rules.filter(r => !r.enabled).length})
                            </button>
                        </div>
                    </div>

                    {/* Rules List */}
                    <div className="flex-1 overflow-y-auto">
                        {filteredRules.length === 0 ? (
                            <div className="p-4 text-center text-gray-400 text-sm">
                                {searchTerm ? 'No rules match your search' : 'No rules yet'}
                            </div>
                        ) : (
                            filteredRules.map(rule => (
                                <div
                                    key={rule.id}
                                    className={`p-3 cursor-pointer border-l-4 ${
                                        selectedRule?.id === rule.id
                                            ? 'bg-purple-50 border-purple-500'
                                            : 'border-transparent hover:bg-gray-50'
                                    }`}
                                >
                                    <div 
                                        onClick={() => handleSelectRule(rule)}
                                        className="flex justify-between items-start mb-1"
                                    >
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
                                            className={`w-2 h-2 rounded-full ${
                                                rule.enabled ? 'bg-green-500' : 'bg-gray-400'
                                            }`}
                                            title={rule.enabled ? 'Click to disable' : 'Click to enable'}
                                        />
                                    </div>
                                    {rule.category && (
                                        <div className="text-xs text-purple-600 mb-1">{rule.category}</div>
                                    )}
                                    <p className="text-xs text-gray-500 truncate">{rule.ruleText}</p>
                                </div>
                            ))
                        )}
                    </div>
                </aside>

                {/* Main Content */}
                <main className="flex-1 flex flex-col">
                    {/* Tabs */}
                    <div className="bg-gray-50 border-b border-gray-200 flex">
                        <button
                            onClick={() => setActiveTab('editor')}
                            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium ${
                                activeTab === 'editor'
                                    ? 'bg-white text-purple-600 border-b-2 border-purple-600'
                                    : 'text-gray-600 hover:bg-gray-100'
                            }`}
                        >
                            <FileCode size={16} /> Rule Editor
                        </button>
                        <button
                            onClick={() => setActiveTab('query')}
                            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium ${
                                activeTab === 'query'
                                    ? 'bg-white text-purple-600 border-b-2 border-purple-600'
                                    : 'text-gray-600 hover:bg-gray-100'
                            }`}
                        >
                            <Search size={16} /> SQWRL Query
                        </button>
                        <button
                            onClick={() => setActiveTab('results')}
                            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium ${
                                activeTab === 'results'
                                    ? 'bg-white text-purple-600 border-b-2 border-purple-600'
                                    : 'text-gray-600 hover:bg-gray-100'
                            }`}
                        >
                            <BarChart3 size={16} /> Execution Results
                            {executionResults && (
                                <span className={`px-2 py-0.5 text-xs rounded-full ${
                                    executionResults.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                                }`}>
                                    {executionResults.inferredAxiomsCount}
                                </span>
                            )}
                        </button>
                    </div>

                    {/* Tab Content */}
                    <div className="flex-1 overflow-auto">
                        {activeTab === 'editor' && (
                            <div className="p-4 space-y-4">
                                {/* Rule Name */}
                                <input
                                    type="text"
                                    value={ruleName}
                                    onChange={(e) => setRuleName(e.target.value)}
                                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-lg font-semibold focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                    placeholder="Rule Name"
                                />

                                {/* Category & Comment */}
                                <div className="grid grid-cols-2 gap-4">
                                    <input
                                        type="text"
                                        value={ruleCategory}
                                        onChange={(e) => setRuleCategory(e.target.value)}
                                        className="px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                        placeholder="Category (optional)"
                                    />
                                    <input
                                        type="text"
                                        value={ruleComment}
                                        onChange={(e) => setRuleComment(e.target.value)}
                                        className="px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                        placeholder="Comment (optional)"
                                    />
                                </div>

                                {/* Rule Text */}
                                <textarea
                                    className="w-full h-64 p-3 font-mono text-sm border border-gray-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                    value={ruleText}
                                    onChange={(e) => setRuleText(e.target.value)}
                                    placeholder="Enter SWRL rule... e.g., Pizza(?p) -> Food(?p)"
                                />

                                {/* Validation Feedback */}
                                {validation && (
                                    <div className={`p-4 rounded-lg border ${
                                        validation.valid
                                            ? 'bg-green-50 border-green-200'
                                            : 'bg-red-50 border-red-200'
                                    }`}>
                                        <div className="flex items-start gap-2">
                                            {validation.valid ? (
                                                <CheckCircle size={20} className="text-green-600 flex-shrink-0" />
                                            ) : (
                                                <XCircle size={20} className="text-red-600 flex-shrink-0" />
                                            )}
                                            <div className="flex-1">
                                                <div className={`font-semibold ${
                                                    validation.valid ? 'text-green-800' : 'text-red-800'
                                                }`}>
                                                    {validation.valid ? 'Valid SWRL Syntax' : 'Syntax Error'}
                                                </div>
                                                {validation.errorMessage && (
                                                    <div className={`text-sm mt-1 ${
                                                        validation.valid ? 'text-green-700' : 'text-red-700'
                                                    }`}>
                                                        {validation.errorMessage}
                                                    </div>
                                                )}
                                                {/* ✅ NEW: Display suggestions */}
                                                {!validation.valid && validation.suggestions && validation.suggestions.length > 0 && (
                                                    <ul className="mt-2 space-y-1 text-sm text-red-700">
                                                        {validation.suggestions.map((suggestion, idx) => (
                                                            <li key={idx} className="flex items-start gap-2">
                                                                <span className="flex-shrink-0">💡</span>
                                                                <span>{suggestion}</span>
                                                            </li>
                                                        ))}
                                                    </ul>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                )}

                                {/* Actions */}
                                <div className="flex items-center justify-between">
                                    <div className="flex gap-2">
                                        <button
                                            onClick={handleDeleteRule}
                                            disabled={!selectedRule}
                                            className="flex items-center gap-2 px-4 py-2 text-sm text-red-600 bg-red-50 rounded-lg hover:bg-red-100 disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            <Trash2 size={16} /> Delete
                                        </button>
                                    </div>
                                    <div className="flex gap-2">
                                        <button
                                            onClick={handleSaveRule}
                                            disabled={!validation?.valid || !ruleName}
                                            className="flex items-center gap-2 px-6 py-2 text-sm text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:bg-purple-300 disabled:cursor-not-allowed"
                                        >
                                            <Save size={16} /> {selectedRule ? 'Update' : 'Save'} Rule
                                        </button>
                                    </div>
                                </div>
                            </div>
                        )}

                        {activeTab === 'query' && (
                            <SQWRLQueryPanel projectId={projectId} context={context} />
                        )}

                        {activeTab === 'results' && (
                            <ExecutionResultsPanel results={executionResults} />
                        )}
                    </div>
                </main>
            </div>
        </div>
    );
};

export default SWRLEditor;