

import {
  FuzzyOntology,
  FuzzyConcept,
  FuzzyIndividual,
  FuzzyConceptExpression,
  AlphaEmbedding
} from '../core/FuzzyOntology';
import { MembershipDegree, TNorm, TCoNorm } from '../core/FuzzyLogic';

export interface ReasoningResult {
  success: boolean;
  degree?: MembershipDegree;
  explanation?: string;
  trace?: ReasoningStep[];
}

export interface ReasoningStep {
  operation: string;
  inputs: any[];
  output: any;
  degree: MembershipDegree;
}

export class FuzzySubsumptionReasoner {

  constructor(private ontology: FuzzyOntology) {}

  checkSubsumption(c1URI: string, c2URI: string): ReasoningResult {
    const concept1 = this.ontology.getConcept(c1URI);
    const concept2 = this.ontology.getConcept(c2URI);

    if (!concept1 || !concept2) {
      return {
        success: false,
        explanation: 'One or both concepts not found'
      };
    }

    let minSubsumptionDegree = 1;
    const trace: ReasoningStep[] = [];

    for (const [individualURI, degree1] of concept1.instances) {
      const degree2 = this.ontology.getMembershipDegree(individualURI, c2URI);

      const subsumptionDegree = degree1 <= degree2 ? 1 : degree2 / degree1;
      minSubsumptionDegree = Math.min(minSubsumptionDegree, subsumptionDegree);

      trace.push({
        operation: 'subsumption_check',
        inputs: [individualURI, degree1, degree2],
        output: subsumptionDegree,
        degree: subsumptionDegree
      });
    }

    return {
      success: true,
      degree: minSubsumptionDegree,
      explanation: `${concept1.label || c1URI} is subsumed by ${concept2.label || c2URI} with degree ${minSubsumptionDegree.toFixed(3)}`,
      trace
    };
  }

  checkEquivalence(c1URI: string, c2URI: string): ReasoningResult {
    const sub1 = this.checkSubsumption(c1URI, c2URI);
    const sub2 = this.checkSubsumption(c2URI, c1URI);

    if (!sub1.success || !sub2.success) {
      return { success: false, explanation: 'Subsumption check failed' };
    }

    const equivalenceDegree = Math.min(sub1.degree!, sub2.degree!);

    return {
      success: true,
      degree: equivalenceDegree,
      explanation: `Concepts are equivalent with degree ${equivalenceDegree.toFixed(3)}`
    };
  }

  findMostSpecificConcepts(individualURI: string, threshold: number = 0.5): FuzzyConcept[] {
    const individual = this.ontology.getIndividual(individualURI);
    if (!individual) return [];

    const candidateConcepts = new Map<string, MembershipDegree>();

    for (const [conceptURI, degree] of individual.memberships) {
      if (degree >= threshold) {
        candidateConcepts.set(conceptURI, degree);
      }
    }

    const mostSpecific: FuzzyConcept[] = [];

    for (const [conceptURI, degree] of candidateConcepts) {
      const concept = this.ontology.getConcept(conceptURI);
      if (!concept) continue;

      let isSpecific = true;

      const subConcepts = this.ontology.getSubConcepts(conceptURI);
      for (const subConcept of subConcepts) {
        const subDegree = individual.memberships.get(subConcept.uri);
        if (subDegree && subDegree >= threshold) {
          isSpecific = false;
          break;
        }
      }

      if (isSpecific) {
        mostSpecific.push(concept);
      }
    }

    return mostSpecific;
  }
}

export class FuzzyConsistencyChecker {

  constructor(private ontology: FuzzyOntology) {}

  checkDisjointness(individualURI: string): ReasoningResult {
    const individual = this.ontology.getIndividual(individualURI);
    if (!individual) {
      return { success: false, explanation: 'Individual not found' };
    }

    const violations: string[] = [];
    let minConsistencyDegree = 1;

    for (const concept of this.ontology.getAllConcepts()) {
      if (!concept.disjointWith) continue;

      const degree1 = this.ontology.getMembershipDegree(individualURI, concept.uri);
      if (degree1 === 0) continue;

      for (const disjointURI of concept.disjointWith) {
        const degree2 = this.ontology.getMembershipDegree(individualURI, disjointURI);

        if (degree2 > 0) {

          const violationDegree = Math.min(degree1, degree2);
          const consistencyDegree = 1 - violationDegree;
          minConsistencyDegree = Math.min(minConsistencyDegree, consistencyDegree);

          violations.push(
            `${concept.label || concept.uri} (${degree1.toFixed(3)}) disjoint with ${disjointURI} (${degree2.toFixed(3)})`
          );
        }
      }
    }

    return {
      success: violations.length === 0,
      degree: minConsistencyDegree,
      explanation: violations.length > 0
        ? `Disjointness violations: ${violations.join(', ')}`
        : 'No disjointness violations'
    };
  }

  checkFunctionalProperties(): ReasoningResult {
    let minConsistencyDegree = 1;
    const violations: string[] = [];

    for (const property of this.ontology['objectProperties'].values()) {
      if (!property.functional) continue;

      for (const [subject, objectMap] of property.relations) {
        if (objectMap.size > 1) {

          const degrees = Array.from(objectMap.values());
          const maxDegree = Math.max(...degrees);
          const otherDegrees = degrees.filter(d => d !== maxDegree);

          if (otherDegrees.length > 0) {
            const violationDegree = Math.max(...otherDegrees);
            const consistencyDegree = 1 - violationDegree;
            minConsistencyDegree = Math.min(minConsistencyDegree, consistencyDegree);

            violations.push(
              `Functional property ${property.label || property.uri} has multiple objects for ${subject}`
            );
          }
        }
      }
    }

    return {
      success: violations.length === 0,
      degree: minConsistencyDegree,
      explanation: violations.length > 0
        ? `Functional property violations: ${violations.join(', ')}`
        : 'No functional property violations'
    };
  }

