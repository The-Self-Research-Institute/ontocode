/**
 * Graph Data Fetch Service
 * Optimized data fetching from GraphDB with proper error handling and transformation
 * Based on webVOWL plugin's proven approach
 * 
 * PERFORMANCE NOTE:
 * - Recursive child fetching is DISABLED for large ontologies
 * - This prevents N+1 query problem that causes "continuous polling" behavior
 * - Only top-level classes are fetched; users can expand nodes on-demand
 */

import { OntologyNode, OntologyEdge } from '../types';
import { authHeaders } from '../utils/authHeaders';

// Gate for noisy per-fetch diagnostics (the ALL CLASSES dump maps every class on every fetch)
const GRAPH_DEBUG = typeof window !== 'undefined' && window.localStorage?.getItem('ontocode.graphView.debug') === 'true';

export class GraphDataFetchService {
  private apiBaseUrl: string;
  private ontologyId: string;
  private authToken: string | null;

  constructor(apiBaseUrl: string, ontologyId: string, authToken: string | null) {
    this.apiBaseUrl = apiBaseUrl;
    this.ontologyId = ontologyId;
    this.authToken = authToken;
  }

  private get headers() {
    return authHeaders(this.authToken);
  }

  /**
   * Fetch complete graph data for visualization
   * Returns nodes and edges in the format expected by the graph view
   * Recursively fetches ALL classes like webVOWL does
   */
  async fetchGraphData(): Promise<{ nodes: OntologyNode[], edges: OntologyEdge[] }> {
    console.log('[GraphDataFetchService] 🚀🚀🚀 STARTING FETCH - ontologyId:', this.ontologyId, 'apiBaseUrl:', this.apiBaseUrl);
    
    try {
      // Fetch all entity types in parallel
      console.log('[GraphDataFetchService] ⏳ Fetching all entity types in parallel...');
      const [classesData, individualsData, objectPropsData, dataPropsData, annotationPropsData, datatypesData] = await Promise.all([
        this.fetchAllClassesRecursively(),
        this.fetchIndividuals(),
        this.fetchObjectProperties(),
        this.fetchDataProperties(),
        this.fetchAnnotationProperties(),
        this.fetchDatatypes()
      ]);

      if (GRAPH_DEBUG) {
        console.log('[GraphDataFetchService] 📊📊📊 RAW DATA FETCHED:', {
          allClasses: classesData.length,
          individuals: individualsData.length,
          objectProps: objectPropsData.length,
          dataProps: dataPropsData.length,
          annotationProps: annotationPropsData.length,
          datatypes: datatypesData.length
        });
        console.log('[GraphDataFetchService] 📋 ALL CLASSES:', classesData.map((c, i) => `${i + 1}. ${c.label || c.name || c.iri}`));
      }

      // Transform to graph format
      const nodes: OntologyNode[] = [];
      const edges: OntologyEdge[] = [];
      const processedClasses = new Set<string>();

      // Add owl:Thing as root if not present
      const owlThingIri = 'http://www.w3.org/2002/07/owl#Thing';
      nodes.push({
        id: owlThingIri,
        label: 'Thing',
        type: 'class',
        uri: owlThingIri
      });
      processedClasses.add(owlThingIri);

      // Process ALL classes (including children)
      for (const cls of classesData) {
        this.processClassNode(cls, nodes, edges, processedClasses);
      }

      // Ensure every subClassOf parent IRI has a node (edges were dropped when parent was missing)
      this.ensureHierarchyEndpointNodes(nodes, edges, processedClasses);

      // CRITICAL FIX: Re-parent orphan classes under owl:Thing so the sidebar
      // hierarchy tree renders properly even when the backend bulk endpoint
      // omits subClassOf for top-level classes. Without this, every class
      // appears as a root and the sidebar shows a flat list.
      const classNodeIds = nodes.filter(n => n.type === 'class' && n.id !== owlThingIri).map(n => n.id);
      const childIds = new Set(
        edges.filter(e => e.type === 'subClassOf').map(e => e.from)
      );
      let orphansAdopted = 0;
      for (const classId of classNodeIds) {
        if (!childIds.has(classId)) {
          edges.push({
            id: `${classId}-subClassOf-${owlThingIri}`,
            from: classId,
            to: owlThingIri,
            type: 'subClassOf',
            label: 'subClassOf'
          });
          orphansAdopted++;
        }
      }
      if (orphansAdopted > 0) {
        console.log(`[GraphDataFetchService] 🌳 Adopted ${orphansAdopted} orphan classes under owl:Thing`);
      }

      // Process individuals
      this.processIndividuals(individualsData, nodes, edges);

      // Process properties
      this.processObjectProperties(objectPropsData, nodes, edges);
      this.processDataProperties(dataPropsData, nodes, edges);
      this.processAnnotationProperties(annotationPropsData, nodes, edges);
      
      // IMPORTANT: Do NOT process all datatypes - only keep datatypes that are connected
      // through data property ranges. This prevents overlapping unconnected datatype nodes.
      // The datatypes that ARE connected are already added in processDataProperties.
      
      // Get all connected datatype IRIs from edges
      const connectedDatatypeIds = new Set<string>();
      for (const edge of edges) {
        if (edge.type === 'range' && nodes.find(n => n.id === edge.to && n.type === 'datatype')) {
          connectedDatatypeIds.add(edge.to);
        }
      }
      
      // Only add datatypes from the explicit datatypes list if they're connected
      this.processDatatypes(datatypesData, nodes, edges, connectedDatatypeIds);

      // Ensure rdfs:Literal exists ONLY if it's actually connected through data properties
      const rdfsLiteralIri = 'http://www.w3.org/2000/01/rdf-schema#Literal';
      if (connectedDatatypeIds.has(rdfsLiteralIri) && !nodes.find(n => n.id === rdfsLiteralIri)) {
        nodes.push({
          id: rdfsLiteralIri,
          label: 'Literal',
          type: 'datatype',
          uri: rdfsLiteralIri
        });
        console.log('[GraphDataFetchService] Added connected rdfs:Literal datatype node');
      }

      const propertyRelationEdges = edges.filter(e => e.type === 'propertyRelation');
      const subClassOfEdges = edges.filter(e => e.type === 'subClassOf');
      
      console.log('[GraphDataFetchService] ✅ Transformation complete:', {
        totalNodes: nodes.length,
        totalEdges: edges.length,
        subClassOfEdges: subClassOfEdges.length,
        classes: processedClasses.size,
        individuals: individualsData.length,
        properties: objectPropsData.length + dataPropsData.length + annotationPropsData.length,
        datatypes: nodes.filter(n => n.type === 'datatype').length,
        propertyRelations: propertyRelationEdges.length
      });
      
      console.log('[GraphDataFetchService] 🔥 All propertyRelation edges:', propertyRelationEdges.map(e => ({
        id: e.id,
        from: e.from.split('#').pop(),
        to: e.to.split('#').pop(),
        label: e.label
      })));

      // Debug: Show node type distribution
      const nodeTypeCount: Record<string, number> = {};
      nodes.forEach(n => {
        nodeTypeCount[n.type] = (nodeTypeCount[n.type] || 0) + 1;
      });
      console.log('[GraphDataFetchService] 📊 Node Types:', nodeTypeCount);
      console.log('[GraphDataFetchService] 📝 Sample nodes:', nodes.slice(0, 5));

      return { nodes, edges };
    } catch (error) {
      console.error('[GraphDataFetchService] ❌ Error fetching graph data:', error);
      throw error;
    }
  }

