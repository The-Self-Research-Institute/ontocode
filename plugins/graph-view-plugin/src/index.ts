/**
 * Graph View Plugin Entry Point
 * Integrated with WebVOWL VOWL Notation Support
 */

export { AdvancedGraphView as default } from './AdvancedGraphView';
export * from './types';
export { graphDataService } from './services/GraphDataService';
export { graphMutationService } from './services/GraphMutationService';
export { vowlNotationService } from './services/VOWLNotationService';
export type { VOWLNodeData, VOWLEdgeData, VOWLGraphData } from './services/VOWLNotationService';
