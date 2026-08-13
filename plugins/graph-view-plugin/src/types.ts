

export type NodeType = 'class' | 'individual' | 'property' | 'dataProperty' | 'objectProperty' | 'annotation' | 'datatype' | 'setOperator';

export type SetOperatorKind = 'union' | 'intersection' | 'complement' | 'oneOf';

export type EdgeType =
  | 'subClassOf'
  | 'instanceOf'
  | 'propertyRelation'
  | 'equivalentClass'
  | 'disjointWith'
  | 'domain'
  | 'range'
  | 'inverseOf'
  | 'custom'
  | 'temporal'
  | 'spatial'
  | 'probabilistic'
  | 'subPropertyOf'
  | 'operand'
  | 'restriction'
  | 'propertyChain';

export type LayoutAlgorithm = 
  | 'force' 
  | 'hierarchical' 
  | 'circular' 
  | 'radial'
  | 'layered'
  | 'organic'
  | 'tree';

export type VisualizationType =
  | 'force'           // Legacy — no longer selectable; Network preset now uses the WebGL engine
  | 'vowl'            // VOWL notation
  | 'ontograph'       // OntoGraph hierarchical view
  | 'spatial3d';      // Legacy — no longer selectable (2.5D depth illusion)

export interface ReifiedRelation {
  id: string;
  subjectNodeId: string;
  predicateNodeId: string;
  objectNodeId: string;
  metadata: Record<string, any>;
  confidence?: number;
  validFrom?: Date;
  validTo?: Date;
  context?: string;
}

export interface NAryRelation {
  id: string;
  type: string;
  participants: Array<{
    role: string;
    nodeId: string;
    order?: number;
  }>;
  metadata: Record<string, any>;
}

export interface TemporalMetadata {
  validTimeStart?: Date;
  validTimeEnd?: Date;
  transactionTimeStart?: Date;
  transactionTimeEnd?: Date;
  effectiveDate?: Date;
  expirationDate?: Date;
}

export interface SpatialMetadata {
  location?: {
    lat: number;
    lng: number;
    alt?: number;
  };
  boundingBox?: {
    minLat: number;
    maxLat: number;
    minLng: number;
    maxLng: number;
  };
  context?: string;
  scope?: string;
}

export interface ProvenanceMetadata {
  wasGeneratedBy?: string;
  wasAttributedTo?: string;
  wasDerivedFrom?: string;
  wasRevisionOf?: string;
  hadPrimarySource?: string;
  wasQuotedFrom?: string;
  generatedAtTime?: Date;
  invalidatedAtTime?: Date;
  citations?: string[];
  trustScore?: number;
  sourceReliability?: 'high' | 'medium' | 'low' | 'unknown';
}

export interface OntologyNode {
  id: string;
  label: string;
  type: NodeType;
  color?: string;
  shape?: 'box' | 'circle' | 'diamond' | 'triangle' | 'star' | 'hexagon';
  size?: number;

  uri?: string;
  namespace?: string;
  description?: string;
  annotations?: Record<string, any>;

  superClasses?: string[];
  equivalentClasses?: string[];
  disjointClasses?: string[];

  confidence?: number;
  uncertainty?: number;

  temporal?: TemporalMetadata;

  spatial?: SpatialMetadata;

  provenance?: ProvenanceMetadata;

  version?: string;
  createdAt?: Date;
  updatedAt?: Date;
  createdBy?: string;

  embedding?: number[];
  semanticSimilarity?: Map<string, number>;
  suggestedSynonyms?: string[];

  metadata?: Record<string, any>;
}

export interface OntologyEdge {
  id: string;
  from: string;
  to: string;
  label: string;
  type: EdgeType;

  weight?: number;
  confidence?: number;
  bidirectional?: boolean;

  temporal?: TemporalMetadata;

  context?: string;
  scope?: string;

  provenance?: ProvenanceMetadata;

  cardinality?: {
    min?: number;
    max?: number;
  };

  metadata?: Record<string, any>;
}

export interface GraphSettings {
  layout: LayoutAlgorithm;
  showLabels: boolean;
  showArrows: boolean;
  physics: boolean;
  nodeSize: number;
  edgeWidth: number;

  showConfidence: boolean;
  showTemporal: boolean;
  showProvenance: boolean;
  colorByType: boolean;
  colorByConfidence: boolean;

  maxNodes: number;
  clusterNodes: boolean;
  lazyLoad: boolean;

  multiSelect: boolean;
  contextMenu: boolean;
  tooltips: boolean;

  renderer?: 'svg' | 'webgl';
}

export interface GraphFilters {
  nodeTypes: Set<NodeType>;
  edgeTypes: Set<EdgeType>;
  confidenceMin?: number;
  confidenceMax?: number;
  temporalFilter?: {
    start?: Date;
    end?: Date;
  };
  searchQuery?: string;
  namespaceFilter?: string[];
  contextFilter?: string[];
}

export interface GraphQuery {
  type: 'pattern' | 'path' | 'neighbor' | 'subgraph' | 'motif';
  parameters: any;
  limit?: number;
}

export interface ReasoningResult {
  inferences: Array<{
    type: 'inferred_class' | 'inferred_property' | 'inconsistency' | 'unsatisfiable';
    nodes: string[];
    explanation: string;
    confidence?: number;
  }>;
  inconsistencies: Array<{
    nodes: string[];
    reason: string;
  }>;
  suggestions: Array<{
    type: 'missing_relation' | 'duplicate' | 'synonym' | 'hierarchy_improvement';
    description: string;
    affectedNodes: string[];
  }>;
}

export interface GraphVersion {
  version: string;
  timestamp: Date;
  author: string;
  message: string;
  diff: {
    addedNodes: OntologyNode[];
    removedNodes: OntologyNode[];
    modifiedNodes: Array<{
      id: string;
      before: Partial<OntologyNode>;
      after: Partial<OntologyNode>;
    }>;
    addedEdges: OntologyEdge[];
    removedEdges: OntologyEdge[];
  };
}

export interface ImpactAnalysis {
  nodeId: string;
  affectedNodes: string[];
  affectedEdges: string[];
  usageCount: number;
  dependencies: Array<{
    nodeId: string;
    relationship: string;
    critical: boolean;
  }>;
  recommendations: string[];
}

export type ExportFormat = 'png' | 'svg' | 'pdf' | 'owl' | 'rdf' | 'json-ld' | 'cypher' | 'graphml';

export interface GraphPluginContext {
  projectId: string;
  userId?: string;
  permissions: {
    canEdit: boolean;
    canDelete: boolean;
    canShare: boolean;
    canExport: boolean;
  };
}