  /**
   * Fetch ALL classes efficiently using bulk endpoint
   * This fetches all classes in ONE request, avoiding N+1 queries
   * 
   * NOTE: Uses the new `/classes/all/` endpoint that fetches everything in a single SPARQL query
   */
  private async fetchAllClassesRecursively(): Promise<any[]> {
    // Try the new bulk endpoint first (faster, one request)
    const bulkUrl = `${this.apiBaseUrl}/api/ontology/classes/all/${this.ontologyId}?limit=10000`;
    console.log('[GraphDataFetchService] 🚀 TRYING BULK FETCH from:', bulkUrl);
    
    try {
      const response = await fetch(bulkUrl, { headers: this.headers });
      
      if (response.ok) {
        const data = await response.json();
        const allClasses = data.success && data.classes ? data.classes : [];
        console.log('[GraphDataFetchService] ✅ BULK FETCH SUCCESS:', allClasses.length, 'classes');
        return allClasses;
      }
      
      console.warn('[GraphDataFetchService] ⚠️ Bulk endpoint failed, falling back to top-level only');
    } catch (error) {
      console.warn('[GraphDataFetchService] ⚠️ Bulk fetch error, falling back:', error);
    }
    
    // Fallback: fetch top-level classes and then recursively fetch their children
    const topLevelUrl = `${this.apiBaseUrl}/api/ontology/classes/top-level/${this.ontologyId}?limit=10000`;
    console.log('[GraphDataFetchService] 📦 Falling back to top-level + recursive children fetch');
    
    try {
      const response = await fetch(topLevelUrl, { headers: this.headers });
      
      if (!response.ok) {
        console.warn('[GraphDataFetchService] ❌ Top-level classes endpoint failed:', response.status);
        return [];
      }
      
      const data = await response.json();
      const topLevelClasses = data.success && data.classes ? data.classes : [];
      console.log('[GraphDataFetchService] ✅ Top-level classes:', topLevelClasses.length);
      
      // Recursively fetch children for each top-level class (unlimited depth with cycle protection)
      const allClasses = [...topLevelClasses];
      const visited = new Set<string>();
      const fetchChildren = async (parentIri: string): Promise<any[]> => {
        if (visited.has(parentIri)) return [];
        visited.add(parentIri);
        try {
          const childUrl = `${this.apiBaseUrl}/api/ontology/classes/children/${this.ontologyId}?parentIri=${encodeURIComponent(parentIri)}&limit=1000`;
          const childResp = await fetch(childUrl, { headers: this.headers });
          if (!childResp.ok) return [];
          const childData = await childResp.json();
          const children = Array.isArray(childData) ? childData : (childData.children || childData.classes || []);
          for (const child of children) {
            child.parent = parentIri;
          }
          const grandChildren = await Promise.all(
            children.map((c: any) => fetchChildren(c.id || c.iri))
          );
          return [...children, ...grandChildren.flat()];
        } catch {
          return [];
        }
      };

      const childResults = await Promise.all(
        topLevelClasses.map((cls: any) => fetchChildren(cls.id || cls.iri))
      );
      allClasses.push(...childResults.flat());
      
      console.log('[GraphDataFetchService] ✅ Total classes (with children):', allClasses.length);
      return allClasses;
    } catch (error) {
      console.error('[GraphDataFetchService] Error fetching classes:', error);
      return [];
    }
  }

