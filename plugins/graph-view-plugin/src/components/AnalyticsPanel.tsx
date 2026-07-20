/**
 * InfraNodue-inspired insights panel — Topics / Concepts / Gaps / Trends tabs,
 * discourse structure meter, AI-summary from local analytics data, and cluster sparklines.
 */

import React, { useMemo, useState, useCallback } from 'react';
import {
  BarChart3, GitBranch, Lightbulb, Network, TrendingUp,
  X, Search, Layers, Copy, Zap, ChevronDown, ChevronUp,
  Clock, MessageSquare, ArrowRight, Hash, EyeOff, Send, Sparkles, Pencil, Plus
} from 'lucide-react';
import type { OntologyNode, OntologyEdge } from '../types';
import type { GraphAnalytics, StructuralGap, DiscourseStructure } from '../services/GraphAnalyticsService';
import {
  generateGraphInsights, askGraphQuestion, suggestTopicsForNode,
  getStoredApiKey, setStoredApiKey, hasApiKey,
  getStoredProvider, setStoredProvider, getStoredModel, setStoredModel,
  getAvailableProviders, getProviderModels,
  LlmConfigError, type LlmInsightRequest, type LlmProvider,
  type SelectedNodeContext, type TopicSuggestion,
} from '../services/LlmInsightsService';

type TabId = 'topics' | 'concepts' | 'gaps' | 'trends';

interface AnalyticsPanelProps {
  analytics: GraphAnalytics;
  nodes: OntologyNode[];
  /** Visible edges — used to describe the selected node's neighborhood to the AI. */
  edges?: OntologyEdge[];
  selectedNode?: OntologyNode | null;
  onSelectNode?: (node: OntologyNode) => void;
  onHighlightGap?: (gap: StructuralGap) => void;
  onClose?: () => void;
  colorByCluster: boolean;
  onToggleColorByCluster?: (value: boolean) => void;
  centralityThreshold?: number;
  onCentralityThresholdChange?: (v: number) => void;
}

interface ClusterInfo {
  id: number;
  members: OntologyNode[];
  size: number;
  color: string;
  topWords: string[];
  edgePct: number; // % of total intra-cluster edges
}

function useClusterInfos(analytics: GraphAnalytics, nodes: OntologyNode[]): ClusterInfo[] {
  return useMemo(() => {
    const byCluster = new Map<number, OntologyNode[]>();
    for (const node of nodes) {
      const c = analytics.communities.get(node.id);
      if (c === undefined) continue;
      if (!byCluster.has(c)) byCluster.set(c, []);
      byCluster.get(c)!.push(node);
    }
    const totalNodes = nodes.length || 1;
    return [...byCluster.entries()]
      .map(([id, members]) => {
        const sorted = [...members].sort(
          (a, b) => (analytics.betweenness.get(b.id) ?? 0) - (analytics.betweenness.get(a.id) ?? 0)
        );
        const topWords = sorted.slice(0, 5).map(n => n.label);
        const edgePct = Math.round((members.length / totalNodes) * 100);
        return {
          id,
          members,
          size: members.length,
          color: analytics.clusterColors.get(id) ?? '#94a3b8',
          topWords,
          edgePct
        };
      })
      .sort((a, b) => b.size - a.size)
      .slice(0, 8);
  }, [analytics, nodes]);
}

function generateAiSummary(
  target: 'cluster' | 'concept',
  cluster: ClusterInfo | null,
  node: OntologyNode | null,
  analytics: GraphAnalytics,
  nodes: OntologyNode[]
): string {
  if (target === 'cluster' && cluster) {
    const pct = cluster.edgePct;
    const top3 = cluster.topWords.slice(0, 3).join(', ');
    const { label } = analytics.discourseStructure;
    return (
      `This topic cluster contains ${cluster.size} concepts (${pct}% of the graph). ` +
      `Key concepts: ${top3}. ` +
      `The cluster is ${label === 'focused' ? 'densely connected internally' : 'loosely connected to peers'}, ` +
      `suggesting ${label === 'focused' ? 'a specialized domain area' : 'broad cross-domain coverage'}.`
    );
  }
  if (target === 'concept' && node) {
    const deg = analytics.degree.get(node.id) ?? 0;
    const btw = analytics.betweenness.get(node.id) ?? 0;
    const maxBtw = Math.max(0.001, ...analytics.betweenness.values());
    const role = btw / maxBtw > 0.5 ? 'a key bridging concept' : deg > 3 ? 'a well-connected concept' : 'a leaf concept';
    return (
      `"${node.label}" is a ${node.type} with ${deg} direct connections. ` +
      `It acts as ${role} in the ontology. ` +
      `Betweenness centrality: ${Math.round((btw / maxBtw) * 100)}% of maximum.`
    );
  }
  const { clusterCount, avgClusterSize, label, focusScore } = analytics.discourseStructure;
  return (
    `This ontology has ${nodes.length} concepts across ${clusterCount} topic clusters ` +
    `(avg ${avgClusterSize} per cluster). ` +
    `Discourse structure is ${label} (${focusScore}%). ` +
    `${label === 'focused' ? 'Consider adding cross-domain links to diversify.' : label === 'diversified' ? 'Consider deepening key topic areas.' : 'A well-balanced ontology.'}`
  );
}

// Mini sparkline SVG from array of values
const Sparkline: React.FC<{ values: number[]; color: string; width?: number; height?: number }> = ({
  values, color, width = 120, height = 28
}) => {
  if (values.length < 2) return null;
  const max = Math.max(...values, 1);
  const pts = values.map((v, i) => {
    const x = (i / (values.length - 1)) * width;
    const y = height - (v / max) * (height - 4) - 2;
    return `${x},${y}`;
  }).join(' ');
  return (
    <svg width={width} height={height} style={{ display: 'block' }}>
      <polyline
        points={pts}
        fill="none"
        stroke={color}
        strokeWidth={1.5}
        strokeLinejoin="round"
        strokeLinecap="round"
        opacity={0.85}
      />
      <circle
        cx={parseFloat(pts.split(' ').pop()!.split(',')[0])}
        cy={parseFloat(pts.split(' ').pop()!.split(',')[1])}
        r={2.5}
        fill={color}
      />
    </svg>
  );
};

