/**
 * OWL Reasoner Plugin
 * OWL reasoner UI for consistency checking, classification, and inference
 * Includes explanation tooltips, class hierarchy view, and full reasoning features
 */

import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import {
  Play,
  Square,
  CheckCircle,
  XCircle,
  AlertTriangle,
  AlertCircle,
  Clock,
  Info,
  Plus,
  Minus,
  Circle,
  Loader,
  Loader2,
  RefreshCw,
  Settings,
  HelpCircle,
  Lightbulb,
  Link2,
  ArrowRight,
  GitBranch,
  GitMerge,
  Database,
  Brain,
  Check,
  Network,
  Search,
  Filter,
  MoreVertical,
  Download,
  Copy,
  FileJson,
  Save,
  Trash2,
  ChevronRight,
  ChevronDown,
  Zap
} from 'lucide-react';

/** fetch with JWT — uses window.authenticatedFetch when host app provides it. */
async function authFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const hostFetch = (window as any).authenticatedFetch;
  if (typeof hostFetch === 'function') {
    return hostFetch(input, init);
  }
  const headers = new Headers(init?.headers);
  const token = localStorage.getItem('authToken');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init?.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return fetch(input, { ...init, headers });
}

interface ReasonerPluginProps {
  projectId: string;
  apiBaseUrl?: string;
  // Dashboard integration props
  selectedReasoner?: string;
  isReasonerRunning?: boolean;
  isReasonerLoading?: boolean;
  reasonerResults?: any;
  consistencyResult?: any;
  inferredClassHierarchy?: any[];
  inferredObjectPropertyHierarchy?: any[];
  inferredDataPropertyHierarchy?: any[];
  inferredAxioms?: any[];
  onStartReasoner?: () => Promise<void>;
  onStopReasoner?: () => void;
  onSelectReasoner?: (reasoner: string) => void;
  onToggleSync?: () => void;
  isReasonerSynced?: boolean;
}

interface ClassNode {
  iri: string;
  label: string;
  depth: number;
  childrenCount: number;
  isSatisfiable: boolean;
  isEquivalent: boolean;
  explanation?: string;
}

interface ExplanationData {
  classIri: string;
  reasons: {
    type: 'subClassOf' | 'equivalentTo' | 'disjointWith' | 'restriction' | 'unsatisfiable';
    description: string;
    relatedClasses: string[];
  }[];
}

interface HierarchyNodeProps {
  node: any;
  level: number;
  type: 'class' | 'objectProperty' | 'dataProperty';
  isDark: boolean;
  expandedSet: Set<string>;
  onToggle: (id: string) => void;
  onNavigate: (id: string) => void;
}

const HierarchyNode: React.FC<HierarchyNodeProps> = ({
  node,
  level,
  type,
  isDark,
  expandedSet,
  onToggle,
  onNavigate
}) => {
  const [showTooltip, setShowTooltip] = useState(false);
  const [tooltipPos, setTooltipPos] = useState({ x: 0, y: 0 });

  // Defensive null checks
  if (!node) return null;

  const id = node.iri || node.id || (typeof node === 'string' ? node : null);
  const isExpanded = id ? expandedSet.has(id) : false;
  const hasChildren = Array.isArray(node.children) && node.children.length > 0;

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (id) onToggle(id);
  };

  const handleMouseEnter = (e: React.MouseEvent) => {
    setTooltipPos({ x: e.clientX, y: e.clientY });
    setShowTooltip(true);
  };

  const handleMouseLeave = () => {
    setShowTooltip(false);
  };

  const getIcon = () => {
    if (type === 'class') {
      const isUnsatisfiable = node.isUnsatisfiable || node.id === 'http://www.w3.org/2002/07/owl#Nothing';
      const hasEquivalents = (node.equivalentClasses && node.equivalentClasses.length > 0) || (node.equivalentProperties && node.equivalentProperties.length > 0);
      return <Circle size={10} fill={isUnsatisfiable ? '#ef4444' : hasEquivalents ? '#f59e0b' : '#3b82f6'} stroke="none" />;
    } else if (type === 'objectProperty') {
      return <Link2 size={12} className="text-green-500" />;
    } else {
      return <Database size={12} className="text-orange-500" />;
    }
  };

  return (
    <div style={{ marginLeft: `${level * 12}px` }} className="relative">
      <div
        className={`flex items-center gap-1 py-1 px-2 rounded cursor-pointer text-xs group ${
          isDark ? 'hover:bg-gray-800' : 'hover:bg-blue-50'
        }`}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
        onClick={() => id && onNavigate(id)}
      >
        {hasChildren ? (
          <button
            onClick={handleToggle}
            className={`p-0.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700`}
          >
            {isExpanded ? (
              <Minus size={12} className={isDark ? 'text-gray-500' : 'text-gray-400'} />
            ) : (
              <Plus size={12} className={isDark ? 'text-gray-500' : 'text-gray-400'} />
            )}
          </button>
        ) : (
          <span className="w-4" />
        )}
        {getIcon()}
        <span className={`font-mono ${isDark ? 'text-blue-400' : 'text-blue-700'} ${node.isUnsatisfiable ? 'text-red-600 font-bold' : ''}`}>
          {node.name || node.label || (node.iri ? (node.iri.includes('#') ? node.iri.split('#').pop() : node.iri.split('/').pop()) : (typeof node === 'string' ? node : 'Unknown'))}
        </span>
        {node.equivalentClasses && node.equivalentClasses.length > 0 && (
          <span className="text-[10px] text-gray-500 italic ml-1">
            ≡ {node.equivalentClasses.map((c: any) => c.label).join(', ')}
          </span>
        )}
        {node.equivalentProperties && node.equivalentProperties.length > 0 && (
          <span className="text-[10px] text-gray-500 italic ml-1">
            ≡ {node.equivalentProperties.map((p: any) => typeof p === 'string' ? p : p.label).join(', ')}
          </span>
        )}
        {node.inferred && (
          <span className={`text-[10px] px-1 rounded ${
            isDark ? 'bg-green-900/30 text-green-400' : 'bg-green-100 text-green-700'
          }`}>inferred</span>
        )}
      </div>

      {/* Explanation Tooltip */}
      {showTooltip && (node.explanation || node.description) && (
        <div
          className={`fixed z-[9999] border-2 rounded-lg shadow-xl p-3 max-w-sm ${
            isDark ? 'bg-gray-800 border-yellow-600 text-gray-200' : 'bg-yellow-50 border-yellow-400 text-gray-800'
          }`}
          style={{
            left: `${tooltipPos.x + 10}px`,
            top: `${tooltipPos.y + 10}px`,
            pointerEvents: 'none'
          }}
        >
          <div className="text-xs font-semibold mb-1">Why inferred:</div>
          <div className="text-xs opacity-90">{node.explanation || node.description}</div>
        </div>
      )}

      {isExpanded && hasChildren && Array.isArray(node.children) && node.children.map((child: any, idx: number) => (
        <HierarchyNode
          key={idx}
          node={child}
          level={level + 1}
          type={type}
          isDark={isDark}
          expandedSet={expandedSet}
          onToggle={onToggle}
          onNavigate={onNavigate}
        />
      ))}
    </div>
  );
};