  /**
   * Recursively fetch all children of a class (same as webVOWL)
   */
  private async fetchChildrenRecursively(parentIri: string): Promise<any[]> {
    const url = `${this.apiBaseUrl}/api/ontology/classes/children/${this.ontologyId}?parentIri=${encodeURIComponent(parentIri)}&limit=10000`;
    
    try {
      const response = await fetch(url, { headers: this.headers });
      if (!response.ok) {
        return [];
      }
      
      const data = await response.json();
      let childrenArray = data.classes || data.data || data;
      
      if (!Array.isArray(childrenArray)) {
        return [];
      }
      
      console.log(`[GraphDataFetchService] Found ${childrenArray.length} children for:`, parentIri);
      
      // Add parent relationship to each child
      const childrenWithParent = childrenArray.map(child => ({
        ...child,
        subClassOf: [parentIri]
      }));
      
      // Collect all descendants
      const allDescendants: any[] = [...childrenWithParent];
      
      // Recursively fetch children of children
      for (const child of childrenArray) {
        const childIri = child.iri || child.id || child.classIRI || child.classIri;
        if (childIri) {
          const grandChildren = await this.fetchChildrenRecursively(childIri);
          allDescendants.push(...grandChildren);
        }
      }
      
      return allDescendants;
    } catch (error) {
      console.warn(`[GraphDataFetchService] Error fetching children for ${parentIri}:`, error);
      return [];
    }
  }

  /**
   * Fetch top-level classes (kept for compatibility)
   */
  private async fetchClasses(): Promise<any[]> {
    const url = `${this.apiBaseUrl}/api/ontology/classes/top-level/${this.ontologyId}?limit=10000`;
    console.log('[GraphDataFetchService] Fetching classes from:', url);
    
    try {
      const response = await fetch(url, { headers: this.headers });
      if (!response.ok) {
        console.warn('[GraphDataFetchService] Classes endpoint failed:', response.status);
        return [];
      }
      
      const data = await response.json();
      return data.success && data.classes ? data.classes : [];
    } catch (error) {
      console.error('[GraphDataFetchService] Error fetching classes:', error);
      return [];
    }
  }

  /**
   * Fetch individuals
   */
  private async fetchIndividuals(): Promise<any[]> {
    const url = `${this.apiBaseUrl}/api/ontology/individuals/${this.ontologyId}?limit=10000`;
    console.log('[GraphDataFetchService] Fetching individuals from:', url);
    
    try {
      const response = await fetch(url, { headers: this.headers });
      if (!response.ok) return [];
      
      const data = await response.json();
      return data.success && data.data ? data.data : [];
    } catch (error) {
      console.error('[GraphDataFetchService] Error fetching individuals:', error);
      return [];
    }
  }

  /**
   * Fetch object properties
   */
  private async fetchObjectProperties(): Promise<any[]> {
    const url = `${this.apiBaseUrl}/api/ontology/properties/${this.ontologyId}?type=object&limit=10000`;
    console.log('🔵 Fetching object properties from:', url);
    
    try {
      const response = await fetch(url, { headers: this.headers });
      if (!response.ok) {
        console.warn('❌ Object properties fetch failed:', response.status);
        return [];
      }
      
      const data = await response.json();
      const props = data.success && data.data ? data.data : [];
      console.log('🔵 Fetched', props.length, 'object properties. Sample:', props.slice(0, 2));
      return props;
    } catch (error) {
      console.error('[GraphDataFetchService] Error fetching object properties:', error);
      return [];
    }
  }

  /**
   * Fetch data properties
   */
  private async fetchDataProperties(): Promise<any[]> {
    const url = `${this.apiBaseUrl}/api/ontology/properties/${this.ontologyId}?type=data&limit=10000`;
    console.log('🟡 Fetching data properties from:', url);
    
    try {
      const response = await fetch(url, { headers: this.headers });
      if (!response.ok) {
        console.warn('❌ Data properties fetch failed:', response.status);
        return [];
      }
      
      const data = await response.json();
      const props = data.success && data.data ? data.data : [];
      console.log('🟡 Fetched', props.length, 'data properties. Sample:', props.slice(0, 2));
      return props;
    } catch (error) {
      console.error('[GraphDataFetchService] Error fetching data properties:', error);
      return [];
    }
  }

