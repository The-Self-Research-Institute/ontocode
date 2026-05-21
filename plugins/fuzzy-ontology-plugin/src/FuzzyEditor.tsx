import React, { useState, useEffect } from 'react';
import { Sparkles, Plus, Trash2, Save, Play, Download, Loader2 } from 'lucide-react';

declare global {
  interface Window { API_BASE_URL?: string; }
}

interface FuzzyMembership {
  id: string;
  entity: string;
  fuzzyClass: string;
  degree: number;
}

interface FuzzyRule {
  id: string;
  name: string;
  condition: string;
  action: string;
  enabled: boolean;
}

interface FuzzyEditorProps {
  projectId: string;
}

function apiUrl(path: string) {
  const base = (window.API_BASE_URL || '').replace(/\/$/, '');
  return `${base}${path}`;
}

function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem('authToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

const FuzzyEditor: React.FC<FuzzyEditorProps> = ({ projectId }) => {
  const [memberships, setMemberships] = useState<FuzzyMembership[]>([]);
  const [rules, setRules] = useState<FuzzyRule[]>([]);
  const [activeTab, setActiveTab] = useState<'memberships' | 'rules' | 'query'>('memberships');

  const [newEntity, setNewEntity] = useState('');
  const [newFuzzyClass, setNewFuzzyClass] = useState('');
  const [newDegree, setNewDegree] = useState(0.5);

  const [newRuleName, setNewRuleName] = useState('');
  const [newCondition, setNewCondition] = useState('');
  const [newAction, setNewAction] = useState('');

  const [fuzzyQuery, setFuzzyQuery] = useState('');
  const [queryResults, setQueryResults] = useState<any[]>([]);

  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isQuerying, setIsQuerying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  useEffect(() => {
    loadFuzzyData();
  }, [projectId]);

  function showSuccess(msg: string) {
    setSuccessMsg(msg);
    setTimeout(() => setSuccessMsg(null), 3000);
  }

  const loadFuzzyData = async () => {
    setIsLoading(true);
    setError(null);
    try {
      const response = await fetch(apiUrl(`/api/sparql/query/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          query: `
            PREFIX fuzzy: <http://fuzzy.org/ontology#>
            SELECT ?entity ?class ?degree
            WHERE {
              ?entity fuzzy:hasMembership ?membership .
              ?membership fuzzy:inClass ?class ;
                          fuzzy:degree ?degree .
            }
          `
        })
      });

      if (!response.ok) {
        const text = await response.text();
        let msg = `Failed to load fuzzy data (${response.status})`;
        try { msg = JSON.parse(text).error || msg; } catch { /* ignore */ }
        setError(msg);
        return;
      }

      const data = await response.json();
      // Backend returns flat {entity, class, degree} rows in data.results
      const rows: any[] = Array.isArray(data.results) ? data.results : [];
      const loadedMemberships = rows.map((row: any, index: number) => ({
        id: `membership-${index}`,
        entity: row.entity || '',
        fuzzyClass: row.class || '',
        degree: parseFloat(row.degree || '0')
      }));
      setMemberships(loadedMemberships);
    } catch (err) {
      setError(`Network error: ${err}`);
    } finally {
      setIsLoading(false);
    }
  };

  const addMembership = () => {
    if (!newEntity || !newFuzzyClass) {
      setError('Please enter entity and fuzzy class');
      return;
    }
    const membership: FuzzyMembership = {
      id: `membership-${Date.now()}`,
      entity: newEntity,
      fuzzyClass: newFuzzyClass,
      degree: newDegree
    };
    setMemberships([...memberships, membership]);
    setNewEntity('');
    setNewFuzzyClass('');
    setNewDegree(0.5);
  };

  const deleteMembership = (id: string) => {
    setMemberships(memberships.filter(m => m.id !== id));
  };

  const addRule = () => {
    if (!newRuleName || !newCondition || !newAction) {
      setError('Please fill in all rule fields');
      return;
    }
    const rule: FuzzyRule = {
      id: `rule-${Date.now()}`,
      name: newRuleName,
      condition: newCondition,
      action: newAction,
      enabled: true
    };
    setRules([...rules, rule]);
    setNewRuleName('');
    setNewCondition('');
    setNewAction('');
  };

  const deleteRule = (id: string) => {
    setRules(rules.filter(r => r.id !== id));
  };

  const toggleRule = (id: string) => {
    setRules(rules.map(r => r.id === id ? { ...r, enabled: !r.enabled } : r));
  };

  const saveFuzzyOntology = async () => {
    setError(null);
    setIsSaving(true);
    try {
      const insertStatements = memberships.map(m => `
        <${m.entity}> fuzzy:hasMembership [
          fuzzy:inClass <${m.fuzzyClass}> ;
          fuzzy:degree "${m.degree}"^^xsd:float
        ] .
      `).join('\n');

      const sparqlUpdate = `
        PREFIX fuzzy: <http://fuzzy.org/ontology#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        INSERT DATA {
          ${insertStatements}
        }
      `;

      const response = await fetch(apiUrl(`/api/sparql/update/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ query: sparqlUpdate })
      });

      if (!response.ok) {
        const text = await response.text();
        let msg = `Failed to save fuzzy ontology (${response.status})`;
        try { msg = JSON.parse(text).error || msg; } catch { /* ignore */ }
        setError(msg);
      } else {
        showSuccess('Fuzzy ontology saved successfully!');
      }
    } catch (err) {
      setError(`Save error: ${err}`);
    } finally {
      setIsSaving(false);
    }
  };

  const executeFuzzyQuery = async () => {
    if (!fuzzyQuery.trim()) {
      setError('Please enter a SPARQL query');
      return;
    }
    setError(null);
    setIsQuerying(true);
    try {
      const response = await fetch(apiUrl(`/api/sparql/query/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ query: fuzzyQuery })
      });

      if (!response.ok) {
        const text = await response.text();
        let msg = `Query failed (${response.status})`;
        try { msg = JSON.parse(text).error || msg; } catch { /* ignore */ }
        setError(msg);
        return;
      }

      const data = await response.json();
      const rows: any[] = Array.isArray(data.results) ? data.results : [];
      setQueryResults(rows);
    } catch (err) {
      setError(`Query error: ${err}`);
    } finally {
      setIsQuerying(false);
    }
  };

  const exportFuzzyData = () => {
    const turtle = `@prefix fuzzy: <http://fuzzy.org/ontology#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

${memberships.map(m => `<${m.entity}> fuzzy:hasMembership [
    fuzzy:inClass <${m.fuzzyClass}> ;
    fuzzy:degree "${m.degree}"^^xsd:float
] .`).join('\n\n')}`;

    const blob = new Blob([turtle], { type: 'text/turtle' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'fuzzy-ontology.ttl';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="flex flex-col h-full bg-[#1e1e1e] text-gray-200">
      {/* Header */}
      <div className="flex items-center justify-between p-4 border-b border-gray-700">
        <div className="flex items-center gap-2">
          <Sparkles className="w-6 h-6 text-purple-400" />
          <h2 className="text-xl font-bold">Fuzzy Ontology Editor</h2>
        </div>
        <div className="flex gap-2">
          <button
            onClick={saveFuzzyOntology}
            disabled={isSaving}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 rounded"
          >
            {isSaving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
            Save to Ontology
          </button>
          <button
            onClick={exportFuzzyData}
            className="flex items-center gap-2 px-4 py-2 bg-green-600 hover:bg-green-700 rounded"
          >
            <Download className="w-4 h-4" />
            Export Turtle
          </button>
        </div>
      </div>

      {/* Notifications */}
      {error && (
        <div className="mx-4 mt-2 p-3 bg-red-900/40 border border-red-500 rounded text-red-300 text-sm flex items-center justify-between">
          <span>{error}</span>
          <button onClick={() => setError(null)} className="ml-4 text-red-400 hover:text-red-200">✕</button>
        </div>
      )}
      {successMsg && (
        <div className="mx-4 mt-2 p-3 bg-green-900/40 border border-green-500 rounded text-green-300 text-sm">
          {successMsg}
        </div>
      )}

      {/* Tab Navigation */}
      <div className="flex border-b border-gray-700">
        <button
          onClick={() => setActiveTab('memberships')}
          className={`px-6 py-3 ${activeTab === 'memberships' ? 'bg-[#2d2d2d] border-b-2 border-purple-400' : 'hover:bg-[#252525]'}`}
        >
          Fuzzy Memberships
        </button>
        <button
          onClick={() => setActiveTab('rules')}
          className={`px-6 py-3 ${activeTab === 'rules' ? 'bg-[#2d2d2d] border-b-2 border-purple-400' : 'hover:bg-[#252525]'}`}
        >
          Fuzzy Rules
        </button>
        <button
          onClick={() => setActiveTab('query')}
          className={`px-6 py-3 ${activeTab === 'query' ? 'bg-[#2d2d2d] border-b-2 border-purple-400' : 'hover:bg-[#252525]'}`}
        >
          Query Builder
        </button>
      </div>

      {/* Loading overlay for initial data fetch */}
      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="w-8 h-8 animate-spin text-purple-400" />
          <span className="ml-3 text-gray-400">Loading fuzzy data…</span>
        </div>
      )}

      {/* Content */}
      {!isLoading && (
        <div className="flex-1 overflow-auto p-4">
          {activeTab === 'memberships' && (
            <div className="space-y-6">
              {/* Add New Membership */}
              <div className="bg-[#252525] p-4 rounded border border-gray-700">
                <h3 className="text-lg font-semibold mb-4 flex items-center gap-2">
                  <Plus className="w-5 h-5" />
                  Add Fuzzy Membership
                </h3>
                <div className="grid grid-cols-4 gap-4">
                  <input
                    type="text"
                    placeholder="Entity IRI (e.g., :patient1)"
                    value={newEntity}
                    onChange={(e) => setNewEntity(e.target.value)}
                    className="col-span-1 px-3 py-2 bg-[#1e1e1e] border border-gray-600 rounded text-white"
                  />
                  <input
                    type="text"
                    placeholder="Fuzzy Class (e.g., :HighRisk)"
                    value={newFuzzyClass}
                    onChange={(e) => setNewFuzzyClass(e.target.value)}
                    className="col-span-1 px-3 py-2 bg-[#1e1e1e] border border-gray-600 rounded text-white"
                  />
                  <div className="col-span-1 flex items-center gap-2">
                    <label className="text-sm">Degree:</label>
                    <input
                      type="range"
                      min="0"
                      max="1"
                      step="0.01"
                      value={newDegree}
                      onChange={(e) => setNewDegree(parseFloat(e.target.value))}
                      className="flex-1"
                    />
                    <span className="text-sm font-mono w-12">{newDegree.toFixed(2)}</span>
                  </div>
                  <button
                    onClick={addMembership}
                    className="col-span-1 px-4 py-2 bg-purple-600 hover:bg-purple-700 rounded flex items-center justify-center gap-2"
                  >
                    <Plus className="w-4 h-4" />
                    Add
                  </button>
                </div>
              </div>

              {/* Membership List */}
              <div className="bg-[#252525] p-4 rounded border border-gray-700">
                <h3 className="text-lg font-semibold mb-4">Fuzzy Memberships ({memberships.length})</h3>
                <div className="space-y-2">
                  {memberships.length === 0 ? (
                    <p className="text-gray-400 text-center py-8">No fuzzy memberships yet. Add one above!</p>
                  ) : (
                    memberships.map((m) => (
                      <div key={m.id} className="flex items-center justify-between p-3 bg-[#1e1e1e] rounded border border-gray-700">
                        <div className="flex-1 grid grid-cols-3 gap-4">
                          <span className="text-sm font-mono text-blue-400">{m.entity}</span>
                          <span className="text-sm font-mono text-green-400">{m.fuzzyClass}</span>
                          <div className="flex items-center gap-2">
                            <div className="flex-1 bg-gray-700 rounded h-2">
                              <div className="bg-purple-500 h-2 rounded" style={{ width: `${m.degree * 100}%` }} />
                            </div>
                            <span className="text-sm font-mono w-12">{m.degree.toFixed(2)}</span>
                          </div>
                        </div>
                        <button onClick={() => deleteMembership(m.id)} className="ml-4 p-2 text-red-400 hover:bg-red-900/20 rounded">
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          )}

          {activeTab === 'rules' && (
            <div className="space-y-6">
              <div className="bg-[#252525] p-4 rounded border border-gray-700">
                <h3 className="text-lg font-semibold mb-4 flex items-center gap-2">
                  <Plus className="w-5 h-5" />
                  Add Fuzzy Rule
                </h3>
                <div className="space-y-3">
                  <input
                    type="text"
                    placeholder="Rule Name"
                    value={newRuleName}
                    onChange={(e) => setNewRuleName(e.target.value)}
                    className="w-full px-3 py-2 bg-[#1e1e1e] border border-gray-600 rounded text-white"
                  />
                  <textarea
                    placeholder="Condition (e.g., ?x fuzzy:hasMembership ?m AND degree > 0.7)"
                    value={newCondition}
                    onChange={(e) => setNewCondition(e.target.value)}
                    className="w-full px-3 py-2 bg-[#1e1e1e] border border-gray-600 rounded h-20 text-white"
                  />
                  <textarea
                    placeholder="Action (e.g., :requiresMonitoring(?x, true))"
                    value={newAction}
                    onChange={(e) => setNewAction(e.target.value)}
                    className="w-full px-3 py-2 bg-[#1e1e1e] border border-gray-600 rounded h-20 text-white"
                  />
                  <button onClick={addRule} className="px-4 py-2 bg-purple-600 hover:bg-purple-700 rounded flex items-center gap-2">
                    <Plus className="w-4 h-4" />
                    Add Rule
                  </button>
                </div>
              </div>

              <div className="bg-[#252525] p-4 rounded border border-gray-700">
                <h3 className="text-lg font-semibold mb-4">Fuzzy Rules ({rules.length})</h3>
                <div className="space-y-3">
                  {rules.length === 0 ? (
                    <p className="text-gray-400 text-center py-8">No fuzzy rules yet. Add one above!</p>
                  ) : (
                    rules.map((rule) => (
                      <div key={rule.id} className="p-4 bg-[#1e1e1e] rounded border border-gray-700">
                        <div className="flex items-start justify-between mb-2">
                          <h4 className="font-semibold text-purple-400">{rule.name}</h4>
                          <div className="flex items-center gap-2">
                            <label className="flex items-center gap-2 cursor-pointer">
                              <input type="checkbox" checked={rule.enabled} onChange={() => toggleRule(rule.id)} className="w-4 h-4" />
                              <span className="text-sm">Enabled</span>
                            </label>
                            <button onClick={() => deleteRule(rule.id)} className="p-2 text-red-400 hover:bg-red-900/20 rounded">
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </div>
                        </div>
                        <div className="space-y-2 text-sm">
                          <div>
                            <span className="text-gray-400">IF:</span>
                            <pre className="mt-1 p-2 bg-[#2d2d2d] rounded text-xs overflow-x-auto">{rule.condition}</pre>
                          </div>
                          <div>
                            <span className="text-gray-400">THEN:</span>
                            <pre className="mt-1 p-2 bg-[#2d2d2d] rounded text-xs overflow-x-auto">{rule.action}</pre>
                          </div>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          )}

          {activeTab === 'query' && (
            <div className="space-y-6">
              <div className="bg-[#252525] p-4 rounded border border-gray-700">
                <h3 className="text-lg font-semibold mb-4">Fuzzy SPARQL Query</h3>
                <textarea
                  value={fuzzyQuery}
                  onChange={(e) => setFuzzyQuery(e.target.value)}
                  placeholder={`PREFIX fuzzy: <http://fuzzy.org/ontology#>
PREFIX : <http://example.org/medical#>

SELECT ?entity ?class ?degree
WHERE {
  ?entity fuzzy:hasMembership ?membership .
  ?membership fuzzy:inClass ?class ;
              fuzzy:degree ?degree .
  FILTER(?degree > 0.7)
}
ORDER BY DESC(?degree)`}
                  className="w-full px-3 py-2 bg-[#1e1e1e] border border-gray-600 rounded h-64 font-mono text-sm text-white"
                />
                <button
                  onClick={executeFuzzyQuery}
                  disabled={isQuerying}
                  className="mt-3 px-4 py-2 bg-green-600 hover:bg-green-700 disabled:opacity-50 rounded flex items-center gap-2"
                >
                  {isQuerying ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                  Execute Query
                </button>
              </div>

              <div className="bg-[#252525] p-4 rounded border border-gray-700">
                <h3 className="text-lg font-semibold mb-4">Query Results ({queryResults.length})</h3>
                {queryResults.length === 0 ? (
                  <p className="text-gray-400 text-center py-8">No results yet. Execute a query above!</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-gray-700">
                          {Object.keys(queryResults[0] || {}).map((key) => (
                            <th key={key} className="text-left p-2 font-semibold text-purple-400">{key}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {queryResults.map((row, idx) => (
                          <tr key={idx} className="border-b border-gray-800 hover:bg-[#2d2d2d]">
                            {Object.values(row).map((cell: any, cellIdx) => (
                              <td key={cellIdx} className="p-2 font-mono text-xs">{typeof cell === 'string' ? cell : cell?.value ?? '-'}</td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default FuzzyEditor;
