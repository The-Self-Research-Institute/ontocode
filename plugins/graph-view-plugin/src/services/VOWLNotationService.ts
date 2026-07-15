/**
 * VOWL Notation Service - Integrated into Graph View
 * Transforms ontology data into VOWL-compliant visual representation
 * Merges WebVOWL VOWL notation with Graph View capabilities
 */

import type {
  OntologyNode,
  OntologyEdge,
  NodeType,
  EdgeType,
} from '../types';

export interface VOWLNodeData {
  id: string;
  type: string;
  label: string;
  iri: string;
  radius?: number;
  strokeColor?: string;
  strokeWidth?: number;
  strokeDasharray?: string | null;
  attributes?: Record<string, any>;
}

export interface VOWLEdgeData {
  id: string;
  source: string;
  target: string;
  type: string;
  label: string;
  iri: string;
  stroke?: string;
  strokeDasharray?: string | null;
  strokeWidth?: number;
  attributes?: Record<string, any>;
}

export interface VOWLGraphData {
  nodes: VOWLNodeData[];
  edges: VOWLEdgeData[];
  namespace: string;
  prefixes: { [key: string]: string };
  statistics: {
    classCount: number;
    objectPropertyCount: number;
    datatypePropertyCount: number;
    annotationPropertyCount: number;
    individualCount: number;
    datatypeCount: number;
    axiomCount: number;
  };
}

export class VOWLNotationService {
  private nodeMap: Map<string, VOWLNodeData> = new Map();
  private edgeMap: Map<string, VOWLEdgeData> = new Map();

  /**
   * Convert OntologyNode to VOWL-styled node with proper notation
   */
  nodeToVOWLNode(node: OntologyNode): VOWLNodeData {
    const vowlType = this.mapToVOWLNodeType(node.type);
    const deprecated = node.metadata?.deprecated || false;
    const external = this.isExternalNode(node);
    
    // VOWL visual styling
    let radius = 12;
    let strokeColor = '#1f2937';
    let strokeWidth = 2;
    let strokeDasharray: string | null = null;
    
    // Adjust styling based on type and state
    if (node.type === 'individual') {
      radius = 8; // Smaller for individuals
    }
    
    if (deprecated) {
      strokeDasharray = '4 2'; // Dashed for deprecated
      strokeColor = '#9ca3af';
    }
    
    if (external) {
      strokeWidth = 1; // Thinner for external entities
    }
    
    return {
      id: node.id,
      type: vowlType,
      label: node.label,
      iri: node.uri || node.id,
      radius,
      strokeColor,
      strokeWidth,
      strokeDasharray,
      attributes: {
        confidence: node.confidence,
        deprecated,
        external,
      },
    };
  }