export const ReasonerPluginView: React.FC<ReasonerPluginProps> = ({
  projectId,
  apiBaseUrl = '',
  selectedReasoner: dashboardSelectedReasoner,
  isReasonerRunning: dashboardIsRunning,
  isReasonerLoading: dashboardIsLoading,
  reasonerResults: dashboardReasonerResults,
  consistencyResult: dashboardConsistencyResult,
  inferredClassHierarchy: dashboardInferredHierarchy,
  inferredObjectPropertyHierarchy: dashboardInferredObjectPropertyHierarchy,
  inferredDataPropertyHierarchy: dashboardInferredDataPropertyHierarchy,
  inferredAxioms: dashboardInferredAxioms,
  onStartReasoner: dashboardStartReasoner,
  onStopReasoner: dashboardStopReasoner,
  onSelectReasoner: dashboardSelectReasoner,
  onToggleSync: dashboardToggleSync,
  isReasonerSynced: dashboardIsReasonerSynced
}) => {
  const resolvedApiBaseUrl =
    (apiBaseUrl && apiBaseUrl.trim().length > 0 ? apiBaseUrl.trim() : undefined) ||
    (typeof window !== 'undefined' && (window as any).API_BASE_URL
      ? ((window as any).API_BASE_URL as string)
      : undefined) ||
    'http://localhost:8082';
  
  // Ensure the API base URL doesn't end with a slash
  const normalizedApiBaseUrl = resolvedApiBaseUrl.replace(/\/$/, '');
  // console.log('[ReasonerPluginView] Using API base URL:', normalizedApiBaseUrl);
  
  // Use Dashboard state if provided, otherwise use local state
  const usingDashboardState = !!dashboardStartReasoner;
  
  // State
  const [showReasonerMenu, setShowReasonerMenu] = useState(false);
  const [showExportMenu, setShowExportMenu] = useState(false);
  const [localSelectedReasoner, setLocalSelectedReasoner] = useState<string>('hermit');
  const [localIsRunning, setLocalIsRunning] = useState(false);
  const [localIsLoading, setLocalIsLoading] = useState(false);
  const [isConsistent, setIsConsistent] = useState<boolean | null>(null);
  const [localReasonerResults, setLocalReasonerResults] = useState<any>(null);
  const [classHierarchy, setClassHierarchy] = useState<ClassNode[]>([]);
  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<any[]>([]);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<any[]>([]);
  const [equivalentClasses, setEquivalentClasses] = useState<any[]>([]);
  const [unsatisfiableClasses, setUnsatisfiableClasses] = useState<any[]>([]);
  const [expandedClasses, setExpandedClasses] = useState<Set<string>>(new Set());
  const [expandedObjectProperties, setExpandedObjectProperties] = useState<Set<string>>(new Set());
  const [expandedDataProperties, setExpandedDataProperties] = useState<Set<string>>(new Set());
  const [activeTab, setActiveTab] = useState<'classes' | 'objectProperties' | 'dataProperties' | 'inferredAxioms'>('classes');
  const [inferredAxioms, setInferredAxioms] = useState<Array<{ axiomType: string; readable: string; axiom: string }>>([]);
  const [inferredAxiomsTotal, setInferredAxiomsTotal] = useState(0);
  const [inferredAxiomsFilter, setInferredAxiomsFilter] = useState('');
  const [hoveredClass, setHoveredClass] = useState<string | null>(null);
  const [tooltipPosition, setTooltipPosition] = useState<{ x: number; y: number } | null>(null);
  const [explanations, setExplanations] = useState<Map<string, ExplanationData>>(new Map());
  const [showSettings, setShowSettings] = useState(false);
  const [showConfigureDialog, setShowConfigureDialog] = useState(false);
  const [showExplainDialog, setShowExplainDialog] = useState(false);
  const [inconsistencyExplanation, setInconsistencyExplanation] = useState<any>(null);
  const [autoSync, setAutoSync] = useState(true);
  const [reasonerStatus, setReasonerStatus] = useState<string>('Not initialized');
  const [stats, setStats] = useState<any>(null);
  const [selectedClassIri, setSelectedClassIri] = useState<string | null>(null);
  
  // New settings state
  const [timeoutSeconds, setTimeoutSeconds] = useState(60);
  const [incrementalReasoning, setIncrementalReasoning] = useState(false);
  const [cacheResults, setCacheResults] = useState(true);
  const [showAdvancedSettings, setShowAdvancedSettings] = useState(false);
  const [exportFormat, setExportFormat] = useState<'json' | 'csv'>('json');
  
  // Use Dashboard values when available
  const selectedReasoner = dashboardSelectedReasoner || localSelectedReasoner;
  const isRunning = dashboardIsRunning ?? localIsRunning;
  const isLoading = dashboardIsLoading ?? localIsLoading;
  const reasonerResults = dashboardReasonerResults || localReasonerResults;
  const tooltipRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const classRefs = useRef<Map<string, HTMLDivElement>>(new Map());

  // Theme detection
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    const checkTheme = () => {
      const isDarkMode = document.documentElement.classList.contains('dark');
      setIsDark(isDarkMode);
    };

    checkTheme();

    const observer = new MutationObserver(checkTheme);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class']
    });

    return () => observer.disconnect();
  }, []);

  // Load saved settings from localStorage
  useEffect(() => {
    const savedSettings = localStorage.getItem('reasonerSettings');
    if (savedSettings) {
      try {
        const settings = JSON.parse(savedSettings);
        if (settings.timeoutSeconds !== undefined) setTimeoutSeconds(settings.timeoutSeconds);
        if (settings.incrementalReasoning !== undefined) setIncrementalReasoning(settings.incrementalReasoning);
        if (settings.cacheResults !== undefined) setCacheResults(settings.cacheResults);
        if (settings.exportFormat !== undefined) setExportFormat(settings.exportFormat);
      } catch (error) {
        console.error('Failed to load reasoner settings:', error);
      }
    }
  }, []);

  // Handle click outside to close menu
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setShowReasonerMenu(false);
      }
    };

    if (showReasonerMenu) {
      document.addEventListener('mousedown', handleClickOutside);
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [showReasonerMenu]);

  // Backend endpoints that can be offloaded to the reasoner-worker (consistency,
  // realization, inferred-axioms — classification has its own dedicated status
  // endpoint and is polled separately below) reply with 202 + {async, jobId, status}
  // instead of the result body when the worker is enabled. /api/dl-query/jobs/{jobId}
  // works as the poll target regardless of which service originally submitted the
  // job — it falls back to asking the worker directly when it has no local record.
  const pollWorkerJob = useCallback(async (jobId: string, timeoutMs = 10 * 60 * 1000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 2000));
      const res = await authFetch(`${normalizedApiBaseUrl}/api/dl-query/jobs/${jobId}`);
      if (!res.ok) {
        throw new Error(`Poll failed: ${res.statusText}`);
      }
      const job = await res.json();
      const status = String(job?.status || '').toUpperCase();
      if (status === 'COMPLETED') return job;
      if (status === 'FAILED') throw new Error(job.error || job.message || 'Reasoning job failed');
    }
    throw new Error('Reasoning job timed out');
  }, [normalizedApiBaseUrl]);

  const fetchInferredAxioms = useCallback(async (reasonerType: string) => {
    try {
      const encodedProjectId = encodeURIComponent(projectId);
      const response = await authFetch(
        `${normalizedApiBaseUrl}/plugin-service/api/reasoner/${encodedProjectId}/inferred-axioms?reasonerType=${reasonerType}`,
      );
      if (!response.ok) {
        console.warn(`[ReasonerPluginView] inferred-axioms request failed: ${response.status} ${response.statusText}`);
        return;
      }
      let data = await response.json();
      if (data.async && (data.jobId || data.taskId)) {
        data = await pollWorkerJob(data.jobId || data.taskId);
      }
      if (data.axioms && Array.isArray(data.axioms)) {
        setInferredAxioms(data.axioms);
        setInferredAxiomsTotal(data.totalInferredAxioms || data.axioms.length);
      } else {
        console.warn('[ReasonerPluginView] inferred-axioms response missing axioms array:', data);
      }
    } catch (err) {
      console.warn('[ReasonerPluginView] Failed to fetch inferred axioms:', err);
    }
  }, [projectId, normalizedApiBaseUrl, pollWorkerJob]);

  // Start reasoning
  const startReasoner = useCallback(async (task: 'consistency' | 'classification' | 'realization') => {
    setLocalIsRunning(true);
    setReasonerStatus(`Running ${task}...`);

    try {
      const encodedProjectId = encodeURIComponent(projectId);
      let endpoint = '';
      switch (task) {
        case 'consistency':
          endpoint = `/plugin-service/api/reasoner/${encodedProjectId}/consistency`;
          break;
        case 'classification':
          endpoint = `/plugin-service/api/reasoner/${encodedProjectId}/classify`;
          break;
        case 'realization':
          endpoint = `/plugin-service/api/reasoner/${encodedProjectId}/realize`;
          break;
      }

      // Map UI reasoner names to backend enum values
      const reasonerMap: Record<string, string> = {
        'hermit': 'HERMIT',
        'pellet': 'PELLET',
        'openllet': 'OPENLLET',
        'fact++': 'FACTPLUSPLUS',
        'elk': 'ELK',
        'structural': 'STRUCTURAL'
      };
      const reasonerType = reasonerMap[selectedReasoner.toLowerCase()] || 'HERMIT';

      console.log('[ReasonerPluginView] Starting reasoner:', {
        task,
        reasonerType,
        endpoint: `${normalizedApiBaseUrl}${endpoint}`,
        projectId,
        fullUrl: `${normalizedApiBaseUrl}${endpoint}`
      });

      const response = await authFetch(`${normalizedApiBaseUrl}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reasonerType })
      });

      console.log('[ReasonerPluginView] Response status:', response.status);

      if (!response.ok) {
        const errorText = await response.text();
        console.error('[ReasonerPluginView] Error response:', errorText);
        throw new Error(`Reasoning failed: ${response.statusText}. ${errorText}`);
      }

      let result = await response.json();
      console.log('[ReasonerPluginView] Reasoning result:', result);

      // Consistency/realization have no dedicated status endpoint of their own — when
      // the reasoner-worker is enabled, the initial POST above returns a queued job
      // (202 + {async, jobId}) instead of the result, so it must be polled generically.
      // Classification is handled separately below via its own /classify/status
      // endpoint, which already understands both the worker and non-worker paths.
      if (result.async && (result.jobId || result.taskId) && task !== 'classification') {
        setReasonerStatus(`Running ${task}... (queued)`);
        result = await pollWorkerJob(result.jobId || result.taskId);
      }

      // Handle async classify response (taskId-based polling)
      if (result.taskId && task === 'classification') {
        const taskId = result.taskId;
        const POLL_INTERVAL = 3000;
        const MAX_POLL_TIME = 600_000;
        const deadline = Date.now() + MAX_POLL_TIME;
        while (Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, POLL_INTERVAL));
          const statusRes = await authFetch(
            `${normalizedApiBaseUrl}/plugin-service/api/reasoner/${encodedProjectId}/classify/status/${taskId}`,
          );
          if (!statusRes.ok) throw new Error(`Poll failed: ${statusRes.statusText}`);
          const statusData = await statusRes.json();
          if (statusData.status === 'COMPLETED') { result = statusData; break; }
          if (statusData.status === 'FAILED') throw new Error(statusData.error || 'Classification failed');
          setReasonerStatus('Classifying... (still running)');
        }
        if (result.taskId && result.status === 'RUNNING') {
          throw new Error('Classification timed out after 10 minutes');
        }
      }

      if (task === 'consistency') {
        setIsConsistent(result.consistent);
        if (!result.consistent && result.unsatisfiableClasses) {
          setUnsatisfiableClasses(result.unsatisfiableClasses);
        }
        if (result.consistent) {
          await fetchInferredAxioms(reasonerType);
        }
      } else if (task === 'classification') {
        // Set consistency from classification result
        if (result.isConsistent !== undefined) {
          setIsConsistent(result.isConsistent);
        }
        
        setClassHierarchy(result.classHierarchy || []);
        setObjectPropertyHierarchy(result.objectPropertyHierarchy || result.objectProperties || []);
        setDataPropertyHierarchy(result.dataPropertyHierarchy || result.dataProperties || []);
        setEquivalentClasses(result.equivalentClasses || []);
        setUnsatisfiableClasses(result.unsatisfiableClasses || []);
        
        // Generate explanations for classes. Guarded: a throw here (e.g. an
        // unexpected field shape from the async classify/status payload) must
        // not prevent the inferred-axioms fetch below from running.
        try {
          generateExplanations(result);
        } catch (explainErr) {
          console.error('[ReasonerPluginView] generateExplanations failed:', explainErr);
        }
        await fetchInferredAxioms(reasonerType);
      } else if (task === 'realization') {
        // Set consistency from realization result
        if (result.isConsistent !== undefined) {
          setIsConsistent(result.isConsistent);
        }
      }

      // Get stats
      const statsRes = await authFetch(`${normalizedApiBaseUrl}/plugin-service/api/reasoner/${encodedProjectId}/stats?reasonerType=${reasonerType}`);
      if (statsRes.ok) {
        const statsData = await statsRes.json();
        setStats(statsData);
      }

      setReasonerStatus(`${task} completed successfully`);
    } catch (error) {
      console.error('[ReasonerPluginView] Reasoning error:', error);
      setReasonerStatus(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setLocalIsRunning(false);
    }
  }, [projectId, apiBaseUrl, selectedReasoner, fetchInferredAxioms, pollWorkerJob]);

  // Generate explanations for reasoning results
  const generateExplanations = (classificationResult: any) => {
    const newExplanations = new Map<string, ExplanationData>();

    // Explanations for unsatisfiable classes
    if (classificationResult.unsatisfiableClasses) {
      classificationResult.unsatisfiableClasses.forEach((cls: any) => {
        newExplanations.set(cls.iri, {
          classIri: cls.iri,
          reasons: [{
            type: 'unsatisfiable',
            description: 'This class is unsatisfiable (equivalent to owl:Nothing). No individuals can be instances of this class.',
            relatedClasses: []
          }]
        });
      });
    }

    // Explanations for equivalent classes
    if (classificationResult.equivalentClasses) {
      classificationResult.equivalentClasses.forEach((group: any) => {
        if (group.classes && group.classes.length > 1) {
          group.classes.forEach((cls: any) => {
            const others = group.classes.filter((c: any) => c.iri !== cls.iri).map((c: any) => c.label);
            newExplanations.set(cls.iri, {
              classIri: cls.iri,
              reasons: [{
                type: 'equivalentTo',
                description: `This class is equivalent to: ${others.join(', ')}. They have exactly the same instances.`,
                relatedClasses: group.classes.filter((c: any) => c.iri !== cls.iri).map((c: any) => c.iri)
              }]
            });
          });
        }
      });
    }

    // Explanations for class hierarchy (subclass relationships)
    if (classificationResult.classHierarchy) {
      classificationResult.classHierarchy.forEach((node: ClassNode) => {
        if (!newExplanations.has(node.iri)) {
          newExplanations.set(node.iri, {
            classIri: node.iri,
            reasons: [{
              type: 'subClassOf',
              description: `This class is inferred to be a subclass based on its axioms and restrictions. Depth in hierarchy: ${node.depth}`,
              relatedClasses: []
            }]
          });
        }
      });
    }

    setExplanations(newExplanations);
  };

  // Fetch detailed inconsistency explanation
  const fetchInconsistencyExplanation = useCallback(async () => {
    if (isConsistent !== false) return;

    setLocalIsRunning(true);
    try {
      const reasonerMap: Record<string, string> = {
        'hermit': 'HERMIT',
        'pellet': 'PELLET',
        'openllet': 'OPENLLET',
        'fact++': 'FACTPLUSPLUS',
        'elk': 'ELK',
        'structural': 'STRUCTURAL'
      };
      const reasonerType = reasonerMap[selectedReasoner.toLowerCase()] || 'HERMIT';

      const encodedProjectId = encodeURIComponent(projectId);
      const response = await authFetch(
        `${resolvedApiBaseUrl}/plugin-service/api/reasoner/${encodedProjectId}/explain-inconsistency`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ reasonerType })
        }
      );

      if (response.ok) {
        const explanation = await response.json();
        setInconsistencyExplanation(explanation);
      } else {
        console.error('Failed to fetch inconsistency explanation');
      }
    } catch (error) {
      console.error('Error fetching inconsistency explanation:', error);
    } finally {
      setLocalIsRunning(false);
    }
  }, [projectId, selectedReasoner, isConsistent, resolvedApiBaseUrl]);

  // Handle class hover
  const handleClassHover = (classIri: string, event: React.MouseEvent) => {
    setHoveredClass(classIri);
    setTooltipPosition({ x: event.clientX, y: event.clientY });
  };

  const handleClassLeave = () => {
    setHoveredClass(null);
    setTooltipPosition(null);
  };

  // Navigate to entity in hierarchy
  const navigateToEntity = (iri: string) => {
    setSelectedClassIri(iri);
    
    // Expand parent nodes to make it visible
    if (activeTab === 'classes') {
      setExpandedClasses(prev => {
        const newSet = new Set(prev);
        classHierarchy.forEach(node => {
          if (node.iri === iri && node.depth > 0) {
            newSet.add(node.iri);
          }
        });
        return newSet;
      });
    } else if (activeTab === 'objectProperties') {
      setExpandedObjectProperties(prev => {
        const newSet = new Set(prev);
        objectPropertyHierarchy.forEach(node => {
          if (node.iri === iri && node.depth > 0) {
            newSet.add(node.iri);
          }
        });
        return newSet;
      });
    } else if (activeTab === 'dataProperties') {
      setExpandedDataProperties(prev => {
        const newSet = new Set(prev);
        dataPropertyHierarchy.forEach(node => {
          if (node.iri === iri && node.depth > 0) {
            newSet.add(node.iri);
          }
        });
        return newSet;
      });
    }
    
    // Scroll to the element
    setTimeout(() => {
      const element = classRefs.current.get(iri);
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'center' });
        // Highlight briefly
        element.style.backgroundColor = isDark ? '#78350f' : '#fef3c7';
        setTimeout(() => {
          element.style.backgroundColor = '';
        }, 2000);
      }
    }, 100);
  };

  // Helper function to render hierarchy
  const renderHierarchy = (nodes: any[], type: 'class' | 'objectProperty' | 'dataProperty'): React.ReactNode => {
    if (!Array.isArray(nodes) || nodes.length === 0) return null;
    
    const expandedSet = type === 'class' ? expandedClasses : (type === 'objectProperty' ? expandedObjectProperties : expandedDataProperties);
    const setExpandedSet = type === 'class' ? setExpandedClasses : (type === 'objectProperty' ? setExpandedObjectProperties : setExpandedDataProperties);

    const handleToggle = (id: string) => {
      setExpandedSet(prev => {
        const newSet = new Set(prev);
        if (newSet.has(id)) newSet.delete(id);
        else newSet.add(id);
        return newSet;
      });
    };

    // Filter out null/undefined nodes and ensure each node has valid structure
    const validNodes = nodes.filter(node => node && (node.iri || node.id));

    return validNodes.map((node: any, idx: number) => (
      <HierarchyNode 
        key={node.iri || node.id || idx} 
        node={node} 
        level={0} 
        type={type} 
        isDark={isDark}
        expandedSet={expandedSet}
        onToggle={handleToggle}
        onNavigate={navigateToEntity}
      />
    ));
  };

  const styles = getStyles(isDark);

  // Derived values for the UI
  const displayStats = reasonerResults?.stats || stats || null;
  const unsatList = reasonerResults?.unsatisfiableClasses || [];
  const equivalentGroups = reasonerResults?.equivalentClasses || [];
  const consistencyData = dashboardConsistencyResult || null;
  const consistencyUnsat = consistencyData?.unsatisfiableClasses || [];
  const combinedUnsat = unsatList.length > 0 ? unsatList : consistencyUnsat;
  const consistentFlag = (displayStats?.isConsistent ?? consistencyData?.consistent ?? consistencyData?.isConsistent);
  const unsatRaw = displayStats?.unsatisfiableClassesRaw;
  const isOntologyInconsistent = consistentFlag === false || unsatRaw === -1 || !!(
    (displayStats && ((displayStats.unsatisfiableClasses ?? 0) > 0 || displayStats.isConsistent === false)) ||
    combinedUnsat.length > 0
  );
  
  // Use inferred hierarchies if available, otherwise fall back to reasoner results
  const classHierarchyToRender = useMemo(() => {
    const source = dashboardInferredHierarchy && dashboardInferredHierarchy.length > 0
      ? dashboardInferredHierarchy
      : (reasonerResults?.classHierarchyTree || reasonerResults?.classHierarchy || []);
    const tree = ensureTree(source);
    return tree;
  }, [dashboardInferredHierarchy, reasonerResults]);

  const objectPropertyHierarchyToRender = useMemo(() => {
    const source = dashboardInferredObjectPropertyHierarchy && dashboardInferredObjectPropertyHierarchy.length > 0
      ? dashboardInferredObjectPropertyHierarchy
      : (reasonerResults?.objectPropertyHierarchy || reasonerResults?.objectPropertyHierarchyTree || reasonerResults?.objectProperties || objectPropertyHierarchy || []);
    return ensureTree(source);
  }, [dashboardInferredObjectPropertyHierarchy, reasonerResults, objectPropertyHierarchy]);

  // Dashboard's own startReasoner() flow fetches inferred axioms itself (Start button
  // always prefers dashboardStartReasoner when provided, so this plugin's own
  // fetchInferredAxioms below never runs in that mode) — prefer that result when present.
  const inferredAxiomsToRender = dashboardInferredAxioms && dashboardInferredAxioms.length > 0
    ? dashboardInferredAxioms
    : inferredAxioms;

  const dataPropertyHierarchyToRender = useMemo(() => {
    const source = dashboardInferredDataPropertyHierarchy && dashboardInferredDataPropertyHierarchy.length > 0
      ? dashboardInferredDataPropertyHierarchy
      : (reasonerResults?.dataPropertyHierarchy || reasonerResults?.dataPropertyHierarchyTree || reasonerResults?.dataProperties || dataPropertyHierarchy || []);
    console.log('[ReasonerPlugin] Data Property Hierarchy Source:', source);
    const tree = ensureTree(source);
    console.log('[ReasonerPlugin] Data Property Hierarchy Tree:', tree);
    return tree;
  }, [dashboardInferredDataPropertyHierarchy, reasonerResults, dataPropertyHierarchy]);

  // Automatically expand root nodes when hierarchy changes
  useEffect(() => {
    if (classHierarchyToRender.length > 0) {
      setExpandedClasses(prev => {
        const newSet = new Set(prev);
        let changed = false;
        classHierarchyToRender.forEach(node => {
          const id = node.iri || node.id;
          if (id && !newSet.has(id)) {
            newSet.add(id);
            changed = true;
          }
        });
        return changed ? newSet : prev;
      });
    }
  }, [classHierarchyToRender]);

  useEffect(() => {
    if (objectPropertyHierarchyToRender.length > 0) {
      setExpandedObjectProperties(prev => {
        const newSet = new Set(prev);
        let changed = false;
        objectPropertyHierarchyToRender.forEach(node => {
          const id = node.iri || node.id;
          if (id && !newSet.has(id)) {
            newSet.add(id);
            changed = true;
          }
        });
        return changed ? newSet : prev;
      });
    }
  }, [objectPropertyHierarchyToRender]);

  useEffect(() => {
    if (dataPropertyHierarchyToRender.length > 0) {
      setExpandedDataProperties(prev => {
        const newSet = new Set(prev);
        let changed = false;
        dataPropertyHierarchyToRender.forEach(node => {
          const id = node.iri || node.id;
          if (id && !newSet.has(id)) {
            newSet.add(id);
            changed = true;
          }
        });
        return changed ? newSet : prev;
      });
    }
  }, [dataPropertyHierarchyToRender]);

  function ensureTree(nodes: any[]): any[] {
    if (!Array.isArray(nodes) || nodes.length === 0) return [];
    
    // Recursive function to ensure all nodes have children arrays
    const normalizeNode = (node: any): any => {
      if (!node) return null;
      const normalized = { 
        ...node, 
        children: Array.isArray(node.children) ? node.children.map(normalizeNode).filter(Boolean) : []
      };
      return normalized;
    };
    
    // If any node has children already, it might be a tree, but flat lists with depth 
    // are common from the backend. If we see depth and it's a flat list, we build the tree.
    // A simple check: if it's a flat list (no children on first few nodes) but has depth > 0 later.
    const isFlat = nodes.length > 1 && !nodes[0].children?.length && nodes.some(n => (n.depth || 0) > 0);
    
    if (!isFlat && nodes[0].children?.length) {
      // Already a tree, just normalize to ensure all children arrays exist
      return nodes.map(normalizeNode).filter(Boolean);
    }

    // Sort by depth to ensure parents come before children if they are not already
    const sortedNodes = [...nodes].sort((a, b) => (a.depth || 0) - (b.depth || 0));

    const stack: any[] = [];
    const roots: any[] = [];

    sortedNodes.forEach((node) => {
      const depth = Number((node && (node as any).depth) ?? 0);
      const copy = { ...node, children: Array.isArray(node.children) ? [...node.children] : [] };

      while (stack.length > 0 && (stack[stack.length - 1]?.depth ?? 0) >= depth) {
        stack.pop();
      }

      if (stack.length === 0) {
        roots.push(copy);
      } else {
        if (!Array.isArray(stack[stack.length - 1].children)) {
          stack[stack.length - 1].children = [];
        }
        stack[stack.length - 1].children.push(copy);
      }

      stack.push(copy);
    });

    // If the only root is a top property (owl:topObjectProperty or owl:topDataProperty), 
    // return its children instead to show properties directly at the top level.
    if (roots.length === 1 && Array.isArray(roots[0].children) && roots[0].children.length > 0) {
      const rootIri = roots[0].iri || roots[0].id || '';
      if (rootIri.endsWith('#topObjectProperty') || rootIri.endsWith('#topDataProperty')) {
        console.log(`[ReasonerPlugin] Flattening top property: ${rootIri}`);
        return roots[0].children.map(normalizeNode).filter(Boolean);
      }
    }

    return roots.map(normalizeNode).filter(Boolean);
  }

  const getAllIds = (nodes: any[]): string[] => {
    let ids: string[] = [];
    nodes.forEach(node => {
      const id = node.iri || node.id;
      if (id) ids.push(id);
      if (node.children) {
        ids = [...ids, ...getAllIds(node.children)];
      }
    });
    return ids;
  };

  const expandAll = () => {
    if (activeTab === 'classes') {
      setExpandedClasses(new Set(getAllIds(classHierarchyToRender)));
    } else if (activeTab === 'objectProperties') {
      setExpandedObjectProperties(new Set(getAllIds(objectPropertyHierarchyToRender)));
    } else if (activeTab === 'dataProperties') {
      setExpandedDataProperties(new Set(getAllIds(dataPropertyHierarchyToRender)));
    }
  };

  const collapseAll = () => {
    if (activeTab === 'classes') {
      setExpandedClasses(new Set());
    } else if (activeTab === 'objectProperties') {
      setExpandedObjectProperties(new Set());
    } else if (activeTab === 'dataProperties') {
      setExpandedDataProperties(new Set());
    }
  };

  // Export functions
  // Helper to count all nodes in a hierarchy tree recursively
  const countNodesInHierarchy = (nodes: any[]): number => {
    if (!Array.isArray(nodes) || nodes.length === 0) return 0;
    
    return nodes.reduce((total, node) => {
      if (!node) return total;
      const childCount = Array.isArray(node.children) ? countNodesInHierarchy(node.children) : 0;
      return total + 1 + childCount;
    }, 0);
  };

  // Compute counts from actual hierarchy data
  const actualClassCount = countNodesInHierarchy(classHierarchyToRender);
  const actualObjectPropertyCount = countNodesInHierarchy(objectPropertyHierarchyToRender);
  const actualDataPropertyCount = countNodesInHierarchy(dataPropertyHierarchyToRender);

  // Compute reasoningTime from stats or reasonerResults if available
  const reasoningTime =
    displayStats?.reasoningTime ||
    reasonerResults?.reasoningTime ||
    stats?.reasoningTime ||
    null;

  const exportToJSON = () => {
    const exportData = {
      projectId,
      reasoner: selectedReasoner,
      timestamp: new Date().toISOString(),
      isConsistent,
      classCount: actualClassCount,
      objectPropertyCount: actualObjectPropertyCount,
      dataPropertyCount: actualDataPropertyCount,
      reasoningTime: reasoningTime,
      classHierarchy: classHierarchyToRender,
      objectPropertyHierarchy: objectPropertyHierarchyToRender,
      dataPropertyHierarchy: dataPropertyHierarchyToRender,
      unsatisfiableClasses,
      equivalentClasses: reasonerResults?.equivalentClasses || []
    };

    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `reasoner-results-${projectId}-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const exportToCSV = () => {
    let csv = 'Type,IRI,Label,Depth,Parent\n';
    
    const addNodes = (nodes: any[], type: string) => {
      const flatten = (node: any, parent: string = ''): any[] => {
        const row = [
          type,
          node.iri || node.id || '',
          node.label || node.name || '',
          node.depth || 0,
          parent
        ];
        const children = Array.isArray(node.children) ? node.children : [];
        return [row, ...children.flatMap((child: any) => flatten(child, node.iri || node.id || ''))];
      };
      return nodes.flatMap(node => flatten(node));
    };

    const rows = [
      ...addNodes(classHierarchyToRender, 'Class'),
      ...addNodes(objectPropertyHierarchyToRender, 'ObjectProperty'),
      ...addNodes(dataPropertyHierarchyToRender, 'DataProperty')
    ];

    rows.forEach(row => {
      csv += row.map((field: any) => `"${field}"`).join(',') + '\n';
    });

    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `reasoner-hierarchy-${projectId}-${Date.now()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const copyToClipboard = async () => {
    const text = JSON.stringify({
      isConsistent,
      classCount: actualClassCount,
      objectPropertyCount: actualObjectPropertyCount,
      dataPropertyCount: actualDataPropertyCount,
      unsatisfiableClasses: unsatisfiableClasses.length,
      reasoningTime
    }, null, 2);

    try {
      await navigator.clipboard.writeText(text);
      alert('Results copied to clipboard!');
    } catch (error) {
      console.error('Failed to copy:', error);
    }
  };

  const clearCache = async () => {
    try {
      const response = await authFetch(
        `${resolvedApiBaseUrl}/plugin-service/api/reasoner/clear-cache`,
        { method: 'POST' }
      );
      if (response.ok) {
        alert('Cache cleared successfully');
      }
    } catch (error) {
      console.error('Error clearing cache:', error);
    }
  };

  function stopReasoner(): void {
    throw new Error('Function not implemented.');
  }

  return (
    <div className={`flex flex-col h-full ${isDark ? 'bg-gray-900 text-gray-100' : 'bg-white text-gray-900'}`}>
      {/* Toolbar */}
      <div className={`flex items-center gap-2 px-3 py-2 border-b flex-shrink-0 ${
        isDark ? 'border-gray-700 bg-gray-800' : 'border-gray-300 bg-gray-50'
      }`}>
        {/* Start Button */}
        <button
          onClick={() => dashboardStartReasoner ? dashboardStartReasoner() : startReasoner('classification')}
          disabled={isLoading || !projectId || isRunning}
          className="px-3 py-1.5 text-xs font-medium bg-green-600 text-white rounded hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5"
          title="Start reasoner"
        >
          {isLoading ? (
            <>
              <Loader2 size={14} className="animate-spin" />
              Starting...
            </>
          ) : (
            <>
              <Play size={14} />
              Start
            </>
          )}
        </button>

        {/* Stop Button */}
        <button
          onClick={() => dashboardStopReasoner ? dashboardStopReasoner() : stopReasoner()}
          disabled={!isRunning || isLoading}
          className="px-3 py-1.5 text-xs font-medium bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5"
          title="Stop reasoner"
        >
          <Square size={14} />
          Stop
        </button>

        <div className={`w-px h-6 mx-1 ${isDark ? 'bg-gray-700' : 'bg-gray-300'}`} />

        {/* Reasoner Selector Dropdown */}
        <div className="relative group">
          <button 
            className={`px-3 py-1.5 text-xs font-medium border rounded flex items-center gap-2 min-w-[140px] ${
              isDark ? 'bg-gray-700 border-gray-600 hover:bg-gray-600' : 'bg-white border-gray-300 hover:bg-gray-50'
            }`}
            onClick={() => setShowReasonerMenu(!showReasonerMenu)}
          >
            <Brain size={14} className="text-purple-500" />
            <span>{selectedReasoner}</span>
            <ChevronDown size={12} className="ml-auto" />
          </button>
          
          {showReasonerMenu && (
            <div 
              ref={menuRef}
              className={`absolute top-full left-0 mt-1 border rounded shadow-lg z-50 min-w-[240px] ${
                isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-300'
              }`}
            >
              {[
                { name: 'HermiT', desc: 'Full OWL 2 DL - Best for complex ontologies' },
                { name: 'ELK', desc: 'EL++ optimized - Fast for large taxonomies' },
                { name: 'Pellet', desc: 'Complete OWL DL with SWRL support' },
                { name: 'Openllet', desc: 'Modern Pellet fork - Improved performance' },
                { name: 'Structural', desc: 'Lightweight - Fast but limited' }
              ].map(reasoner => (
                <button
                  key={reasoner.name}
                  onClick={() => {
                    if (dashboardSelectReasoner) {
                      dashboardSelectReasoner(reasoner.name);
                    } else {
                      setLocalSelectedReasoner(reasoner.name);
                    }
                    setShowReasonerMenu(false);
                  }}
                  className={`w-full text-left px-3 py-2 text-xs flex items-center gap-2 ${
                    isDark ? 'hover:bg-gray-700' : 'hover:bg-blue-50'
                  } ${selectedReasoner === reasoner.name ? (isDark ? 'bg-gray-700' : 'bg-blue-50') : ''}`}
                >
                  {selectedReasoner === reasoner.name && <Check size={12} className="text-blue-500 flex-shrink-0" />}
                  <div className={selectedReasoner === reasoner.name ? '' : 'ml-5'}>
                    <div className={`font-medium ${selectedReasoner === reasoner.name ? 'text-blue-500' : ''}`}>{reasoner.name}</div>
                    <div className={`text-[10px] mt-0.5 ${isDark ? 'text-gray-400' : 'text-gray-500'}`}>{reasoner.desc}</div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>

        <div className={`w-px h-6 mx-1 ${isDark ? 'bg-gray-700' : 'bg-gray-300'}`} />

        {/* Synchronize Checkbox */}
        <label className={`flex items-center gap-2 px-3 py-1.5 text-xs font-medium cursor-pointer rounded ${
          isDark ? 'hover:bg-gray-700' : 'hover:bg-gray-100'
        }`}>
          <input
            type="checkbox"
            checked={dashboardIsReasonerSynced ?? autoSync}
            onChange={() => dashboardToggleSync ? dashboardToggleSync() : setAutoSync(!autoSync)}
            className="w-3.5 h-3.5"
          />
          <span>Synchronize reasoner</span>
        </label>

        {/* Status Indicator */}
        <div className="ml-auto flex items-center gap-2 px-3 py-1.5 text-xs">
          <span className={`w-2 h-2 rounded-full ${isRunning ? 'bg-green-500 animate-pulse' : 'bg-gray-400'}`} />
          <span className={isDark ? 'text-gray-400' : 'text-gray-600'}>
            {isRunning ? 'Active' : 'Stopped'}
          </span>
        </div>

        {/* Configure Button */}
        <button
          className={`px-3 py-1.5 text-xs font-medium border rounded ${
            isDark ? 'bg-gray-700 border-gray-600 hover:bg-gray-600' : 'bg-white border-gray-300 hover:bg-gray-50'
          }`}
          title="Configure reasoner"
          onClick={() => setShowConfigureDialog(true)}
        >
          <Settings size={14} />
        </button>

        {/* Export Button with Dropdown */}
        <div className="relative group">
          <button
            className={`px-3 py-1.5 text-xs font-medium border rounded flex items-center gap-1.5 ${
              isDark ? 'bg-gray-700 border-gray-600 hover:bg-gray-600' : 'bg-white border-gray-300 hover:bg-gray-50'
            }`}
            onClick={() => setShowExportMenu(!showExportMenu)}
            disabled={!reasonerResults}
            title="Export results"
          >
            <Download size={14} />
            Export
            <ChevronDown size={12} />
          </button>
          
          {showExportMenu && reasonerResults && (
            <div
              className={`absolute top-full right-0 mt-1 border rounded shadow-lg z-50 min-w-[180px] ${
                isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-300'
              }`}
            >
              <button
                onClick={() => { exportToJSON(); setShowExportMenu(false); }}
                className={`w-full px-4 py-2 text-xs text-left flex items-center gap-2 ${
                  isDark ? 'hover:bg-gray-700' : 'hover:bg-gray-100'
                }`}
              >
                <FileJson size={14} />
                Export as JSON
              </button>
              <button
                onClick={() => { exportToCSV(); setShowExportMenu(false); }}
                className={`w-full px-4 py-2 text-xs text-left flex items-center gap-2 ${
                  isDark ? 'hover:bg-gray-700' : 'hover:bg-gray-100'
                }`}
              >
                <Save size={14} />
                Export as CSV
              </button>
              <button
                onClick={() => { copyToClipboard(); setShowExportMenu(false); }}
                className={`w-full px-4 py-2 text-xs text-left flex items-center gap-2 ${
                  isDark ? 'hover:bg-gray-700' : 'hover:bg-gray-100'
                }`}
              >
                <Copy size={14} />
                Copy to Clipboard
              </button>
            </div>
          )}
        </div>

        {/* Advanced Settings Button */}
        <button
          className={`px-3 py-1.5 text-xs font-medium border rounded flex items-center gap-1.5 ${
            isDark ? 'bg-gray-700 border-gray-600 hover:bg-gray-600' : 'bg-white border-gray-300 hover:bg-gray-50'
          }`}
          onClick={() => setShowAdvancedSettings(true)}
          title="Advanced settings"
        >
          <Settings size={14} />
          Advanced
        </button>

        {/* Cache Control */}
        {cacheResults && (
          <button
            className={`px-3 py-1.5 text-xs font-medium border rounded flex items-center gap-1.5 ${
              isDark ? 'bg-gray-700 border-gray-600 hover:bg-red-600' : 'bg-white border-gray-300 hover:bg-red-50'
            }`}
            onClick={clearCache}
            title="Clear reasoner cache"
          >
            <Trash2 size={14} />
            Clear Cache
          </button>
        )}
      </div>

      {/* Main Content Area */}
      <div className="flex-1 flex overflow-hidden">
        {!reasonerResults && !isLoading ? (
          /* Empty State - Before Starting Reasoner */
          <div className={`flex-1 flex flex-col items-center justify-center gap-4 text-center p-8 ${
            isDark ? 'bg-gray-900' : 'bg-gray-50'
          }`}>
            <Brain size={64} className={isDark ? 'text-gray-700' : 'text-gray-300'} />
            <div className="space-y-2">
              <h3 className={`text-lg font-semibold ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>No Reasoner Running</h3>
              <p className={`text-sm max-w-md ${isDark ? 'text-gray-500' : 'text-gray-500'}`}>
                Click <strong>Start</strong> to begin reasoning over the ontology.<br />
                Select a reasoner from the dropdown menu above.
              </p>
            </div>
            {consistencyData && (
              <div className={`mt-4 px-4 py-2 rounded-md text-sm border ${
                (consistencyData.consistent === false || consistencyData.isConsistent === false)
                  ? (isDark ? 'bg-red-900/20 border-red-800 text-red-400' : 'bg-red-50 border-red-200 text-red-700')
                  : (isDark ? 'bg-green-900/20 border-green-800 text-green-400' : 'bg-green-50 border-green-200 text-green-700')
              }`}>
                {(consistencyData.consistent === false || consistencyData.isConsistent === false)
                  ? '⚠ Ontology is inconsistent'
                  : '✓ Ontology is consistent'}
              </div>
            )}
          </div>
        ) : isLoading ? (
          /* Loading State */
          <div className={`flex-1 flex flex-col items-center justify-center gap-4 ${
            isDark ? 'bg-gray-900' : 'bg-gray-50'
          }`}>
            <Loader2 size={48} className="animate-spin text-purple-500" />
            <div className="text-center">
              <p className={`text-sm font-medium ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>Running {selectedReasoner} Reasoner...</p>
              <p className={`text-xs mt-1 ${isDark ? 'text-gray-500' : 'text-gray-500'}`}>This may take a moment for large ontologies</p>
            </div>
          </div>
        ) : (
          /* Results View Layout */
          <>
            {/* Left Panel - Consistency & Stats */}
            <div className={`w-80 border-r flex flex-col overflow-hidden ${
              isDark ? 'border-gray-700 bg-gray-900' : 'border-gray-300 bg-white'
            }`}>
              {/* Consistency Status */}
              <div className={`px-4 py-3 border-b ${
                isOntologyInconsistent 
                  ? (isDark ? 'bg-red-900/20 border-red-900/30' : 'bg-red-50 border-red-100') 
                  : (isDark ? 'bg-green-900/20 border-green-900/30' : 'bg-green-50 border-green-100')
              }`}>
                <div className="flex items-center gap-2 mb-1">
                  <AlertCircle size={16} className={isOntologyInconsistent ? 'text-red-500' : 'text-green-500'} />
                  <span className={`text-sm font-semibold ${isDark ? 'text-gray-200' : 'text-gray-900'}`}>Consistency</span>
                </div>
                <div className={`text-xs font-medium ${
                  isOntologyInconsistent ? 'text-red-500' : 'text-green-600'
                }`}>
                  {isOntologyInconsistent ? '✗ Ontology is inconsistent' : '✓ Ontology is consistent'}
                </div>
                {displayStats && (
                  <div className={`text-[11px] mt-1 ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>
                    {(displayStats.satisfiableClasses ?? 'N/A')} satisfiable · {' '}
                    {(displayStats.unsatisfiableClassesRaw === -1 ? 'N/A' : (displayStats.unsatisfiableClasses ?? 0))} unsatisfiable
                  </div>
                )}
              </div>

              {/* Reasoner Info */}
              <div className={`px-4 py-3 border-b ${isDark ? 'border-gray-700 bg-gray-800/50' : 'border-gray-300 bg-gray-50'}`}>
                <div className={`text-xs font-semibold mb-2 ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>Reasoner Information</div>
                <div className={`space-y-1 text-[11px] ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>
                  <div className="flex justify-between">
                    <span>Name:</span>
                    <span className={`font-medium ${isDark ? 'text-gray-200' : 'text-gray-900'}`}>{selectedReasoner}</span>
                  </div>
                  <div className="flex justify-between">
                    <span>Version:</span>
                    <span className={`font-medium ${isDark ? 'text-gray-200' : 'text-gray-900'}`}>
                      {selectedReasoner === 'HermiT' ? '1.4.5.519' : 
                       selectedReasoner === 'ELK' ? '0.4.3' :
                       selectedReasoner === 'Openllet' ? '2.6.5' : 
                       selectedReasoner === 'Pellet' ? '2.3.1' : 
                       '1.0.0'}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span>Status:</span>
                    <span className={`font-medium ${isRunning ? 'text-green-500' : 'text-gray-500'}`}>
                      {isRunning ? 'Running' : 'Stopped'}
                    </span>
                  </div>
                  {reasoningTime !== null && (
                    <div className="flex justify-between">
                      <span>Reasoning Time:</span>
                      <span className={`font-medium ${isDark ? 'text-blue-400' : 'text-blue-600'}`}>
                        {reasoningTime < 1000 ? `${reasoningTime}ms` : `${(reasoningTime / 1000).toFixed(2)}s`}
                      </span>
                    </div>
                  )}
                </div>
              </div>

              {/* Entity Counts */}
              <div className={`px-4 py-3 border-b ${isDark ? 'border-gray-700' : 'border-gray-300'}`}>
                <div className={`text-xs font-semibold mb-2 ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>Entity Counts</div>
                <div className="space-y-1.5">
                  {[
                    { label: 'Classes', value: displayStats?.classHierarchyNodes ?? reasonerResults?.totalClasses ?? 0, icon: '🔷' },
                    { label: 'Object Properties', value: displayStats?.objectPropertyNodes ?? reasonerResults?.totalObjectProperties ?? 0, icon: '🔗' },
                    { label: 'Data Properties', value: displayStats?.dataPropertyNodes ?? reasonerResults?.totalDataProperties ?? 0, icon: '📊' },
                    { label: 'Individuals', value: displayStats?.individuals ?? reasonerResults?.totalIndividuals ?? 0, icon: '👤' }
                  ].map((item) => (
                    <div key={item.label} className="flex items-center justify-between text-xs">
                      <span className={isDark ? 'text-gray-400' : 'text-gray-600'}>
                        <span className="mr-1.5">{item.icon}</span>
                        {item.label}
                      </span>
                      <span className={`font-semibold px-2 py-0.5 rounded ${
                        isDark ? 'bg-gray-800 text-gray-200' : 'bg-gray-100 text-gray-900'
                      }`}>
                        {item.value}
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              {/* Unsatisfiable Classes */}
              {combinedUnsat.length > 0 && (
                <div className={`px-4 py-3 border-b flex-1 overflow-hidden flex flex-col ${
                  isDark ? 'bg-red-900/10 border-gray-700' : 'bg-red-50 border-gray-300'
                }`}>
                  <div className="flex items-center justify-between mb-2">
                    <div className={`text-xs font-semibold ${isDark ? 'text-red-400' : 'text-red-700'}`}>
                      ⚠ Unsatisfiable Classes ({combinedUnsat.length})
                    </div>
                    <button
                      onClick={() => setShowExplainDialog(true)}
                      className="text-[10px] px-2 py-0.5 bg-red-600 text-white rounded hover:bg-red-700"
                      disabled={isLoading}
                    >
                      Explain
                    </button>
                  </div>
                  <div className="flex-1 overflow-y-auto space-y-1">
                    {combinedUnsat.map((cls: any, idx: number) => {
                      const clsData = typeof cls === 'string'
                        ? { iri: cls, label: cls.split('#').pop() || cls.split('/').pop() || cls }
                        : cls;
                      return (
                        <div 
                          key={idx} 
                          className={`border rounded px-2 py-1.5 text-[11px] cursor-pointer ${
                            isDark ? 'bg-gray-800 border-red-900/50 hover:bg-gray-700' : 'bg-white border-red-200 hover:bg-red-50'
                          }`}
                          onClick={() => navigateToEntity(clsData.iri)}
                        >
                          <div className={`font-semibold ${isDark ? 'text-red-400' : 'text-red-900'}`}>{clsData.label}</div>
                          <div className={`text-[10px] truncate ${isDark ? 'text-gray-500' : 'text-red-600'}`} title={clsData.iri}>
                            {clsData.iri}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Equivalent Classes */}
              {equivalentGroups.length > 0 && (
                <div className={`px-4 py-3 border-b ${isDark ? 'bg-blue-900/10 border-gray-700' : 'bg-blue-50 border-gray-300'}`}>
                  <div className={`text-xs font-semibold mb-2 flex items-center gap-1.5 ${isDark ? 'text-blue-400' : 'text-blue-700'}`}>
                    <GitMerge size={14} />
                    Equivalent Classes ({equivalentGroups.length})
                  </div>
                  <div className="space-y-1 max-h-32 overflow-y-auto">
                    {equivalentGroups.map((group: any, idx: number) => {
                      const groupArray = Array.isArray(group) ? group : (group.classes || [group]);
                      const displayText = groupArray.length > 0 
                        ? groupArray.map((item: any) => typeof item === 'string' ? item : (item.label || item.iri || String(item))).join(' ≡ ')
                        : 'Unknown';
                      
                      return (
                        <div key={idx} className={`border rounded px-2 py-1 text-[11px] ${
                          isDark ? 'bg-gray-800 border-blue-900/50 text-blue-300' : 'bg-white border-blue-200 text-blue-800'
                        }`}>
                          {displayText}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Actions */}
              <div className={`px-4 py-3 border-t mt-auto ${isDark ? 'border-gray-700 bg-gray-800/50' : 'border-gray-300 bg-gray-50'}`}>
                <div className="space-y-2">
                  <button
                    onClick={() => setShowExplainDialog(true)}
                    disabled={!isOntologyInconsistent || isLoading}
                    className={`w-full px-3 py-2 text-xs font-medium border rounded disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 ${
                      isDark ? 'bg-gray-700 border-gray-600 hover:bg-gray-600 text-gray-200' : 'bg-white border-gray-300 hover:bg-gray-50 text-gray-700'
                    }`}
                  >
                    <AlertCircle size={14} />
                    Explain Inconsistency
                  </button>
                  <button
                    onClick={() => dashboardStartReasoner ? dashboardStartReasoner() : startReasoner('classification')}
                    disabled={isLoading || !projectId}
                    className="w-full px-3 py-2 text-xs font-medium bg-purple-600 text-white rounded hover:bg-purple-700 disabled:opacity-50 flex items-center justify-center gap-2"
                  >
                    <RefreshCw size={14} />
                    Re-classify
                  </button>
                </div>
              </div>
            </div>

            {/* Right Panel - Inferred Hierarchies */}
            <div className={`flex-1 flex flex-col overflow-hidden ${isDark ? 'bg-gray-900' : 'bg-white'}`}>
              <div className={`px-4 py-0 border-b ${isDark ? 'border-gray-700 bg-gray-800/50' : 'border-gray-300 bg-gray-50'}`}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center">
                    {[
                      { id: 'classes', label: 'Classes', icon: <Network size={14} /> },
                      { id: 'objectProperties', label: 'Object Properties', icon: <Link2 size={14} /> },
                      { id: 'dataProperties', label: 'Data Properties', icon: <Database size={14} /> },
                      { id: 'inferredAxioms', label: 'Inferred Axioms', icon: <Zap size={14} /> },
                    ].map((tab) => (
                      <button
                        key={tab.id}
                        onClick={() => setActiveTab(tab.id as any)}
                        className={`px-4 py-3 text-xs font-medium flex items-center gap-2 border-b-2 transition-colors ${
                          activeTab === tab.id
                            ? 'border-purple-500 text-purple-500'
                            : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                        }`}
                      >
                        {tab.icon}
                        {tab.label}
                      </button>
                    ))}
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={expandAll}
                      className={`p-1.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-500 transition-colors`}
                      title="Expand All"
                    >
                      <ChevronDown size={14} />
                    </button>
                    <button
                      onClick={collapseAll}
                      className={`p-1.5 rounded hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-500 transition-colors`}
                      title="Collapse All"
                    >
                      <ChevronRight size={14} />
                    </button>
                    <div className={`w-px h-4 mx-1 ${isDark ? 'bg-gray-700' : 'bg-gray-300'}`} />
                    <button
                      onClick={() => dashboardStartReasoner ? dashboardStartReasoner() : startReasoner('classification')}
                      disabled={isLoading || !projectId}
                      className={`px-3 py-1.5 text-xs font-medium border rounded disabled:opacity-50 flex items-center gap-1.5 ${
                        isDark ? 'bg-gray-700 border-gray-600 hover:bg-gray-600 text-gray-200' : 'bg-white border-gray-300 hover:bg-gray-50 text-gray-700'
                      }`}
                    >
                      <RefreshCw size={12} />
                      Refresh
                    </button>
                  </div>
                </div>
              </div>
              <div className="flex-1 overflow-y-auto px-4 py-3">
                {activeTab === 'classes' && (
                  classHierarchyToRender && classHierarchyToRender.length > 0 ? (
                    <div className="space-y-0.5 text-sm">
                      {renderHierarchy(classHierarchyToRender, 'class')}
                    </div>
                  ) : (
                    <div className={`flex items-center justify-center h-full text-sm italic ${isDark ? 'text-gray-600' : 'text-gray-500'}`}>
                      No inferred class hierarchy available. Run the reasoner to generate results.
                    </div>
                  )
                )}

                {activeTab === 'objectProperties' && (
                  objectPropertyHierarchyToRender && objectPropertyHierarchyToRender.length > 0 ? (
                    <div className="space-y-0.5 text-sm">
                      {renderHierarchy(objectPropertyHierarchyToRender, 'objectProperty')}
                    </div>
                  ) : (
                    <div className={`flex items-center justify-center h-full text-sm italic ${isDark ? 'text-gray-600' : 'text-gray-500'}`}>
                      No inferred object property hierarchy available. Run the reasoner to generate results.
                    </div>
                  )
                )}

                {activeTab === 'dataProperties' && (
                  dataPropertyHierarchyToRender && dataPropertyHierarchyToRender.length > 0 ? (
                    <div className="space-y-0.5 text-sm">
                      {renderHierarchy(dataPropertyHierarchyToRender, 'dataProperty')}
                    </div>
                  ) : (
                    <div className={`flex items-center justify-center h-full text-sm italic ${isDark ? 'text-gray-600' : 'text-gray-500'}`}>
                      No inferred data property hierarchy available. Run the reasoner to generate results.
                    </div>
                  )
                )}

                {activeTab === 'inferredAxioms' && (
                  inferredAxiomsToRender.length > 0 ? (
                    <div className="space-y-3 text-sm">
                      <div className="flex items-center gap-2">
                        <input
                          type="text"
                          value={inferredAxiomsFilter}
                          onChange={(e) => setInferredAxiomsFilter(e.target.value)}
                          placeholder="Filter axioms..."
                          className={`flex-1 px-3 py-1.5 text-xs rounded border ${
                            isDark ? 'bg-gray-800 border-gray-600 text-gray-200' : 'bg-white border-gray-300 text-gray-800'
                          }`}
                        />
                        <span className={`text-xs whitespace-nowrap ${isDark ? 'text-gray-400' : 'text-gray-500'}`}>
                          {inferredAxiomsTotal > inferredAxiomsToRender.length
                            ? `Showing ${inferredAxiomsToRender.length} of ${inferredAxiomsTotal}`
                            : `${inferredAxiomsToRender.length} axioms`}
                        </span>
                      </div>
                      <div className="space-y-1">
                        {inferredAxiomsToRender
                          .filter((ax) => {
                            const q = inferredAxiomsFilter.trim().toLowerCase();
                            if (!q) return true;
                            return (
                              (ax.readable || '').toLowerCase().includes(q) ||
                              (ax.axiom || '').toLowerCase().includes(q) ||
                              (ax.axiomType || '').toLowerCase().includes(q)
                            );
                          })
                          .map((ax, idx) => (
                            <div
                              key={idx}
                              className={`px-3 py-2 rounded border text-xs font-mono ${
                                isDark ? 'bg-gray-800/50 border-gray-700 text-gray-200' : 'bg-gray-50 border-gray-200 text-gray-800'
                              }`}
                            >
                              <span className={`inline-block px-1.5 py-0.5 mr-2 rounded text-[10px] font-sans font-medium ${
                                isDark ? 'bg-purple-900/40 text-purple-300' : 'bg-purple-100 text-purple-700'
                              }`}>
                                {ax.axiomType}
                              </span>
                              {ax.readable || ax.axiom}
                            </div>
                          ))}
                      </div>
                    </div>
                  ) : (
                    <div className={`flex items-center justify-center h-full text-sm italic ${isDark ? 'text-gray-600' : 'text-gray-500'}`}>
                      No inferred axioms yet. Run consistency check or classification to populate this view.
                    </div>
                  )
                )}
              </div>
            </div>
          </>
        )}
      </div>

      {/* Explanation Dialog */}
      {showExplainDialog && (
        <div className="fixed inset-0 z-[10000] flex items-center justify-center bg-black/50 p-4">
          <div className={`w-full max-w-2xl rounded-lg shadow-2xl flex flex-col max-h-[80vh] ${
            isDark ? 'bg-gray-800 text-gray-100' : 'bg-white text-gray-900'
          }`}>
            <div className={`flex items-center justify-between px-6 py-4 border-b ${isDark ? 'border-gray-700' : 'border-gray-200'}`}>
              <h3 className="text-lg font-semibold flex items-center gap-2">
                <Lightbulb className="text-yellow-500" size={20} />
                Reasoner Explanation
              </h3>
              <button onClick={() => setShowExplainDialog(false)} className="text-gray-500 hover:text-gray-700">
                <XCircle size={24} />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-6">
              {isOntologyInconsistent ? (
                <div className="space-y-4">
                  <div className={`p-4 rounded-md border ${isDark ? 'bg-red-900/20 border-red-800 text-red-400' : 'bg-red-50 border-red-200 text-red-700'}`}>
                    <div className="font-bold mb-1">Ontology is Inconsistent</div>
                    <div className="text-sm">The reasoner has detected logical contradictions in the ontology.</div>
                  </div>
                  {/* Add more detailed explanation logic here if available */}
                  <div className="text-sm opacity-80">
                    Detailed explanation for inconsistency is being computed...
                  </div>
                </div>
              ) : (
                <div className="text-center py-8 text-gray-500 italic">
                  No inconsistencies detected.
                </div>
              )}
            </div>
            <div className={`px-6 py-4 border-t flex justify-end ${isDark ? 'border-gray-700' : 'border-gray-200'}`}>
              <button 
                onClick={() => setShowExplainDialog(false)}
                className={`px-4 py-2 text-sm font-medium rounded ${
                  isDark ? 'bg-gray-700 hover:bg-gray-600' : 'bg-gray-100 hover:bg-gray-200'
                }`}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Advanced Settings Dialog */}
      {showAdvancedSettings && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={() => setShowAdvancedSettings(false)}>
          <div 
            className={`rounded-lg shadow-xl w-full max-w-md ${isDark ? 'bg-gray-800' : 'bg-white'}`}
            onClick={(e) => e.stopPropagation()}
          >
            <div className={`px-6 py-4 border-b ${isDark ? 'border-gray-700' : 'border-gray-200'}`}>
              <h2 className={`text-lg font-semibold ${isDark ? 'text-gray-100' : 'text-gray-900'}`}>
                Advanced Reasoner Settings
              </h2>
            </div>
            
            <div className="px-6 py-4 space-y-4">
              {/* Timeout Setting */}
              <div>
                <label className={`block text-sm font-medium mb-2 ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>
                  Reasoning Timeout (seconds): {timeoutSeconds}
                </label>
                <input
                  type="range"
                  min="5"
                  max="300"
                  value={timeoutSeconds}
                  onChange={(e) => setTimeoutSeconds(Number(e.target.value))}
                  className="w-full"
                />
                <div className="flex justify-between text-xs text-gray-500 mt-1">
                  <span>5s</span>
                  <span>300s (5min)</span>
                </div>
              </div>

              {/* Incremental Reasoning Toggle */}
              <div className="flex items-center justify-between">
                <label className={`text-sm font-medium ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>
                  Incremental Reasoning
                </label>
                <button
                  onClick={() => setIncrementalReasoning(!incrementalReasoning)}
                  className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                    incrementalReasoning ? 'bg-blue-600' : 'bg-gray-300'
                  }`}
                >
                  <span
                    className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                      incrementalReasoning ? 'translate-x-6' : 'translate-x-1'
                    }`}
                  />
                </button>
              </div>
              <p className={`text-xs ${isDark ? 'text-gray-500' : 'text-gray-600'}`}>
                Only recompute changed parts of the ontology
              </p>

              {/* Cache Results Toggle */}
              <div className="flex items-center justify-between">
                <label className={`text-sm font-medium ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>
                  Cache Results
                </label>
                <button
                  onClick={() => setCacheResults(!cacheResults)}
                  className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                    cacheResults ? 'bg-blue-600' : 'bg-gray-300'
                  }`}
                >
                  <span
                    className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                      cacheResults ? 'translate-x-6' : 'translate-x-1'
                    }`}
                  />
                </button>
              </div>
              <p className={`text-xs ${isDark ? 'text-gray-500' : 'text-gray-600'}`}>
                Cache reasoning results for faster subsequent queries
              </p>

              {/* Export Format Preference */}
              <div>
                <label className={`block text-sm font-medium mb-2 ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>
                  Default Export Format
                </label>
                <select
                  value={exportFormat}
                  onChange={(e) => setExportFormat(e.target.value as 'json' | 'csv')}
                  className={`w-full px-3 py-2 text-sm border rounded ${
                    isDark ? 'bg-gray-700 border-gray-600 text-gray-100' : 'bg-white border-gray-300 text-gray-900'
                  }`}
                >
                  <option value="json">JSON</option>
                  <option value="csv">CSV</option>
                </select>
              </div>
            </div>

            <div className={`px-6 py-4 border-t flex justify-end gap-2 ${isDark ? 'border-gray-700' : 'border-gray-200'}`}>
              <button 
                onClick={() => setShowAdvancedSettings(false)}
                className={`px-4 py-2 text-sm font-medium rounded ${
                  isDark ? 'bg-gray-700 hover:bg-gray-600' : 'bg-gray-100 hover:bg-gray-200'
                }`}
              >
                Cancel
              </button>
              <button 
                onClick={() => {
                  // Save settings to localStorage
                  localStorage.setItem('reasonerSettings', JSON.stringify({
                    timeoutSeconds,
                    incrementalReasoning,
                    cacheResults,
                    exportFormat
                  }));
                  setShowAdvancedSettings(false);
                }}
                className="px-4 py-2 text-sm font-medium bg-blue-600 text-white rounded hover:bg-blue-700"
              >
                Save Settings
              </button>
            </div>
          </div>
        </div>
      )}

      <style>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
        .animate-spin {
          animation: spin 1s linear infinite;
        }
      `}</style>
    </div>
  );
};

const getStyles = (isDark: boolean): { [key: string]: React.CSSProperties } => ({
  // Keep some legacy styles for internal components if needed, but most are now Tailwind-like classes
  container: {
    width: '100%',
    height: '100%',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: isDark ? '#111827' : '#ffffff',
    fontFamily: 'Inter, system-ui, -apple-system, sans-serif'
  }
});

export default ReasonerPluginView;
