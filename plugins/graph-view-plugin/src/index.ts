/**
 * Graph View Plugin Entry Point
 * Integrated with WebVOWL VOWL Notation Support
 */

export { AdvancedGraphView as default } from './AdvancedGraphView';
export * from './types';

// Services
export { graphDataService } from './services/GraphDataService';
export { graphMutationService } from './services/GraphMutationService';
export { vowlNotationService } from './services/VOWLNotationService';
export type { VOWLNodeData, VOWLEdgeData, VOWLGraphData } from './services/VOWLNotationService';

// Views
export { ProtegeStyleGraphView } from './ProtegeStyleGraphView';

// Hierarchy / focus components (Protege-parity + Obsidian-inspired)
export {
  ClassHierarchyPanel,
  type ClassHierarchyPanelProps,
  type ClassHierarchyContextAction,
  type AssertionViewMode,
  type HierarchyDirection
} from './components/ClassHierarchyPanel';
export { LocalGraphView } from './components/LocalGraphView';

// Hierarchy helpers (cycle-safe, multi-parent)
export {
  buildHierarchyIndex,
  getRootNodes,
  getParents,
  getChildren,
  hasChildren,
  getAllDescendants,
  findPathToNode,
  searchNodesWithPaths,
  toggleNodeExpansion,
  expandAll,
  collapseAll,
  getExpansionStats,
  type HierarchyIndex
} from './HierarchicalLazyLoading';