  /**
   * Fetch annotation properties
   */
  private async fetchAnnotationProperties(): Promise<any[]> {
    const url = `${this.apiBaseUrl}/api/ontology/annotation-properties/${this.ontologyId}?limit=10000`;
    
    try {
      const response = await fetch(url, { headers: this.headers });
      if (!response.ok) return [];
      
      const data = await response.json();
      return data.success && data.data ? data.data : [];
    } catch (error) {
      console.error('[GraphDataFetchService] Error fetching annotation properties:', error);
      return [];
    }
  }

  /**
   * Fetch datatypes
   */
  private async fetchDatatypes(): Promise<any[]> {
    const url = `${this.apiBaseUrl}/api/ontology/datatypes/${this.ontologyId}?limit=10000`;
    
    try {
      console.log('[GraphDataFetchService] Fetching datatypes from:', url);
      const response = await fetch(url, { headers: this.headers });
      if (!response.ok) {
        console.warn('[GraphDataFetchService] Datatypes fetch failed with status:', response.status);
        return [];
      }
      
      const data = await response.json();
      const datatypes = data.success && data.data ? data.data : [];
      console.log('[GraphDataFetchService] Fetched datatypes:', datatypes.length, datatypes);
      return datatypes;
    } catch (error) {
      console.error('[GraphDataFetchService] Error fetching datatypes:', error);
      return [];
    }
  }

  /**
   * Process a single class node without recursing into children
   * Children will be loaded on-demand by the graph view
   */
  private processClassNode(
    classData: any,
    nodes: OntologyNode[],
    edges: OntologyEdge[],
    processedClasses: Set<string>
  ): void {
    const classIri = classData.classIri || classData.classIRI || classData.iri || classData.id || classData.uri;
    
    // Skip if no valid IRI
    if (!classIri) {
      console.warn('[GraphDataFetchService] Skipping class with no IRI:', classData);
      return;
    }
    
    if (processedClasses.has(classIri)) return;

    // Add class node
    nodes.push({
      id: classIri,
      label: classData.label || classIri?.split('#').pop()?.split('/').pop() || 'Class',
      type: 'class',
      uri: classIri
    });
    processedClasses.add(classIri);

    // Add subClassOf edges — prefer explicit array from bulk endpoint
    const superClasses = classData.subClassOf || classData.superClasses || classData.parents || [];
    // Also accept singular parent / parentIri (used by recursive fallback fetcher)
    const singleParent = classData.parent || classData.parentIri || classData.parentIRI;
    const allParents: string[] = Array.isArray(superClasses) ? [...superClasses] : [];
    if (singleParent && !allParents.includes(singleParent)) {
      allParents.push(singleParent);
    }
    for (const parent of allParents) {
      if (!parent || parent === classIri) continue;
      edges.push({
        id: `${classIri}-subClassOf-${parent}`,
        from: classIri,
        to: parent,
        type: 'subClassOf',
        label: 'subClassOf'
      });
    }

    // Add equivalentClass edges
    const equivalentClasses = classData.equivalentClass || classData.equivalentClasses || [];
    for (const equiv of equivalentClasses) {
      edges.push({
        id: `${classIri}-equivalentClass-${equiv}`,
        from: classIri,
        to: equiv,
        type: 'equivalentClass',
        label: 'equivalentClass'
      });
    }

    // Add disjointWith edges
    const disjointClasses = classData.disjointWith || classData.disjointClasses || [];
    for (const disjoint of disjointClasses) {
      edges.push({
        id: `${classIri}-disjointWith-${disjoint}`,
        from: classIri,
        to: disjoint,
        type: 'disjointWith',
        label: 'disjointWith'
      });
    }

    // Set-operator expressions (owl:unionOf / intersectionOf / complementOf / oneOf):
    // each becomes a VOWL operator node linked to the owning class by its axiom type
    // and to every named operand by an 'operand' edge.
    const classExpressions = classData.classExpressions || [];
    for (const expr of classExpressions) {
      if (!expr?.id || !expr.expressionType || !Array.isArray(expr.operands) || expr.operands.length === 0) continue;
      nodes.push({
        id: expr.id,
        label: expr.definition || expr.expressionType,
        type: 'setOperator',
        uri: expr.id,
        metadata: {
          setOperator: expr.expressionType,
          axiomType: expr.axiomType,
          definition: expr.definition,
          ownerClass: classIri
        }
      });
      // 'custom' (not 'subClassOf') for anonymous-superclass axioms so operator nodes
      // never enter the class-hierarchy computations (tree layout, roots, expansion).
      edges.push({
        id: `${classIri}-${expr.axiomType}-${expr.id}`,
        from: classIri,
        to: expr.id,
        type: expr.axiomType === 'equivalentClass' ? 'equivalentClass' : 'custom',
        label: expr.axiomType === 'equivalentClass' ? 'equivalentClass' : 'subClassOf'
      });
      for (const operand of expr.operands) {
        if (!operand?.iri) continue;
        edges.push({
          id: `${expr.id}-operand-${operand.iri}`,
          from: expr.id,
          to: operand.iri,
          type: 'operand',
          label: ''
        });
      }
    }
  }

