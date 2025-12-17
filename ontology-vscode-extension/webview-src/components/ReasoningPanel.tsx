import React, { useState } from 'react';
import { Play, RefreshCw, CheckCircle, XCircle, AlertTriangle, Clock, Loader2, ChevronDown, ChevronRight, Brain, ListTree } from 'lucide-react';
import apiClient from '../services/apiClient';

interface ReasoningPanelProps {
  projectId: string;
}

interface ReasonerResult {
  consistent?: boolean;
  success?: boolean;
  reasonerType?: string;
  durationMs?: number;
  message?: string;
  unsatisfiableClasses?: Array<{ iri: string; label: string }>;
  inferredAxiomsCount?: number;
  classificationMs?: number;
  realizationMs?: number;
  consistencyCheckMs?: number;
  totalDurationMs?: number;
}

interface InferredAxiom {
  axiomType: string;
  axiom: string;
  readable: string;
}

interface ReasonerStats {
  reasonerName?: string;
  reasonerVersion?: string;
  reasonerType?: string;
  isConsistent?: boolean;
  classCount?: number;
  objectPropertyCount?: number;
  individualCount?: number;
  unsatisfiableClassCount?: number;
}

const ReasoningPanel: React.FC<ReasoningPanelProps> = ({ projectId }) => {
  const [reasonerType, setReasonerType] = useState<'HERMIT' | 'STRUCTURAL' | 'PELLET'>('HERMIT');
  const [isRunning, setIsRunning] = useState(false);
  const [result, setResult] = useState<ReasonerResult | null>(null);
  const [inferredAxioms, setInferredAxioms] = useState<InferredAxiom[]>([]);
  const [stats, setStats] = useState<ReasonerStats | null>(null);
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(['summary']));

  const toggleSection = (section: string) => {
    const newExpanded = new Set(expandedSections);
    if (newExpanded.has(section)) {
      newExpanded.delete(section);
    } else {
      newExpanded.add(section);
    }
    setExpandedSections(newExpanded);
  };

  // Run full reasoning
  const runFullReasoning = async () => {
    setIsRunning(true);
    setResult(null);
    setInferredAxioms([]);
    
    try {
      const response = await apiClient.post<ReasonerResult>(
        `/api/ontology/${projectId}/reasoner/run`,
        null,
        { params: { reasonerType } }
      );
      
      setResult(response);
      
      // If successful, fetch inferred axioms
      if (response.success) {
        fetchInferredAxioms();
      }
      
    } catch (error: any) {
      setResult({
        success: false,
        message: error.message || 'Reasoning failed'
      });
    } finally {
      setIsRunning(false);
    }
  };

  // Check consistency only
  const checkConsistency = async () => {
    setIsRunning(true);
    setResult(null);
    
    try {
      const response = await apiClient.post<ReasonerResult>(
        `/api/ontology/${projectId}/reasoner/consistency`,
        null,
        { params: { reasonerType } }
      );
      
      setResult(response);
      
    } catch (error: any) {
      setResult({
        success: false,
        message: error.message || 'Consistency check failed'
      });
    } finally {
      setIsRunning(false);
    }
  };

  // Classify ontology
  const classify = async () => {
    setIsRunning(true);
    
    try {
      const response = await apiClient.post<ReasonerResult>(
        `/api/ontology/${projectId}/reasoner/classify`,
        null,
        { params: { reasonerType } }
      );
      
      setResult(response);
      
    } catch (error: any) {
      setResult({
        success: false,
        message: error.message || 'Classification failed'
      });
    } finally {
      setIsRunning(false);
    }
  };

  // Realize ontology
  const realize = async () => {
    setIsRunning(true);
    
    try {
      const response = await apiClient.post<ReasonerResult>(
        `/api/ontology/${projectId}/reasoner/realize`,
        null,
        { params: { reasonerType } }
      );
      
      setResult(response);
      
    } catch (error: any) {
      setResult({
        success: false,
        message: error.message || 'Realization failed'
      });
    } finally {
      setIsRunning(false);
    }
  };

  // Fetch inferred axioms
  const fetchInferredAxioms = async () => {
    try {
      const response = await apiClient.get<{
        axioms: InferredAxiom[];
        totalInferredAxioms: number;
      }>(
        `/api/ontology/${projectId}/reasoner/inferred-axioms`,
        { reasonerType }
      );
      
      setInferredAxioms(response.axioms || []);
      
    } catch (error) {
      console.error('Failed to fetch inferred axioms', error);
    }
  };

  // Fetch reasoner stats
  const fetchStats = async () => {
    try {
      const response = await apiClient.get<ReasonerStats>(
        `/api/ontology/${projectId}/reasoner/stats`,
        { reasonerType }
      );
      
      setStats(response);
      
    } catch (error) {
      console.error('Failed to fetch reasoner stats', error);
    }
  };

  // Clear cache
  const clearCache = async () => {
    try {
      await apiClient.post('/api/ontology/reasoner/clear-cache');
      setResult(null);
      setInferredAxioms([]);
      setStats(null);
    } catch (error) {
      console.error('Failed to clear cache', error);
    }
  };

  React.useEffect(() => {
    if (projectId) {
      fetchStats();
    }
  }, [projectId, reasonerType]);

  const Section: React.FC<{
    id: string;
    title: string;
    icon: React.ReactNode;
    children: React.ReactNode;
  }> = ({ id, title, icon, children }) => {
    const isExpanded = expandedSections.has(id);
    
    return (
      <div className="border border-gray-200 rounded-lg overflow-hidden">
        <button
          onClick={() => toggleSection(id)}
          className="w-full flex items-center justify-between p-4 bg-gray-50 hover:bg-gray-100 transition-colors"
        >
          <div className="flex items-center gap-2">
            {icon}
            <h3 className="font-semibold text-gray-800">{title}</h3>
          </div>
          {isExpanded ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
        </button>
        
        {isExpanded && (
          <div className="p-4 bg-white">
            {children}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="h-full flex flex-col bg-white">
      {/* Header */}
      <header className="p-6 border-b border-gray-200 bg-gradient-to-r from-purple-50 to-indigo-50">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="text-2xl font-bold text-gray-800 flex items-center gap-2">
              <Brain className="text-purple-600" size={32} />
              Reasoning & Validation
            </h1>
            <p className="text-sm text-gray-600 mt-1">
              Check consistency, classify, and infer new knowledge
            </p>
          </div>
          
          <button
            onClick={clearCache}
            className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded-lg transition-colors"
          >
            Clear Cache
          </button>
        </div>

        {/* Reasoner Selection */}
        <div className="flex items-center gap-4">
          <label className="text-sm font-medium text-gray-700">
            Reasoner:
          </label>
          <select
            value={reasonerType}
            onChange={(e) => setReasonerType(e.target.value as any)}
            disabled={isRunning}
            className="px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
          >
            <option value="HERMIT">HermiT (Hypertableau)</option>
            <option value="PELLET">Pellet/Openllet</option>
            <option value="STRUCTURAL">Structural (Fast, No Inference)</option>
          </select>

          {/* Action Buttons */}
          <div className="flex gap-2 ml-auto">
            <button
              onClick={checkConsistency}
              disabled={isRunning}
              className="flex items-center gap-2 px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:bg-blue-300 transition-colors"
            >
              {isRunning ? <Loader2 size={16} className="animate-spin" /> : <CheckCircle size={16} />}
              Check Consistency
            </button>
            
            <button
              onClick={classify}
              disabled={isRunning}
              className="flex items-center gap-2 px-4 py-2 text-sm bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:bg-indigo-300 transition-colors"
            >
              {isRunning ? <Loader2 size={16} className="animate-spin" /> : <ListTree size={16} />}
              Classify
            </button>
            
            <button
              onClick={runFullReasoning}
              disabled={isRunning}
              className="flex items-center gap-2 px-4 py-2 text-sm bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 transition-colors shadow-lg"
            >
              {isRunning ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />}
              Run Full Reasoning
            </button>
          </div>
        </div>
      </header>

      {/* Results */}
      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        
        {/* Reasoner Statistics */}
        {stats && (
          <Section id="stats" title="Reasoner Information" icon={<Brain size={20} className="text-purple-600" />}>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div className="p-3 bg-gray-50 rounded-lg">
                <p className="text-xs text-gray-600">Reasoner</p>
                <p className="text-sm font-semibold">{stats.reasonerName}</p>
              </div>
              <div className="p-3 bg-gray-50 rounded-lg">
                <p className="text-xs text-gray-600">Version</p>
                <p className="text-sm font-semibold">{stats.reasonerVersion}</p>
              </div>
              <div className="p-3 bg-gray-50 rounded-lg">
                <p className="text-xs text-gray-600">Classes</p>
                <p className="text-sm font-semibold">{stats.classCount}</p>
              </div>
              <div className="p-3 bg-gray-50 rounded-lg">
                <p className="text-xs text-gray-600">Individuals</p>
                <p className="text-sm font-semibold">{stats.individualCount}</p>
              </div>
            </div>
          </Section>
        )}

        {/* Result Summary */}
        {result && (
          <Section id="summary" title="Reasoning Results" icon={
            result.success ? 
              <CheckCircle size={20} className="text-green-600" /> : 
              <XCircle size={20} className="text-red-600" />
          }>
            <div className="space-y-4">
              {/* Status */}
              <div className={`p-4 rounded-lg ${result.success ? 'bg-green-50 border border-green-200' : 'bg-red-50 border border-red-200'}`}>
                <div className="flex items-center gap-2 mb-2">
                  {result.consistent !== undefined ? (
                    result.consistent ? (
                      <CheckCircle size={20} className="text-green-600" />
                    ) : (
                      <XCircle size={20} className="text-red-600" />
                    )
                  ) : result.success ? (
                    <CheckCircle size={20} className="text-green-600" />
                  ) : (
                    <XCircle size={20} className="text-red-600" />
                  )}
                  <h4 className="font-semibold text-gray-800">
                    {result.consistent !== undefined 
                      ? result.consistent ? 'Ontology is Consistent' : 'Ontology is Inconsistent'
                      : result.message || (result.success ? 'Success' : 'Failed')
                    }
                  </h4>
                </div>
                
                <p className="text-sm text-gray-700 ml-7">
                  {result.message || `Reasoner: ${result.reasonerType || reasonerType}`}
                </p>
              </div>

              {/* Timing */}
              {(result.durationMs || result.totalDurationMs) && (
                <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
                  {result.consistencyCheckMs && (
                    <div className="p-3 bg-blue-50 rounded-lg">
                      <div className="flex items-center gap-2 text-xs text-blue-600 mb-1">
                        <Clock size={14} />
                        Consistency
                      </div>
                      <p className="text-lg font-bold text-blue-800">{result.consistencyCheckMs} ms</p>
                    </div>
                  )}
                  {result.classificationMs && (
                    <div className="p-3 bg-indigo-50 rounded-lg">
                      <div className="flex items-center gap-2 text-xs text-indigo-600 mb-1">
                        <Clock size={14} />
                        Classification
                      </div>
                      <p className="text-lg font-bold text-indigo-800">{result.classificationMs} ms</p>
                    </div>
                  )}
                  {result.realizationMs && (
                    <div className="p-3 bg-purple-50 rounded-lg">
                      <div className="flex items-center gap-2 text-xs text-purple-600 mb-1">
                        <Clock size={14} />
                        Realization
                      </div>
                      <p className="text-lg font-bold text-purple-800">{result.realizationMs} ms</p>
                    </div>
                  )}
                  {(result.totalDurationMs || result.durationMs) && (
                    <div className="p-3 bg-gray-100 rounded-lg">
                      <div className="flex items-center gap-2 text-xs text-gray-600 mb-1">
                        <Clock size={14} />
                        Total Time
                      </div>
                      <p className="text-lg font-bold text-gray-800">{result.totalDurationMs || result.durationMs} ms</p>
                    </div>
                  )}
                </div>
              )}

              {/* Inferred Axioms Count */}
              {result.inferredAxiomsCount !== undefined && (
                <div className="p-4 bg-purple-50 rounded-lg border border-purple-200">
                  <p className="text-sm font-medium text-purple-800">
                    Inferred {result.inferredAxiomsCount} new axioms
                  </p>
                </div>
              )}

              {/* Unsatisfiable Classes */}
              {result.unsatisfiableClasses && result.unsatisfiableClasses.length > 0 && (
                <div className="p-4 bg-red-50 rounded-lg border border-red-200">
                  <div className="flex items-center gap-2 mb-3">
                    <AlertTriangle size={20} className="text-red-600" />
                    <h4 className="font-semibold text-red-800">
                      Unsatisfiable Classes ({result.unsatisfiableClasses.length})
                    </h4>
                  </div>
                  <div className="space-y-2 max-h-60 overflow-y-auto">
                    {result.unsatisfiableClasses.map((cls, idx) => (
                      <div key={idx} className="p-2 bg-white rounded border border-red-200">
                        <p className="text-sm font-medium text-gray-800">{cls.label}</p>
                        <p className="text-xs text-gray-600 font-mono">{cls.iri}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </Section>
        )}

        {/* Inferred Axioms */}
        {inferredAxioms.length > 0 && (
          <Section id="axioms" title={`Inferred Axioms (${inferredAxioms.length})`} icon={<ListTree size={20} className="text-indigo-600" />}>
            <div className="space-y-2 max-h-96 overflow-y-auto">
              {inferredAxioms.map((axiom, idx) => (
                <div key={idx} className="p-3 bg-gray-50 rounded-lg border border-gray-200 hover:bg-gray-100 transition-colors">
                  <div className="flex items-start justify-between">
                    <div className="flex-1">
                      <p className="text-sm font-medium text-gray-800">{axiom.readable}</p>
                      <p className="text-xs text-gray-700 mt-1">{axiom.axiomType}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </Section>
        )}

        {/* Loading State */}
        {isRunning && !result && (
          <div className="flex items-center justify-center h-64">
            <div className="text-center">
              <Loader2 size={48} className="animate-spin text-purple-600 mx-auto mb-4" />
              <p className="text-gray-600">Running {reasonerType} reasoner...</p>
              <p className="text-sm text-gray-700 mt-2">This may take a few moments for large ontologies</p>
            </div>
          </div>
        )}

        {/* Empty State */}
        {!result && !isRunning && (
          <div className="flex items-center justify-center h-64 text-gray-600">
            <div className="text-center">
              <Brain size={64} className="mx-auto mb-4 opacity-20" />
              <p className="text-lg font-medium">No reasoning results yet</p>
              <p className="text-sm mt-2">Select a reasoner and click "Run Full Reasoning" to start</p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ReasoningPanel;