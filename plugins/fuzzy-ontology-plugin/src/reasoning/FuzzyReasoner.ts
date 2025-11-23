/**
 * Fuzzy Reasoning Engine
 * Implements fuzzy inference, subsumption checking, and consistency verification
 */

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

/**
 * Fuzzy subsumption reasoning
 */
export class FuzzySubsumptionReasoner {

  constructor(private ontology: FuzzyOntology) {}

  /**
   * Check if concept C1 is subsumed by C2 with degree d
   * C1 ⊑_d C2 means for all individuals x: μ_C1(x) ≤ μ_C2(x) with degree d
   */
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

    // Check all instances of C1
    for (const [individualURI, degree1] of concept1.instances) {
      const degree2 = this.ontology.getMembershipDegree(individualURI, c2URI);

      // Subsumption degree: how much C1 membership implies C2 membership
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

  /**
   * Check equivalence between concepts
   */
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

  /**
   * Find most specific concepts for an individual
   */
  findMostSpecificConcepts(individualURI: string, threshold: number = 0.5): FuzzyConcept[] {
    const individual = this.ontology.getIndividual(individualURI);
    if (!individual) return [];

    const candidateConcepts = new Map<string, MembershipDegree>();

    // Collect all concepts with membership >= threshold
    for (const [conceptURI, degree] of individual.memberships) {
      if (degree >= threshold) {
        candidateConcepts.set(conceptURI, degree);
      }
    }

    // Filter out concepts that have more specific subconcepts
    const mostSpecific: FuzzyConcept[] = [];

    for (const [conceptURI, degree] of candidateConcepts) {
      const concept = this.ontology.getConcept(conceptURI);
      if (!concept) continue;

      let isSpecific = true;

      // Check if there's a more specific subconcept
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

/**
 * Fuzzy consistency checker
 */
export class FuzzyConsistencyChecker {

  constructor(private ontology: FuzzyOntology) {}

  /**
   * Check if an individual satisfies disjoint constraints
   */
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
          // Violation degree: how much the disjointness is violated
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

  /**
   * Check functional property constraints
   */
  checkFunctionalProperties(): ReasoningResult {
    let minConsistencyDegree = 1;
    const violations: string[] = [];

    for (const property of this.ontology['objectProperties'].values()) {
      if (!property.functional) continue;

      for (const [subject, objectMap] of property.relations) {
        if (objectMap.size > 1) {
          // Multiple objects for functional property - compute violation degree
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

  /**
   * Overall consistency check
   */
  checkConsistency(): ReasoningResult {
    const results: ReasoningResult[] = [];

    // Check disjointness for all individuals
    for (const individual of this.ontology.getAllIndividuals()) {
      results.push(this.checkDisjointness(individual.uri));
    }

    // Check functional properties
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

/**
 * Fuzzy query engine
 */
export class FuzzyQueryEngine {

  constructor(private ontology: FuzzyOntology) {}

  /**
   * Query individuals by concept with threshold
   */
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

    // Sort by degree descending
    results.sort((a, b) => b.degree - a.degree);

    return results.slice(0, maxResults);
  }

  /**
   * Query by fuzzy concept expression
   */
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

  /**
   * Top-k query: find k individuals with highest membership
   */
  topKQuery(conceptURI: string, k: number): Array<{ uri: string; degree: MembershipDegree }> {
    return this.queryByConcept(conceptURI, 0, k).map(r => ({ uri: r.uri, degree: r.degree }));
  }

  /**
   * Threshold query: find all individuals above threshold
   */
  thresholdQuery(
    conceptURI: string,
    threshold: number
  ): Array<{ uri: string; degree: MembershipDegree }> {
    return this.queryByConcept(conceptURI, threshold, Number.MAX_SAFE_INTEGER).map(r => ({
      uri: r.uri,
      degree: r.degree
    }));
  }

  /**
   * Range query: find individuals with membership in range
   */
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

/**
 * Alpha-cut based hierarchical reasoner
 */
export class AlphaCutReasoner {

  constructor(private ontology: FuzzyOntology, private alphaDecay: number = 0.8) {}

  /**
   * Compute alpha-embeddings for hierarchical concepts
   */
  computeAlphaEmbeddings(rootConceptURI: string, dimensions: number = 128): Map<string, AlphaEmbedding> {
    const embeddings = new Map<string, AlphaEmbedding>();
    const visited = new Set<string>();

    const computeRecursive = (conceptURI: string, depth: number) => {
      if (visited.has(conceptURI)) return;
      visited.set(conceptURI);

      const concept = this.ontology.getConcept(conceptURI);
      if (!concept) return;

      // Initialize embedding
      const embedding = new Array(dimensions).fill(0);

      // Get instances and compute embedding from membership degrees
      for (const [individualURI, degree] of concept.instances) {
        const hash = this.hashString(individualURI);
        for (let i = 0; i < dimensions; i++) {
          embedding[i] += degree * Math.sin(hash + i);
        }
      }

      // Apply alpha decay based on depth
      const alpha = Math.pow(this.alphaDecay, depth);

      // Normalize
      const norm = Math.sqrt(embedding.reduce((sum, val) => sum + val * val, 0));
      if (norm > 0) {
        for (let i = 0; i < dimensions; i++) {
          embedding[i] = (embedding[i] / norm) * alpha;
        }
      }

      embeddings.set(conceptURI, { conceptURI, embedding, alpha });

      // Recurse to subconcepts
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

  /**
   * Query using alpha-cuts
   */
  getAlphaCut(conceptURI: string, alpha: number): Set<string> {
    const instances = this.ontology.getInstances(conceptURI, alpha);
    return new Set(instances.keys());
  }

  /**
   * Get strong alpha-cut (membership > alpha)
   */
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