  /**
   * Create stub class nodes for superclass IRIs referenced by subClassOf edges
   * but missing from the class list (common when parent is owl:Thing or external).
   */
  private ensureHierarchyEndpointNodes(
    nodes: OntologyNode[],
    edges: OntologyEdge[],
    processedClasses: Set<string>
  ): void {
    const nodeIds = new Set(nodes.map(n => n.id));
    const builtin = new Set([
      'http://www.w3.org/2002/07/owl#Thing',
      'http://www.w3.org/2002/07/owl#Nothing',
      'http://www.w3.org/2002/07/owl#Class'
    ]);
    let added = 0;

    for (const edge of edges) {
      if (edge.type !== 'subClassOf') continue;
      const parentIri = edge.to;
      if (!parentIri || nodeIds.has(parentIri) || builtin.has(parentIri)) continue;

      nodes.push({
        id: parentIri,
        label: parentIri.split('#').pop()?.split('/').pop() || parentIri,
        type: 'class',
        uri: parentIri
      });
      nodeIds.add(parentIri);
      processedClasses.add(parentIri);
      added++;
    }

    if (added > 0) {
      console.log(`[GraphDataFetchService] 🌳 Added ${added} missing superclass nodes for subClassOf edges`);
    }
  }

  /**
   * Recursively process a class and its children (used for on-demand loading)
   * This method is kept for future hierarchical expansion features
   */
  private async processClassRecursively(
    classData: any,
    nodes: OntologyNode[],
    edges: OntologyEdge[],
    processedClasses: Set<string>
  ): Promise<void> {
    const classIri = classData.iri || classData.classIRI || classData.id || classData.uri;
    if (!classIri || processedClasses.has(classIri)) return;

    // Add class node
    nodes.push({
      id: classIri,
      label: classData.label || classIri?.split('#').pop()?.split('/').pop() || 'Class',
      type: 'class',
      uri: classIri
    });
    processedClasses.add(classIri);

    // Add subClassOf edges
    const superClasses = classData.subClassOf || classData.superClasses || classData.parents || [];
    for (const parent of superClasses) {
      edges.push({
        id: `${classIri}-subClassOf-${parent}`,
        from: classIri,
        to: parent,
        type: 'subClassOf',
        label: 'subClassOf'
      });
    }

    // Add equivalentClass edges
    const equivalentClasses = classData.equivalentClass || classData.equivalentClasses || [];
    for (const equiv of equivalentClasses) {
      edges.push({
        id: `${classIri}-equivalentClass-${equiv}`,
        from: classIri,
        to: equiv,
        type: 'equivalentClass',
        label: 'equivalentClass'
      });
    }

    // Add disjointWith edges
    const disjointClasses = classData.disjointWith || classData.disjointClasses || [];
    for (const disjoint of disjointClasses) {
      edges.push({
        id: `${classIri}-disjointWith-${disjoint}`,
        from: classIri,
        to: disjoint,
        type: 'disjointWith',
        label: 'disjointWith'
      });
    }

    // Fetch and process children
    try {
      const childrenUrl = `${this.apiBaseUrl}/api/ontology/classes/children/${this.ontologyId}?parentIri=${encodeURIComponent(classIri)}&limit=10000`;
      const response = await fetch(childrenUrl, { headers: this.headers });
      
      if (response.ok) {
        const childrenData = await response.json();
        const childrenArray = childrenData.classes || childrenData.data || childrenData;
        
        if (Array.isArray(childrenArray) && childrenArray.length > 0) {
          console.log(`[GraphDataFetchService] Found ${childrenArray.length} children for: ${classData.label || classIri}`);
          
          for (const child of childrenArray) {
            const childIri = child.iri || child.id || child.classIRI || child.classIri;
            if (!processedClasses.has(childIri)) {
              const childData = {
                ...child,
                subClassOf: [classIri]
              };
              await this.processClassRecursively(childData, nodes, edges, processedClasses);
            }
          }
        }
      }
    } catch (error) {
      console.warn(`[GraphDataFetchService] Error fetching children for ${classIri}:`, error);
    }
  }

  /**
   * Process individuals into nodes and edges
   */
  private processIndividuals(individualsData: any[], nodes: OntologyNode[], edges: OntologyEdge[]): void {
    for (const ind of individualsData) {
      const indIri = ind.iri || ind.individualIRI || ind.id || ind.uri;
      if (!indIri) continue;
      nodes.push({
        id: indIri,
        label: ind.label || indIri?.split('#').pop()?.split('/').pop() || 'Individual',
        type: 'individual',
        uri: indIri
      });

      // Add type edges
      if (ind.types && Array.isArray(ind.types)) {
        for (const type of ind.types) {
          edges.push({
            id: `${indIri}-type-${type}`,
            from: indIri,
            to: type,
            type: 'instanceOf',
            label: 'type'
          });
        }
      }

      // Add object property assertions
      if (ind.objectProperties && Array.isArray(ind.objectProperties)) {
        for (const objProp of ind.objectProperties) {
          const target = objProp.target || objProp.value;
          edges.push({
            id: `${indIri}-${objProp.property || 'relatedTo'}-${target}`,
            from: indIri,
            to: target,
            type: 'propertyRelation',
            label: objProp.property || objProp.label || 'relatedTo'
          });
        }
      }
    }
  }

