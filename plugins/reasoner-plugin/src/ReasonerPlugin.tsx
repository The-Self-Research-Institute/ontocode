/**
 * OWL Reasoner Plugin - HermiT-inspired
 * Provides consistency checking, classification, realization, and inference
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Play,
  Square,
  CheckCircle,
  XCircle,
  AlertTriangle,
  Clock,
  Database,
  GitBranch,
  Zap,
  FileText,
  Settings,
  ChevronDown,
  ChevronRight,
  Loader,
  RefreshCw,
  Download,
  Info
} from 'lucide-react';

import type {
  ReasonerType,
  ReasoningTask,
  ReasonerConfig,
  ConsistencyResult,
  ClassificationResult,
  RealizationResult,
  ReasonerStatus,
  InferredAxiom,
  ReasonerStats
} from './types';

interface ReasonerPluginProps {
  projectId: string;
  ontologyIri?: string;
  apiBaseUrl?: string;
  onInferredAxiomsChange?: (axioms: InferredAxiom[]) => void;
}

export const ReasonerPlugin: React.FC<ReasonerPluginProps> = ({
  projectId,
  ontologyIri,
  apiBaseUrl = 'http://localhost:8087',
  onInferredAxiomsChange
}) => {
  const [config, setConfig] = useState<ReasonerConfig>({
    reasonerType: 'hermit',
    timeout: 30000,
    useIncrementalReasoning: true,
    cacheResults: true,
    maxConcurrentTasks: 2
  });

  const [status, setStatus] = useState<ReasonerStatus>({
    isRunning: false
  });

  const [consistencyResult, setConsistencyResult] = useState<ConsistencyResult | null>(null);
  const [classificationResult, setClassificationResult] = useState<ClassificationResult | null>(null);
  const [realizationResult, setRealizationResult] = useState<RealizationResult | null>(null);
  const [inferredAxioms, setInferredAxioms] = useState<InferredAxiom[]>([]);
  const [stats, setStats] = useState<ReasonerStats | null>(null);
  const [inconsistencyExplanation, setInconsistencyExplanation] = useState<any | null>(null);

  const [expandedSections, setExpandedSections] = useState({
    config: true,
    help: false,
    tasks: true,
    results: true,
    inferred: false,
    stats: false,
    explanation: true
  });

  const [selectedTask, setSelectedTask] = useState<ReasoningTask>('consistency');
  const [error, setError] = useState<string | null>(null);

  const toggleSection = (section: keyof typeof expandedSections) => {
    setExpandedSections(prev => ({
      ...prev,
      [section]: !prev[section]
    }));
  };

  // Run reasoning task
  const runReasoning = useCallback(async (task: ReasoningTask) => {
    if (!projectId) {
      setError('No project selected');
      return;
    }

    setStatus({ isRunning: true, currentTask: task, progress: 0, message: `Running ${task}...` });
    setError(null);

    try {
      const startTime = Date.now();
      let endpoint = '';
      let method = 'POST';
      
      // Map task to backend endpoint
      switch (task) {
        case 'consistency':
          endpoint = `/plugin-service/api/reasoner/${projectId}/consistency`;
          break;
        case 'classification':
          endpoint = `/plugin-service/api/reasoner/${projectId}/classify`;
          break;
        case 'realization':
          endpoint = `/plugin-service/api/reasoner/${projectId}/realize`;
          break;
        default:
          throw new Error(`Unknown task: ${task}`);
      }

      const response = await fetch(`${apiBaseUrl}${endpoint}`, {
        method: method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reasonerType: config.reasonerType.toUpperCase() })
      });

      if (!response.ok) {
        throw new Error(`Reasoning failed: ${response.statusText}`);
      }

      const result = await response.json();
      const duration = Date.now() - startTime;

      // Update results based on task type
      switch (task) {
        case 'consistency':
          const unsatisfiableClasses = result.unsatisfiableClasses?.map((cls: any) => ({
            iri: cls.iri,
            label: cls.label || cls.iri
          })) || [];
          
          setConsistencyResult({ 
            isConsistent: result.consistent || false,
            duration: result.durationMs || duration,
            timestamp: new Date().toISOString(),
            errors: unsatisfiableClasses.map((cls: any) => `Unsatisfiable class: ${cls.label}`),
            unsatisfiableClasses: unsatisfiableClasses
          });
          
          // Get inferred axioms if consistent
          if (result.consistent) {
            try {
              const axiomsRes = await fetch(`${apiBaseUrl}/plugin-service/api/reasoner/${projectId}/inferred-axioms?reasonerType=${config.reasonerType.toUpperCase()}`);
              if (axiomsRes.ok) {
                const axiomsData = await axiomsRes.json();
                if (axiomsData.axioms) {
                  const inferredAxioms: InferredAxiom[] = axiomsData.axioms.map((ax: any) => ({
                    type: ax.axiomType,
                    subject: ax.readable || ax.axiom,
                    object: '',
                    confidence: 1.0
                  }));
                  setInferredAxioms(inferredAxioms);
                  onInferredAxiomsChange?.(inferredAxioms);
                }
              }
            } catch (err) {
              console.warn('Failed to fetch inferred axioms:', err);
            }
          }
          break;
          
        case 'classification':
          // Map unsatisfiable classes to proper format
          const classUnsatisfiable = (result.unsatisfiableClasses || []).map((item: any) => {
            if (typeof item === 'string') {
              return item; // Just IRI string
            } else if (item.iri) {
              return item.iri; // Object with iri property
            }
            return item;
          });
          
          setClassificationResult({
            timestamp: new Date().toISOString(),
            duration: result.durationMs || duration,
            classHierarchy: result.classHierarchy || [],
            equivalentClasses: result.equivalentClasses || [],
            unsatisfiableClasses: classUnsatisfiable
          });
          break;
          
        case 'realization':
          setRealizationResult({
            timestamp: new Date().toISOString(),
            duration: result.durationMs || duration,
            instances: result.instances || []
          });
          break;
      }

      // Get stats
      try {
        const statsRes = await fetch(`${apiBaseUrl}/plugin-service/api/reasoner/${projectId}/stats?reasonerType=${config.reasonerType.toUpperCase()}`);
        if (statsRes.ok) {
          const statsData = await statsRes.json();
          console.log('Stats data received:', statsData); // Debug log
          
          // Handle -1 value for unsatisfiableClasses (means inconsistent ontology)
          const unsatCount = statsData.unsatisfiableClasses === -1 ? 0 : (statsData.unsatisfiableClasses || 0);
          const isConsistent = statsData.isConsistent !== false;
          
          setStats({
            totalClasses: statsData.classCount || 0,
            totalIndividuals: statsData.individualCount || 0,
            totalProperties: statsData.propertyCount || 0,
            satisfiableClasses: statsData.satisfiableClasses || 0,
            unsatisfiableClasses: unsatCount,
            inferredAxioms: statsData.logicalAxiomCount || statsData.inferredAxioms || 0,
            lastReasoningTime: result.durationMs || duration
          });
        }
      } catch (err) {
        console.warn('Failed to fetch reasoner stats:', err);
      }

      setStatus({ isRunning: false, message: `${task} completed successfully` });
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'Unknown error';
      setError(errorMsg);
      setStatus({ isRunning: false, message: `${task} failed` });
    }
  }, [projectId, ontologyIri, apiBaseUrl, config, onInferredAxiomsChange]);

  // Explain inconsistency
  const explainInconsistency = useCallback(async () => {
    if (!projectId) {
      setError('No project selected');
      return;
    }

    setStatus({ isRunning: true, currentTask: 'explanation', progress: 0, message: 'Explaining inconsistency...' });
    setError(null);

    try {
      const endpoint = `/plugin-service/api/reasoner/${projectId}/explain-inconsistency`;

      const response = await fetch(`${apiBaseUrl}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reasonerType: config.reasonerType.toUpperCase() })
      });

      if (!response.ok) {
        throw new Error(`Failed to explain inconsistency: ${response.statusText}`);
      }

      const result = await response.json();
      setInconsistencyExplanation(result);
      setStatus({ isRunning: false, message: 'Explanation completed' });
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'Unknown error';
      setError(errorMsg);
      setStatus({ isRunning: false, message: 'Explanation failed' });
    }
  }, [projectId, apiBaseUrl, config]);

  // Stop reasoning
  const stopReasoning = useCallback(async () => {
    try {
      setStatus({ isRunning: false, message: 'Reasoning stopped' });
    } catch (err) {
      console.error('Failed to stop reasoning:', err);
    }
  }, [projectId, apiBaseUrl]);

  // Export results
  const exportResults = useCallback(() => {
    const results = {
      consistency: consistencyResult,
      classification: classificationResult,
      realization: realizationResult,
      inferredAxioms,
      stats,
      exportDate: new Date().toISOString()
    };

    const blob = new Blob([JSON.stringify(results, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `reasoner-results-${projectId}-${Date.now()}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }, [consistencyResult, classificationResult, realizationResult, inferredAxioms, stats, projectId]);

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <div style={styles.headerTitle}>
          <Zap size={20} style={{ color: '#667eea' }} />
          <span style={styles.title}>OWL Reasoner</span>
        </div>
        <div style={styles.headerActions}>
          <button
            style={{ ...styles.iconButton, opacity: !consistencyResult ? 0.5 : 1 }}
            onClick={exportResults}
            disabled={!consistencyResult}
            title="Export results"
          >
            <Download size={16} />
          </button>
        </div>
      </div>

      {error && (
        <div style={styles.errorBanner}>
          <AlertTriangle size={16} />
          <span>{error}</span>
        </div>
      )}

      {/* Status Bar */}
      {status.isRunning && (
        <div style={styles.statusBar}>
          <Loader size={16} style={{ animation: 'spin 1s linear infinite' }} />
          <span>{status.message}</span>
          {status.progress !== undefined && (
            <div style={styles.progressBar}>
              <div style={{ ...styles.progressFill, width: `${status.progress}%` }} />
            </div>
          )}
        </div>
      )}

      <div style={styles.scrollContent}>
        {/* Configuration Section */}
        <div style={styles.section}>
          <div style={styles.sectionHeader} onClick={() => toggleSection('config')}>
            {expandedSections.config ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            <Settings size={16} />
            <span style={styles.sectionTitle}>Configuration</span>
          </div>
          {expandedSections.config && (
            <div style={styles.sectionContent}>
              <div style={styles.formGroup}>
                <label style={styles.label}>Reasoner Type</label>
                <select
                  style={styles.select}
                  value={config.reasonerType}
                  onChange={(e) => setConfig({ ...config, reasonerType: e.target.value as ReasonerType })}
                  disabled={status.isRunning}
                >
                  <option value="hermit">HermiT (Hypertableau)</option>
                  <option value="pellet">Pellet</option>
                  <option value="fact++">FaCT++</option>
                  <option value="elk">ELK</option>
                </select>
              </div>

              <div style={styles.formGroup}>
                <label style={styles.label}>Timeout (seconds)</label>
                <input
                  type="number"
                  style={styles.input}
                  value={config.timeout / 1000}
                  onChange={(e) => setConfig({ ...config, timeout: parseInt(e.target.value) * 1000 })}
                  min={5}
                  max={300}
                  disabled={status.isRunning}
                />
              </div>

              <div style={styles.checkboxGroup}>
                <label style={styles.checkboxLabel}>
                  <input
                    type="checkbox"
                    checked={config.useIncrementalReasoning}
                    onChange={(e) => setConfig({ ...config, useIncrementalReasoning: e.target.checked })}
                    disabled={status.isRunning}
                  />
                  <span>Use Incremental Reasoning</span>
                </label>
              </div>

              <div style={styles.checkboxGroup}>
                <label style={styles.checkboxLabel}>
                  <input
                    type="checkbox"
                    checked={config.cacheResults}
                    onChange={(e) => setConfig({ ...config, cacheResults: e.target.checked })}
                    disabled={status.isRunning}
                  />
                  <span>Cache Results</span>
                </label>
              </div>
            </div>
          )}
        </div>

        {/* About HermiT / Reasoner Info Section */}
        <div style={styles.section}>
          <div style={styles.sectionHeader} onClick={() => toggleSection('help')}>
            {expandedSections.help ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            <Info size={16} />
            <span style={styles.sectionTitle}>About OWL Reasoning</span>
          </div>
          {expandedSections.help && (
            <div style={styles.sectionContent}>
              <div style={styles.helpSection}>
                <h4 style={styles.helpTitle}>🧠 What is Reasoning?</h4>
                <p style={styles.helpText}>
                  Reasoners automatically compute logical inferences from your ontology—discovering 
                  relationships and knowledge that isn't explicitly stated.
                </p>

                <h4 style={styles.helpTitle}>⚡ HermiT - Hypertableau Advantage</h4>
                <p style={styles.helpText}>
                  <strong>HermiT</strong> uses a novel <em>hypertableau calculus</em> algorithm that reduces 
                  non-deterministic guessing, making it faster and more robust for complex ontologies 
                  compared to standard tableau reasoners.
                </p>

                <h4 style={styles.helpTitle}>🎯 Core Capabilities</h4>
                <ul style={styles.helpList}>
                  <li><strong>Consistency Checking:</strong> Detects logical contradictions in your ontology</li>
                  <li><strong>Class Classification:</strong> Computes the inferred class hierarchy automatically</li>
                  <li><strong>Instance Realization:</strong> Determines which classes individuals belong to</li>
                  <li><strong>Property Classification:</strong> Organizes properties into hierarchies</li>
                </ul>

                <h4 style={styles.helpTitle}>📊 Understanding Results</h4>
                <div style={styles.helpTable}>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>✅ Consistent</span>
                    <span style={styles.helpValue}>No logical contradictions found</span>
                  </div>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>❌ Inconsistent</span>
                    <span style={styles.helpValue}>Logical contradictions detected (e.g., disjoint classes)</span>
                  </div>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>🟡 Unsatisfiable</span>
                    <span style={styles.helpValue}>Classes that cannot have any instances</span>
                  </div>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>🔍 Inferred Axioms</span>
                    <span style={styles.helpValue}>New knowledge discovered through logical inference</span>
                  </div>
                </div>

                <h4 style={styles.helpTitle}>🔬 Reasoner Comparison</h4>
                <div style={styles.helpTable}>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>HermiT</span>
                    <span style={styles.helpValue}>Best for complex ontologies, OWL 2 DL compliant</span>
                  </div>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>Pellet</span>
                    <span style={styles.helpValue}>Good all-rounder, supports SWRL rules</span>
                  </div>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>FaCT++</span>
                    <span style={styles.helpValue}>Fast for large TBoxes, optimized for classification</span>
                  </div>
                  <div style={styles.helpRow}>
                    <span style={styles.helpLabel}>ELK</span>
                    <span style={styles.helpValue}>Extremely fast for EL++ profile ontologies</span>
                  </div>
                </div>

                <h4 style={styles.helpTitle}>💡 Tips</h4>
                <ul style={styles.helpList}>
                  <li>Start with <strong>Consistency Check</strong> to ensure your ontology is logically sound</li>
                  <li>Use <strong>Classification</strong> to discover the inferred class hierarchy</li>
                  <li>Run <strong>Realization</strong> to find all types for your individuals</li>
                  <li>Enable <strong>Incremental Reasoning</strong> for faster updates after small changes</li>
                  <li>Yellow highlights in Protégé indicate inferred relationships</li>
                </ul>
              </div>
            </div>
          )}
        </div>

        {/* Reasoning Tasks Section */}
        <div style={styles.section}>
          <div style={styles.sectionHeader} onClick={() => toggleSection('tasks')}>
            {expandedSections.tasks ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            <Play size={16} />
            <span style={styles.sectionTitle}>Reasoning Tasks</span>
          </div>
          {expandedSections.tasks && (
            <div style={styles.sectionContent}>
              <div style={styles.taskGrid}>
                <button
                  style={styles.taskButton}
                  onClick={() => runReasoning('consistency')}
                  disabled={status.isRunning}
                >
                  <CheckCircle size={18} />
                  <span>Check Consistency</span>
                </button>

                <button
                  style={styles.taskButton}
                  onClick={() => runReasoning('classification')}
                  disabled={status.isRunning}
                >
                  <GitBranch size={18} />
                  <span>Classify</span>
                </button>

                <button
                  style={styles.taskButton}
                  onClick={() => runReasoning('realization')}
                  disabled={status.isRunning}
                >
                  <Database size={18} />
                  <span>Realize Instances</span>
                </button>

                {consistencyResult && !consistencyResult.isConsistent && (
                  <button
                    style={{...styles.taskButton, borderColor: '#f59e0b', backgroundColor: '#fffbeb'}}
                    onClick={explainInconsistency}
                    disabled={status.isRunning}
                  >
                    <AlertTriangle size={18} style={{ color: '#f59e0b' }} />
                    <span style={{ color: '#f59e0b' }}>Explain Inconsistency</span>
                  </button>
                )}

                {status.isRunning && (
                  <button style={styles.stopButton} onClick={stopReasoning}>
                    <Square size={18} />
                    <span>Stop</span>
                  </button>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Results Section */}
        <div style={styles.section}>
          <div style={styles.sectionHeader} onClick={() => toggleSection('results')}>
            {expandedSections.results ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            <FileText size={16} />
            <span style={styles.sectionTitle}>Results</span>
          </div>
          {expandedSections.results && (
            <div style={styles.sectionContent}>
              {/* Consistency Result */}
              {consistencyResult && (
                <div style={styles.resultCard}>
                  <div style={styles.resultHeader}>
                    {consistencyResult.isConsistent ? (
                      <CheckCircle size={16} style={{ color: '#10b981' }} />
                    ) : (
                      <XCircle size={16} style={{ color: '#ef4444' }} />
                    )}
                    <span style={styles.resultTitle}>Consistency Check</span>
                    <span style={styles.resultTime}>
                      <Clock size={12} />
                      {consistencyResult.duration}ms
                    </span>
                  </div>
                  <div style={styles.resultBody}>
                    <p style={{
                      ...styles.resultText,
                      color: consistencyResult.isConsistent ? '#10b981' : '#ef4444',
                      fontWeight: '600',
                      fontSize: '15px'
                    }}>
                      Ontology is {consistencyResult.isConsistent ? 'consistent ✓' : 'inconsistent ✗'}
                    </p>
                    
                    {!consistencyResult.isConsistent && consistencyResult.unsatisfiableClasses && consistencyResult.unsatisfiableClasses.length > 0 && (
                      <div style={styles.unsatisfiableSection}>
                        <div style={styles.unsatisfiableHeader}>
                          <AlertTriangle size={16} style={{ color: '#ef4444' }} />
                          <span style={styles.unsatisfiableTitle}>
                            Unsatisfiable Classes ({consistencyResult.unsatisfiableClasses.length})
                          </span>
                        </div>
                        <div style={styles.unsatisfiableList}>
                          {consistencyResult.unsatisfiableClasses.map((cls, idx) => (
                            <div key={idx} style={styles.unsatisfiableItem}>
                              <span style={styles.unsatisfiableLabel}>{cls.label}</span>
                              <span style={styles.unsatisfiableIri}>{cls.iri}</span>
                            </div>
                          ))}
                        </div>
                        <div style={styles.unsatisfiableHint}>
                          <Info size={14} />
                          <span>
                            These classes cannot have any instances due to logical contradictions. 
                            Click "Explain Inconsistency" to understand why.
                          </span>
                        </div>
                      </div>
                    )}

                    {consistencyResult.errors && consistencyResult.errors.length > 0 && (
                      <div style={styles.errorList}>
                        {consistencyResult.errors.slice(0, 5).map((err, idx) => (
                          <div key={idx} style={styles.errorItem}>{err}</div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Classification Result */}
              {classificationResult && (
                <div style={styles.resultCard}>
                  <div style={styles.resultHeader}>
                    <GitBranch size={16} style={{ color: '#667eea' }} />
                    <span style={styles.resultTitle}>Classification</span>
                    <span style={styles.resultTime}>
                      <Clock size={12} />
                      {classificationResult.duration}ms
                    </span>
                  </div>
                  <div style={styles.resultBody}>
                    <div style={styles.statRow}>
                      <span>Class Hierarchy Nodes:</span>
                      <span style={styles.statValue}>{classificationResult.classHierarchy.length}</span>
                    </div>
                    <div style={styles.statRow}>
                      <span>Equivalent Classes:</span>
                      <span style={styles.statValue}>{classificationResult.equivalentClasses.length}</span>
                    </div>
                    <div style={styles.statRow}>
                      <span>Unsatisfiable Classes:</span>
                      <span style={{ ...styles.statValue, color: classificationResult.unsatisfiableClasses.length > 0 ? '#ef4444' : '#10b981' }}>
                        {classificationResult.unsatisfiableClasses.length}
                      </span>
                    </div>
                    {classificationResult.unsatisfiableClasses.length > 0 && (
                      <div style={styles.unsatisfiableSection}>
                        <div style={styles.unsatisfiableHeader}>
                          <AlertTriangle size={14} style={{ color: '#ef4444' }} />
                          <span style={styles.unsatisfiableTitle}>
                            Unsatisfiable Classes Found
                          </span>
                        </div>
                        <div style={styles.unsatisfiableList}>
                          {classificationResult.unsatisfiableClasses.slice(0, 5).map((cls, idx) => (
                            <div key={idx} style={styles.unsatisfiableItem}>
                              <span style={styles.unsatisfiableLabel}>{typeof cls === 'string' ? cls.split('#').pop() || cls.split('/').pop() || cls : cls}</span>
                              <span style={styles.unsatisfiableIri}>{typeof cls === 'string' ? cls : cls}</span>
                            </div>
                          ))}
                          {classificationResult.unsatisfiableClasses.length > 5 && (
                            <div style={styles.moreItems}>
                              +{classificationResult.unsatisfiableClasses.length - 5} more
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Realization Result */}
              {realizationResult && (
                <div style={styles.resultCard}>
                  <div style={styles.resultHeader}>
                    <Database size={16} style={{ color: '#06b6d4' }} />
                    <span style={styles.resultTitle}>Realization</span>
                    <span style={styles.resultTime}>
                      <Clock size={12} />
                      {realizationResult.duration}ms
                    </span>
                  </div>
                  <div style={styles.resultBody}>
                    <div style={styles.statRow}>
                      <span>Individuals Realized:</span>
                      <span style={styles.statValue}>{realizationResult.instances.length}</span>
                    </div>
                    {realizationResult.instances.length > 0 && (
                      <div style={{ marginTop: '12px', maxHeight: '200px', overflowY: 'auto' }}>
                        <div style={{ fontSize: '13px', fontWeight: '600', marginBottom: '8px', color: '#374151' }}>
                          Instance Type Mappings:
                        </div>
                        {realizationResult.instances.slice(0, 10).map((inst: any, idx: number) => (
                          <div key={idx} style={{
                            padding: '6px 10px',
                            marginBottom: '6px',
                            backgroundColor: '#f0f9ff',
                            border: '1px solid #bae6fd',
                            borderRadius: '4px',
                            fontSize: '12px'
                          }}>
                            <div style={{ fontWeight: '600', color: '#0c4a6e' }}>
                              {inst.individualLabel || inst.individualIri?.split('#').pop() || 'Unknown'}
                            </div>
                            <div style={{ fontSize: '11px', color: '#0369a1', marginTop: '2px' }}>
                              → {inst.classLabel || inst.classIri?.split('#').pop() || 'Unknown'}
                            </div>
                          </div>
                        ))}
                        {realizationResult.instances.length > 10 && (
                          <div style={styles.moreItems}>
                            +{realizationResult.instances.length - 10} more instances
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              )}

              {!consistencyResult && !classificationResult && !realizationResult && (
                <div style={styles.emptyState}>
                  <Info size={24} style={{ color: '#9ca3af' }} />
                  <p style={styles.emptyText}>Run a reasoning task to see results</p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Inconsistency Explanation Section */}
        {inconsistencyExplanation && (
          <div style={styles.section}>
            <div style={styles.sectionHeader} onClick={() => toggleSection('explanation')}>
              {expandedSections.explanation ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
              <AlertTriangle size={16} style={{ color: '#f59e0b' }} />
              <span style={styles.sectionTitle}>Inconsistency Explanation</span>
            </div>
            {expandedSections.explanation && (
              <div style={styles.sectionContent}>
                <div style={styles.explanationContainer}>
                  <div style={styles.explanationHeader}>
                    <XCircle size={20} style={{ color: '#ef4444' }} />
                    <div>
                      <h3 style={styles.explanationTitle}>Why is the ontology inconsistent?</h3>
                      <p style={styles.explanationSubtitle}>
                        The reasoner detected {inconsistencyExplanation.unsatisfiableClasses?.length || 0} unsatisfiable class(es)
                      </p>
                    </div>
                  </div>

                  {inconsistencyExplanation.unsatisfiableClasses && inconsistencyExplanation.unsatisfiableClasses.length > 0 && (
                    <div style={styles.explanationContent}>
                      <h4 style={styles.explanationSectionTitle}>Unsatisfiable Classes</h4>
                      <div style={styles.classExplanationList}>
                        {inconsistencyExplanation.unsatisfiableClasses.map((cls: any, idx: number) => (
                          <div key={idx} style={styles.classExplanationCard}>
                            <div style={styles.classExplanationHeader}>
                              <span style={styles.classExplanationName}>{cls.label || cls.classLabel}</span>
                              <span style={styles.classExplanationBadge}>Unsatisfiable</span>
                            </div>
                            <div style={styles.classExplanationIri}>{cls.iri || cls.classIri}</div>
                            {cls.reason && (
                              <div style={styles.classExplanationReason}>
                                <strong>Reason:</strong> {cls.reason}
                              </div>
                            )}
                            {cls.axioms && cls.axioms.length > 0 && (
                              <div style={styles.classExplanationAxioms}>
                                <strong>Conflicting Axioms:</strong>
                                <ul style={styles.axiomExplanationList}>
                                  {cls.axioms.map((axiom: string, axIdx: number) => (
                                    <li key={axIdx} style={styles.axiomExplanationItem}>{axiom}</li>
                                  ))}
                                </ul>
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {inconsistencyExplanation.explanations && inconsistencyExplanation.explanations.length > 0 && (
                    <div style={styles.explanationContent}>
                      <h4 style={styles.explanationSectionTitle}>Detailed Explanations</h4>
                      <div style={styles.detailedExplanationList}>
                        {inconsistencyExplanation.explanations.map((exp: any, idx: number) => (
                          <div key={idx} style={styles.detailedExplanationCard}>
                            <div style={styles.detailedExplanationHeader}>
                              <span style={styles.detailedExplanationTitle}>Explanation {idx + 1}</span>
                              {exp.classLabel && (
                                <span style={styles.detailedExplanationClass}>{exp.classLabel}</span>
                              )}
                            </div>
                            {exp.reason && (
                              <p style={styles.detailedExplanationText}>{exp.reason}</p>
                            )}
                            {exp.axioms && exp.axioms.length > 0 && (
                              <div style={styles.axiomsList}>
                                {exp.axioms.map((axiom: string, axIdx: number) => (
                                  <div key={axIdx} style={styles.axiomItem2}>{axiom}</div>
                                ))}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  <div style={styles.explanationFooter}>
                    <Info size={16} />
                    <p>
                      To fix these issues, review the conflicting axioms and modify your ontology to remove logical contradictions.
                      Common causes include disjoint classes with shared instances, conflicting cardinality restrictions, or 
                      contradictory property assertions.
                    </p>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* Inferred Axioms Section */}
        {inferredAxioms.length > 0 && (
          <div style={styles.section}>
            <div style={styles.sectionHeader} onClick={() => toggleSection('inferred')}>
              {expandedSections.inferred ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
              <Zap size={16} />
              <span style={styles.sectionTitle}>Inferred Axioms ({inferredAxioms.length})</span>
            </div>
            {expandedSections.inferred && (
              <div style={styles.sectionContent}>
                <div style={styles.axiomList}>
                  {inferredAxioms.slice(0, 50).map((axiom, idx) => (
                    <div key={idx} style={styles.axiomItem}>
                      <span style={styles.axiomType}>{axiom.type}</span>
                      <span style={styles.axiomText}>
                        {axiom.subject} → {axiom.object}
                      </span>
                      <span style={styles.axiomConfidence}>{(axiom.confidence * 100).toFixed(0)}%</span>
                    </div>
                  ))}
                  {inferredAxioms.length > 50 && (
                    <div style={styles.moreItems}>
                      +{inferredAxioms.length - 50} more axioms
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Statistics Section */}
        {stats && (
          <div style={styles.section}>
            <div style={styles.sectionHeader} onClick={() => toggleSection('stats')}>
              {expandedSections.stats ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
              <Database size={16} />
              <span style={styles.sectionTitle}>Statistics</span>
            </div>
            {expandedSections.stats && (
              <div style={styles.sectionContent}>
                <div style={styles.statsGrid}>
                  <div style={styles.statCard}>
                    <span style={styles.statLabel}>Total Classes</span>
                    <span style={styles.statNumber}>{stats.totalClasses}</span>
                  </div>
                  <div style={styles.statCard}>
                    <span style={styles.statLabel}>Individuals</span>
                    <span style={styles.statNumber}>{stats.totalIndividuals}</span>
                  </div>
                  <div style={styles.statCard}>
                    <span style={styles.statLabel}>Properties</span>
                    <span style={styles.statNumber}>{stats.totalProperties}</span>
                  </div>
                  <div style={styles.statCard}>
                    <span style={styles.statLabel}>Satisfiable</span>
                    <span style={{ ...styles.statNumber, color: '#10b981' }}>{stats.satisfiableClasses}</span>
                  </div>
                  <div style={styles.statCard}>
                    <span style={styles.statLabel}>Unsatisfiable</span>
                    <span style={{ ...styles.statNumber, color: '#ef4444' }}>{stats.unsatisfiableClasses}</span>
                  </div>
                  <div style={styles.statCard}>
                    <span style={styles.statLabel}>Inferred Axioms</span>
                    <span style={styles.statNumber}>{stats.inferredAxioms}</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    backgroundColor: '#ffffff',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '12px 16px',
    borderBottom: '1px solid #e5e7eb',
    backgroundColor: '#f9fafb',
  },
  headerTitle: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
  },
  title: {
    fontSize: '16px',
    fontWeight: '600',
    color: '#1f2937',
  },
  headerActions: {
    display: 'flex',
    gap: '8px',
  },
  iconButton: {
    padding: '6px',
    border: 'none',
    borderRadius: '4px',
    backgroundColor: 'transparent',
    cursor: 'pointer',
    color: '#6b7280',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  errorBanner: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '12px 16px',
    backgroundColor: '#fef2f2',
    borderBottom: '1px solid #fecaca',
    color: '#991b1b',
    fontSize: '14px',
  },
  statusBar: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    padding: '12px 16px',
    backgroundColor: '#eff6ff',
    borderBottom: '1px solid #bfdbfe',
    color: '#1e40af',
    fontSize: '14px',
  },
  progressBar: {
    flex: 1,
    height: '6px',
    backgroundColor: '#dbeafe',
    borderRadius: '3px',
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    backgroundColor: '#3b82f6',
    transition: 'width 0.3s ease',
  },
  scrollContent: {
    flex: 1,
    overflowY: 'auto',
    padding: '16px',
  },
  section: {
    marginBottom: '16px',
    border: '1px solid #e5e7eb',
    borderRadius: '8px',
    overflow: 'hidden',
  },
  sectionHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '12px 16px',
    backgroundColor: '#f9fafb',
    cursor: 'pointer',
    userSelect: 'none',
  },
  sectionTitle: {
    fontSize: '14px',
    fontWeight: '600',
    color: '#1f2937',
  },
  sectionContent: {
    padding: '16px',
  },
  formGroup: {
    marginBottom: '16px',
  },
  label: {
    display: 'block',
    fontSize: '13px',
    fontWeight: '500',
    color: '#374151',
    marginBottom: '6px',
  },
  select: {
    width: '100%',
    padding: '8px 12px',
    border: '1px solid #d1d5db',
    borderRadius: '6px',
    fontSize: '14px',
    backgroundColor: '#ffffff',
  },
  input: {
    width: '100%',
    padding: '8px 12px',
    border: '1px solid #d1d5db',
    borderRadius: '6px',
    fontSize: '14px',
  },
  checkboxGroup: {
    marginBottom: '12px',
  },
  checkboxLabel: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    fontSize: '14px',
    color: '#374151',
    cursor: 'pointer',
  },
  taskGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
    gap: '12px',
  },
  taskButton: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '8px',
    padding: '16px',
    border: '1px solid #e5e7eb',
    borderRadius: '8px',
    backgroundColor: '#ffffff',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500',
    color: '#374151',
    transition: 'all 0.2s',
  },
  stopButton: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '8px',
    padding: '16px',
    border: '1px solid #fecaca',
    borderRadius: '8px',
    backgroundColor: '#fef2f2',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500',
    color: '#991b1b',
  },
  resultCard: {
    marginBottom: '12px',
    padding: '12px',
    border: '1px solid #e5e7eb',
    borderRadius: '8px',
    backgroundColor: '#f9fafb',
  },
  resultHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    marginBottom: '12px',
  },
  resultTitle: {
    flex: 1,
    fontSize: '14px',
    fontWeight: '600',
    color: '#1f2937',
  },
  resultTime: {
    display: 'flex',
    alignItems: 'center',
    gap: '4px',
    fontSize: '12px',
    color: '#6b7280',
  },
  resultBody: {
    fontSize: '14px',
  },
  resultText: {
    marginBottom: '8px',
    fontWeight: '500',
  },
  statRow: {
    display: 'flex',
    justifyContent: 'space-between',
    padding: '6px 0',
    fontSize: '13px',
    color: '#374151',
  },
  statValue: {
    fontWeight: '600',
    color: '#1f2937',
  },
  errorList: {
    marginTop: '8px',
  },
  errorItem: {
    padding: '6px 12px',
    marginBottom: '4px',
    backgroundColor: '#fef2f2',
    border: '1px solid #fecaca',
    borderRadius: '4px',
    fontSize: '12px',
    color: '#991b1b',
  },
  emptyState: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '32px',
    gap: '12px',
  },
  emptyText: {
    fontSize: '14px',
    color: '#6b7280',
    textAlign: 'center',
  },
  axiomList: {
    maxHeight: '300px',
    overflowY: 'auto',
  },
  axiomItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '8px 12px',
    marginBottom: '6px',
    backgroundColor: '#f9fafb',
    border: '1px solid #e5e7eb',
    borderRadius: '6px',
    fontSize: '13px',
  },
  axiomType: {
    padding: '2px 8px',
    backgroundColor: '#dbeafe',
    color: '#1e40af',
    borderRadius: '4px',
    fontSize: '11px',
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  axiomText: {
    flex: 1,
    color: '#374151',
  },
  axiomConfidence: {
    fontSize: '12px',
    fontWeight: '600',
    color: '#10b981',
  },
  moreItems: {
    padding: '8px',
    textAlign: 'center',
    fontSize: '13px',
    color: '#6b7280',
    fontStyle: 'italic',
  },
  statsGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))',
    gap: '12px',
  },
  statCard: {
    display: 'flex',
    flexDirection: 'column',
    padding: '12px',
    backgroundColor: '#f9fafb',
    border: '1px solid #e5e7eb',
    borderRadius: '8px',
    textAlign: 'center',
  },
  statLabel: {
    fontSize: '12px',
    color: '#6b7280',
    marginBottom: '6px',
  },
  statNumber: {
    fontSize: '20px',
    fontWeight: '700',
    color: '#1f2937',
  },
  helpSection: {
    fontSize: '14px',
    lineHeight: '1.6',
    color: '#374151',
  },
  helpTitle: {
    fontSize: '15px',
    fontWeight: '600',
    color: '#1f2937',
    marginTop: '16px',
    marginBottom: '8px',
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
  },
  helpText: {
    marginBottom: '12px',
    color: '#4b5563',
  },
  helpList: {
    marginLeft: '20px',
    marginBottom: '16px',
    paddingLeft: '0',
  },
  helpTable: {
    marginBottom: '16px',
    border: '1px solid #e5e7eb',
    borderRadius: '6px',
    overflow: 'hidden',
  },
  helpRow: {
    display: 'flex',
    justifyContent: 'space-between',
    padding: '10px 14px',
    borderBottom: '1px solid #e5e7eb',
  },
  helpLabel: {
    fontWeight: '600',
    color: '#1f2937',
    minWidth: '120px',
  },
  helpValue: {
    color: '#6b7280',
    flex: 1,
    textAlign: 'right',
  },
  unsatisfiableSection: {
    marginTop: '16px',
    padding: '16px',
    backgroundColor: '#fef2f2',
    border: '1px solid #fecaca',
    borderRadius: '8px',
  },
  unsatisfiableHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    marginBottom: '12px',
  },
  unsatisfiableTitle: {
    fontSize: '14px',
    fontWeight: '600',
    color: '#991b1b',
  },
  unsatisfiableList: {
    maxHeight: '300px',
    overflowY: 'auto',
    marginBottom: '12px',
  },
  unsatisfiableItem: {
    padding: '10px 12px',
    marginBottom: '8px',
    backgroundColor: '#ffffff',
    border: '1px solid #fecaca',
    borderRadius: '6px',
    display: 'flex',
    flexDirection: 'column',
    gap: '4px',
  },
  unsatisfiableLabel: {
    fontSize: '14px',
    fontWeight: '600',
    color: '#991b1b',
  },
  unsatisfiableIri: {
    fontSize: '12px',
    color: '#6b7280',
    fontFamily: 'monospace',
    wordBreak: 'break-all',
  },
  unsatisfiableHint: {
    display: 'flex',
    alignItems: 'flex-start',
    gap: '8px',
    padding: '12px',
    backgroundColor: '#fffbeb',
    border: '1px solid #fde68a',
    borderRadius: '6px',
    fontSize: '13px',
    color: '#78350f',
    lineHeight: '1.5',
  },
  explanationContainer: {
    display: 'flex',
    flexDirection: 'column',
    gap: '20px',
  },
  explanationHeader: {
    display: 'flex',
    alignItems: 'flex-start',
    gap: '12px',
    padding: '16px',
    backgroundColor: '#fef2f2',
    borderRadius: '8px',
    border: '1px solid #fecaca',
  },
  explanationTitle: {
    fontSize: '16px',
    fontWeight: '600',
    color: '#991b1b',
    margin: 0,
    marginBottom: '4px',
  },
  explanationSubtitle: {
    fontSize: '14px',
    color: '#7f1d1d',
    margin: 0,
  },
  explanationContent: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
  },
  explanationSectionTitle: {
    fontSize: '15px',
    fontWeight: '600',
    color: '#1f2937',
    margin: 0,
    marginBottom: '12px',
  },
  classExplanationList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
  },
  classExplanationCard: {
    padding: '16px',
    backgroundColor: '#f9fafb',
    border: '1px solid #e5e7eb',
    borderRadius: '8px',
  },
  classExplanationHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '8px',
  },
  classExplanationName: {
    fontSize: '15px',
    fontWeight: '600',
    color: '#1f2937',
  },
  classExplanationBadge: {
    padding: '4px 10px',
    backgroundColor: '#fef2f2',
    color: '#991b1b',
    borderRadius: '12px',
    fontSize: '12px',
    fontWeight: '600',
    border: '1px solid #fecaca',
  },
  classExplanationIri: {
    fontSize: '12px',
    color: '#6b7280',
    fontFamily: 'monospace',
    marginBottom: '12px',
    wordBreak: 'break-all',
  },
  classExplanationReason: {
    fontSize: '14px',
    color: '#374151',
    lineHeight: '1.6',
    marginBottom: '12px',
    padding: '12px',
    backgroundColor: '#fffbeb',
    borderRadius: '6px',
    border: '1px solid #fde68a',
  },
  classExplanationAxioms: {
    fontSize: '13px',
    color: '#374151',
  },
  axiomExplanationList: {
    marginTop: '8px',
    marginLeft: '16px',
    paddingLeft: '0',
  },
  axiomExplanationItem: {
    padding: '6px 0',
    borderBottom: '1px solid #e5e7eb',
    fontFamily: 'monospace',
    fontSize: '12px',
    color: '#4b5563',
  },
  detailedExplanationList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
  },
  detailedExplanationCard: {
    padding: '16px',
    backgroundColor: '#ffffff',
    border: '1px solid #e5e7eb',
    borderRadius: '8px',
  },
  detailedExplanationHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '12px',
  },
  detailedExplanationTitle: {
    fontSize: '14px',
    fontWeight: '600',
    color: '#374151',
  },
  detailedExplanationClass: {
    padding: '4px 10px',
    backgroundColor: '#eff6ff',
    color: '#1e40af',
    borderRadius: '12px',
    fontSize: '12px',
    fontWeight: '500',
  },
  detailedExplanationText: {
    fontSize: '14px',
    color: '#4b5563',
    lineHeight: '1.6',
    marginBottom: '12px',
  },
  axiomsList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
  },
  axiomItem2: {
    padding: '8px 12px',
    backgroundColor: '#f9fafb',
    border: '1px solid #e5e7eb',
    borderRadius: '4px',
    fontFamily: 'monospace',
    fontSize: '12px',
    color: '#374151',
  },
  explanationFooter: {
    display: 'flex',
    alignItems: 'flex-start',
    gap: '12px',
    padding: '16px',
    backgroundColor: '#f0f9ff',
    borderRadius: '8px',
    border: '1px solid #bae6fd',
    fontSize: '13px',
    color: '#0c4a6e',
    lineHeight: '1.6',
  },
};