// Discourse structure focus meter
const FocusMeter: React.FC<{ ds: DiscourseStructure; onDiversify?: () => void }> = ({ ds, onDiversify }) => {
  const meterColor = ds.label === 'focused' ? '#f59e0b' : ds.label === 'diversified' ? '#3b82f6' : '#10b981';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: '10px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>Discourse Structure</span>
        <span style={{
          fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 999,
          background: `${meterColor}22`, color: meterColor, border: `1px solid ${meterColor}44`
        }}>
          {ds.label} {ds.focusScore}%
        </span>
      </div>
      <div style={{ height: 6, background: 'var(--surface-3)', borderRadius: 3, overflow: 'hidden' }}>
        <div style={{
          width: `${ds.focusScore}%`, height: '100%',
          background: `linear-gradient(90deg, ${meterColor}88, ${meterColor})`,
          borderRadius: 3, transition: 'width 0.4s ease'
        }} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 11, color: 'var(--text-tertiary)', flex: 1 }}>
          advice:
        </span>
        <button
          type="button"
          onClick={onDiversify}
          style={{
            display: 'flex', alignItems: 'center', gap: 4, padding: '3px 10px',
            background: '#3b82f6', color: '#fff', border: 'none', borderRadius: 999,
            fontSize: 11, fontWeight: 600, cursor: 'pointer'
          }}
        >
          <ArrowRight size={10} />
          {ds.advice}
        </button>
      </div>
    </div>
  );
};