  /**
   * Convert OntologyEdge to VOWL-styled edge with proper characteristics
   */
  edgeToVOWLEdge(edge: OntologyEdge): VOWLEdgeData {
    const vowlType = this.mapToVOWLEdgeType(edge.type);
    
    // VOWL edge styling based on type
    let stroke = '#000';
    let strokeDasharray: string | null = null;
    let strokeWidth = 2;
    
    // SubClassOf: dashed line (inheritance hierarchy) - Dark gray
    if (edge.type === 'subClassOf') {
      stroke = '#374151'; // Dark gray
      strokeDasharray = '5 3';
      strokeWidth = 2;
    }
    // Object properties: solid cyan line
    else if (edge.type === 'propertyRelation') {
      stroke = '#0891b2'; // Cyan for object properties
      strokeDasharray = null;
      strokeWidth = 2;
    }
    // Custom/other relations
    else if (edge.type === 'custom') {
      stroke = '#000';
      strokeDasharray = null;
      strokeWidth = 2;
    }
    // Domain/Range: lighter dashed
    else if (edge.type === 'domain' || edge.type === 'range') {
      stroke = '#9ca3af';
      strokeDasharray = '4 2';
    }
    // Annotation properties: purple dotted line
    else if (edge.metadata?.annotationProperty) {
      stroke = '#7c3aed'; // Purple
      strokeDasharray = '2 2';
    }
    // EquivalentClass: thick blue line. (Was green — paired with disjointWith's red, which is
    // the exact red/green combination color-vision-deficiency guidance warns against, since
    // ~8% of men can't reliably distinguish them. Blue/orange is a safer opposing pair, and
    // both edge types still carry a redundant, non-color signal: this one is solid+thick,
    // disjointWith is dashed.)
    else if (edge.type === 'equivalentClass') {
      stroke = '#2563eb'; // Blue
      strokeWidth = 3;
    }
    // DisjointWith: dashed orange line (see equivalentClass comment above for why not red/green)
    else if (edge.type === 'disjointWith') {
      stroke = '#f97316'; // Orange
      strokeDasharray = '8 4';
    }
    // Restriction (someValuesFrom/allValuesFrom/hasValue/cardinality): amber solid line,
    // distinct from the plain asserted propertyRelation edge it sits alongside
    else if (edge.type === 'restriction') {
      stroke = '#d97706';
      strokeDasharray = null;
      strokeWidth = 2;
    }
    // PropertyChain (owl:propertyChainAxiom composition): dotted pink line — was violet,
    // identical to the annotation-property color above and distinguishable only by dash
    // spacing (2 2 vs 1 3), an easy mix-up at a glance. Pink is otherwise unused here.
    else if (edge.type === 'propertyChain') {
      stroke = '#ec4899';
      strokeDasharray = '1 3';
      strokeWidth = 2;
    }

    return {
      id: edge.id,
      source: edge.from,
      target: edge.to,
      type: vowlType,
      label: edge.label,
      iri: `${edge.from}#${vowlType}#${edge.to}`,
      stroke,
      strokeDasharray,
      strokeWidth,
      attributes: {
        functional: edge.metadata?.functional,
        inverseFunctional: edge.metadata?.inverseFunctional,
        symmetric: edge.metadata?.symmetric,
        transitive: edge.metadata?.transitive,
        reflexive: edge.metadata?.reflexive,
        irreflexive: edge.metadata?.irreflexive,
        asymmetric: edge.metadata?.asymmetric,
        bidirectional: edge.bidirectional,
        weight: edge.weight,
      },
    };
  }

  /**
   * Map Graph View node type to VOWL node type
   */
  private mapToVOWLNodeType(graphNodeType: NodeType): string {
    const typeMap: Record<NodeType, string> = {
      class: 'owl:Class',
      individual: 'owl:NamedIndividual',
      property: 'owl:ObjectProperty',
      dataProperty: 'owl:DatatypeProperty',
      objectProperty: 'owl:ObjectProperty',
      annotation: 'owl:AnnotationProperty',
      datatype: 'rdfs:Datatype',
      setOperator: 'owl:Class',
    };
    return typeMap[graphNodeType] || 'owl:Class';
  }

  /**
   * Map Graph View edge type to VOWL edge type
   */
  private mapToVOWLEdgeType(graphEdgeType: EdgeType): string {
    const typeMap: Record<EdgeType, string> = {
      subClassOf: 'rdfs:subClassOf',
      instanceOf: 'rdf:type',
      propertyRelation: 'owl:propertyRelation',
      equivalentClass: 'owl:equivalentClass',
      disjointWith: 'owl:disjointWith',
      domain: 'rdfs:domain',
      range: 'rdfs:range',
      inverseOf: 'owl:inverseOf',
      custom: 'rdfs:relation',
      temporal: 'rdfs:temporal',
      spatial: 'rdfs:spatial',
      probabilistic: 'rdfs:probabilistic',
      subPropertyOf: 'rdfs:subPropertyOf',
      operand: 'owl:operand',
      restriction: 'owl:Restriction',
      propertyChain: 'owl:propertyChainAxiom',
    };
    return typeMap[graphEdgeType] || 'rdfs:relation';
  }

  /**
   * Check if node is external (from standard ontologies)
   */
  private isExternalNode(node: OntologyNode): boolean {
    const uri = node.uri || node.id;
    if (!uri || typeof uri !== 'string') return false;
    return (
      uri.startsWith('http://www.w3.org/') ||
      uri.startsWith('http://xmlns.com/') ||
      uri.startsWith('http://purl.org/dc/')
    );
  }

