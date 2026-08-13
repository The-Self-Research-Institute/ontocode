

import { OntologyNode, OntologyEdge } from '../types';
import { authHeaders } from '../utils/authHeaders';

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

  async fetchGraphData(): Promise<{ nodes: OntologyNode[], edges: OntologyEdge[] }> {
    console.log('[GraphDataFetchService] 🚀🚀🚀 STARTING FETCH - ontologyId:', this.ontologyId, 'apiBaseUrl:', this.apiBaseUrl);

    try {

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

      const nodes: OntologyNode[] = [];
      const edges: OntologyEdge[] = [];
      const processedClasses = new Set<string>();

      const owlThingIri = 'http://www.w3.org/2002/07/owl#Thing';
      nodes.push({
        id: owlThingIri,
        label: 'Thing',
        type: 'class',
        uri: owlThingIri
      });
      processedClasses.add(owlThingIri);

      for (const cls of classesData) {
        this.processClassNode(cls, nodes, edges, processedClasses);
      }

      this.ensureHierarchyEndpointNodes(nodes, edges, processedClasses);

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
            label: 'subClassOf',

            metadata: { synthetic: true }
          });
          orphansAdopted++;
        }
      }
      if (orphansAdopted > 0) {
        console.log(`[GraphDataFetchService] 🌳 Adopted ${orphansAdopted} orphan classes under owl:Thing`);
      }

      this.processIndividuals(individualsData, nodes, edges);

      this.processObjectProperties(objectPropsData, nodes, edges);
      this.processDataProperties(dataPropsData, nodes, edges);
      this.processAnnotationProperties(annotationPropsData, nodes, edges);

      const connectedDatatypeIds = new Set<string>();
      for (const edge of edges) {
        if (edge.type === 'range' && nodes.find(n => n.id === edge.to && n.type === 'datatype')) {
          connectedDatatypeIds.add(edge.to);
        }
      }

      this.processDatatypes(datatypesData, nodes, edges, connectedDatatypeIds);

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

  private async fetchAllClassesRecursively(): Promise<any[]> {

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

      const childrenWithParent = childrenArray.map(child => ({
        ...child,
        subClassOf: [parentIri]
      }));

      const allDescendants: any[] = [...childrenWithParent];

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

  private addRestrictionEdges(classIri: string, restrictions: any[] | undefined, edges: OntologyEdge[]): void {
    if (!Array.isArray(restrictions)) return;
    for (const r of restrictions) {
      const fillerIri = r?.fillerIri;
      if (!fillerIri) continue;
      const restrictionType = r.restrictionType || 'some';
      const propertyLabel = r.propertyLabel || r.propertyIri?.split('#').pop()?.split('/').pop() || 'property';
      const label = r.cardinality
        ? `${propertyLabel} ${restrictionType} ${r.cardinality}`
        : `${propertyLabel} ${restrictionType}`;
      edges.push({
        id: `${classIri}-restriction-${r.propertyIri}-${restrictionType}-${fillerIri}`,
        from: classIri,
        to: fillerIri,
        type: 'restriction',
        label,
        metadata: {
          propertyIri: r.propertyIri,
          propertyLabel,
          restrictionType,
          cardinality: r.cardinality,
          axiomType: r.axiomType
        }
      });
    }
  }

  private addPropertyChainEdges(propIri: string, propertyChains: string[] | undefined, edges: OntologyEdge[]): void {
    if (!Array.isArray(propertyChains)) return;
    propertyChains.forEach((chain, chainIndex) => {
      if (typeof chain !== 'string') return;
      const members = chain.split(' o ').map(s => s.trim()).filter(Boolean);
      members.forEach((memberIri, position) => {
        if (!memberIri || memberIri === propIri) return;
        edges.push({
          id: `${propIri}-propertyChain-${chainIndex}-${position}-${memberIri}`,
          from: propIri,
          to: memberIri,
          type: 'propertyChain',
          label: `chain[${position + 1}]`,
          metadata: { chainIndex, position, chainLength: members.length }
        });
      });
    });
  }

  private processClassNode(
    classData: any,
    nodes: OntologyNode[],
    edges: OntologyEdge[],
    processedClasses: Set<string>
  ): void {
    const classIri = classData.classIri || classData.classIRI || classData.iri || classData.id || classData.uri;

    if (!classIri) {
      console.warn('[GraphDataFetchService] Skipping class with no IRI:', classData);
      return;
    }

    if (processedClasses.has(classIri)) return;

    nodes.push({
      id: classIri,
      label: classData.label || classIri?.split('#').pop()?.split('/').pop() || 'Class',
      type: 'class',
      uri: classIri
    });
    processedClasses.add(classIri);

    const superClasses = classData.subClassOf || classData.superClasses || classData.parents || [];

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

    const equivalentClasses = classData.equivalentClass || classData.equivalentClasses || [];
    for (const equiv of equivalentClasses) {
      const equivIri = typeof equiv === 'string' ? equiv : equiv?.iri;
      if (!equivIri) continue;
      edges.push({
        id: `${classIri}-equivalentClass-${equivIri}`,
        from: classIri,
        to: equivIri,
        type: 'equivalentClass',
        label: 'equivalentClass'
      });
    }

    const disjointClasses = classData.disjointWith || classData.disjointClasses || [];
    for (const disjoint of disjointClasses) {
      const disjointIri = typeof disjoint === 'string' ? disjoint : disjoint?.iri;
      if (!disjointIri) continue;
      edges.push({
        id: `${classIri}-disjointWith-${disjointIri}`,
        from: classIri,
        to: disjointIri,
        type: 'disjointWith',
        label: 'disjointWith'
      });
    }

    this.addRestrictionEdges(classIri, classData.restrictions, edges);

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

  private async processClassRecursively(
    classData: any,
    nodes: OntologyNode[],
    edges: OntologyEdge[],
    processedClasses: Set<string>
  ): Promise<void> {
    const classIri = classData.iri || classData.classIRI || classData.id || classData.uri;
    if (!classIri || processedClasses.has(classIri)) return;

    nodes.push({
      id: classIri,
      label: classData.label || classIri?.split('#').pop()?.split('/').pop() || 'Class',
      type: 'class',
      uri: classIri
    });
    processedClasses.add(classIri);

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

    const equivalentClasses = classData.equivalentClass || classData.equivalentClasses || [];
    for (const equiv of equivalentClasses) {
      const equivIri = typeof equiv === 'string' ? equiv : equiv?.iri;
      if (!equivIri) continue;
      edges.push({
        id: `${classIri}-equivalentClass-${equivIri}`,
        from: classIri,
        to: equivIri,
        type: 'equivalentClass',
        label: 'equivalentClass'
      });
    }

    const disjointClasses = classData.disjointWith || classData.disjointClasses || [];
    for (const disjoint of disjointClasses) {
      const disjointIri = typeof disjoint === 'string' ? disjoint : disjoint?.iri;
      if (!disjointIri) continue;
      edges.push({
        id: `${classIri}-disjointWith-${disjointIri}`,
        from: classIri,
        to: disjointIri,
        type: 'disjointWith',
        label: 'disjointWith'
      });
    }

    this.addRestrictionEdges(classIri, classData.restrictions, edges);

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

  private processObjectProperties(objectPropsData: any[], nodes: OntologyNode[], edges: OntologyEdge[]): void {
    console.log('\n🔵🔵🔵 PROCESSING OBJECT PROPERTIES 🔵🔵🔵');
    console.log('Object Properties Count:', objectPropsData.length);
    console.log('Sample raw property data:', objectPropsData.slice(0, 2));
    console.log('First property full object:', JSON.stringify(objectPropsData[0], null, 2));

    for (const prop of objectPropsData) {
      const propIri = prop.iri || prop.propertyIRI || prop.id || prop.uri;
      if (!propIri) continue;
      const propLabel = prop.label || propIri?.split('#').pop()?.split('/').pop() || 'ObjectProperty';

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

        inverseOf: (prop.inverseProperties && prop.inverseProperties[0]) || null,
        subPropertyOf: prop.superProperties || []
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

      for (const domain of domains) {
        edges.push({
          id: `${domain}-domain-${propIri}`,
          from: domain,
          to: propIri,
          type: 'domain',
          label: 'domain'
        });
      }

      for (const range of ranges) {
        edges.push({
          id: `${propIri}-range-${range}`,
          from: propIri,
          to: range,
          type: 'range',
          label: 'range'
        });
      }

      if (domains.length > 0 && ranges.length > 0) {
        for (const domain of domains) {
          for (const range of ranges) {
            const edgeId = `${domain}-${propIri}-${range}`;

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

        const owlThing = 'http://www.w3.org/2002/07/owl#Thing';
        const fallbackDomains = domains.length > 0 ? domains : [owlThing];
        const fallbackRanges = ranges.length > 0 ? ranges : [owlThing];
        for (const domain of fallbackDomains) {
          for (const range of fallbackRanges) {
            edges.push({
              id: `${domain}-${propIri}-${range}`,
              from: domain,
              to: range,
              type: 'propertyRelation',
              label: propLabel,
              metadata: {
                propertyIri: propIri,
                propertyType: 'objectProperty',
                characteristics: prop.characteristics || [],
                vowlOnly: true
              }
            });
          }
        }
        console.log(`  ➕ Property ${propLabel} missing domain/range — added VOWL-only owl:Thing fallback edge`);
      }

      const superProperties = prop.superProperties || prop.subPropertyOf || [];
      if (Array.isArray(superProperties)) {
        for (const parent of superProperties) {
          edges.push({
            id: `${propIri}-subPropertyOf-${parent}`,
            from: propIri,
            to: parent,
            type: 'subPropertyOf',
            label: 'subPropertyOf'
          });
        }
      }

      const inverseProps = prop.inverseProperties || (prop.inverseOf ? [prop.inverseOf] : []);
      for (const inverse of inverseProps) {
        if (!inverse) continue;
        edges.push({
          id: `${propIri}-inverseOf-${inverse}`,
          from: propIri,
          to: inverse,
          type: 'inverseOf',
          label: 'inverseOf',
          bidirectional: true
        });
      }

      for (const equiv of prop.equivalentProperties || []) {
        if (!equiv) continue;
        edges.push({
          id: `${propIri}-equivalentProperty-${equiv}`,
          from: propIri,
          to: equiv,
          type: 'equivalentClass',
          label: 'equivalentProperty'
        });
      }
      for (const disjoint of prop.disjointProperties || []) {
        if (!disjoint) continue;
        edges.push({
          id: `${propIri}-disjointWithProperty-${disjoint}`,
          from: propIri,
          to: disjoint,
          type: 'disjointWith',
          label: 'propertyDisjointWith'
        });
      }

      this.addPropertyChainEdges(propIri, prop.propertyChains, edges);
    }
  }

  private processDataProperties(dataPropsData: any[], nodes: OntologyNode[], edges: OntologyEdge[]): void {
    console.log('\n🟡🟡🟡 PROCESSING DATA PROPERTIES 🟡🟡🟡');
    console.log('Data Properties Count:', dataPropsData.length);
    console.log('Sample data:', dataPropsData.slice(0, 2));

    for (const prop of dataPropsData) {
      const propIri = prop.iri || prop.propertyIRI || prop.id || prop.uri;
      if (!propIri) continue;
      const propLabel = prop.label || propIri?.split('#').pop()?.split('/').pop() || 'DataProperty';

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

          subPropertyOf: prop.superProperties || prop.subPropertyOf || []
        }
      });

      const domains = prop.domains && Array.isArray(prop.domains) ? prop.domains : [];
      const ranges = prop.ranges && Array.isArray(prop.ranges) ? prop.ranges : [];

      console.log(`🔸 Data Property: ${propLabel}, domains: ${domains.length}, ranges: ${ranges.length}`);
      if (domains.length > 0) console.log('  Domains:', (domains as string[]).map((d: string) => d.split('#').pop()).join(', '));
      if (ranges.length > 0) console.log('  Ranges:', (ranges as string[]).map((r: string) => r.split('#').pop()).join(', '));

      for (const domain of domains) {
        edges.push({
          id: `${domain}-domain-${propIri}`,
          from: domain,
          to: propIri,
          type: 'domain',
          label: 'domain'
        });
      }

      for (const range of ranges) {
        const datatypeId = range;

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

      if (domains.length > 0 && ranges.length > 0) {
        for (const domain of domains) {
          for (const range of ranges) {

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

        const owlThing = 'http://www.w3.org/2002/07/owl#Thing';
        const rdfsLiteral = 'http://www.w3.org/2000/01/rdf-schema#Literal';
        const fallbackDomains = domains.length > 0 ? domains : [owlThing];
        const fallbackRanges = ranges.length > 0 ? ranges : [rdfsLiteral];
        for (const domain of fallbackDomains) {
          for (const range of fallbackRanges) {
            if (!nodes.find(n => n.id === range)) {
              nodes.push({
                id: range,
                label: range.split('#').pop()?.split('/').pop() || 'Literal',
                type: 'datatype',
                uri: range
              });
            }
            edges.push({
              id: `${domain}-${propIri}-${range}`,
              from: domain,
              to: range,
              type: 'propertyRelation',
              label: propLabel,
              metadata: {
                propertyIri: propIri,
                propertyType: 'dataProperty',
                characteristics: prop.characteristics || [],
                vowlOnly: true
              }
            });
          }
        }
        console.log(`  ➕ Data property ${propLabel} missing domain/range — added VOWL-only Thing/Literal fallback edge`);
      }

      const dpSuperProperties = prop.superProperties || prop.subPropertyOf || [];
      if (Array.isArray(dpSuperProperties)) {
        for (const parent of dpSuperProperties) {
          edges.push({
            id: `${propIri}-subPropertyOf-${parent}`,
            from: propIri,
            to: parent,
            type: 'subPropertyOf',
            label: 'subPropertyOf'
          });
        }
      }

      for (const equiv of prop.equivalentProperties || []) {
        if (!equiv) continue;
        edges.push({
          id: `${propIri}-equivalentProperty-${equiv}`,
          from: propIri,
          to: equiv,
          type: 'equivalentClass',
          label: 'equivalentProperty'
        });
      }
      for (const disjoint of prop.disjointProperties || []) {
        if (!disjoint) continue;
        edges.push({
          id: `${propIri}-disjointWithProperty-${disjoint}`,
          from: propIri,
          to: disjoint,
          type: 'disjointWith',
          label: 'propertyDisjointWith'
        });
      }
    }
  }

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

  private processDatatypes(datatypesData: any[], nodes: OntologyNode[], edges: OntologyEdge[], connectedDatatypeIds?: Set<string>): void {
    console.log('[GraphDataFetchService] Processing datatypes:', datatypesData.length);
    console.log('[GraphDataFetchService] Connected datatypes:', connectedDatatypeIds?.size || 0);

    for (const datatype of datatypesData) {
      const datatypeIri = datatype.iri || datatype.datatypeIRI || datatype.id || datatype.uri;
      if (!datatypeIri) continue;

      if (connectedDatatypeIds && !connectedDatatypeIds.has(datatypeIri)) {
        continue;
      }

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