  /**
   * Process object properties into nodes and edges
   */
  private processObjectProperties(objectPropsData: any[], nodes: OntologyNode[], edges: OntologyEdge[]): void {
    console.log('\n🔵🔵🔵 PROCESSING OBJECT PROPERTIES 🔵🔵🔵');
    console.log('Object Properties Count:', objectPropsData.length);
    console.log('Sample raw property data:', objectPropsData.slice(0, 2));
    console.log('First property full object:', JSON.stringify(objectPropsData[0], null, 2));
    
    for (const prop of objectPropsData) {
      const propIri = prop.iri || prop.propertyIRI || prop.id || prop.uri;
      if (!propIri) continue;
      const propLabel = prop.label || propIri?.split('#').pop()?.split('/').pop() || 'ObjectProperty';

      // Capture all OWL property characteristics on the node for the details panel
      const propCharacteristics = prop.characteristics || [];
      const hasNodeChar = (...names: string[]) => names.some((n: string) => propCharacteristics.includes(n));
      const nodeMetadata = {
        propertyType: 'objectProperty',
        characteristics: propCharacteristics,
        functional: hasNodeChar('Functional', 'FUNCTIONAL'),
        inverseFunctional: hasNodeChar('InverseFunctional', 'INVERSE_FUNCTIONAL', 'InverseFunctionalProperty'),
        symmetric: hasNodeChar('Symmetric', 'SYMMETRIC', 'SymmetricProperty'),
        asymmetric: hasNodeChar('Asymmetric', 'ASYMMETRIC', 'AsymmetricProperty'),
        transitive: hasNodeChar('Transitive', 'TRANSITIVE', 'TransitiveProperty'),
        reflexive: hasNodeChar('Reflexive', 'REFLEXIVE', 'ReflexiveProperty'),
        irreflexive: hasNodeChar('Irreflexive', 'IRREFLEXIVE', 'IrreflexiveProperty'),
        domains: prop.domains || [],
        ranges: prop.ranges || [],
        inverseOf: prop.inverseOf || null,
        subPropertyOf: prop.subPropertyOf || []
      };

      nodes.push({
        id: propIri,
        label: propLabel,
        type: 'objectProperty',
        uri: propIri,
        metadata: nodeMetadata
      });

      const domains = prop.domains && Array.isArray(prop.domains) ? prop.domains : [];
      const ranges = prop.ranges && Array.isArray(prop.ranges) ? prop.ranges : [];

      console.log(`🔸 Property: ${propLabel}, domains: ${domains.length}, ranges: ${ranges.length}`);
      if (domains.length > 0) console.log('  Domains:', (domains as string[]).map((d: string) => d.split('#').pop()).join(', '));
      if (ranges.length > 0) console.log('  Ranges:', (ranges as string[]).map((r: string) => r.split('#').pop()).join(', '));

      // Domain edges (class → property)
      for (const domain of domains) {
        edges.push({
          id: `${domain}-domain-${propIri}`,
          from: domain,
          to: propIri,
          type: 'domain',
          label: 'domain'
        });
      }

      // Range edges (property → class)
      for (const range of ranges) {
        edges.push({
          id: `${propIri}-range-${range}`,
          from: propIri,
          to: range,
          type: 'range',
          label: 'range'
        });
      }

      // **NEW: Create direct property relationship edges between domain and range classes**
      // This shows the actual semantic relationship (e.g., UserAccount -hasCreator-> Tagging)
      if (domains.length > 0 && ranges.length > 0) {
        for (const domain of domains) {
          for (const range of ranges) {
            const edgeId = `${domain}-${propIri}-${range}`;
            
            // Extract characteristics for WebVOWL notation
            const characteristics = prop.characteristics || [];
            const hasChar = (...names: string[]) => names.some(n => characteristics.includes(n));
            const isFunctional = hasChar('Functional', 'FUNCTIONAL');
            const isInverseFunctional = hasChar('InverseFunctional', 'INVERSE_FUNCTIONAL', 'InverseFunctionalProperty');
            const isSymmetric = hasChar('Symmetric', 'SYMMETRIC', 'SymmetricProperty');
            const isAsymmetric = hasChar('Asymmetric', 'ASYMMETRIC', 'AsymmetricProperty');
            const isTransitive = hasChar('Transitive', 'TRANSITIVE', 'TransitiveProperty');
            const isReflexive = hasChar('Reflexive', 'REFLEXIVE', 'ReflexiveProperty');
            const isIrreflexive = hasChar('Irreflexive', 'IRREFLEXIVE', 'IrreflexiveProperty');

            edges.push({
              id: edgeId,
              from: domain,
              to: range,
              type: 'propertyRelation',
              label: propLabel, // Use the property label directly
              bidirectional: isSymmetric, // symmetric properties are visually bidirectional
              metadata: {
                propertyIri: propIri,
                propertyType: 'objectProperty',
                functional: isFunctional,
                inverseFunctional: isInverseFunctional,
                symmetric: isSymmetric,
                asymmetric: isAsymmetric,
                transitive: isTransitive,
                reflexive: isReflexive,
                irreflexive: isIrreflexive,
                characteristics: characteristics
              }
            });
            console.log(`  ✅ Created edge: ${domain.split('#').pop()} --${propLabel}--> ${range.split('#').pop()}${isFunctional ? ' (functional)' : ''}`);
          }
        }
      } else {
        console.warn(`  ⚠️ Property ${propLabel} has no domain or range - SKIPPING`);
      }

      // SubPropertyOf edges
      if (prop.subPropertyOf && Array.isArray(prop.subPropertyOf)) {
        for (const parent of prop.subPropertyOf) {
          edges.push({
            id: `${propIri}-subPropertyOf-${parent}`,
            from: propIri,
            to: parent,
            type: 'subPropertyOf',
            label: 'subPropertyOf'
          });
        }
      }

      // InverseOf edges
      if (prop.inverseOf) {
        edges.push({
          id: `${propIri}-inverseOf-${prop.inverseOf}`,
          from: propIri,
          to: prop.inverseOf,
          type: 'inverseOf',
          label: 'inverseOf',
          bidirectional: true
        });
      }
    }
  }