export const AnalyticsPanel: React.FC<AnalyticsPanelProps> = ({
  analytics,
  nodes,
  edges,
  selectedNode,
  onSelectNode,
  onHighlightGap,
  onClose,
  colorByCluster,
  onToggleColorByCluster,
  centralityThreshold = 100,
  onCentralityThresholdChange
}) => {
  const [activeTab, setActiveTab] = useState<TabId>('topics');
  const [showAiBox, setShowAiBox] = useState(true);
  const [aiSummary, setAiSummary] = useState<string>('');
  const [selectedCluster, setSelectedCluster] = useState<ClusterInfo | null>(null);

  // BYOK LLM insights (user supplies their own API key — no OntoCode cost)
  const [llmAction, setLlmAction] = useState<'insights' | 'ask' | 'topics' | null>(null);
  const llmLoading = llmAction !== null;
  const [llmError, setLlmError] = useState<string>('');
  const [llmText, setLlmText] = useState<string>('');
  const [question, setQuestion] = useState<string>('');
  const [topicSuggestions, setTopicSuggestions] = useState<TopicSuggestion[]>([]);
  const [editingTopicIdx, setEditingTopicIdx] = useState<number | null>(null);
  const [topicDraft, setTopicDraft] = useState<string>('');
  const [newTopic, setNewTopic] = useState<string>('');
  const [showKeyInput, setShowKeyInput] = useState(false);
  const [provider, setProvider] = useState<LlmProvider>(getStoredProvider());
  const [model, setModel] = useState<string>(getStoredModel());
  const [keyDraft, setKeyDraft] = useState<string>('');
  const [keySaved, setKeySaved] = useState<boolean>(hasApiKey());

  const providersList = getAvailableProviders();
  const modelsList = getProviderModels(provider);

  const clusters = useClusterInfos(analytics, nodes);
  const maxBetweenness = Math.max(0.001, ...analytics.topConcepts.map(t => t.score));

  // Sparkline values: cluster sizes in ranked order (for discourse timeline visual)
  const clusterSizeSparkline = useMemo(
    () => clusters.map(c => c.size),
    [clusters]
  );

  const handleToAi = useCallback((cluster: ClusterInfo) => {
    setSelectedCluster(cluster);
    const summary = generateAiSummary('cluster', cluster, null, analytics, nodes);
    setAiSummary(summary);
    setShowAiBox(true);
  }, [analytics, nodes]);

  const handleConceptToAi = useCallback((node: OntologyNode) => {
    const summary = generateAiSummary('concept', null, node, analytics, nodes);
    setAiSummary(summary);
    setShowAiBox(true);
  }, [analytics, nodes]);

  const handleCopyCluster = useCallback((cluster: ClusterInfo) => {
    const text = `${cluster.edgePct}%: ${cluster.topWords.join(' ')}`;
    navigator.clipboard?.writeText(text).catch(() => {});
  }, []);

  const buildLlmRequest = useCallback((): LlmInsightRequest => ({
    nodeCount: nodes.length,
    clusterCount: analytics.discourseStructure.clusterCount,
    discourseLabel: analytics.discourseStructure.label,
    focusScore: analytics.discourseStructure.focusScore,
    topConcepts: analytics.topConcepts.map(t => t.node?.label).filter(Boolean) as string[],
    clusters: clusters.map(c => ({ topWords: c.topWords, size: c.size })),
    gaps: analytics.gaps.map(g => ({ a: g.labelA, b: g.labelB, suggestion: g.suggestion })),
  }), [analytics, nodes, clusters]);

  // Neighborhood context for the selected node, fed to the AI prompts.
  const selectedNodeContext = useMemo((): SelectedNodeContext | null => {
    if (!selectedNode) return null;
    const labelById = new Map(nodes.map(n => [n.id, n.label]));
    const neighborIds = new Set<string>();
    for (const e of edges ?? []) {
      if (e.from === selectedNode.id) neighborIds.add(e.to);
      else if (e.to === selectedNode.id) neighborIds.add(e.from);
    }
    neighborIds.delete(selectedNode.id);
    const neighbors = [...neighborIds]
      .map(id => labelById.get(id))
      .filter((l): l is string => !!l);
    const clusterId = analytics.communities.get(selectedNode.id);
    const cluster = clusters.find(c => c.id === clusterId);
    return {
      label: selectedNode.label,
      type: selectedNode.type,
      iri: selectedNode.uri,
      neighbors,
      clusterTopWords: cluster?.topWords,
    };
  }, [selectedNode, nodes, edges, analytics, clusters]);

  const ensureKey = useCallback((): boolean => {
    if (hasApiKey()) return true;
    setShowKeyInput(true);
    setKeyDraft(getStoredApiKey());
    return false;
  }, []);

  const handleGenerateLlmInsights = useCallback(async () => {
    if (llmAction || !ensureKey()) return;
    setLlmAction('insights');
    setLlmError('');
    setLlmText('');
    setShowAiBox(true);
    try {
      const text = await generateGraphInsights(buildLlmRequest());
      setLlmText(text);
    } catch (e) {
      if (e instanceof LlmConfigError) {
        setShowKeyInput(true);
      }
      setLlmError(e instanceof Error ? e.message : 'Failed to generate insights.');
    } finally {
      setLlmAction(null);
    }
  }, [llmAction, ensureKey, buildLlmRequest]);

  const handleAskQuestion = useCallback(async () => {
    const q = question.trim();
    if (!q || llmAction || !ensureKey()) return;
    setLlmAction('ask');
    setLlmError('');
    setLlmText('');
    setShowAiBox(true);
    try {
      const text = await askGraphQuestion(q, buildLlmRequest(), selectedNodeContext);
      setLlmText(text);
    } catch (e) {
      if (e instanceof LlmConfigError) {
        setShowKeyInput(true);
      }
      setLlmError(e instanceof Error ? e.message : 'Failed to answer the question.');
    } finally {
      setLlmAction(null);
    }
  }, [question, llmAction, ensureKey, buildLlmRequest, selectedNodeContext]);

  const handleSuggestTopics = useCallback(async () => {
    if (!selectedNodeContext || llmAction || !ensureKey()) return;
    setLlmAction('topics');
    setLlmError('');
    setTopicSuggestions([]);
    setEditingTopicIdx(null);
    setTopicDraft('');
    setShowAiBox(true);
    try {
      const topics = await suggestTopicsForNode(selectedNodeContext, buildLlmRequest());
      setTopicSuggestions(topics);
    } catch (e) {
      if (e instanceof LlmConfigError) {
        setShowKeyInput(true);
      }
      setLlmError(e instanceof Error ? e.message : 'Failed to suggest topics.');
    } finally {
      setLlmAction(null);
    }
  }, [selectedNodeContext, llmAction, ensureKey, buildLlmRequest]);

  // A suggested topic that already exists in the graph selects that node;
  // an unknown one is copied to the clipboard as a modeling candidate.
  const handleTopicClick = useCallback((t: TopicSuggestion) => {
    const match = nodes.find(n => n.label.toLowerCase() === t.topic.toLowerCase());
    if (match) onSelectNode?.(match);
    else navigator.clipboard?.writeText(t.topic).catch(() => {});
  }, [nodes, onSelectNode]);

  const startTopicEdit = useCallback((i: number, current: string) => {
    setEditingTopicIdx(i);
    setTopicDraft(current);
  }, []);

  // Commit the inline edit; an emptied topic is removed from the list.
  const commitTopicEdit = useCallback(() => {
    setTopicSuggestions(prev => {
      if (editingTopicIdx === null || editingTopicIdx >= prev.length) return prev;
      const t = topicDraft.trim();
      if (!t) return prev.filter((_, i) => i !== editingTopicIdx);
      return prev.map((s, i) => (i === editingTopicIdx ? { ...s, topic: t } : s));
    });
    setEditingTopicIdx(null);
    setTopicDraft('');
  }, [editingTopicIdx, topicDraft]);

  const cancelTopicEdit = useCallback(() => {
    setEditingTopicIdx(null);
    setTopicDraft('');
  }, []);

  const removeTopic = useCallback((i: number) => {
    setTopicSuggestions(prev => prev.filter((_, idx) => idx !== i));
    setEditingTopicIdx(null);
    setTopicDraft('');
  }, []);

  const addCustomTopic = useCallback(() => {
    const t = newTopic.trim();
    if (!t) return;
    setTopicSuggestions(prev => [...prev, { topic: t, reason: 'Added by you' }]);
    setNewTopic('');
  }, [newTopic]);

  const handleSaveKey = useCallback(() => {
    setStoredProvider(provider);
    setStoredModel(model);
    setStoredApiKey(keyDraft);
    setKeySaved(hasApiKey());
    setShowKeyInput(false);
    setLlmError('');
  }, [keyDraft, provider, model]);

  const TABS: Array<{ id: TabId; label: string; icon: React.ReactNode }> = [
    { id: 'topics', label: 'Topics', icon: <Hash size={11} /> },
    { id: 'concepts', label: 'Concepts', icon: <Network size={11} /> },
    { id: 'gaps', label: 'Gaps', icon: <Lightbulb size={11} /> },
    { id: 'trends', label: 'Trends', icon: <Clock size={11} /> }
  ];

  return (
    <div
      data-testid="graph-analytics-panel"
      style={{
        display: 'flex',
        flexDirection: 'column',
        width: 280,
        height: '100%',
        background: 'var(--surface-1)',
        borderLeft: '1px solid var(--border)',
        flexShrink: 0,
        overflow: 'hidden',
        fontFamily: 'system-ui, -apple-system, sans-serif'
      }}
    >
      {/* Panel header */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6, padding: '8px 12px',
        borderBottom: '1px solid var(--border)', background: 'var(--surface-2)', flexShrink: 0
      }}>
        <TrendingUp size={14} style={{ color: 'var(--accent)' }} />
        <span style={{ fontWeight: 700, fontSize: 13, color: 'var(--text-primary)', flex: 1 }}>
          Graph Insights
        </span>
        <button
          type="button"
          title="Search"
          onClick={() => {}}
          style={iconBtn}
        >
          <Search size={13} />
        </button>
        <button
          type="button"
          title="Toggle cluster coloring"
          onClick={() => onToggleColorByCluster?.(!colorByCluster)}
          style={{ ...iconBtn, background: colorByCluster ? 'var(--accent)' : undefined, color: colorByCluster ? '#fff' : undefined }}
        >
          <Layers size={13} />
        </button>
        {onClose && (
          <button type="button" onClick={onClose} style={iconBtn} title="Close insights">
            <X size={13} />
          </button>
        )}
      </div>

      {/* AI Summarize box */}
      <div style={{
        borderBottom: '1px solid var(--border)', background: 'var(--surface-2)', flexShrink: 0
      }}>
        <button
          type="button"
          onClick={() => setShowAiBox(v => !v)}
          style={{
            display: 'flex', alignItems: 'center', gap: 6, width: '100%',
            padding: '7px 12px', background: 'none', border: 'none',
            cursor: 'pointer', color: 'var(--text-primary)', fontSize: 12
          }}
        >
          <Zap size={12} style={{ color: '#f59e0b' }} />
          <span style={{ flex: 1, textAlign: 'left', fontWeight: 600, fontSize: 12 }}>
            AI: Summarize Selected Topic
          </span>
          {showAiBox ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
        </button>
        {showAiBox && (
          <div style={{ padding: '0 12px 10px' }}>
            {aiSummary ? (
              <p style={{
                fontSize: 12, color: 'var(--text-secondary)', margin: 0,
                lineHeight: 1.5, background: 'var(--surface-3)',
                padding: '8px 10px', borderRadius: 6, border: '1px solid var(--border)'
              }}>
                {aiSummary}
              </p>
            ) : (
              <p style={{ fontSize: 11, color: 'var(--text-tertiary)', margin: 0, lineHeight: 1.5 }}>
                Click "to AI" on a topic cluster or concept to generate a summary.
              </p>
            )}
            {!aiSummary && (
              <button
                type="button"
                onClick={() => {
                  const summary = generateAiSummary('concept', null, null, analytics, nodes);
                  setAiSummary(summary);
                }}
                style={{
                  marginTop: 6, padding: '4px 10px', background: '#3b82f6', color: '#fff',
                  border: 'none', borderRadius: 999, fontSize: 11, fontWeight: 600, cursor: 'pointer'
                }}
              >
                Summarize ontology
              </button>
            )}

            {/* BYOK LLM insights — user supplies their own Gemini key */}
            <div style={{ marginTop: 10, paddingTop: 10, borderTop: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
                <button
                  type="button"
                  onClick={handleGenerateLlmInsights}
                  disabled={llmLoading}
                  style={{
                    flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                    padding: '5px 10px',
                    background: llmLoading ? 'var(--surface-3)' : 'linear-gradient(90deg,#8b5cf6,#6366f1)',
                    color: llmLoading ? 'var(--text-tertiary)' : '#fff',
                    border: 'none', borderRadius: 999, fontSize: 11, fontWeight: 600,
                    cursor: llmLoading ? 'default' : 'pointer'
                  }}
                  title="Generate AI insights using your own Gemini API key"
                >
                  <Zap size={12} />
                  {llmAction === 'insights' ? 'Generating…' : 'AI Insights (your key)'}
                </button>
                <button
                  type="button"
                  onClick={() => { setShowKeyInput(v => !v); setKeyDraft(getStoredApiKey()); }}
                  style={iconBtn}
                  title={keySaved ? 'Update your Gemini API key' : 'Add your Gemini API key'}
                >
                  <Search size={13} />
                </button>
              </div>

              {/* Custom AI message — free-form question about the graph / selected node */}
              <div style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
                <textarea
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      handleAskQuestion();
                    }
                  }}
                  placeholder={selectedNode
                    ? `Ask AI about "${selectedNode.label}" or the graph…`
                    : 'Ask AI about this graph…'}
                  rows={2}
                  data-testid="graph-ai-question"
                  style={{
                    flex: 1, resize: 'none', padding: '6px 8px', fontSize: 11, lineHeight: 1.4,
                    borderRadius: 6, border: '1px solid var(--border)',
                    background: 'var(--surface-1)', color: 'var(--text-primary)', fontFamily: 'inherit'
                  }}
                />
                <button
                  type="button"
                  onClick={handleAskQuestion}
                  disabled={llmLoading || !question.trim()}
                  title="Send your question to the AI (Enter)"
                  style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    padding: '0 10px', border: 'none', borderRadius: 6,
                    background: llmAction === 'ask' ? 'var(--surface-3)' : '#6366f1',
                    color: llmAction === 'ask' ? 'var(--text-tertiary)' : '#fff',
                    cursor: llmLoading || !question.trim() ? 'default' : 'pointer',
                    opacity: !question.trim() ? 0.6 : 1
                  }}
                >
                  <Send size={12} />
                </button>
              </div>

              {/* Topic suggestions for the selected node */}
              {selectedNode && (
                <button
                  type="button"
                  onClick={handleSuggestTopics}
                  disabled={llmLoading}
                  data-testid="graph-ai-suggest-topics"
                  style={{
                    width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
                    gap: 6, padding: '5px 10px', marginBottom: 6,
                    background: llmAction === 'topics' ? 'var(--surface-3)' : 'linear-gradient(90deg,#0ea5e9,#6366f1)',
                    color: llmAction === 'topics' ? 'var(--text-tertiary)' : '#fff',
                    border: 'none', borderRadius: 999, fontSize: 11, fontWeight: 600,
                    cursor: llmLoading ? 'default' : 'pointer'
                  }}
                  title={`Suggest related topics for "${selectedNode.label}" based on its connections`}
                >
                  <Sparkles size={12} />
                  {llmAction === 'topics'
                    ? 'Suggesting…'
                    : `Suggest topics for “${selectedNode.label.length > 18 ? `${selectedNode.label.slice(0, 17)}…` : selectedNode.label}”`}
                </button>
              )}

              {showKeyInput && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 6 }}>
                  {/* Provider Selector */}
                  <div>
                    <label style={{ fontSize: 10, color: 'var(--text-secondary)', display: 'block', marginBottom: 3 }}>
                      LLM Provider
                    </label>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(70px, 1fr))', gap: 4 }}>
                      {providersList.map((p) => (
                        <button
                          key={p.id}
                          type="button"
                          onClick={() => {
                            setProvider(p.id as LlmProvider);
                            setModel(getProviderModels(p.id as LlmProvider)[0].id);
                          }}
                          style={{
                            padding: '5px 8px', fontSize: 10, borderRadius: 4,
                            border: provider === p.id ? '2px solid #10b981' : '1px solid var(--border)',
                            background: provider === p.id ? 'rgba(16,185,129,0.1)' : 'var(--surface-1)',
                            color: 'var(--text-primary)', cursor: 'pointer', fontWeight: provider === p.id ? 600 : 400
                          }}
                        >
                          {p.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Model Selector */}
                  <div>
                    <label style={{ fontSize: 10, color: 'var(--text-secondary)', display: 'block', marginBottom: 3 }}>
                      Model
                    </label>
                    <select
                      value={model}
                      onChange={(e) => setModel(e.target.value)}
                      style={{
                        width: '100%', boxSizing: 'border-box', padding: '5px 8px', fontSize: 11,
                        borderRadius: 6, border: '1px solid var(--border)',
                        background: 'var(--surface-1)', color: 'var(--text-primary)'
                      }}
                    >
                      {modelsList.map((m) => (
                        <option key={m.id} value={m.id}>
                          {m.label}
                        </option>
                      ))}
                    </select>
                  </div>

                  {/* API Key Input */}
                  <div>
                    <label style={{ fontSize: 10, color: 'var(--text-secondary)', display: 'block', marginBottom: 3 }}>
                      API Key
                    </label>
                    <input
                      type="password"
                      value={keyDraft}
                      onChange={(e) => setKeyDraft(e.target.value)}
                      placeholder={`Paste your ${providersList.find(p => p.id === provider)?.label} API key`}
                      style={{
                        width: '100%', boxSizing: 'border-box', padding: '5px 8px', fontSize: 11,
                        borderRadius: 6, border: '1px solid var(--border)',
                        background: 'var(--surface-1)', color: 'var(--text-primary)'
                      }}
                    />
                  </div>

                  {/* Save/Remove Buttons */}
                  <div style={{ display: 'flex', gap: 6 }}>
                    <button
                      type="button"
                      onClick={handleSaveKey}
                      style={{
                        flex: 1, padding: '4px 10px', background: '#10b981', color: '#fff',
                        border: 'none', borderRadius: 999, fontSize: 11, fontWeight: 600, cursor: 'pointer'
                      }}
                    >
                      Save
                    </button>
                    {keySaved && (
                      <button
                        type="button"
                        onClick={() => { setStoredApiKey(''); setKeyDraft(''); setKeySaved(false); }}
                        style={{
                          padding: '4px 10px', background: 'var(--surface-3)', color: 'var(--text-secondary)',
                          border: '1px solid var(--border)', borderRadius: 999, fontSize: 11, cursor: 'pointer'
                        }}
                      >
                        Remove
                      </button>
                    )}
                  </div>

                  {/* Security Notice */}
                  <p style={{ fontSize: 10, color: 'var(--text-tertiary)', margin: 0, lineHeight: 1.4 }}>
                    🔒 Your key is stored only in this browser and sent directly to {providersList.find(p => p.id === provider)?.label}. OntoCode never sees or stores it.
                  </p>
                </div>
              )}

              {llmError && (
                <p style={{
                  fontSize: 11, color: '#ef4444', margin: '4px 0 0', lineHeight: 1.4,
                  background: 'rgba(239,68,68,0.08)', padding: '6px 8px', borderRadius: 6
                }}>
                  {llmError}
                </p>
              )}

              {llmText && (
                <div style={{
                  marginTop: 6, fontSize: 12, color: 'var(--text-secondary)', lineHeight: 1.5,
                  whiteSpace: 'pre-wrap', background: 'var(--surface-3)',
                  padding: '8px 10px', borderRadius: 6, border: '1px solid var(--border)',
                  maxHeight: 240, overflowY: 'auto'
                }}>
                  {llmText}
                </div>
              )}

              {topicSuggestions.length > 0 && (
                <div style={{ marginTop: 6 }} data-testid="graph-ai-topic-suggestions">
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4 }}>
                    <Sparkles size={10} style={{ color: '#6366f1' }} />
                    <span style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-secondary)' }}>
                      Suggested topics{selectedNode ? ` for “${selectedNode.label}”` : ''}
                    </span>
                    <span style={{ flex: 1 }} />
                    <button
                      type="button"
                      onClick={() => navigator.clipboard?.writeText(topicSuggestions.map(t => t.topic).join('\n')).catch(() => {})}
                      style={{ ...actionBtn, border: 'none', background: 'none' }}
                      title="Copy all topics"
                    >
                      <Copy size={10} />
                    </button>
                    <button
                      type="button"
                      onClick={() => { setTopicSuggestions([]); cancelTopicEdit(); }}
                      style={{ ...actionBtn, border: 'none', background: 'none' }}
                      title="Dismiss suggestions"
                    >
                      <X size={10} />
                    </button>
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                    {topicSuggestions.map((t, i) => {
                      if (editingTopicIdx === i) {
                        return (
                          <input
                            key={`edit-${i}`}
                            autoFocus
                            value={topicDraft}
                            onChange={(e) => setTopicDraft(e.target.value)}
                            onBlur={commitTopicEdit}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') { e.preventDefault(); commitTopicEdit(); }
                              if (e.key === 'Escape') { e.preventDefault(); cancelTopicEdit(); }
                            }}
                            style={{
                              padding: '3px 9px', borderRadius: 999, fontSize: 11,
                              border: '1px solid var(--accent)', outline: 'none',
                              background: 'var(--surface-1)', color: 'var(--text-primary)',
                              width: Math.max(80, Math.min(200, topicDraft.length * 7 + 30))
                            }}
                          />
                        );
                      }
                      const existing = nodes.some(n => n.label.toLowerCase() === t.topic.toLowerCase());
                      return (
                        <span
                          key={`${t.topic}-${i}`}
                          style={{
                            display: 'inline-flex', alignItems: 'center', gap: 2, padding: '3px 6px 3px 9px',
                            borderRadius: 999, fontSize: 11,
                            border: existing ? '1px solid var(--accent)' : '1px dashed var(--border)',
                            background: existing ? 'var(--accent-tint)' : 'var(--surface-1)',
                            color: existing ? 'var(--accent)' : 'var(--text-primary)',
                            fontWeight: existing ? 600 : 400
                          }}
                        >
                          <button
                            type="button"
                            onClick={() => handleTopicClick(t)}
                            onDoubleClick={() => startTopicEdit(i, t.topic)}
                            title={`${t.reason || t.topic}${existing
                              ? ' — already in the graph; click to select it'
                              : ' — not in the graph yet; click to copy the name'}. Double-click to edit.`}
                            style={{
                              display: 'inline-flex', alignItems: 'center', gap: 4,
                              border: 'none', background: 'none', padding: 0, cursor: 'pointer',
                              font: 'inherit', color: 'inherit'
                            }}
                          >
                            {existing ? <Search size={9} /> : <Copy size={9} />}
                            {t.topic}
                          </button>
                          <button
                            type="button"
                            onClick={() => startTopicEdit(i, t.topic)}
                            title="Edit this topic"
                            style={{
                              border: 'none', background: 'none', padding: '1px 2px', cursor: 'pointer',
                              color: 'var(--text-tertiary)', display: 'inline-flex', alignItems: 'center'
                            }}
                          >
                            <Pencil size={9} />
                          </button>
                          <button
                            type="button"
                            onClick={() => removeTopic(i)}
                            title="Remove this topic"
                            style={{
                              border: 'none', background: 'none', padding: '1px 2px', cursor: 'pointer',
                              color: 'var(--text-tertiary)', display: 'inline-flex', alignItems: 'center'
                            }}
                          >
                            <X size={9} />
                          </button>
                        </span>
                      );
                    })}
                  </div>
                  {/* Free-text: add your own topic to the list */}
                  <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
                    <input
                      value={newTopic}
                      onChange={(e) => setNewTopic(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') { e.preventDefault(); addCustomTopic(); }
                      }}
                      placeholder="Add your own topic…"
                      data-testid="graph-ai-add-topic"
                      style={{
                        flex: 1, padding: '4px 8px', fontSize: 11, borderRadius: 999,
                        border: '1px dashed var(--border)', outline: 'none',
                        background: 'var(--surface-1)', color: 'var(--text-primary)'
                      }}
                    />
                    <button
                      type="button"
                      onClick={addCustomTopic}
                      disabled={!newTopic.trim()}
                      title="Add topic (Enter)"
                      style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        padding: '0 8px', border: '1px solid var(--border)', borderRadius: 999,
                        background: newTopic.trim() ? 'var(--surface-2)' : 'var(--surface-1)',
                        color: newTopic.trim() ? 'var(--text-primary)' : 'var(--text-tertiary)',
                        cursor: newTopic.trim() ? 'pointer' : 'default'
                      }}
                    >
                      <Plus size={11} />
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Tab bar */}
      <div style={{
        display: 'flex', borderBottom: '1px solid var(--border)',
        background: 'var(--surface-2)', flexShrink: 0
      }}>
        {TABS.map(tab => (
          <button
            key={tab.id}
            type="button"
            onClick={() => setActiveTab(tab.id)}
            style={{
              flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
              gap: 4, padding: '7px 4px', border: 'none', cursor: 'pointer', fontSize: 11,
              fontWeight: activeTab === tab.id ? 700 : 400,
              color: activeTab === tab.id ? 'var(--accent)' : 'var(--text-secondary)',
              background: activeTab === tab.id ? 'var(--surface-1)' : 'transparent',
              borderBottom: activeTab === tab.id ? '2px solid var(--accent)' : '2px solid transparent',
              transition: 'all 0.15s'
            }}
          >
            {tab.icon}
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div style={{ flex: 1, overflow: 'auto', padding: '10px 12px' }}>

        {/* ─── TOPICS tab ─── */}
        {activeTab === 'topics' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', flex: 1 }}>
                Trending Topics:
              </span>
              <button type="button" style={iconBtn} title="Copy all topics"
                onClick={() => navigator.clipboard?.writeText(clusters.map(c => `${c.edgePct}%: ${c.topWords.join(' ')}`).join('\n')).catch(() => {})}>
                <Copy size={12} />
              </button>
            </div>

            {clusters.length === 0 && (
              <p style={{ fontSize: 12, color: 'var(--text-tertiary)', margin: 0 }}>
                Load graph data to detect topic clusters.
              </p>
            )}

            {clusters.map((c, idx) => (
              <div key={c.id} style={{
                background: 'var(--surface-2)', borderRadius: 8,
                border: `1px solid ${selectedCluster?.id === c.id ? c.color : 'var(--border)'}`,
                padding: '8px 10px', marginBottom: 4,
                boxShadow: selectedCluster?.id === c.id ? `0 0 0 1px ${c.color}44` : 'none'
              }}>
                {/* Cluster pill */}
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6, marginBottom: 6 }}>
                  <span style={{
                    padding: '3px 8px', borderRadius: 999, fontSize: 11, fontWeight: 700,
                    background: `${c.color}22`, color: c.color, border: `1px solid ${c.color}44`,
                    whiteSpace: 'nowrap', flexShrink: 0
                  }}>
                    {c.edgePct}%
                  </span>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3 }}>
                    {c.topWords.map((w, wi) => (
                      <span key={wi} style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 11 }}>
                        <span style={{ width: 7, height: 7, borderRadius: '50%', background: c.color, flexShrink: 0 }} />
                        <span style={{ color: 'var(--text-primary)', fontWeight: wi === 0 ? 600 : 400 }}>{w}</span>
                      </span>
                    ))}
                  </div>
                </div>

                {/* Sparkline */}
                {idx === 0 && (
                  <div style={{ marginBottom: 6 }}>
                    <Sparkline
                      values={clusterSizeSparkline}
                      color={c.color}
                      width={230}
                      height={24}
                    />
                  </div>
                )}

                {/* Actions */}
                <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span style={{ fontSize: 10, color: 'var(--text-tertiary)', flex: 1 }}>
                    {c.size} concepts
                  </span>
                  <button type="button" style={actionBtn} title="Copy" onClick={() => handleCopyCluster(c)}>
                    <Copy size={10} />
                  </button>
                  <button type="button" style={actionBtn} title="Focus on cluster"
                    onClick={() => { setSelectedCluster(c); onSelectNode?.(c.members[0]); }}>
                    <Search size={10} />
                  </button>
                  <button type="button" style={actionBtn} title="Hide cluster"
                    onClick={() => setSelectedCluster(null)}>
                    <EyeOff size={10} />
                  </button>
                  <button
                    type="button"
                    onClick={() => handleToAi(c)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 3, padding: '2px 8px',
                      background: '#3b82f6', color: '#fff', border: 'none',
                      borderRadius: 999, fontSize: 10, fontWeight: 600, cursor: 'pointer'
                    }}
                    title="Summarize this topic with AI"
                  >
                    <MessageSquare size={9} />
                    to AI
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* ─── CONCEPTS tab ─── */}
        {activeTab === 'concepts' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', flex: 1 }}>
                Top Concepts
              </span>
              <span style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>betweenness</span>
            </div>
            {analytics.topConcepts.length === 0 && (
              <p style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>No data yet.</p>
            )}
            {analytics.topConcepts.map(({ node, score, degree }, i) => {
              const clusterColor = analytics.clusterColors.get(analytics.communities.get(node.id) ?? -1) ?? 'var(--accent)';
              return (
                <button
                  key={node.id}
                  type="button"
                  data-testid="graph-top-concept"
                  onClick={() => { onSelectNode?.(node); handleConceptToAi(node); }}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 8, padding: '7px 8px',
                    border: '1px solid var(--border)', borderRadius: 7,
                    background: selectedNode?.id === node.id ? 'var(--surface-3)' : 'var(--surface-2)',
                    cursor: 'pointer', fontSize: 12, color: 'var(--text-primary)', width: '100%',
                    textAlign: 'left'
                  }}
                >
                  <span style={{ width: 18, color: 'var(--text-tertiary)', fontSize: 11, fontWeight: 600 }}>
                    #{i + 1}
                  </span>
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: clusterColor, flexShrink: 0 }} />
                  <span style={{ flex: 1, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {node.label}
                  </span>
                  <span style={{ fontSize: 10, color: 'var(--text-secondary)' }}>deg {degree}</span>
                  <div style={{ width: 44, height: 4, background: 'var(--surface-3)', borderRadius: 2, flexShrink: 0 }}>
                    <div style={{
                      width: `${Math.round((score / maxBetweenness) * 100)}%`,
                      height: '100%', background: clusterColor, borderRadius: 2
                    }} />
                  </div>
                </button>
              );
            })}
          </div>
        )}

        {/* ─── GAPS tab ─── */}
        {activeTab === 'gaps' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', flex: 1 }}>
                Structural Gaps
              </span>
              <span style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>disconnected clusters</span>
            </div>
            {analytics.gaps.length === 0 ? (
              <div style={{
                padding: '16px 12px', background: 'var(--surface-2)', borderRadius: 8,
                border: '1px solid var(--border)', textAlign: 'center'
              }}>
                <GitBranch size={20} style={{ color: '#10b981', marginBottom: 6 }} />
                <p style={{ fontSize: 12, color: '#10b981', margin: 0, fontWeight: 600 }}>
                  No major gaps
                </p>
                <p style={{ fontSize: 11, color: 'var(--text-tertiary)', margin: '4px 0 0' }}>
                  All clusters are linked in the visible graph.
                </p>
              </div>
            ) : (
              analytics.gaps.map((gap, i) => {
                const colorA = analytics.clusterColors.get(gap.clusterA) ?? '#f59e0b';
                const colorB = analytics.clusterColors.get(gap.clusterB) ?? '#ef4444';
                return (
                  <button
                    key={`${gap.clusterA}-${gap.clusterB}-${i}`}
                    type="button"
                    data-testid="graph-structural-gap"
                    onClick={() => onHighlightGap?.(gap)}
                    style={{
                      display: 'flex', flexDirection: 'column', gap: 4, padding: '8px 10px',
                      border: '1px solid var(--border)', borderRadius: 8,
                      background: 'var(--surface-2)', cursor: 'pointer', textAlign: 'left', width: '100%'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ width: 8, height: 8, borderRadius: '50%', background: colorA, flexShrink: 0 }} />
                      <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-primary)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{gap.labelA}</span>
                      <ArrowRight size={10} style={{ color: 'var(--text-tertiary)' }} />
                      <span style={{ width: 8, height: 8, borderRadius: '50%', background: colorB, flexShrink: 0 }} />
                      <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-primary)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{gap.labelB}</span>
                    </div>
                    <p style={{ fontSize: 11, color: 'var(--text-secondary)', margin: 0, lineHeight: 1.4 }}>
                      {gap.suggestion}
                    </p>
                  </button>
                );
              })
            )}
          </div>
        )}

        {/* ─── TRENDS tab ─── */}
        {activeTab === 'trends' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {/* Cluster size bars (trending chart) */}
            <div>
              <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', flex: 1 }}>
                  Trending Topics:
                </span>
                <button
                  type="button"
                  onClick={() => handleToAi(clusters[0])}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 3, padding: '3px 8px',
                    background: '#3b82f6', color: '#fff', border: 'none',
                    borderRadius: 999, fontSize: 10, fontWeight: 600, cursor: 'pointer'
                  }}
                  disabled={clusters.length === 0}
                >
                  <MessageSquare size={9} />
                  to AI
                </button>
              </div>

              {clusters.length > 0 && (
                <>
                  {/* Top cluster pill */}
                  <div style={{
                    display: 'inline-flex', alignItems: 'center', flexWrap: 'wrap', gap: 3,
                    padding: '4px 8px', background: `${clusters[0].color}18`,
                    border: `1px solid ${clusters[0].color}44`, borderRadius: 8, marginBottom: 8, width: '100%'
                  }}>
                    <span style={{ fontSize: 11, fontWeight: 700, color: clusters[0].color }}>
                      {clusters[0].edgePct}%:
                    </span>
                    {clusters[0].topWords.map((w, i) => (
                      <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: 2, fontSize: 11 }}>
                        <span style={{ width: 6, height: 6, borderRadius: '50%', background: clusters[0].color }} />
                        {w}
                      </span>
                    ))}
                  </div>

                  {/* Sparkline chart */}
                  <div style={{ background: 'var(--surface-2)', borderRadius: 6, padding: '6px 8px', marginBottom: 8 }}>
                    <Sparkline values={clusterSizeSparkline} color={clusters[0].color} width={228} height={32} />
                  </div>
                </>
              )}
            </div>

            {/* Discourse Timeline slider */}
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--border)',
              borderRadius: 8, padding: '10px 12px'
            }}>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 8 }}>
                Discourse Timeline:
              </div>
              <input
                type="range"
                min={10}
                max={100}
                step={5}
                value={centralityThreshold}
                onChange={e => onCentralityThresholdChange?.(Number(e.target.value))}
                style={{ width: '100%', accentColor: 'var(--accent)', marginBottom: 4 }}
              />
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: 'var(--text-tertiary)' }}>
                <span>top 10%</span>
                <span style={{ fontWeight: 600, color: 'var(--text-secondary)' }}>
                  top {centralityThreshold}%
                </span>
                <span>all</span>
              </div>
            </div>

            {/* Discourse Structure meter */}
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--border)',
              borderRadius: 8, padding: '10px 12px'
            }}>
              <FocusMeter
                ds={analytics.discourseStructure}
                onDiversify={() => {
                  const summary = generateAiSummary('concept', null, null, analytics, nodes);
                  setAiSummary(summary);
                  setShowAiBox(true);
                }}
              />
              <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
                <div style={{ textAlign: 'center', flex: 1 }}>
                  <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--text-primary)' }}>
                    {analytics.discourseStructure.clusterCount}
                  </div>
                  <div style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>clusters</div>
                </div>
                <div style={{ textAlign: 'center', flex: 1 }}>
                  <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--text-primary)' }}>
                    {analytics.discourseStructure.avgClusterSize}
                  </div>
                  <div style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>avg size</div>
                </div>
                <div style={{ textAlign: 'center', flex: 1 }}>
                  <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--text-primary)' }}>
                    {nodes.length}
                  </div>
                  <div style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>concepts</div>
                </div>
              </div>
            </div>

            {/* Cluster size distribution */}
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--border)',
              borderRadius: 8, padding: '10px 12px'
            }}>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 8 }}>
                Cluster Distribution
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                {clusters.slice(0, 6).map(c => (
                  <div key={c.id} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ width: 8, height: 8, borderRadius: '50%', background: c.color, flexShrink: 0 }} />
                    <div style={{ flex: 1, height: 6, background: 'var(--surface-3)', borderRadius: 3, overflow: 'hidden' }}>
                      <div style={{
                        width: `${c.edgePct}%`, minWidth: 4,
                        height: '100%', background: c.color, borderRadius: 3
                      }} />
                    </div>
                    <span style={{ fontSize: 10, color: 'var(--text-secondary)', width: 26, textAlign: 'right' }}>
                      {c.edgePct}%
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Footer: cluster coloring toggle */}
      <div style={{
        padding: '8px 12px', borderTop: '1px solid var(--border)',
        background: 'var(--surface-2)', flexShrink: 0,
        display: 'flex', alignItems: 'center', gap: 8
      }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, cursor: 'pointer', flex: 1 }}>
          <input
            type="checkbox"
            checked={colorByCluster}
            onChange={(e) => onToggleColorByCluster?.(e.target.checked)}
            data-testid="graph-color-by-cluster"
          />
          <span style={{ color: 'var(--text-secondary)' }}>Color by cluster</span>
        </label>
        <span style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>
          {clusters.length} groups · {nodes.length} nodes
        </span>
      </div>
    </div>
  );
};

const iconBtn: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--surface-1)',
  borderRadius: 5,
  padding: '4px 5px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer',
  color: 'var(--text-secondary)'
};

const actionBtn: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--surface-1)',
  borderRadius: 4,
  padding: '3px 4px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer',
  color: 'var(--text-secondary)'
};

export default AnalyticsPanel;