  /**
   * Get VOWL node color based on type (WebVOWL standard colors)
   * These colors MUST match the actual rendering in AdvancedGraphView.tsx
   * @param nodeType The type of the node
   * @param isDark Whether dark mode is active
   */
  getVOWLNodeColor(nodeType: string, isDark: boolean = false): string {
    // Light mode colors
    const lightColorMap: Record<string, string> = {
      'owl:Class': '#acd5f2',
      'owl:NamedIndividual': '#dcd5f7',
      'owl:ObjectProperty': '#acd5f2',
      'owl:DatatypeProperty': '#ffffcc',
      'owl:AnnotationProperty': '#e8d5f2',
      'rdfs:Datatype': '#FFD9B3',
      'owl:Thing': '#ffffff',
      'rdfs:Literal': '#FFD9B3',
      'class': '#acd5f2',
      'datatype': '#FFD9B3',
      'individual': '#dcd5f7',
      'property': '#acd5f2',
      'objectProperty': '#acd5f2',
      'dataProperty': '#ffffcc',
      'annotation': '#e8d5f2',
    };
    
    // Dark mode colors - adjusted for better visibility on dark backgrounds
    const darkColorMap: Record<string, string> = {
      'owl:Class': '#6b92c4',
      'owl:NamedIndividual': '#fbb6ce',
      'owl:ObjectProperty': '#6b92c4',
      'owl:DatatypeProperty': '#fef08a',
      'owl:AnnotationProperty': '#9333ea',
      'rdfs:Datatype': '#d97706',
      'owl:Thing': '#374151',
      'rdfs:Literal': '#d97706',
      'class': '#6b92c4',
      'datatype': '#d97706',
      'individual': '#fbb6ce',
      'property': '#6b92c4',
      'objectProperty': '#6b92c4',
      'dataProperty': '#fef08a',
      'annotation': '#9333ea',
    };
    
    const colorMap = isDark ? darkColorMap : lightColorMap;
    return colorMap[nodeType] || (isDark ? '#6b92c4' : '#acd5f2');
  }

  /**
   * Get VOWL edge stroke style based on type and attributes (WebVOWL standard)
   */
  getVOWLEdgeStyle(edgeType: string, attributes?: Record<string, any>): {
    stroke: string;
    strokeDasharray?: string;
    strokeWidth: number;
  } {
    const baseWidth = 1.5;

    // Annotation properties use dashed lines
    if (edgeType === 'owl:AnnotationProperty') {
      return {
        stroke: '#000000',
        strokeDasharray: '3,3',
        strokeWidth: baseWidth,
      };
    }

    // Datatype properties use solid black
    if (edgeType === 'owl:DatatypeProperty') {
      return {
        stroke: '#000000',
        strokeWidth: baseWidth,
      };
    }

    // Object properties use solid black
    if (edgeType === 'owl:ObjectProperty' || edgeType === 'owl:propertyRelation') {
      return {
        stroke: '#000000',
        strokeWidth: baseWidth,
      };
    }

    // SubClassOf relationships - dashed line
    if (edgeType === 'rdfs:subClassOf') {
      return {
        stroke: '#000000',
        strokeDasharray: '5,5',
        strokeWidth: 1.5,
      };
    }

    // Equivalent class - solid thicker line (blue, not green — see edgeToVOWLEdge for why)
    if (edgeType === 'owl:equivalentClass') {
      return {
        stroke: '#2563eb',
        strokeDasharray: '2,2',
        strokeWidth: baseWidth,
      };
    }

    // Disjoint - dotted line (orange, not red — paired with equivalentClass, avoids the
    // red/green color-vision-deficiency combination)
    if (edgeType === 'owl:disjointWith') {
      return {
        stroke: '#f97316',
        strokeDasharray: '1,2',
        strokeWidth: baseWidth,
      };
    }

    return {
      stroke: '#d1d5db',
      strokeWidth: baseWidth,
    };
  }