  checkConsistency(): ReasoningResult {
    const results: ReasoningResult[] = [];

    for (const individual of this.ontology.getAllIndividuals()) {
      results.push(this.checkDisjointness(individual.uri));
    }

    results.push(this.checkFunctionalProperties());

    const minDegree = Math.min(...results.map(r => r.degree || 1));
    const allSuccess = results.every(r => r.success);

    return {
      success: allSuccess,
      degree: minDegree,
      explanation: allSuccess
        ? `Ontology is consistent with degree ${minDegree.toFixed(3)}`
        : 'Ontology has consistency violations',
      trace: results.map(r => ({
        operation: 'consistency_check',
        inputs: [],
        output: r.explanation || '',
        degree: r.degree || 0
      }))
    };
  }
}

export class FuzzyQueryEngine {

  constructor(private ontology: FuzzyOntology) {}

  queryByConcept(
    conceptURI: string,
    minDegree: number = 0,
    maxResults: number = 100
  ): Array<{ uri: string; degree: MembershipDegree; individual: FuzzyIndividual }> {
    const results: Array<{ uri: string; degree: MembershipDegree; individual: FuzzyIndividual }> = [];

    for (const individual of this.ontology.getAllIndividuals()) {
      const degree = this.ontology.getMembershipDegree(individual.uri, conceptURI);

      if (degree >= minDegree) {
        results.push({ uri: individual.uri, degree, individual });
      }
    }

    results.sort((a, b) => b.degree - a.degree);

    return results.slice(0, maxResults);
  }

  queryByExpression(
    expression: FuzzyConceptExpression,
    minDegree: number = 0,
    maxResults: number = 100
  ): Array<{ uri: string; degree: MembershipDegree; individual: FuzzyIndividual }> {
    const results: Array<{ uri: string; degree: MembershipDegree; individual: FuzzyIndividual }> = [];

    for (const individual of this.ontology.getAllIndividuals()) {
      const degree = this.ontology.evaluateConceptExpression(individual.uri, expression);

      if (degree >= minDegree) {
        results.push({ uri: individual.uri, degree, individual });
      }
    }

    results.sort((a, b) => b.degree - a.degree);
    return results.slice(0, maxResults);
  }

  topKQuery(conceptURI: string, k: number): Array<{ uri: string; degree: MembershipDegree }> {
    return this.queryByConcept(conceptURI, 0, k).map(r => ({ uri: r.uri, degree: r.degree }));
  }

  thresholdQuery(
    conceptURI: string,
    threshold: number
  ): Array<{ uri: string; degree: MembershipDegree }> {
    return this.queryByConcept(conceptURI, threshold, Number.MAX_SAFE_INTEGER).map(r => ({
      uri: r.uri,
      degree: r.degree
    }));
  }

  rangeQuery(
    conceptURI: string,
    minDegree: number,
    maxDegree: number
  ): Array<{ uri: string; degree: MembershipDegree }> {
    const results = this.queryByConcept(conceptURI, minDegree, Number.MAX_SAFE_INTEGER);
    return results
      .filter(r => r.degree <= maxDegree)
      .map(r => ({ uri: r.uri, degree: r.degree }));
  }
}

export class AlphaCutReasoner {

  constructor(private ontology: FuzzyOntology, private alphaDecay: number = 0.8) {}

  computeAlphaEmbeddings(rootConceptURI: string, dimensions: number = 128): Map<string, AlphaEmbedding> {
    const embeddings = new Map<string, AlphaEmbedding>();
    const visited = new Set<string>();

    const computeRecursive = (conceptURI: string, depth: number) => {
      if (visited.has(conceptURI)) return;
      visited.add(conceptURI);

      const concept = this.ontology.getConcept(conceptURI);
      if (!concept) return;

      const embedding = new Array(dimensions).fill(0);

      for (const [individualURI, degree] of concept.instances) {
        const hash = this.hashString(individualURI);
        for (let i = 0; i < dimensions; i++) {
          embedding[i] += degree * Math.sin(hash + i);
        }
      }

      const alpha = Math.pow(this.alphaDecay, depth);

      const norm = Math.sqrt(embedding.reduce((sum, val) => sum + val * val, 0));
      if (norm > 0) {
        for (let i = 0; i < dimensions; i++) {
          embedding[i] = (embedding[i] / norm) * alpha;
        }
      }

      embeddings.set(conceptURI, { conceptURI, embedding, alpha });

      const subConcepts = this.ontology.getSubConcepts(conceptURI);
      for (const subConcept of subConcepts) {
        computeRecursive(subConcept.uri, depth + 1);
      }
    };

    computeRecursive(rootConceptURI, 0);
    return embeddings;
  }

  private hashString(str: string): number {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      const char = str.charCodeAt(i);
      hash = (hash << 5) - hash + char;
      hash = hash & hash;
    }
    return hash;
  }

  getAlphaCut(conceptURI: string, alpha: number): Set<string> {
    const instances = this.ontology.getInstances(conceptURI, alpha);
    return new Set(instances.keys());
  }

  getStrongAlphaCut(conceptURI: string, alpha: number): Set<string> {
    const instances = this.ontology.getInstances(conceptURI, 0);
    const result = new Set<string>();

    for (const [individualURI, degree] of instances) {
      if (degree > alpha) {
        result.add(individualURI);
      }
    }

    return result;
  }
}
