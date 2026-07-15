import React, { useState, useEffect, useRef } from 'react';
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
  downgradedWarning?: string;
  tooLargeForReasoner?: boolean;
  suggestedReasoner?: string;
  tripleCount?: number;
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
  const [reasonerType, setReasonerType] = useState<'HERMIT' | 'ELK' | 'STRUCTURAL' | 'PELLET'>('HERMIT');
  const [isRunning, setIsRunning] = useState(false);
  const [result, setResult] = useState<ReasonerResult | null>(null);
  const [inferredAxioms, setInferredAxioms] = useState<InferredAxiom[]>([]);
  const [stats, setStats] = useState<ReasonerStats | null>(null);
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(['summary']));
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const elapsedRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastPathRef = useRef<string | null>(null);

  const toggleSection = (section: string) => {
    const newExpanded = new Set(expandedSections);
    if (newExpanded.has(section)) {
      newExpanded.delete(section);
    } else {
      newExpanded.add(section);
    }
    setExpandedSections(newExpanded);
  };

  const startTimer = () => {
    setElapsedSeconds(0);
    elapsedRef.current = setInterval(() => setElapsedSeconds((s) => s + 1), 1000);
  };

  const stopTimer = () => {
    if (elapsedRef.current) {
      clearInterval(elapsedRef.current);
      elapsedRef.current = null;
    }
  };

  useEffect(() => () => stopTimer(), []);

  const formatElapsed = (s: number) => {
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    const rem = s % 60;
    return rem > 0 ? `${m}m ${rem}s` : `${m}m`;
  };

  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

  const extractErrorMessage = (error: any): string => {
    // Try to get the friendly message from the response body first (backend sets it there)
    const fromBody = error?.response?.data?.message || error?.response?.data?.error;
    if (fromBody) return fromBody;
    // Network / timeout errors
    if (error?.code === 'ECONNABORTED' || error?.message?.toLowerCase().includes('timeout')) {
      return 'Reasoning timed out. Try ELK for large ontologies.';
    }
    if (error?.code === 'ERR_NETWORK' || error?.message?.toLowerCase().includes('network')) {
      return 'Cannot reach the reasoning service. Please check your connection.';
    }
    return error?.message || 'Reasoning failed — please try again.';
  };

  const pollReasoningJob = async (jobId: string, timeoutMs = 30 * 60 * 1000): Promise<ReasonerResult> => {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      const job: any = await apiClient.get(`/api/dl-query/jobs/${jobId}`);
      const status = String(job?.status || '').toUpperCase();
      if (status === 'COMPLETED') {
        return { ...job, success: job.success !== false };
      }
      if (status === 'FAILED') {
        return {
          success: false,
          message: job.error || job.message || 'Reasoning failed',
        };
      }
      await sleep(status === 'QUEUED' ? 2000 : 1500);
    }
    return { success: false, message: 'Reasoning timed out. Try again later.' };
  };

  const postReasoningTask = async (path: string, overrideReasonerType?: string): Promise<ReasonerResult> => {
    const rt = overrideReasonerType ?? reasonerType;
    const response: any = await apiClient.post(path, null, { params: { reasonerType: rt } });
    if (response?.async && response?.jobId) {
      return pollReasoningJob(response.jobId);
    }
    return response;
  };

  // Run full reasoning
  const runFullReasoning = async () => {
    const path = `/api/ontology/${projectId}/reasoner/run`;
    lastPathRef.current = path;
    setIsRunning(true);
    setResult(null);
    setInferredAxioms([]);
    startTimer();

    try {
      const response = await postReasoningTask(path);
      setResult(response);
      if (response.success) {
        fetchInferredAxioms();
      }
    } catch (error: any) {
      setResult({ success: false, message: extractErrorMessage(error) });
    } finally {
      stopTimer();
      setIsRunning(false);
    }
  };

  // Check consistency only
  const checkConsistency = async () => {
    const path = `/api/ontology/${projectId}/reasoner/consistency`;
    lastPathRef.current = path;
    setIsRunning(true);
    setResult(null);
    startTimer();

    try {
      const response = await postReasoningTask(path);
      setResult(response);
    } catch (error: any) {
      setResult({ success: false, message: extractErrorMessage(error) });
    } finally {
      stopTimer();
      setIsRunning(false);
    }
  };

  // Classify ontology
  const classify = async () => {
    const path = `/api/ontology/${projectId}/reasoner/classify`;
    lastPathRef.current = path;
    setIsRunning(true);
    setResult(null);
    startTimer();

    try {
      const response = await postReasoningTask(path);
      setResult(response);
    } catch (error: any) {
      setResult({ success: false, message: extractErrorMessage(error) });
    } finally {
      stopTimer();
      setIsRunning(false);
    }
  };

  // Realize ontology
  const realize = async () => {
    const path = `/api/ontology/${projectId}/reasoner/realize`;
    lastPathRef.current = path;
    setIsRunning(true);
    setResult(null);
    startTimer();

    try {
      const response = await postReasoningTask(path);
      setResult(response);
    } catch (error: any) {
      setResult({ success: false, message: extractErrorMessage(error) });
    } finally {
      stopTimer();
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

  // Auto-expand results section whenever a new result arrives
  useEffect(() => {
    if (result) {
      setExpandedSections(s => new Set([...s, 'summary']));
    }
  }, [result]);

  const switchToElkAndRetry = async () => {
    if (!lastPathRef.current) return;
    setReasonerType('ELK');
    setResult(null);
    setInferredAxioms([]);
    setIsRunning(true);
    startTimer();
    try {
      const response = await postReasoningTask(lastPathRef.current, 'ELK');
      setResult(response);
      if (response.success) {
        fetchInferredAxioms();
      }
    } catch (error: any) {
      setResult({ success: false, message: extractErrorMessage(error) });
    } finally {
      stopTimer();
      setIsRunning(false);
    }
  };

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
            <option value="HERMIT">HermiT (Complete, slow on large files)</option>
            <option value="ELK">ELK (Fast, OWL EL profile)</option>
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

        {/* Too-large-for-reasoner blocking banner */}
        {result?.tooLargeForReasoner && (
          <div className="rounded-xl border-2 border-orange-300 bg-orange-50 p-5 space-y-3">
            <div className="flex items-start gap-3">
              <AlertTriangle size={24} className="text-orange-600 shrink-0 mt-0.5" />
              <div>
                <h3 className="font-semibold text-orange-900 text-base">
                  {reasonerType} cannot handle this ontology
                </h3>
                <p className="text-sm text-orange-800 mt-1">
                  {result.tripleCount
                    ? `This ontology has ${result.tripleCount.toLocaleString()} triples — `
                    : 'This ontology is too large — '}
                  {reasonerType} will not complete at this scale.
                  Only <strong>ELK</strong> is supported for large ontologies.
                </p>
              </div>
            </div>
            <button
              onClick={switchToElkAndRetry}
              disabled={isRunning}
              className="flex items-center gap-2 px-4 py-2 bg-orange-600 hover:bg-orange-700 disabled:bg-orange-300 text-white text-sm font-medium rounded-lg transition-colors"
            >
              <Play size={14} />
              Switch to ELK and retry
            </button>
          </div>
        )}

        {/* Result Summary */}
        {result && !result.tooLargeForReasoner && (
          <Section id="summary" title="Reasoning Results" icon={
            result.success ? 
              <CheckCircle size={20} className="text-green-600" /> : 
              <XCircle size={20} className="text-red-600" />
          }>
            <div className="space-y-4">
              {/* ELK auto-downgrade warning */}
              {result.downgradedWarning && (
                <div className="p-3 rounded-lg bg-amber-50 border border-amber-300 flex items-start gap-2">
                  <AlertTriangle size={16} className="text-amber-600 mt-0.5 shrink-0" />
                  <p className="text-sm text-amber-800">{result.downgradedWarning}</p>
                </div>
              )}

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
                  {result.message || (result as any).error || `Reasoner: ${result.reasonerType || reasonerType}`}
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

              {/* Inconsistency Issues */}
              {(result.inconsistent || result.consistent === false) && (
                <div className="p-4 bg-orange-50 rounded-lg border border-orange-300">
                  <div className="flex items-center gap-2 mb-3">
                    <AlertTriangle size={20} className="text-orange-600" />
                    <h4 className="font-semibold text-orange-800">
                      Ontology is Inconsistent
                      {result.issues && result.issues.length > 0 && ` — ${result.issues.length} issue${result.issues.length === 1 ? '' : 's'} found`}
                    </h4>
                  </div>
                  {result.issues && result.issues.length > 0 ? (
                    <div className="space-y-2 max-h-72 overflow-y-auto">
                      {result.issues.map((issue: any, idx: number) => (
                        <div key={idx} className="p-3 bg-white rounded-lg border border-orange-200">
                          <div className="flex items-start gap-2">
                            <span className="mt-0.5 shrink-0 text-xs font-semibold px-1.5 py-0.5 rounded bg-orange-100 text-orange-700">
                              {issue.type === 'complement_conflict' ? 'Complement' : 'Disjoint'}
                            </span>
                            <div className="min-w-0">
                              <p className="text-sm text-gray-800">{issue.message}</p>
                              {issue.conflictingTypes && (
                                <p className="text-xs text-gray-500 mt-1">
                                  Conflicting types: <span className="font-mono">{issue.conflictingTypes.join(' ⊕ ')}</span>
                                </p>
                              )}
                              <p className="text-xs text-gray-400 font-mono mt-1 truncate">{issue.iri}</p>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-sm text-orange-700">
                      The conflict may involve property restrictions or complex class expressions.
                      Check your <span className="font-semibold">disjoint constraints</span> and <span className="font-semibold">complement definitions</span> in the editor.
                    </p>
                  )}
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

        {/* Loading State — TSRI-161 */}
        {isRunning && !result && (
          <div className="flex items-center justify-center h-64">
            <div className="text-center space-y-3">
              <Loader2 size={48} className="animate-spin text-purple-600 mx-auto" />
              <p className="text-gray-800 font-semibold">Running {reasonerType} reasoner…</p>
              <div className="flex items-center justify-center gap-2 text-purple-700 font-mono text-lg">
                <Clock size={18} />
                <span>{formatElapsed(elapsedSeconds)}</span>
              </div>
              {elapsedSeconds >= 30 && (
                <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-4 py-2 max-w-sm mx-auto">
                  Still running — this is normal.{' '}
                  {reasonerType === 'HERMIT' || reasonerType === 'PELLET'
                    ? 'HermiT/Pellet can take 15–40 minutes on large ontologies (100MB+).'
                    : reasonerType === 'ELK'
                    ? 'ELK is classifying the ontology. Large ontologies (5M+ triples) can take several minutes.'
                    : 'Processing is active.'}
                </p>
              )}
              {elapsedSeconds >= 120 && reasonerType === 'ELK' && (
                <p className="text-xs text-gray-500 max-w-sm mx-auto">
                  ELK covers the OWL EL profile. Cardinality restrictions, allValuesFrom, and complement/union axioms are not inferred.
                </p>
              )}
              {elapsedSeconds >= 120 && (reasonerType === 'HERMIT' || reasonerType === 'PELLET') && (
                <p className="text-xs text-gray-500 max-w-sm mx-auto">
                  Tip: For large ontologies, ELK is significantly faster if your ontology uses the OWL EL profile.
                </p>
              )}
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