  /**
   * Process data properties into nodes and edges
   */
  private processDataProperties(dataPropsData: any[], nodes: OntologyNode[], edges: OntologyEdge[]): void {
    console.log('\n🟡🟡🟡 PROCESSING DATA PROPERTIES 🟡🟡🟡');
    console.log('Data Properties Count:', dataPropsData.length);
    console.log('Sample data:', dataPropsData.slice(0, 2));
    
    for (const prop of dataPropsData) {
      const propIri = prop.iri || prop.propertyIRI || prop.id || prop.uri;
      if (!propIri) continue;
      const propLabel = prop.label || propIri?.split('#').pop()?.split('/').pop() || 'DataProperty';

      // Capture data-property characteristics on the node for the details panel
      const dpCharacteristics = prop.characteristics || [];
      const dpHasChar = (...names: string[]) => names.some((n: string) => dpCharacteristics.includes(n));

      nodes.push({
        id: propIri,
        label: propLabel,
        type: 'dataProperty',
        uri: propIri,
        metadata: {
          propertyType: 'dataProperty',
          characteristics: dpCharacteristics,
          functional: dpHasChar('Functional', 'FUNCTIONAL'),
          domains: prop.domains || [],
          ranges: prop.ranges || [],
          subPropertyOf: prop.subPropertyOf || []
        }
      });

      const domains = prop.domains && Array.isArray(prop.domains) ? prop.domains : [];
      const ranges = prop.ranges && Array.isArray(prop.ranges) ? prop.ranges : [];

      console.log(`🔸 Data Property: ${propLabel}, domains: ${domains.length}, ranges: ${ranges.length}`);
      if (domains.length > 0) console.log('  Domains:', (domains as string[]).map((d: string) => d.split('#').pop()).join(', '));
      if (ranges.length > 0) console.log('  Ranges:', (ranges as string[]).map((r: string) => r.split('#').pop()).join(', '));

      // Domain edges (class → property)
      for (const domain of domains) {
        edges.push({
          id: `${domain}-domain-${propIri}`,
          from: domain,
          to: propIri,
          type: 'domain',
          label: 'domain'
        });
      }

      // Range (datatype) edges and nodes
      for (const range of ranges) {
        const datatypeId = range;
        // Ensure datatype node exists
        if (!nodes.find(n => n.id === datatypeId)) {
          const datatypeLabel = datatypeId.split('#').pop()?.split('/').pop() || 'Literal';
          nodes.push({
            id: datatypeId,
            label: datatypeLabel,
            type: 'datatype',
            uri: datatypeId
          });
          console.log(`[GraphDataFetchService] Created datatype node: ${datatypeLabel} (${datatypeId})`);
        }
        edges.push({
          id: `${propIri}-range-${datatypeId}`,
          from: propIri,
          to: datatypeId,
          type: 'range',
          label: 'range'
        });
      }

      // **NEW: Create direct data property edges between domain classes and datatypes**
      // This shows the actual data property relationship (e.g., Tag -tagLabel-> rdfs:Literal)
      if (domains.length > 0 && ranges.length > 0) {
        for (const domain of domains) {
          for (const range of ranges) {
            // Ensure datatype node exists
            if (!nodes.find(n => n.id === range)) {
              const datatypeLabel = range.split('#').pop()?.split('/').pop() || 'Literal';
              nodes.push({
                id: range,
                label: datatypeLabel,
                type: 'datatype',
                uri: range
              });
            }
            const edgeId = `${domain}-${propIri}-${range}`;
            
            // Extract characteristics for WebVOWL notation
            const characteristics = prop.characteristics || [];
            const isFunctional = characteristics.includes('Functional') || characteristics.includes('FUNCTIONAL');
            
            edges.push({
              id: edgeId,
              from: domain,
              to: range,
              type: 'propertyRelation',
              label: propLabel, // Use the property label directly
              metadata: {
                propertyIri: propIri,
                propertyType: 'dataProperty',
                functional: isFunctional,
                characteristics: characteristics
              }
            });
            console.log(`  ✅ Created data edge: ${domain.split('#').pop()} --${propLabel}--> ${range.split('#').pop()}${isFunctional ? ' (functional)' : ''}`);
          }
        }
      } else {
        console.warn(`  ⚠️ Data Property ${propLabel} has no domain or range - SKIPPING`);
      }

      // SubPropertyOf edges
      if (prop.subPropertyOf && Array.isArray(prop.subPropertyOf)) {
        for (const parent of prop.subPropertyOf) {
          edges.push({
            id: `${propIri}-subPropertyOf-${parent}`,
            from: propIri,
            to: parent,
            type: 'subPropertyOf',
            label: 'subPropertyOf'
          });
        }
      }
    }
  }