  /**
   * Create VOWL notation legend entries
   */
  getVOWLLegend(): Array<{
    name: string;
    type: 'node' | 'edge';
    nodeType?: string;
    edgeType?: string;
    color?: string;
    stroke?: string;
    strokeDasharray?: string;
  }> {
    return [
      { name: 'Class', type: 'node', nodeType: 'owl:Class', color: '#3b82f6' },
      { name: 'Individual', type: 'node', nodeType: 'owl:NamedIndividual', color: '#6366f1' },
      { name: 'Datatype', type: 'node', nodeType: 'rdfs:Datatype', color: '#f59e0b' },
      { name: 'SubClass', type: 'edge', edgeType: 'rdfs:subClassOf', stroke: '#6b7280' },
      {
        name: 'Object Property',
        type: 'edge',
        edgeType: 'owl:ObjectProperty',
        stroke: '#3b82f6',
      },
      {
        name: 'Data Property',
        type: 'edge',
        edgeType: 'owl:DatatypeProperty',
        stroke: '#f59e0b',
      },
      {
        name: 'Annotation Property',
        type: 'edge',
        edgeType: 'owl:AnnotationProperty',
        stroke: '#9c27b0',
        strokeDasharray: '4,2',
      },
      {
        name: 'Equivalent Class',
        type: 'edge',
        edgeType: 'owl:equivalentClass',
        stroke: '#2563eb',
        strokeDasharray: '2,2',
      },
      {
        name: 'Disjoint With',
        type: 'edge',
        edgeType: 'owl:disjointWith',
        stroke: '#f97316',
        strokeDasharray: '1,2',
      },
    ];
  }

  /**
   * Format node label with namespace prefix
   */
  formatNodeLabel(label: string, namespace?: string): string {
    if (!label) return '';

    // Extract prefix if namespace provided
    if (namespace) {
      const lastSlash = label.lastIndexOf('/');
      const lastHash = label.lastIndexOf('#');
      const splitIndex = Math.max(lastSlash, lastHash);

      if (splitIndex > 0) {
        const localName = label.substring(splitIndex + 1);
        return localName;
      }
    }

    return label;
  }

  /**
   * Get property characteristic indicators
   */
  getPropertyCharacteristics(
    attributes?: Record<string, any>
  ): Array<{
    name: string;
    symbol: string;
    description: string;
  }> {
    const characteristics = [];

    if (attributes?.functional) {
      characteristics.push({
        name: 'Functional',
        symbol: 'F',
        description: 'Each instance has at most one value',
      });
    }
    if (attributes?.inverseFunctional) {
      characteristics.push({
        name: 'Inverse Functional',
        symbol: 'IF',
        description: 'Inverse property is functional',
      });
    }
    if (attributes?.symmetric) {
      characteristics.push({
        name: 'Symmetric',
        symbol: 'S',
        description: 'If A relates to B then B relates to A',
      });
    }
    if (attributes?.transitive) {
      characteristics.push({
        name: 'Transitive',
        symbol: 'T',
        description: 'If A relates to B and B relates to C then A relates to C',
      });
    }
    if (attributes?.reflexive) {
      characteristics.push({
        name: 'Reflexive',
        symbol: 'R',
        description: 'Each instance relates to itself',
      });
    }
    if (attributes?.irreflexive) {
      characteristics.push({
        name: 'Irreflexive',
        symbol: 'IR',
        description: 'No instance relates to itself',
      });
    }
    if (attributes?.asymmetric) {
      characteristics.push({
        name: 'Asymmetric',
        symbol: 'A',
        description: 'If A relates to B then B does not relate to A',
      });
    }

    return characteristics;
  }

  /**
   * Get VOWL-compliant statistics from nodes and edges
   */
  calculateVOWLStatistics(
    nodes: OntologyNode[],
    edges: OntologyEdge[]
  ): VOWLGraphData['statistics'] {
    const classes = nodes.filter((n) => n.type === 'class');
    const individuals = nodes.filter((n) => n.type === 'individual');
    const objectProperties = nodes.filter((n) => n.type === 'objectProperty');
    const datatypeProperties = nodes.filter((n) => n.type === 'dataProperty');
    const annotationProperties = nodes.filter((n) => n.type === 'annotation');
    const datatypes = nodes.filter((n) => n.type === 'dataProperty' || n.label?.includes('Datatype'));

    return {
      classCount: classes.length,
      objectPropertyCount: objectProperties.length,
      datatypePropertyCount: datatypeProperties.length,
      annotationPropertyCount: annotationProperties.length,
      individualCount: individuals.length,
      datatypeCount: datatypes.length,
      axiomCount: nodes.length + edges.length,
    };
  }
}

export const vowlNotationService = new VOWLNotationService();
