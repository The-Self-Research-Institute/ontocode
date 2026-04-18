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
    return {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${this.authToken}`
    };
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

      console.log('[GraphDataFetchService] 📊📊📊 RAW DATA FETCHED:', {
        allClasses: classesData.length,
        individuals: individualsData.length,
        objectProps: objectPropsData.length,
        dataProps: dataPropsData.length,
        annotationProps: annotationPropsData.length,
        datatypes: datatypesData.length
      });
      
      console.log('[GraphDataFetchService] 📋 ALL CLASSES:', classesData.map((c, i) => `${i+1}. ${c.label || c.name || c.iri}`));

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
      
      console.log('[GraphDataFetchService] ✅ Transformation complete:', {
        totalNodes: nodes.length,
        totalEdges: edges.length,
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
    
    // Fallback: fetch only top-level classes
    const topLevelUrl = `${this.apiBaseUrl}/api/ontology/classes/top-level/${this.ontologyId}?limit=10000`;
    console.log('[GraphDataFetchService] 📦 Falling back to top-level fetch from:', topLevelUrl);
    
    try {
      const response = await fetch(topLevelUrl, { headers: this.headers });
      
      if (!response.ok) {
        console.warn('[GraphDataFetchService] ❌ Top-level classes endpoint failed:', response.status);
        return [];
      }
      
      const data = await response.json();
      const topLevelClasses = data.success && data.classes ? data.classes : [];
      console.log('[GraphDataFetchService] ✅ Top-level classes extracted:', topLevelClasses.length);
      console.log('[GraphDataFetchService] ⚠️ NOTE: Showing top-level classes only (bulk endpoint unavailable)');
      
      return topLevelClasses;
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
      
      nodes.push({
        id: propIri,
        label: propLabel,
        type: 'objectProperty',
        uri: propIri
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
            const isFunctional = characteristics.includes('Functional') || characteristics.includes('FUNCTIONAL');
            const isInverseFunctional = characteristics.includes('InverseFunctional') || characteristics.includes('INVERSE_FUNCTIONAL');
            
            edges.push({
              id: edgeId,
              from: domain,
              to: range,
              type: 'propertyRelation',
              label: propLabel, // Use the property label directly
              metadata: {
                propertyIri: propIri,
                propertyType: 'objectProperty',
                functional: isFunctional,
                inverseFunctional: isInverseFunctional,
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
      
      nodes.push({
        id: propIri,
        label: propLabel,
        type: 'dataProperty',
        uri: propIri
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
