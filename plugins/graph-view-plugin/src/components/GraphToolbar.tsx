import React, { useEffect, useRef, useState } from 'react';
import {
  RefreshCw,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Search,
  Filter,
  Settings,
  FileText,
  Download,
  Edit3,
  Zap,
  Grid,
  LayoutGrid,
  Orbit,
  Home,
  Box,
  GitBranch,
  Trash2,
  Maximize,
  MinusSquare,
  Save,
  TrendingUp,
  SlidersHorizontal,
  Boxes
} from 'lucide-react';
import type { EdgeType, VisualizationType, OntologyNode } from '../types';
import { OntographLayoutType, VowlDisplayOptions, DEFAULT_VOWL_OPTIONS } from '../viewMemory';

export const RELATIONSHIP_VISIBILITY_CONTROLS: Array<{
  label: string;
  shortLabel: string;
  title: string;
  edgeTypes: EdgeType[];
}> = [
  {
    label: 'SubClass',
    shortLabel: 'Sub',
    title: 'Show or hide class hierarchy edges',
    edgeTypes: ['subClassOf']
  },
  {
    label: 'Equivalent',
    shortLabel: 'Eq',
    title: 'Show or hide equivalent class/property edges',
    edgeTypes: ['equivalentClass']
  },
  {
    label: 'Disjoint',
    shortLabel: 'Dis',
    title: 'Show or hide disjointness edges',
    edgeTypes: ['disjointWith']
  },
  {
    label: 'Instance',
    shortLabel: 'Inst',
    title: 'Show or hide individual type assertions',
    edgeTypes: ['instanceOf']
  },
  {
    label: 'Properties',
    shortLabel: 'Prop',
    title: 'Show or hide object/data/custom property relationship edges',
    edgeTypes: ['propertyRelation', 'subPropertyOf', 'inverseOf', 'custom']
  },
  {
    label: 'Domain/Range',
    shortLabel: 'D/R',
    title: 'Show or hide property domain and range edges',
    edgeTypes: ['domain', 'range']
  }
];

export interface GraphToolbarProps {
  styles: Record<string, React.CSSProperties>;
  loading: boolean;
  hasNodes: boolean;
  visualizationType: VisualizationType;
  ontographLayoutType: OntographLayoutType;
  assertionView: 'asserted' | 'inferred' | 'all';
  inferredGraphStatus: 'idle' | 'loading' | 'ready' | 'unavailable';
  edgeTypeFilters: Set<EdgeType>;
  savedViews: Array<{ id: string; name: string }>;
  selectedSavedViewId: string;
  canEdit: boolean;
  editMode: boolean;
  focusedNodeId: string | null;
  selectedNodeInfo: OntologyNode | null;
  showSearch: boolean;
  showFilters: boolean;
  showSettings: boolean;
  showPropertyPanel: boolean;
  showAnalytics: boolean;
  showGrid: boolean;
  physicsEnabled: boolean;
  showLegend: boolean;
  showHierarchyDialog: boolean;
  statsLabel: string;
  statsData: { visible: number; total: number; expanded: number };
  lazyLoadingActive: boolean;
  webglRenderer: boolean;
  webglSupported: boolean;
  vowlDisplayOptions: VowlDisplayOptions;
  onChangeVowlOptions: (patch: Partial<VowlDisplayOptions>) => void;
  /** Pulse the overflow button for the first few sessions after the toolbar restructure */
  showOverflowHint: boolean;

  onRefresh: () => void;
  onPresetNetwork: () => void;
  onPresetTree: () => void;
  onSetVisualizationType: (t: VisualizationType) => void;
  onSetOntographLayout: (l: OntographLayoutType) => void;
  onSetAssertionView: (v: 'asserted' | 'inferred' | 'all') => void;
  onToggleRelationship: (edgeTypes: EdgeType[]) => void;
  onShowAllRelations: () => void;
  onHideAllRelations: () => void;
  onSaveView: () => void;
  onLoadView: (id: string) => void;
  onDeleteView: (id: string) => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFit: () => void;
  onResetZoom: () => void;
  onExpandAll: () => void;
  onCollapseAll: () => void;
  onToggleEdit: () => void;
  onToggleSearch: () => void;
  onToggleFilters: () => void;
  onToggleSettings: () => void;
  onToggleExplorer: () => void;
  onToggleInsights: () => void;
  onToggleGrid: () => void;
  onTogglePhysics: () => void;
  onToggleLegend: () => void;
  onToggleNavigator: () => void;
  onEnterFocus: () => void;
  onExitFocus: () => void;
  onExport: (format: 'svg' | 'png') => void;
  onToggleWebGL: () => void;
}

