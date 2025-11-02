import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Play, Save, CheckCircle, XCircle, FileCode, Search, BrainCircuit, Loader2, DatabaseZap, ChevronDown, ChevronRight } from 'lucide-react';
import apiClient from '../services/apiClient';
import type { SparqlQuery, SparqlQueryResult, OntologyPrefix } from '../types';

const SparqlQueryEditor: React.FC<{ projectId: string; prefixes: OntologyPrefix[] }> = ({ projectId, prefixes }) => {
    const [queries, setQueries] = useState<SparqlQuery[]>([]);
    const [selectedQuery, setSelectedQuery] = useState<SparqlQuery | null>(null);
    const [queryText, setQueryText] = useState('');
    const [queryName, setQueryName] = useState('');
    const [results, setResults] = useState<SparqlQueryResult | null>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [isPrefixesVisible, setPrefixesVisible] = useState(false);

    const fetchQueries = useCallback(async () => {
        try {
            // Mocking API call
            await new Promise(res => setTimeout(res, 300));
            const mockQueries: SparqlQuery[] = [
                { id: 'q1', name: 'All Pizzas', queryText: 'SELECT ?pizza ?label WHERE { ?pizza a :Pizza ; rdfs:label ?label . }' },
                { id: 'q2', name: 'Pizzas with Mozzarella', queryText: 'SELECT ?pizza ?label WHERE { ?pizza a :Pizza ; rdfs:label ?label ; :hasTopping :MozzarellaTopping . }' },
            ];
            setQueries(mockQueries);
        } catch (error) {
            console.error('Failed to fetch SPARQL queries:', error);
        }
    }, [projectId]);

    useEffect(() => {
        fetchQueries();
    }, [fetchQueries]);
    
    const handleSelectQuery = (query: SparqlQuery) => {
        setSelectedQuery(query);
        setQueryText(query.queryText);
        setQueryName(query.name);
        setResults(null);
    };
    
    const handleNewQuery = () => {
        setSelectedQuery(null);
        setQueryText('');
        setQueryName('New Query');
        setResults(null);
    };
    
    const handleSaveQuery = async () => {
        const queryData = { name: queryName, queryText };
        try {
            if (selectedQuery) {
                // Update
                const updatedQuery = { ...selectedQuery, ...queryData };
                setQueries(queries.map(q => q.id === updatedQuery.id ? updatedQuery : q));
                setSelectedQuery(updatedQuery);
            } else {
                // Create
                const newQuery = { ...queryData, id: `q${Date.now()}`};
                setQueries([...queries, newQuery]);
                setSelectedQuery(newQuery);
            }
            console.log("Query saved successfully.");
        } catch (_error) {
            console.error("Failed to save query.");
        }
    };
    
    const handleDeleteQuery = async () => {
        if (!selectedQuery) return;
        if (window.confirm(`Are you sure you want to delete "${selectedQuery.name}"?`)) {
            setQueries(queries.filter(q => q.id !== selectedQuery.id));
            handleNewQuery();
        }
    };

    const handleExecuteQuery = async () => {
        setIsLoading(true);
        setResults(null);
        try {
            // Mocking API call for query execution
            await new Promise(resolve => setTimeout(resolve, 1500));
            const mockResults: SparqlQueryResult = {
                head: { vars: ["pizza", "label"] },
                results: {
                    bindings: [
                        { pizza: { type: 'uri', value: 'http://...#Margherita' }, label: { type: 'literal', value: 'Margherita' } },
                        { pizza: { type: 'uri', value: 'http://...#Americana' }, label: { type: 'literal', value: 'Americana' } },
                    ]
                }
            };
            setResults(mockResults);
        } catch (error) {
            console.error('Failed to execute SPARQL query:', error);
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="h-full flex flex-col bg-gray-100">
            <header className="bg-white p-4 border-b border-gray-200">
                <h1 className="text-xl font-bold text-gray-800">SPARQL Query Editor</h1>
                <p className="text-sm text-gray-500">Execute SPARQL queries against your ontology.</p>
            </header>
            <div className="flex flex-1 overflow-hidden">
                <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
                    <div className="p-2 border-b border-gray-200 flex">
                        <button onClick={handleNewQuery} className="flex-1 flex items-center justify-center gap-2 text-xs text-white bg-purple-600 hover:bg-purple-700 px-3 py-2 rounded-md transition-colors shadow-sm">
                            <Plus size={14} /> New Query
                        </button>
                    </div>
                    <div className="flex-1 overflow-y-auto">
                        {queries.map(query => (
                            <div key={query.id} onClick={() => handleSelectQuery(query)} className={`p-3 cursor-pointer border-l-4 ${selectedQuery?.id === query.id ? 'bg-purple-50 border-purple-500' : 'border-transparent hover:bg-gray-50'}`}>
                                <span className={`text-sm font-medium ${selectedQuery?.id === query.id ? 'text-purple-800' : 'text-gray-800'}`}>{query.name}</span>
                                <p className="text-xs text-gray-500 truncate font-mono">{query.queryText}</p>
                            </div>
                        ))}
                    </div>
                </aside>
                <main className="flex-1 flex flex-col p-4 gap-4">
                    <div className="bg-white border border-gray-200 rounded-lg p-4 space-y-3 flex-shrink-0">
                        <input type="text" value={queryName} onChange={(e) => setQueryName(e.target.value)} className="w-full px-3 py-2 border border-gray-300 rounded-lg text-lg font-semibold focus:ring-2 focus:ring-purple-500 focus:border-transparent" placeholder="Query Name" />
                        <div className="border rounded-md">
                            <button onClick={() => setPrefixesVisible(!isPrefixesVisible)} className="w-full flex items-center justify-between p-2 bg-gray-50 text-xs font-medium text-gray-600 hover:bg-gray-100">
                                <span>Ontology Prefixes</span>
                                {isPrefixesVisible ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                            </button>
                            {isPrefixesVisible && (
                                <div className="p-2 border-t bg-white max-h-24 overflow-y-auto">
                                    <pre className="text-xs text-gray-700 font-mono">
                                        {prefixes.map(p => `PREFIX ${p.prefix} <${p.namespace}>`).join('\n')}
                                    </pre>
                                </div>
                            )}
                        </div>
                        <textarea
                            className="w-full h-48 p-3 font-mono text-sm border border-gray-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                            value={queryText}
                            onChange={(e) => setQueryText(e.target.value)}
                            placeholder="Enter SPARQL query... e.g., SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10"
                        />
                        <div className="flex items-center justify-end gap-2">
                            <button onClick={handleDeleteQuery} disabled={!selectedQuery} className="px-4 py-2 text-sm bg-gray-200 rounded-lg hover:bg-gray-300 disabled:opacity-50 disabled:cursor-not-allowed">
                                <Trash2 size={16} className="text-gray-600" />
                            </button>
                            <button onClick={handleSaveQuery} className="flex items-center gap-2 px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg">
                                <Save size={16} /> Save
                            </button>
                            <button onClick={handleExecuteQuery} disabled={isLoading} className="flex items-center gap-2 px-4 py-2 text-sm text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:bg-purple-300">
                                {isLoading ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />} Execute Query
                            </button>
                        </div>
                    </div>
                    <div className="flex-grow bg-white border border-gray-200 rounded-lg overflow-hidden flex flex-col">
                        <div className="p-3 border-b text-sm font-semibold text-gray-700 bg-gray-50">Query Results</div>
                        {isLoading ? (
                             <div className="flex-1 flex items-center justify-center text-gray-500">
                                <Loader2 size={24} className="animate-spin mr-2" />
                                <span>Executing query...</span>
                            </div>
                        ) : results ? (
                            <div className="overflow-auto flex-1">
                                <table className="min-w-full text-sm">
                                    <thead className="bg-gray-100 sticky top-0">
                                        <tr>
                                            {results.head.vars.map((col) => <th key={col} className="p-2 text-left font-semibold text-gray-600">{col}</th>)}
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-gray-200">
                                        {results.results.bindings.map((binding, rowIndex) => (
                                            <tr key={rowIndex} className="hover:bg-gray-50">
                                                {results.head.vars.map((col) => (
                                                    <td key={col} className="p-2 text-gray-700 whitespace-nowrap">
                                                        <span title={binding[col]?.value}>{binding[col]?.value.split('#').pop() || binding[col]?.value}</span>
                                                    </td>
                                                ))}
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        ) : (
                            <div className="flex-1 flex items-center justify-center text-gray-400">
                                <p>Query results will appear here.</p>
                            </div>
                        )}
                    </div>
                </main>
            </div>
        </div>
    );
};

export default SparqlQueryEditor;
