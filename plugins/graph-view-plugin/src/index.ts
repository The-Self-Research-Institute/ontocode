

export { AdvancedGraphView as default } from './AdvancedGraphView';
export * from './types';

export { graphDataService } from './services/GraphDataService';
export { graphMutationService } from './services/GraphMutationService';
export { vowlNotationService } from './services/VOWLNotationService';
export type { VOWLNodeData, VOWLEdgeData, VOWLGraphData } from './services/VOWLNotationService';

export { OntoHierarchyGraphView } from './OntoHierarchyGraphView';

export {
  ClassHierarchyPanel,
  type ClassHierarchyPanelProps,
  type ClassHierarchyContextAction,
  type AssertionViewMode,
  type HierarchyDirection
} from './components/ClassHierarchyPanel';
export { LocalGraphView } from './components/LocalGraphView';
export { AnalyticsPanel } from './components/AnalyticsPanel';
export { computeGraphAnalytics } from './services/GraphAnalyticsService';

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