  /**
   * Process annotation properties into nodes and edges
   */
  private processAnnotationProperties(annotationPropsData: any[], nodes: OntologyNode[], edges: OntologyEdge[]): void {
    for (const prop of annotationPropsData) {
      const propIri = prop.iri || prop.propertyIRI || prop.id || prop.uri;
      if (!propIri) continue;
      nodes.push({
        id: propIri,
        label: prop.label || propIri?.split('#').pop()?.split('/').pop() || 'AnnotationProperty',
        type: 'annotation',
        uri: propIri
      });

      // Domain edges (annotation properties can have domains)
      if (prop.domain) {
        const domains = Array.isArray(prop.domain) ? prop.domain : [prop.domain];
        for (const domain of domains) {
          if (domain) {
            edges.push({
              id: `${domain}-${propIri}-domain`,
              from: domain,
              to: propIri,
              type: 'propertyRelation',
              label: 'has annotation'
            });
          }
        }
      }

      // Range edges (annotation properties can have ranges)
      if (prop.range) {
        const ranges = Array.isArray(prop.range) ? prop.range : [prop.range];
        for (const range of ranges) {
          if (range) {
            edges.push({
              id: `${propIri}-${range}-range`,
              from: propIri,
              to: range,
              type: 'propertyRelation',
              label: 'range'
            });
          }
        }
      }

      // SubPropertyOf edges
      if (prop.subPropertyOf && Array.isArray(prop.subPropertyOf)) {
        for (const parent of prop.subPropertyOf) {
          edges.push({
            id: `${propIri}-subPropertyOf-${parent}`,
            from: propIri,
            to: parent,
            type: 'custom',
            label: 'subPropertyOf'
          });
        }
      }
    }
  }

  /**
   * Process datatypes into nodes and edges
   */
  private processDatatypes(datatypesData: any[], nodes: OntologyNode[], edges: OntologyEdge[], connectedDatatypeIds?: Set<string>): void {
    console.log('[GraphDataFetchService] Processing datatypes:', datatypesData.length);
    console.log('[GraphDataFetchService] Connected datatypes:', connectedDatatypeIds?.size || 0);
    
    for (const datatype of datatypesData) {
      const datatypeIri = datatype.iri || datatype.datatypeIRI || datatype.id || datatype.uri;
      if (!datatypeIri) continue;
      
      // If we have a set of connected datatypes, only process those
      if (connectedDatatypeIds && !connectedDatatypeIds.has(datatypeIri)) {
        continue;
      }
      
      // Only add if not already present (might have been added from data property ranges)
      if (!nodes.find(n => n.id === datatypeIri)) {
        console.log('[GraphDataFetchService] Adding datatype node:', datatypeIri, datatype.label);
        nodes.push({
          id: datatypeIri,
          label: datatype.label || datatypeIri?.split('#').pop()?.split('/').pop() || 'Datatype',
          type: 'datatype',
          uri: datatypeIri
        });
      } else {
        console.log('[GraphDataFetchService] Datatype already exists, skipping:', datatypeIri);
      }

      // If there are any relationships (equivalent, subDatatype, etc.)
      if (datatype.equivalentDatatype && Array.isArray(datatype.equivalentDatatype)) {
        for (const equiv of datatype.equivalentDatatype) {
          edges.push({
            id: `${datatypeIri}-equivalentDatatype-${equiv}`,
            from: datatypeIri,
            to: equiv,
            type: 'equivalentClass',
            label: 'equivalentDatatype'
          });
        }
      }
    }
  }
}

export const createGraphDataFetchService = (apiBaseUrl: string, ontologyId: string, authToken: string | null) => {
  return new GraphDataFetchService(apiBaseUrl, ontologyId, authToken);
};
