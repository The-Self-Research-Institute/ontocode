/**
 * Protégé-Style Reasoner Plugin
 * Complete implementation matching desktop Protégé reasoner functionality
 * Includes explanation tooltips, class hierarchy view, and full reasoning features
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Play,
  Square,
  CheckCircle,
  XCircle,
  AlertTriangle,
  Clock,
  Info,
  ChevronDown,
  ChevronRight,
  Circle,
  Loader,
  RefreshCw,
  Settings,
  HelpCircle,
  Lightbulb,
  Link2,
  ArrowRight,
  GitBranch,
  Database
} from 'lucide-react';

interface ReasonerPluginProps {
  projectId: string;
  apiBaseUrl?: string;
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

export const ProtegeReasonerPlugin: React.FC<ReasonerPluginProps> = ({
  projectId,
  apiBaseUrl = ''
}) => {
  const resolvedApiBaseUrl =
    (apiBaseUrl && apiBaseUrl.trim().length > 0 ? apiBaseUrl.trim() : undefined) ||
    (typeof window !== 'undefined' && (window as any).API_BASE_URL
      ? ((window as any).API_BASE_URL as string)
      : undefined) ||
    'http://localhost:8082';
  // State
  const [showReasonerMenu, setShowReasonerMenu] = useState(false);
  const [selectedReasoner, setSelectedReasoner] = useState<string>('hermit');
  const [isRunning, setIsRunning] = useState(false);
  const [isConsistent, setIsConsistent] = useState<boolean | null>(null);
  const [classHierarchy, setClassHierarchy] = useState<ClassNode[]>([]);
  const [equivalentClasses, setEquivalentClasses] = useState<any[]>([]);
  const [unsatisfiableClasses, setUnsatisfiableClasses] = useState<any[]>([]);
  const [expandedClasses, setExpandedClasses] = useState<Set<string>>(new Set());
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
  const tooltipRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const classRefs = useRef<Map<string, HTMLDivElement>>(new Map());

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

  // Start reasoning
  const startReasoner = useCallback(async (task: 'consistency' | 'classification' | 'realization') => {
    setIsRunning(true);
    setReasonerStatus(`Running ${task}...`);

    try {
      let endpoint = '';
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

      console.log('[ProtegeReasonerPlugin] Starting reasoner:', {
        task,
        reasonerType,
        endpoint: `${resolvedApiBaseUrl}${endpoint}`,
        projectId
      });

      const response = await fetch(`${resolvedApiBaseUrl}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reasonerType })
      });

      console.log('[ProtegeReasonerPlugin] Response status:', response.status);

      if (!response.ok) {
        const errorText = await response.text();
        console.error('[ProtegeReasonerPlugin] Error response:', errorText);
        throw new Error(`Reasoning failed: ${response.statusText}. ${errorText}`);
      }

      const result = await response.json();
      console.log('[ProtegeReasonerPlugin] Reasoning result:', result);

      if (task === 'consistency') {
        setIsConsistent(result.consistent);
        if (!result.consistent && result.unsatisfiableClasses) {
          setUnsatisfiableClasses(result.unsatisfiableClasses);
        }
      } else if (task === 'classification') {
        // Set consistency from classification result
        if (result.isConsistent !== undefined) {
          setIsConsistent(result.isConsistent);
        }
        
        setClassHierarchy(result.classHierarchy || []);
        setEquivalentClasses(result.equivalentClasses || []);
        setUnsatisfiableClasses(result.unsatisfiableClasses || []);
        
        // Generate explanations for classes
        generateExplanations(result);
      } else if (task === 'realization') {
        // Set consistency from realization result
        if (result.isConsistent !== undefined) {
          setIsConsistent(result.isConsistent);
        }
      }

      // Get stats
      const statsRes = await fetch(`${resolvedApiBaseUrl}/plugin-service/api/reasoner/${projectId}/stats?reasonerType=${reasonerType}`);
      if (statsRes.ok) {
        const statsData = await statsRes.json();
        setStats(statsData);
      }

      setReasonerStatus(`${task} completed successfully`);
    } catch (error) {
      console.error('[ProtegeReasonerPlugin] Reasoning error:', error);
      setReasonerStatus(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsRunning(false);
    }
  }, [projectId, apiBaseUrl, selectedReasoner]);

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

    setIsRunning(true);
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

      const response = await fetch(
        `${resolvedApiBaseUrl}/plugin-service/api/reasoner/${projectId}/explain-inconsistency`,
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
      setIsRunning(false);
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

  // Navigate to class in hierarchy
  const navigateToClass = (classIri: string) => {
    setSelectedClassIri(classIri);
    // Expand parent classes to make it visible
    setExpandedClasses(prev => {
      const newSet = new Set(prev);
      // Find and expand all parents (simplified - in production would walk hierarchy)
      classHierarchy.forEach(node => {
        if (node.iri === classIri && node.depth > 0) {
          newSet.add(node.iri);
        }
      });
      return newSet;
    });
    
    // Scroll to the class element
    setTimeout(() => {
      const element = classRefs.current.get(classIri);
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'center' });
        // Highlight briefly
        element.style.backgroundColor = '#fef3c7';
        setTimeout(() => {
          element.style.backgroundColor = '';
        }, 2000);
      }
    }, 100);
  };

  // Toggle class expansion
  const toggleClassExpansion = (classIri: string) => {
    setExpandedClasses(prev => {
      const newSet = new Set(prev);
      if (newSet.has(classIri)) {
        newSet.delete(classIri);
      } else {
        newSet.add(classIri);
      }
      return newSet;
    });
  };

  // Render class hierarchy tree
  const renderClassNode = (node: ClassNode) => {
    const isExpanded = expandedClasses.has(node.iri);
    const hasChildren = node.childrenCount > 0;
    const explanation = explanations.get(node.iri);
    const isUnsatisfiable = unsatisfiableClasses.some(cls => cls.iri === node.iri);
    const isSelected = selectedClassIri === node.iri;

    return (
      <div key={node.iri} style={{ marginLeft: `${node.depth * 20}px` }}>
        <div
          ref={(el) => {
            if (el) classRefs.current.set(node.iri, el);
          }}
          style={{
            display: 'flex',
            alignItems: 'center',
            padding: '4px 8px',
            cursor: 'pointer',
            backgroundColor: isSelected ? '#fef3c7' : (hoveredClass === node.iri ? '#f0f0f0' : 'transparent'),
            borderLeft: isUnsatisfiable ? '3px solid #ef4444' : (isSelected ? '3px solid #f59e0b' : 'none'),
            color: isUnsatisfiable ? '#ef4444' : 'inherit',
            transition: 'background-color 0.3s ease'
          }}
          onMouseEnter={(e) => handleClassHover(node.iri, e)}
          onMouseLeave={handleClassLeave}
          onClick={() => navigateToClass(node.iri)}
        >
          {hasChildren && (
            <button
              onClick={() => toggleClassExpansion(node.iri)}
              style={{
                border: 'none',
                background: 'none',
                padding: '2px',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center'
              }}
            >
              {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
          )}
          {!hasChildren && <span style={{ width: '18px' }} />}
          
          <Circle
            size={10}
            fill={isUnsatisfiable ? '#ef4444' : node.isEquivalent ? '#f59e0b' : '#3b82f6'}
            stroke="none"
            style={{ marginRight: '6px' }}
          />
          
          <span style={{ fontSize: '13px', flex: 1 }}>{node.label}</span>
          
          {explanation && (
            <HelpCircle size={14} style={{ color: '#9ca3af', marginLeft: '4px' }} />
          )}
        </div>
      </div>
    );
  };

  return (
    <div style={styles.container}>
      {/* Header with Reasoner Dropdown Menu */}
      <div style={styles.header}>
        <div style={styles.headerLeft}>
          <div style={styles.menuContainer}>
            <button
              style={styles.menuButton}
              onClick={() => setShowReasonerMenu(!showReasonerMenu)}
            >
              Reasoner
            </button>
            
            {showReasonerMenu && (
              <div ref={menuRef} style={styles.dropdownMenu}>
                <div 
                  style={styles.menuItem}
                  onClick={() => {
                    startReasoner('consistency');
                    setShowReasonerMenu(false);
                  }}
                >
                  Start reasoner
                </div>
                <div 
                  style={styles.menuItem}
                  onClick={() => {
                    setAutoSync(!autoSync);
                  }}
                >
                  <input 
                    type="checkbox" 
                    checked={autoSync} 
                    onChange={() => {}}
                    style={{ marginRight: '8px' }}
                  />
                  Synchronize reasoner
                </div>
                <div 
                  style={styles.menuItem}
                  onClick={() => {
                    setIsConsistent(null);
                    setClassHierarchy([]);
                    setExplanations(new Map());
                    setShowReasonerMenu(false);
                  }}
                >
                  Stop reasoner
                </div>
                <div 
                  style={{
                    ...styles.menuItem,
                    ...(isConsistent === false ? {} : styles.menuItemDisabled)
                  }}
                  onClick={async () => {
                    if (isConsistent === false) {
                      setShowExplainDialog(true);
                      setShowReasonerMenu(false);
                      // Fetch detailed explanation
                      await fetchInconsistencyExplanation();
                    }
                  }}
                >
                  Explain inconsistent ontology
                </div>
                <div style={styles.menuDivider} />
                <div 
                  style={styles.menuItem}
                  onClick={() => {
                    setShowConfigureDialog(true);
                    setShowReasonerMenu(false);
                  }}
                >
                  Configure...
                </div>
                <div style={styles.menuDivider} />
                <div style={styles.menuItemLabel}>ELK 0.6.0</div>
                <div 
                  style={{
                    ...styles.menuItem,
                    backgroundColor: selectedReasoner === 'hermit' ? '#e0e7ff' : 'transparent'
                  }}
                  onClick={() => {
                    setSelectedReasoner('hermit');
                    setShowReasonerMenu(false);
                  }}
                >
                  <span style={{ marginLeft: selectedReasoner === 'hermit' ? '0' : '20px' }}>
                    {selectedReasoner === 'hermit' && '• '}HermiT 1.4.3.456
                  </span>
                </div>
                <div style={styles.menuItemLabel}>Ontop 4.2.2</div>
                <div 
                  style={{
                    ...styles.menuItem,
                    backgroundColor: selectedReasoner === 'pellet' ? '#e0e7ff' : 'transparent'
                  }}
                  onClick={() => {
                    setSelectedReasoner('pellet');
                    setShowReasonerMenu(false);
                  }}
                >
                  <span style={{ marginLeft: selectedReasoner === 'pellet' ? '0' : '20px' }}>
                    {selectedReasoner === 'pellet' && '• '}Pellet
                  </span>
                </div>
                <div 
                  style={{
                    ...styles.menuItem,
                    backgroundColor: selectedReasoner === 'openllet' ? '#e0e7ff' : 'transparent'
                  }}
                  onClick={() => {
                    setSelectedReasoner('openllet');
                    setShowReasonerMenu(false);
                  }}
                >
                  <span style={{ marginLeft: selectedReasoner === 'openllet' ? '0' : '20px' }}>
                    {selectedReasoner === 'openllet' && '• '}Pellet (Incremental)
                  </span>
                </div>
                <div 
                  style={{
                    ...styles.menuItem,
                    backgroundColor: selectedReasoner === 'jcel' ? '#e0e7ff' : 'transparent'
                  }}
                  onClick={() => {
                    setSelectedReasoner('jcel');
                    setShowReasonerMenu(false);
                  }}
                >
                  <span style={{ marginLeft: selectedReasoner === 'jcel' ? '0' : '20px' }}>
                    {selectedReasoner === 'jcel' && '• '}jcel
                  </span>
                </div>
                <div 
                  style={{
                    ...styles.menuItem,
                    backgroundColor: selectedReasoner === 'none' ? '#e0e7ff' : 'transparent'
                  }}
                  onClick={() => {
                    setSelectedReasoner('none');
                    setShowReasonerMenu(false);
                  }}
                >
                  <span style={{ marginLeft: selectedReasoner === 'none' ? '0' : '20px' }}>
                    {selectedReasoner === 'none' && '• '}None
                  </span>
                </div>
              </div>
            )}
          </div>
          <Lightbulb size={20} style={{ color: '#667eea', marginLeft: '16px' }} />
          <span style={styles.title}>Reasoner: {selectedReasoner === 'hermit' ? 'HermiT 1.4.3.456' : selectedReasoner.charAt(0).toUpperCase() + selectedReasoner.slice(1)}</span>
        </div>
        <button
          onClick={() => setShowSettings(!showSettings)}
          style={styles.iconButton}
          title="Settings"
        >
          <Settings size={16} />
        </button>
      </div>

      {/* Configure Dialog */}
      {showConfigureDialog && (
        <div style={styles.dialogOverlay} onClick={() => setShowConfigureDialog(false)}>
          <div style={styles.dialog} onClick={(e) => e.stopPropagation()}>
            <div style={styles.dialogHeader}>
              <span style={styles.dialogTitle}>Reasoner Preferences</span>
              <button
                onClick={() => setShowConfigureDialog(false)}
                style={styles.closeButton}
              >
                ×
              </button>
            </div>
            <div style={styles.dialogContent}>
              <div style={styles.settingRow}>
                <label style={styles.checkboxLabel}>
                  <input
                    type="checkbox"
                    checked={autoSync}
                    onChange={(e) => setAutoSync(e.target.checked)}
                  />
                  <span>Synchronize reasoner automatically</span>
                </label>
              </div>
              <div style={styles.settingRow}>
                <span style={styles.settingLabel}>Explanation depth:</span>
                <select style={styles.select}>
                  <option>Full</option>
                  <option>Medium</option>
                  <option>Minimal</option>
                </select>
              </div>
              <div style={styles.settingRow}>
                <span style={styles.settingLabel}>Timeout (seconds):</span>
                <input type="number" defaultValue="30" style={styles.input} />
              </div>
            </div>
            <div style={styles.dialogFooter}>
              <button style={styles.button} onClick={() => setShowConfigureDialog(false)}>
                OK
              </button>
              <button style={styles.button} onClick={() => setShowConfigureDialog(false)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Explain Inconsistent Ontology Dialog */}
      {showExplainDialog && (
        <div style={styles.dialogOverlay} onClick={() => setShowExplainDialog(false)}>
          <div style={{...styles.dialog, maxWidth: '700px', maxHeight: '80vh', overflowY: 'auto'}} onClick={(e) => e.stopPropagation()}>
            <div style={styles.dialogHeader}>
              <span style={styles.dialogTitle}>🔍 Inconsistency Analysis</span>
              <button
                onClick={() => setShowExplainDialog(false)}
                style={styles.closeButton}
              >
                ×
              </button>
            </div>
            <div style={styles.dialogContent}>
              {isRunning && !inconsistencyExplanation ? (
                <div style={{ textAlign: 'center', padding: '24px' }}>
                  <Loader size={32} style={{ animation: 'spin 1s linear infinite', color: '#3b82f6' }} />
                  <p style={{ marginTop: '12px', color: '#6b7280' }}>Analyzing inconsistency...</p>
                </div>
              ) : inconsistencyExplanation && inconsistencyExplanation.causes ? (
                <>
                  {/* Show detailed causes from backend */}
                  {inconsistencyExplanation.causes.map((cause: any, idx: number) => (
                    <div key={idx} style={{ marginBottom: '20px' }}>
                      {cause.type === 'UNSATISFIABLE_CLASSES' && (
                        <div style={{ padding: '16px', backgroundColor: '#fee2e2', borderLeft: '4px solid #ef4444', borderRadius: '6px' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                            <XCircle size={20} style={{ color: '#dc2626' }} />
                            <span style={{ fontSize: '15px', fontWeight: 600, color: '#991b1b' }}>{cause.title}</span>
                          </div>
                          <p style={{ fontSize: '13px', color: '#7f1d1d', marginBottom: '12px' }}>{cause.description}</p>
                          <div style={{ maxHeight: '200px', overflowY: 'auto' }}>
                            {cause.classes?.map((cls: any, cidx: number) => (
                              <div
                                key={cidx}
                                style={{
                                  fontSize: '13px',
                                  padding: '8px',
                                  marginBottom: '6px',
                                  backgroundColor: 'white',
                                  borderRadius: '4px',
                                  cursor: 'pointer',
                                  border: '1px solid #fecaca'
                                }}
                                onClick={() => {
                                  navigateToClass(cls.iri);
                                  setShowExplainDialog(false);
                                }}
                              >
                                <div style={{ fontWeight: 600, marginBottom: '4px' }}>{cls.label}</div>
                                <div style={{ fontSize: '11px', color: '#6b7280' }}>{cls.reason}</div>
                                <div style={{ fontSize: '10px', color: '#9ca3af', marginTop: '4px' }}>Click to navigate →</div>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                      
                      {cause.type === 'DISJOINT_VIOLATIONS' && cause.violations?.length > 0 && (
                        <div style={{ padding: '16px', backgroundColor: '#fef3c7', borderLeft: '4px solid #f59e0b', borderRadius: '6px' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                            <AlertTriangle size={20} style={{ color: '#d97706' }} />
                            <span style={{ fontSize: '15px', fontWeight: 600, color: '#78350f' }}>{cause.title}</span>
                          </div>
                          <p style={{ fontSize: '13px', color: '#78350f', marginBottom: '12px' }}>{cause.description}</p>
                          <div>
                            {cause.violations.map((v: any, vidx: number) => (
                              <div key={vidx} style={{ fontSize: '12px', padding: '6px 8px', marginBottom: '4px', backgroundColor: 'white', borderRadius: '4px' }}>
                                <strong>{v.individual}</strong> belongs to disjoint classes: {v.disjointClasses?.join(', ')}
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                      
                      {cause.type === 'PROPERTY_VIOLATIONS' && cause.violations?.length > 0 && (
                        <div style={{ padding: '16px', backgroundColor: '#dbeafe', borderLeft: '4px solid #3b82f6', borderRadius: '6px' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                            <Info size={20} style={{ color: '#2563eb' }} />
                            <span style={{ fontSize: '15px', fontWeight: 600, color: '#1e3a8a' }}>{cause.title}</span>
                          </div>
                          <p style={{ fontSize: '13px', color: '#1e40af', marginBottom: '12px' }}>{cause.description}</p>
                          <div>
                            {cause.violations.map((v: any, vidx: number) => (
                              <div key={vidx} style={{ fontSize: '12px', padding: '6px 8px', marginBottom: '4px', backgroundColor: 'white', borderRadius: '4px' }}>
                                <strong>{v.property}</strong> has {v.hasDomainConstraints ? 'domain' : ''}{v.hasDomainConstraints && v.hasRangeConstraints ? ' and ' : ''}{v.hasRangeConstraints ? 'range' : ''} constraints
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                      
                      {cause.type === 'RECOMMENDATIONS' && (
                        <div style={{ padding: '16px', backgroundColor: '#e0f2fe', borderLeft: '4px solid #0ea5e9', borderRadius: '6px' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                            <Lightbulb size={20} style={{ color: '#0284c7' }} />
                            <span style={{ fontSize: '15px', fontWeight: 600, color: '#075985' }}>{cause.title}</span>
                          </div>
                          <ul style={{ fontSize: '13px', lineHeight: 1.8, color: '#0c4a6e', paddingLeft: '20px', margin: 0 }}>
                            {cause.tips?.map((tip: string, tidx: number) => (
                              <li key={tidx}>{tip}</li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>
                  ))}
                </>
              ) : (
                /* Fallback to generic explanation */
                <>
                  <div style={{ marginBottom: '16px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                      <AlertTriangle size={20} style={{ color: '#f59e0b' }} />
                      <span style={{ fontSize: '15px', fontWeight: 600 }}>Why is this ontology inconsistent?</span>
                    </div>
                    <p style={{ fontSize: '13px', lineHeight: 1.6, color: '#4b5563', marginBottom: '12px' }}>
                      An ontology is inconsistent when it contains logical contradictions. Common causes:
                    </p>
                    <ul style={{ fontSize: '13px', lineHeight: 1.8, color: '#4b5563', paddingLeft: '20px' }}>
                      <li><strong>Disjoint class violations:</strong> Conflicting class memberships</li>
                      <li><strong>Cardinality restrictions:</strong> Conflicting min/max constraints</li>
                      <li><strong>Property restrictions:</strong> Contradictory property values</li>
                    </ul>
                  </div>
                  
                  {unsatisfiableClasses.length > 0 && (
                    <div style={{ marginTop: '16px', padding: '12px', backgroundColor: '#fef3c7', borderRadius: '6px' }}>
                      <div style={{ fontSize: '14px', fontWeight: 600, marginBottom: '8px', color: '#92400e' }}>
                        Found {unsatisfiableClasses.length} Unsatisfiable Class{unsatisfiableClasses.length > 1 ? 'es' : ''}:
                      </div>
                      <div style={{ maxHeight: '200px', overflowY: 'auto' }}>
                        {unsatisfiableClasses.map((cls: any) => (
                          <div
                            key={cls.iri}
                            style={{
                              fontSize: '13px',
                              padding: '6px 8px',
                              marginBottom: '4px',
                              backgroundColor: 'white',
                              borderRadius: '4px',
                              cursor: 'pointer',
                              display: 'flex',
                              alignItems: 'center',
                              gap: '8px'
                            }}
                            onClick={() => {
                              navigateToClass(cls.iri);
                              setShowExplainDialog(false);
                            }}
                          >
                            <XCircle size={12} style={{ color: '#dc2626' }} />
                            <span>{cls.label}</span>
                            <span style={{ marginLeft: 'auto', fontSize: '11px', color: '#9ca3af' }}>Click to navigate →</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>
            <div style={styles.dialogFooter}>
              <button style={styles.button} onClick={() => setShowExplainDialog(false)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Status Bar */}
      <div style={{
        ...styles.statusBar,
        backgroundColor: isConsistent === true ? '#d1fae5' : 
                        isConsistent === false ? '#fee2e2' : 
                        '#f3f4f6'
      }}>
        {isRunning ? (
          <>
            <Loader size={14} style={{ animation: 'spin 1s linear infinite' }} />
            <span>{reasonerStatus}</span>
          </>
        ) : isConsistent === true ? (
          <>
            <CheckCircle size={14} style={{ color: '#059669' }} />
            <span style={{ color: '#059669' }}>Ontology is consistent</span>
          </>
        ) : isConsistent === false ? (
          <>
            <XCircle size={14} style={{ color: '#dc2626' }} />
            <span style={{ color: '#dc2626' }}>Ontology is inconsistent</span>
          </>
        ) : (
          <>
            <Info size={14} style={{ color: '#6b7280' }} />
            <span style={{ color: '#6b7280' }}>{reasonerStatus}</span>
          </>
        )}
      </div>

      {/* Quick Action Buttons */}
      <div style={styles.quickActions}>
        <button
          onClick={() => startReasoner('consistency')}
          disabled={isRunning}
          style={{...styles.button, ...styles.primaryButton}}
          title="Check consistency"
        >
          <Play size={14} />
          <span>Start reasoner</span>
        </button>
        
        <button
          onClick={() => startReasoner('classification')}
          disabled={isRunning}
          style={styles.button}
          title="Classify ontology"
        >
          <GitBranch size={14} />
          <span>Classify</span>
        </button>
        
        <button
          onClick={() => startReasoner('realization')}
          disabled={isRunning}
          style={styles.button}
          title="Realize individuals"
        >
          <Database size={14} />
          <span>Realize</span>
        </button>
      </div>

      {/* Main Content */}
      <div style={styles.content}>
        {/* Statistics Panel */}
        {stats && (
          <div style={styles.statsPanel}>
            <div style={styles.statItem}>
              <span style={styles.statLabel}>Classes:</span>
              <span style={styles.statValue}>{stats.classCount || 0}</span>
            </div>
            <div style={styles.statItem}>
              <span style={styles.statLabel}>Individuals:</span>
              <span style={styles.statValue}>{stats.individualCount || 0}</span>
            </div>
            <div style={styles.statItem}>
              <span style={styles.statLabel}>Properties:</span>
              <span style={styles.statValue}>{stats.propertyCount || 0}</span>
            </div>
            <div style={styles.statItem}>
              <span style={styles.statLabel}>Satisfiable:</span>
              <span style={{...styles.statValue, color: '#059669'}}>{stats.satisfiableClasses || 0}</span>
            </div>
            <div style={styles.statItem}>
              <span style={styles.statLabel}>Unsatisfiable:</span>
              <span style={{...styles.statValue, color: '#dc2626'}}>{stats.unsatisfiableClasses || 0}</span>
            </div>
          </div>
        )}

        {/* Unsatisfiable Classes Warning */}
        {unsatisfiableClasses.length > 0 && (
          <div style={styles.warningPanel}>
            <AlertTriangle size={16} style={{ color: '#f59e0b' }} />
            <div style={{ flex: 1 }}>
              <div style={styles.warningTitle}>
                {unsatisfiableClasses.length} Unsatisfiable Class{unsatisfiableClasses.length > 1 ? 'es' : ''}
              </div>
              <div style={styles.warningText}>
                These classes are equivalent to owl:Nothing and cannot have any instances
              </div>
              <div style={styles.unsatisfiableList}>
                {unsatisfiableClasses.map((cls: any) => (
                  <div
                    key={cls.iri}
                    style={styles.unsatisfiableItem}
                    onMouseEnter={(e) => handleClassHover(cls.iri, e)}
                    onMouseLeave={handleClassLeave}
                    onClick={() => navigateToClass(cls.iri)}
                  >
                    <XCircle size={12} style={{ color: '#dc2626' }} />
                    <span>{cls.label}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Equivalent Classes */}
        {equivalentClasses.length > 0 && (
          <div style={styles.section}>
            <div style={styles.sectionHeader}>
              <Link2 size={16} style={{ color: '#f59e0b' }} />
              <span style={styles.sectionTitle}>Equivalent Classes ({equivalentClasses.length})</span>
            </div>
            <div style={styles.sectionContent}>
              {equivalentClasses.map((group: any, idx: number) => (
                <div key={idx} style={styles.equivalentGroup}>
                  {group.classes?.map((cls: any, cidx: number) => (
                    <React.Fragment key={cls.iri}>
                      <span
                        style={styles.equivalentClass}
                        onMouseEnter={(e) => handleClassHover(cls.iri, e)}
                        onMouseLeave={handleClassLeave}
                        onClick={() => navigateToClass(cls.iri)}
                      >
                        {cls.label}
                      </span>
                      {cidx < group.classes.length - 1 && (
                        <ArrowRight size={14} style={{ margin: '0 8px', color: '#9ca3af' }} />
                      )}
                    </React.Fragment>
                  ))}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Class Hierarchy */}
        {classHierarchy.length > 0 && (
          <div style={styles.section}>
            <div style={styles.sectionHeader}>
              <GitBranch size={16} style={{ color: '#3b82f6' }} />
              <span style={styles.sectionTitle}>Inferred Class Hierarchy ({classHierarchy.length})</span>
              <span style={styles.helpText}>
                <HelpCircle size={12} style={{ marginRight: '4px' }} />
                Hover over classes to see explanations
              </span>
            </div>
            <div style={styles.hierarchyContainer}>
              {classHierarchy.map(node => renderClassNode(node))}
            </div>
          </div>
        )}

        {/* Empty State */}
        {isConsistent === null && classHierarchy.length === 0 && unsatisfiableClasses.length === 0 && (
          <div style={styles.emptyState}>
            <Lightbulb size={48} style={{ color: '#d1d5db' }} />
            <div style={styles.emptyTitle}>No reasoning results yet</div>
            <div style={styles.emptyText}>
              Click "Start reasoner" or "Classify" to compute the class hierarchy and check consistency
            </div>
          </div>
        )}
      </div>

      {/* Explanation Tooltip */}
      {hoveredClass && tooltipPosition && explanations.has(hoveredClass) && (
        <div
          ref={tooltipRef}
          style={{
            ...styles.tooltip,
            left: tooltipPosition.x + 10,
            top: tooltipPosition.y + 10
          }}
        >
          <div style={styles.tooltipHeader}>
            <Lightbulb size={14} style={{ color: '#667eea' }} />
            <span style={styles.tooltipTitle}>Explanation</span>
          </div>
          <div style={styles.tooltipContent}>
            {explanations.get(hoveredClass)?.reasons.map((reason, idx) => (
              <div key={idx} style={styles.explanationItem}>
                <div style={styles.explanationType}>
                  {reason.type === 'unsatisfiable' && <XCircle size={12} style={{ color: '#dc2626' }} />}
                  {reason.type === 'equivalentTo' && <Link2 size={12} style={{ color: '#f59e0b' }} />}
                  {reason.type === 'subClassOf' && <GitBranch size={12} style={{ color: '#3b82f6' }} />}
                  <span>{reason.type.replace(/([A-Z])/g, ' $1').trim()}</span>
                </div>
                <div style={styles.explanationText}>{reason.description}</div>
                {reason.relatedClasses.length > 0 && (
                  <div style={styles.relatedClasses}>
                    <span style={styles.relatedLabel}>Related:</span>
                    {reason.relatedClasses.join(', ')}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      <style>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
};

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    width: '100%',
    height: '100%',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#ffffff',
    fontFamily: 'Arial, sans-serif'
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '8px 16px',
    borderBottom: '1px solid #e5e7eb',
    backgroundColor: '#f9fafb'
  },
  headerLeft: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px'
  },
  menuContainer: {
    position: 'relative'
  },
  menuButton: {
    padding: '4px 12px',
    border: 'none',
    backgroundColor: 'transparent',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: 500,
    color: '#374151'
  },
  dropdownMenu: {
    position: 'absolute',
    top: '100%',
    left: 0,
    backgroundColor: 'white',
    border: '1px solid #d1d5db',
    borderRadius: '4px',
    boxShadow: '0 4px 6px rgba(0,0,0,0.1)',
    minWidth: '220px',
    zIndex: 1000,
    marginTop: '4px'
  },
  menuItem: {
    padding: '8px 16px',
    fontSize: '13px',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    transition: 'background-color 0.2s'
  },
  menuItemLabel: {
    padding: '8px 16px',
    fontSize: '11px',
    color: '#9ca3af',
    fontWeight: 600,
    textTransform: 'uppercase',
    backgroundColor: '#f9fafb'
  },
  menuItemDisabled: {
    color: '#9ca3af',
    cursor: 'not-allowed'
  },
  menuDivider: {
    height: '1px',
    backgroundColor: '#e5e7eb',
    margin: '4px 0'
  },
  title: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#111827'
  },
  iconButton: {
    padding: '6px',
    border: '1px solid #d1d5db',
    borderRadius: '4px',
    backgroundColor: 'white',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center'
  },
  quickActions: {
    padding: '12px 16px',
    borderBottom: '1px solid #e5e7eb',
    display: 'flex',
    gap: '8px',
    flexWrap: 'wrap',
    backgroundColor: '#fafafa'
  },
  button: {
    padding: '6px 12px',
    border: '1px solid #d1d5db',
    borderRadius: '4px',
    backgroundColor: 'white',
    cursor: 'pointer',
    fontSize: '13px',
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    transition: 'all 0.2s'
  },
  primaryButton: {
    backgroundColor: '#667eea',
    color: 'white',
    border: 'none'
  },
  dialogOverlay: {
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.5)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 10000
  },
  dialog: {
    backgroundColor: 'white',
    borderRadius: '8px',
    boxShadow: '0 20px 25px rgba(0,0,0,0.3)',
    minWidth: '400px',
    maxWidth: '600px'
  },
  dialogHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '16px 20px',
    borderBottom: '1px solid #e5e7eb'
  },
  dialogTitle: {
    fontSize: '16px',
    fontWeight: 600,
    color: '#111827'
  },
  closeButton: {
    border: 'none',
    background: 'none',
    fontSize: '24px',
    cursor: 'pointer',
    color: '#6b7280',
    padding: '0',
    width: '24px',
    height: '24px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center'
  },
  dialogContent: {
    padding: '20px'
  },
  dialogFooter: {
    display: 'flex',
    gap: '8px',
    justifyContent: 'flex-end',
    padding: '16px 20px',
    borderTop: '1px solid #e5e7eb'
  },
  settingRow: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    marginBottom: '12px'
  },
  checkboxLabel: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    fontSize: '13px',
    cursor: 'pointer'
  },
  settingLabel: {
    fontSize: '13px',
    fontWeight: 500,
    minWidth: '150px'
  },
  select: {
    padding: '6px 8px',
    border: '1px solid #d1d5db',
    borderRadius: '4px',
    fontSize: '13px',
    backgroundColor: 'white',
    flex: 1
  },
  input: {
    padding: '6px 8px',
    border: '1px solid #d1d5db',
    borderRadius: '4px',
    fontSize: '13px',
    flex: 1
  },
  statusBar: {
    padding: '8px 16px',
    borderBottom: '1px solid #e5e7eb',
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    fontSize: '13px'
  },
  content: {
    flex: 1,
    overflowY: 'auto',
    padding: '16px'
  },
  statsPanel: {
    display: 'flex',
    gap: '24px',
    padding: '12px',
    backgroundColor: '#f9fafb',
    borderRadius: '6px',
    marginBottom: '16px',
    flexWrap: 'wrap'
  },
  statItem: {
    display: 'flex',
    flexDirection: 'column',
    gap: '4px'
  },
  statLabel: {
    fontSize: '11px',
    color: '#6b7280',
    textTransform: 'uppercase'
  },
  statValue: {
    fontSize: '18px',
    fontWeight: 600,
    color: '#111827'
  },
  warningPanel: {
    padding: '12px',
    backgroundColor: '#fffbeb',
    border: '1px solid #fef3c7',
    borderRadius: '6px',
    marginBottom: '16px',
    display: 'flex',
    gap: '12px'
  },
  warningTitle: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#92400e',
    marginBottom: '4px'
  },
  warningText: {
    fontSize: '12px',
    color: '#78350f',
    marginBottom: '8px'
  },
  unsatisfiableList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '4px'
  },
  unsatisfiableItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '4px 8px',
    backgroundColor: 'white',
    borderRadius: '4px',
    fontSize: '12px',
    cursor: 'pointer'
  },
  section: {
    marginBottom: '16px',
    border: '1px solid #e5e7eb',
    borderRadius: '6px',
    overflow: 'hidden'
  },
  sectionHeader: {
    padding: '10px 12px',
    backgroundColor: '#f9fafb',
    borderBottom: '1px solid #e5e7eb',
    display: 'flex',
    alignItems: 'center',
    gap: '8px'
  },
  sectionTitle: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#111827',
    flex: 1
  },
  helpText: {
    fontSize: '11px',
    color: '#6b7280',
    display: 'flex',
    alignItems: 'center'
  },
  sectionContent: {
    padding: '12px'
  },
  equivalentGroup: {
    display: 'flex',
    alignItems: 'center',
    padding: '8px',
    backgroundColor: '#fef3c7',
    borderRadius: '4px',
    marginBottom: '8px',
    flexWrap: 'wrap'
  },
  equivalentClass: {
    fontSize: '13px',
    color: '#92400e',
    cursor: 'pointer',
    fontWeight: 500
  },
  hierarchyContainer: {
    padding: '8px',
    maxHeight: '500px',
    overflowY: 'auto'
  },
  emptyState: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '48px 24px',
    textAlign: 'center'
  },
  emptyTitle: {
    fontSize: '16px',
    fontWeight: 600,
    color: '#374151',
    marginTop: '16px'
  },
  emptyText: {
    fontSize: '13px',
    color: '#6b7280',
    marginTop: '8px',
    maxWidth: '400px'
  },
  tooltip: {
    position: 'fixed',
    backgroundColor: '#1f2937',
    color: 'white',
    borderRadius: '6px',
    padding: '12px',
    maxWidth: '400px',
    boxShadow: '0 10px 25px rgba(0,0,0,0.3)',
    zIndex: 10000,
    pointerEvents: 'none'
  },
  tooltipHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    marginBottom: '8px',
    paddingBottom: '8px',
    borderBottom: '1px solid #374151'
  },
  tooltipTitle: {
    fontSize: '13px',
    fontWeight: 600
  },
  tooltipContent: {
    fontSize: '12px',
    lineHeight: 1.5
  },
  explanationItem: {
    marginBottom: '8px'
  },
  explanationType: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    fontSize: '11px',
    fontWeight: 600,
    textTransform: 'uppercase',
    color: '#9ca3af',
    marginBottom: '4px'
  },
  explanationText: {
    fontSize: '12px',
    color: '#e5e7eb',
    marginBottom: '4px'
  },
  relatedClasses: {
    fontSize: '11px',
    color: '#9ca3af',
    fontStyle: 'italic'
  },
  relatedLabel: {
    fontWeight: 600,
    marginRight: '4px'
  }
};

export default ProtegeReasonerPlugin;