/**
 * Graph toolbar with progressive disclosure: a minimal primary bar for the actions
 * users touch constantly, and a "View options" popover holding everything else.
 * All controls that existed on the old flat toolbar remain reachable here.
 */
export const GraphToolbar: React.FC<GraphToolbarProps> = (props) => {
  const { styles } = props;
  const [optionsOpen, setOptionsOpen] = useState(false);
  const popoverRef = useRef<HTMLDivElement>(null);
  const overflowBtnRef = useRef<HTMLButtonElement>(null);

  // Close the popover on outside click or Escape
  useEffect(() => {
    if (!optionsOpen) return;
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (popoverRef.current?.contains(target) || overflowBtnRef.current?.contains(target)) return;
      setOptionsOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOptionsOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [optionsOpen]);

  const sectionTitle: React.CSSProperties = {
    fontSize: 11,
    fontWeight: 700,
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    color: 'var(--text-secondary, #6b7280)',
    margin: '10px 0 6px'
  };
  const sectionRow: React.CSSProperties = { display: 'flex', gap: 4, alignItems: 'center', flexWrap: 'wrap' };

  return (
    <div style={{ ...styles.toolbar, position: 'relative' }}>
      {/* MVP view presets — OntoCode Network vs Hierarchy */}
      <div style={{ display: 'flex', gap: '2px', alignItems: 'center', backgroundColor: 'var(--surface-2)', padding: '2px', borderRadius: '6px', border: '1px solid var(--border)' }}>
        <button
          data-testid="graph-preset-network"
          onClick={props.onPresetNetwork}
          style={props.webglRenderer ? styles.btnActive : styles.btn}
          title="Network view — force-directed graph on the WebGL engine (modules, focus, ask)"
        >
          Network
        </button>
        <button
          data-testid="graph-preset-tree"
          onClick={props.onPresetTree}
          style={!props.webglRenderer && props.visualizationType === 'ontograph' ? styles.btnActive : styles.btn}
          title="Tree view — class hierarchy layout (OntoCode hierarchy style)"
        >
          Tree
        </button>
      </div>

      <div style={styles.divider} />

      <button onClick={props.onToggleSearch} style={props.showSearch ? styles.btnActive : styles.btn} title="Search">
        <Search size={16} />
      </button>

      <button onClick={props.onZoomIn} style={styles.btn} title="Zoom In">
        <ZoomIn size={16} />
      </button>
      <button onClick={props.onZoomOut} style={styles.btn} title="Zoom Out">
        <ZoomOut size={16} />
      </button>
      <button onClick={props.onFit} style={styles.btn} title="Fit to Screen">
        <Maximize2 size={16} />
      </button>

      <div style={styles.divider} />

      <button
        onClick={props.onExpandAll}
        style={styles.btn}
        title="Expand All - Show full hierarchy of all entity types"
        disabled={props.loading || !props.hasNodes}
      >
        <Box size={16} />
      </button>
      <button
        onClick={props.onCollapseAll}
        style={styles.btn}
        title="Collapse All - Show root classes with their immediate children"
        disabled={props.loading || !props.hasNodes}
      >
        <MinusSquare size={16} />
      </button>

      {props.canEdit && (
        <>
          <div style={styles.divider} />
          <button
            onClick={props.onToggleEdit}
            style={props.editMode ? styles.btnActive : styles.btn}
            title="Edit Mode"
          >
            <Edit3 size={16} />
            {props.editMode ? 'Editing' : 'Edit'}
          </button>
        </>
      )}

      {/* Exit Focus surfaces on the bar only while focus mode is active */}
      {props.focusedNodeId && (
        <button
          onClick={props.onExitFocus}
          style={{ ...styles.btnActive, backgroundColor: '#7c3aed', borderColor: '#6d28d9', color: '#fff' }}
          title="Exit focus mode — show full graph"
        >
          <Maximize2 size={16} />
          Exit Focus
        </button>
      )}

      <div style={styles.divider} />

      <button
        data-testid="graph-insights-toggle"
        onClick={props.onToggleInsights}
        style={props.showAnalytics ? styles.btnActive : styles.btn}
        title="OntoCode insights — clusters, top concepts, structural gaps"
      >
        <TrendingUp size={16} />
        Insights
      </button>

      <div style={{ flex: 1 }} />

      {/* Stats */}
      <div
        style={styles.stats}
        data-testid="graph-stats"
        data-visible-nodes={props.statsData.visible}
        data-total-nodes={props.statsData.total}
        data-expanded-nodes={props.statsData.expanded}
      >
        {props.statsLabel}
        {props.lazyLoadingActive && <span style={{ color: '#10b981', marginLeft: '8px' }}>⚡ Lazy Loading</span>}
      </div>

      <button onClick={props.onRefresh} disabled={props.loading} style={styles.btn} title="Refresh graph">
        <RefreshCw size={16} className={props.loading ? 'spinning' : ''} />
      </button>

      <button
        ref={overflowBtnRef}
        data-testid="graph-toolbar-overflow"
        onClick={() => setOptionsOpen(v => !v)}
        style={{ ...(optionsOpen ? styles.btnActive : styles.btn), position: 'relative' }}
        title="View options — visualization mode, layouts, data, saved views, panels, export"
      >
        <SlidersHorizontal size={16} />
        {props.showOverflowHint && !optionsOpen && (
          <span
            style={{
              position: 'absolute',
              top: 2,
              right: 2,
              width: 8,
              height: 8,
              borderRadius: '50%',
              backgroundColor: '#3b82f6',
              animation: 'graphToolbarPulse 1.6s ease-in-out infinite'
            }}
            title="Layout options here — including Hierarchy (Tree), Radial, Grid, and Clustered views"
          />
        )}
      </button>
      <style>{`@keyframes graphToolbarPulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(0.75); } }`}</style>

      {optionsOpen && (
        <div
          ref={popoverRef}
          data-testid="graph-view-options"
          style={{
            position: 'absolute',
            top: 'calc(100% + 6px)',
            right: 8,
            zIndex: 60,
            width: 340,
            maxHeight: '70vh',
            overflowY: 'auto',
            padding: '12px 14px',
            borderRadius: 8,
            border: '1px solid var(--border)',
            backgroundColor: 'var(--surface-1)',
            boxShadow: '0 12px 32px rgba(0,0,0,0.18)'
          }}
        >
          <div style={sectionTitle}>Visualization</div>
          <div style={sectionRow}>
            <select
              data-testid="graph-mode-select"
              value={props.webglRenderer ? 'webgl' : props.visualizationType}
              onChange={(e) => {
                const value = e.target.value;
                if (value === 'webgl') {
                  if (!props.webglRenderer) props.onToggleWebGL();
                } else {
                  props.onSetVisualizationType(value as VisualizationType);
                }
              }}
              style={{
                padding: '6px 12px',
                border: '1px solid var(--border)',
                borderRadius: '6px',
                fontSize: '13px',
                backgroundColor: 'var(--surface-1)',
                color: 'var(--text-primary)',
                cursor: 'pointer',
                width: '100%',
                fontWeight: 500
              }}
              title="Switch layout — Network, VOWL, or Hierarchy (Tree/Radial/Grid/Clustered)"
            >
              <option value="webgl" disabled={!props.webglSupported}>
                Network (WebGL{props.webglSupported ? '' : ' — unavailable'})
              </option>
              <option value="vowl">VOWL</option>
              <option value="ontograph">Hierarchy (Tree Layout)</option>
            </select>
          </div>
          {props.visualizationType === 'ontograph' && !props.webglRenderer && (
            <div style={{ ...sectionRow, marginTop: 6 }}>
              <button onClick={props.onFit} style={styles.toolbarIconBtn} title="Home - Reset View">
                <Home size={14} />
              </button>
              <button
                onClick={() => props.onSetOntographLayout('grid')}
                style={props.ontographLayoutType === 'grid' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn}
                title="Grid Layout"
              >
                <LayoutGrid size={14} />
              </button>
              <button
                onClick={() => props.onSetOntographLayout('radial')}
                style={props.ontographLayoutType === 'radial' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn}
                title="Radial Layout"
              >
                <Orbit size={14} />
              </button>
              <button
                onClick={() => props.onSetOntographLayout('spring')}
                style={props.ontographLayoutType === 'spring' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn}
                title="Spring (Force) Layout"
              >
                <Zap size={14} />
              </button>
              <button
                onClick={() => props.onSetOntographLayout('tree')}
                style={props.ontographLayoutType === 'tree' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn}
                title="Tree Layout (Vertical)"
              >
                <div style={{ transform: 'rotate(90deg)', display: 'flex' }}><GitBranch size={14} /></div>
              </button>
              <button
                onClick={() => props.onSetOntographLayout('horizontal')}
                style={props.ontographLayoutType === 'horizontal' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn}
                title="Tree Layout (Horizontal)"
              >
                <GitBranch size={14} />
              </button>
              <button
                onClick={() => props.onSetOntographLayout('cluster')}
                style={props.ontographLayoutType === 'cluster' ? styles.toolbarIconBtnActive : styles.toolbarIconBtn}
                title="Clustered Layout — groups classes by structural community"
              >
                <Boxes size={14} />
              </button>
              <button onClick={props.onResetZoom} style={styles.toolbarIconBtn} title="Reset Zoom">
                <Maximize size={14} />
              </button>
            </div>
          )}

          {props.visualizationType === 'vowl' && !props.webglRenderer && (
            <>
              <div style={sectionTitle}>Notation &amp; density</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: 12 }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8 }} title="Hide nodes with fewer connections than this — declutters dense graphs">
                  <span style={{ minWidth: 110 }}>Collapse degree &lt; {props.vowlDisplayOptions.degreeCollapsing || 'off'}</span>
                  <input
                    type="range"
                    min={0}
                    max={6}
                    step={1}
                    value={props.vowlDisplayOptions.degreeCollapsing}
                    onChange={(e) => props.onChangeVowlOptions({ degreeCollapsing: Number(e.target.value) })}
                    style={{ flex: 1 }}
                    data-testid="graph-degree-collapsing"
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8 }} title="Cap label length (labels always stay inside their shapes)">
                  <span style={{ minWidth: 110 }}>Max label chars: {props.vowlDisplayOptions.maxLabelChars}</span>
                  <input
                    type="range"
                    min={6}
                    max={40}
                    step={1}
                    value={props.vowlDisplayOptions.maxLabelChars}
                    onChange={(e) => props.onChangeVowlOptions({ maxLabelChars: Number(e.target.value) })}
                    style={{ flex: 1 }}
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8 }} title="Scale node box width — label truncation adjusts automatically so text always stays inside">
                  <span style={{ minWidth: 110 }}>Box width: {props.vowlDisplayOptions.nodeWidthScale.toFixed(1)}x</span>
                  <input
                    type="range"
                    min={0.5}
                    max={2}
                    step={0.1}
                    value={props.vowlDisplayOptions.nodeWidthScale}
                    onChange={(e) => props.onChangeVowlOptions({ nodeWidthScale: Number(e.target.value) })}
                    style={{ flex: 1 }}
                    data-testid="graph-node-width-scale"
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8 }} title="Scale node box height">
                  <span style={{ minWidth: 110 }}>Box height: {props.vowlDisplayOptions.nodeHeightScale.toFixed(1)}x</span>
                  <input
                    type="range"
                    min={0.5}
                    max={2}
                    step={0.1}
                    value={props.vowlDisplayOptions.nodeHeightScale}
                    onChange={(e) => props.onChangeVowlOptions({ nodeHeightScale: Number(e.target.value) })}
                    style={{ flex: 1 }}
                    data-testid="graph-node-height-scale"
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8 }} title="Label font size — label truncation adjusts automatically so text always stays inside">
                  <span style={{ minWidth: 110 }}>Label font size: {props.vowlDisplayOptions.labelFontSize}px</span>
                  <input
                    type="range"
                    min={8}
                    max={16}
                    step={1}
                    value={props.vowlDisplayOptions.labelFontSize}
                    onChange={(e) => props.onChangeVowlOptions({ labelFontSize: Number(e.target.value) })}
                    style={{ flex: 1 }}
                    data-testid="graph-label-font-size"
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Hide classes whose only relationship is a single subClassOf">
                  <input
                    type="checkbox"
                    checked={props.vowlDisplayOptions.hideSolitarySubclasses}
                    onChange={(e) => props.onChangeVowlOptions({ hideSolitarySubclasses: e.target.checked })}
                    data-testid="graph-solitary-filter"
                  />
                  Hide solitary subclasses
                </label>
                {props.visualizationType === 'vowl' && (
                  <>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Hide '(disjoint)' captions and property-characteristic suffixes">
                      <input
                        type="checkbox"
                        checked={props.vowlDisplayOptions.compactNotation}
                        onChange={(e) => props.onChangeVowlOptions({ compactNotation: e.target.checked })}
                      />
                      Compact notation
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Tint classes from foreign namespaces a deeper blue">
                      <input
                        type="checkbox"
                        checked={props.vowlDisplayOptions.colorExternals}
                        onChange={(e) => props.onChangeVowlOptions({ colorExternals: e.target.checked })}
                      />
                      Color externals
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Show owl:unionOf / intersectionOf / complementOf / oneOf operator nodes (∪ ∩ ¬)">
                      <input
                        type="checkbox"
                        checked={props.vowlDisplayOptions.showSetOperators}
                        onChange={(e) => props.onChangeVowlOptions({ showSetOperators: e.target.checked })}
                        data-testid="graph-set-operators-filter"
                      />
                      Set operators
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Merge owl:equivalentClass into one node with a double border and comma label (Document, CreativeWork)">
                      <input
                        type="checkbox"
                        checked={props.vowlDisplayOptions.mergeEquivalents}
                        onChange={(e) => props.onChangeVowlOptions({ mergeEquivalents: e.target.checked })}
                        data-testid="graph-merge-equivalents"
                      />
                      Merge equivalents
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Show owl:disjointWith axiom edges">
                      <input
                        type="checkbox"
                        checked={props.vowlDisplayOptions.showDisjointness}
                        onChange={(e) => props.onChangeVowlOptions({ showDisjointness: e.target.checked })}
                        data-testid="graph-show-disjointness"
                      />
                      Disjointness
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Show property edges that loop back to the same class (domain = range)">
                      <input
                        type="checkbox"
                        checked={props.vowlDisplayOptions.showPropertyLoops}
                        onChange={(e) => props.onChangeVowlOptions({ showPropertyLoops: e.target.checked })}
                        data-testid="graph-show-property-loops"
                      />
                      Property loops
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }} title="Clicking a class shows only it and its neighborhood — right-click a node to expand the neighborhood from there">
                      <input
                        type="checkbox"
                        checked={!!props.vowlDisplayOptions.isolateOnSelect}
                        onChange={(e) => props.onChangeVowlOptions({ isolateOnSelect: e.target.checked })}
                        data-testid="graph-isolate-on-select"
                      />
                      Isolate on click
                    </label>
                    <button
                      type="button"
                      onClick={() => props.onChangeVowlOptions({ ...DEFAULT_VOWL_OPTIONS })}
                      style={{
                        marginTop: 4,
                        alignSelf: 'flex-start',
                        padding: '5px 10px',
                        fontSize: 11,
                        fontWeight: 600,
                        border: '1px solid var(--border)',
                        borderRadius: 6,
                        background: 'var(--surface-1)',
                        color: 'var(--text-primary)',
                        cursor: 'pointer'
                      }}
                      title="Restore VOWL defaults (spacing, labels, externals)"
                      data-testid="graph-reset-vowl-defaults"
                    >
                      Reset to VOWL defaults
                    </button>
                  </>
                )}
              </div>
            </>
          )}

          <div style={sectionTitle}>Data</div>
          <div style={sectionRow}>
            <select
              value={props.assertionView}
              onChange={(e) => props.onSetAssertionView(e.target.value as 'asserted' | 'inferred' | 'all')}
              style={{
                padding: '6px 12px',
                border: '1px solid var(--border)',
                borderRadius: '6px',
                fontSize: '13px',
                backgroundColor: 'var(--surface-1)',
                color: 'var(--text-primary)',
                cursor: 'pointer',
                minWidth: 150,
                fontWeight: 500
              }}
              title="Choose asserted, inferred, or combined graph data"
            >
              <option value="asserted">Asserted</option>
              <option value="inferred">Inferred</option>
              <option value="all">Asserted + Inferred</option>
            </select>
            {props.assertionView !== 'asserted' && (
              <span
                style={{
                  padding: '4px 8px',
                  borderRadius: 999,
                  fontSize: 11,
                  fontWeight: 600,
                  color: props.inferredGraphStatus === 'ready' ? '#047857' : '#92400e',
                  backgroundColor: props.inferredGraphStatus === 'ready' ? '#d1fae5' : '#fef3c7',
                  border: `1px solid ${props.inferredGraphStatus === 'ready' ? '#a7f3d0' : '#fde68a'}`
                }}
                title="Inferred graph data is generated from the current reasoner class hierarchy"
              >
                {props.inferredGraphStatus === 'loading'
                  ? 'Reasoning...'
                  : props.inferredGraphStatus === 'ready'
                    ? 'Inferred styling on'
                    : 'Run reasoner for inferred data'}
              </span>
            )}
          </div>
          <div style={{ ...styles.relationshipControlsGroup, marginTop: 6 }} title="OntoCode relationship visibility">
            <span style={styles.relationshipControlsLabel}>Relations</span>
            {RELATIONSHIP_VISIBILITY_CONTROLS.map(control => {
              const isEnabled = control.edgeTypes.every(type => props.edgeTypeFilters.has(type));
              const isPartial = !isEnabled && control.edgeTypes.some(type => props.edgeTypeFilters.has(type));
              return (
                <button
                  key={control.label}
                  onClick={() => props.onToggleRelationship(control.edgeTypes)}
                  style={isEnabled || isPartial ? styles.relationshipPillActive : styles.relationshipPill}
                  title={control.title}
                >
                  {control.shortLabel}
                </button>
              );
            })}
            <button onClick={props.onShowAllRelations} style={styles.relationshipMiniAction} title="Show all relationship types">
              All
            </button>
            <button onClick={props.onHideAllRelations} style={styles.relationshipMiniAction} title="Hide all relationship types">
              None
            </button>
          </div>

          <div style={sectionTitle}>Saved views</div>
          <div style={{ ...styles.savedViewsGroup, ...sectionRow }}>
            <button onClick={props.onSaveView} style={styles.toolbarIconBtn} title="Save current graph view">
              <Save size={14} />
            </button>
            <select
              value={props.selectedSavedViewId}
              onChange={(e) => props.onLoadView(e.target.value)}
              style={styles.savedViewsSelect}
              title="Load saved graph view"
            >
              <option value="">Saved Views</option>
              {props.savedViews.map(view => (
                <option key={view.id} value={view.id}>
                  {view.name}
                </option>
              ))}
            </select>
            <button
              onClick={() => props.onDeleteView(props.selectedSavedViewId)}
              disabled={!props.selectedSavedViewId}
              style={!props.selectedSavedViewId ? styles.toolbarIconBtnDisabled : styles.toolbarIconBtn}
              title="Delete selected saved view"
            >
              <Trash2 size={14} />
            </button>
          </div>

          <div style={sectionTitle}>Panels &amp; toggles</div>
          <div style={sectionRow}>
            <button onClick={props.onToggleFilters} style={props.showFilters ? styles.btnActive : styles.btn} title="Filters">
              <Filter size={16} />
            </button>
            <button onClick={props.onToggleSettings} style={props.showSettings ? styles.btnActive : styles.btn} title="Settings">
              <Settings size={16} />
            </button>
            <button onClick={props.onToggleExplorer} style={props.showPropertyPanel ? styles.btnActive : styles.btn} title="Graph Explorer Sidebar">
              <FileText size={16} />
              <span style={{ marginLeft: 6 }}>Explorer</span>
            </button>
            <button
              data-testid="graph-navigator-toggle"
              onClick={props.onToggleNavigator}
              style={props.showHierarchyDialog ? styles.btnActive : styles.btn}
              title={props.showHierarchyDialog ? 'Hide class tree navigator panel' : 'Show class tree navigator panel'}
            >
              <GitBranch size={16} />
              Navigator
            </button>
            <button onClick={props.onToggleGrid} style={props.showGrid ? styles.btnActive : styles.btn} title="Grid">
              <Grid size={16} />
            </button>
            <button onClick={props.onTogglePhysics} style={props.physicsEnabled ? styles.btnActive : styles.btn} title="Physics">
              <Zap size={16} />
            </button>
            <button onClick={props.onToggleLegend} style={props.showLegend ? styles.btnActive : styles.btn} title="Toggle Legend">
              <FileText size={16} />
            </button>
            {!props.focusedNodeId && (
              <button
                onClick={props.onEnterFocus}
                disabled={!props.selectedNodeInfo}
                style={props.selectedNodeInfo ? styles.btn : { ...styles.btn, opacity: 0.4, cursor: 'not-allowed' }}
                title={props.selectedNodeInfo ? `Focus on "${props.selectedNodeInfo.label}" and its neighborhood` : 'Select a node first, then click to focus'}
              >
                <Maximize size={16} />
                Focus
              </button>
            )}
          </div>

          <div style={sectionTitle}>Export</div>
          <div style={sectionRow}>
            <button onClick={() => props.onExport('svg')} style={styles.btn} title="Export SVG">
              <Download size={16} />
              SVG
            </button>
            <button onClick={() => props.onExport('png')} style={styles.btn} title="Export PNG">
              <Download size={16} />
              PNG
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
