/**
 * Fuzzy Ontology Model
 * Extends classical OWL ontologies with fuzzy membership degrees
 */

import { MembershipDegree, FuzzySet, TNorm, TCoNorm, MembershipFunctionParams } from './FuzzyLogic';

/**
 * Fuzzy individual with membership degrees in concepts
 */
export interface FuzzyIndividual {
  uri: string;
  label?: string;
  memberships: Map<string, MembershipDegree>; // conceptURI -> degree
  dataProperties: Map<string, FuzzyDataValue>;
}

/**
 * Fuzzy data value with degree
 */
export interface FuzzyDataValue {
  value: any;
  degree: MembershipDegree;
}

/**
 * Fuzzy concept (class) definition
 */
export interface FuzzyConcept {
  uri: string;
  label?: string;
  description?: string;
  membershipFunction?: MembershipFunctionParams;
  instances: Map<string, MembershipDegree>; // individualURI -> degree
  subClassOf?: string[]; // parent concept URIs
  equivalentTo?: FuzzyConceptExpression;
  disjointWith?: string[];
}

/**
 * Fuzzy concept expressions (complex concepts)
 */
export type FuzzyConceptExpression =
  | { type: 'atomic'; uri: string }
  | { type: 'conjunction'; concepts: FuzzyConceptExpression[]; norm: TNorm }
  | { type: 'disjunction'; concepts: FuzzyConceptExpression[]; conorm: TCoNorm }
  | { type: 'negation'; concept: FuzzyConceptExpression }
  | { type: 'existential'; property: string; concept: FuzzyConceptExpression }
  | { type: 'universal'; property: string; concept: FuzzyConceptExpression };

/**
 * Fuzzy object property
 */
export interface FuzzyObjectProperty {
  uri: string;
  label?: string;
  domain?: string; // concept URI
  range?: string; // concept URI
  relations: Map<string, Map<string, MembershipDegree>>; // subject -> object -> degree
  inverse?: string;
  transitive?: boolean;
  symmetric?: boolean;
  functional?: boolean;
}

/**
 * Fuzzy data property
 */
export interface FuzzyDataProperty {
  uri: string;
  label?: string;
  domain?: string;
  range?: string; // XSD datatype
  membershipFunction?: MembershipFunctionParams;
}

/**
 * Fuzzy axiom with degree
 */
export interface FuzzyAxiom {
  type: 'subClassOf' | 'equivalentClass' | 'disjointWith' | 'memberOf' | 'propertyAssertion';
  subject: string;
  predicate?: string;
  object: string;
  degree: MembershipDegree;
}

/**
 * Alpha-embedding for hierarchical fuzzy concepts
 */
export interface AlphaEmbedding {
  conceptURI: string;
  embedding: number[];
  alpha: number; // decay parameter
}

/**
 * Main Fuzzy Ontology class
 */
export class FuzzyOntology {
  private concepts: Map<string, FuzzyConcept>;
  private individuals: Map<string, FuzzyIndividual>;
  private objectProperties: Map<string, FuzzyObjectProperty>;
  private dataProperties: Map<string, FuzzyDataProperty>;
  private axioms: FuzzyAxiom[];
  private embeddings: Map<string, AlphaEmbedding>;

  private defaultTNorm: TNorm;
  private defaultTCoNorm: TCoNorm;

  constructor(config?: { tNorm?: TNorm; tCoNorm?: TCoNorm }) {
    this.concepts = new Map();
    this.individuals = new Map();
    this.objectProperties = new Map();
    this.dataProperties = new Map();
    this.axioms = [];
    this.embeddings = new Map();

    this.defaultTNorm = config?.tNorm || TNorm.PRODUCT;
    this.defaultTCoNorm = config?.tCoNorm || TCoNorm.PROBABILISTIC;
  }

  // Concept management
  addConcept(concept: FuzzyConcept): void {
    this.concepts.set(concept.uri, concept);
  }

  getConcept(uri: string): FuzzyConcept | undefined {
    return this.concepts.get(uri);
  }

  getAllConcepts(): FuzzyConcept[] {
    return Array.from(this.concepts.values());
  }

  // Individual management
  addIndividual(individual: FuzzyIndividual): void {
    this.individuals.set(individual.uri, individual);

    // Update concept instances
    for (const [conceptURI, degree] of individual.memberships) {
      const concept = this.concepts.get(conceptURI);
      if (concept) {
        concept.instances.set(individual.uri, degree);
      }
    }
  }

  getIndividual(uri: string): FuzzyIndividual | undefined {
    return this.individuals.get(uri);
  }

  getAllIndividuals(): FuzzyIndividual[] {
    return Array.from(this.individuals.values());
  }

  // Membership degree operations
  getMembershipDegree(individualURI: string, conceptURI: string): MembershipDegree {
    const individual = this.individuals.get(individualURI);
    if (!individual) return 0;

    const directDegree = individual.memberships.get(conceptURI);
    if (directDegree !== undefined) return directDegree;

    // Infer from subclass hierarchy
    return this.inferMembershipFromHierarchy(individualURI, conceptURI);
  }

  setMembershipDegree(individualURI: string, conceptURI: string, degree: MembershipDegree): void {
    let individual = this.individuals.get(individualURI);

    if (!individual) {
      individual = {
        uri: individualURI,
        memberships: new Map(),
        dataProperties: new Map()
      };
      this.individuals.set(individualURI, individual);
    }

    individual.memberships.set(conceptURI, degree);

    // Update concept instances
    const concept = this.concepts.get(conceptURI);
    if (concept) {
      concept.instances.set(individualURI, degree);
    }

    // Add axiom
    this.axioms.push({
      type: 'memberOf',
      subject: individualURI,
      object: conceptURI,
      degree
    });
  }

  // Hierarchy operations
  private inferMembershipFromHierarchy(individualURI: string, conceptURI: string): MembershipDegree {
    const concept = this.concepts.get(conceptURI);
    if (!concept || !concept.subClassOf) return 0;

    // Get membership in parent concepts and take max
    let maxDegree = 0;
    for (const parentURI of concept.subClassOf) {
      const parentDegree = this.getMembershipDegree(individualURI, parentURI);
      maxDegree = Math.max(maxDegree, parentDegree);
    }

    return maxDegree;
  }

  getSubConcepts(conceptURI: string): FuzzyConcept[] {
    const subConcepts: FuzzyConcept[] = [];

    for (const concept of this.concepts.values()) {
      if (concept.subClassOf?.includes(conceptURI)) {
        subConcepts.push(concept);
      }
    }

    return subConcepts;
  }

  getSuperConcepts(conceptURI: string): FuzzyConcept[] {
    const concept = this.concepts.get(conceptURI);
    if (!concept || !concept.subClassOf) return [];

    return concept.subClassOf
      .map(uri => this.concepts.get(uri))
      .filter(c => c !== undefined) as FuzzyConcept[];
  }

  // Property management
  addObjectProperty(property: FuzzyObjectProperty): void {
    this.objectProperties.set(property.uri, property);
  }

  addDataProperty(property: FuzzyDataProperty): void {
    this.dataProperties.set(property.uri, property);
  }

  getObjectPropertyDegree(subject: string, property: string, object: string): MembershipDegree {
    const prop = this.objectProperties.get(property);
    if (!prop) return 0;

    const objectMap = prop.relations.get(subject);
    return objectMap?.get(object) || 0;
  }

  setObjectPropertyDegree(
    subject: string,
    property: string,
    object: string,
    degree: MembershipDegree
  ): void {
    const prop = this.objectProperties.get(property);
    if (!prop) return;

    if (!prop.relations.has(subject)) {
      prop.relations.set(subject, new Map());
    }

    prop.relations.get(subject)!.set(object, degree);

    // Add axiom
    this.axioms.push({
      type: 'propertyAssertion',
      subject,
      predicate: property,
      object,
      degree
    });
  }

  // Fuzzy concept expression evaluation
  evaluateConceptExpression(
    individualURI: string,
    expression: FuzzyConceptExpression
  ): MembershipDegree {
    switch (expression.type) {
      case 'atomic':
        return this.getMembershipDegree(individualURI, expression.uri);

      case 'conjunction': {
        const degrees = expression.concepts.map(c =>
          this.evaluateConceptExpression(individualURI, c)
        );
        return degrees.reduce((acc, d) =>
          this.applyTNorm(acc, d, expression.norm), 1
        );
      }

      case 'disjunction': {
        const degrees = expression.concepts.map(c =>
          this.evaluateConceptExpression(individualURI, c)
        );
        return degrees.reduce((acc, d) =>
          this.applyTCoNorm(acc, d, expression.conorm), 0
        );
      }

      case 'negation':
        return 1 - this.evaluateConceptExpression(individualURI, expression.concept);

      case 'existential':
        return this.evaluateExistential(individualURI, expression.property, expression.concept);

      case 'universal':
        return this.evaluateUniversal(individualURI, expression.property, expression.concept);

      default:
        return 0;
    }
  }

  private applyTNorm(a: MembershipDegree, b: MembershipDegree, norm?: TNorm): MembershipDegree {
    const n = norm || this.defaultTNorm;
    switch (n) {
      case TNorm.PRODUCT:
        return a * b;
      case TNorm.GODEL:
        return Math.min(a, b);
      case TNorm.LUKASIEWICZ:
        return Math.max(0, a + b - 1);
    }
  }

  private applyTCoNorm(a: MembershipDegree, b: MembershipDegree, conorm?: TCoNorm): MembershipDegree {
    const c = conorm || this.defaultTCoNorm;
    switch (c) {
      case TCoNorm.PROBABILISTIC:
        return a + b - a * b;
      case TCoNorm.GODEL:
        return Math.max(a, b);
      case TCoNorm.LUKASIEWICZ:
        return Math.min(1, a + b);
    }
  }

  private evaluateExistential(
    individualURI: string,
    propertyURI: string,
    concept: FuzzyConceptExpression
  ): MembershipDegree {
    const property = this.objectProperties.get(propertyURI);
    if (!property) return 0;

    const relations = property.relations.get(individualURI);
    if (!relations) return 0;

    let maxDegree = 0;
    for (const [objectURI, relationDegree] of relations) {
      const conceptDegree = this.evaluateConceptExpression(objectURI, concept);
      const combined = this.applyTNorm(relationDegree, conceptDegree);
      maxDegree = Math.max(maxDegree, combined);
    }

    return maxDegree;
  }

  private evaluateUniversal(
    individualURI: string,
    propertyURI: string,
    concept: FuzzyConceptExpression
  ): MembershipDegree {
    const property = this.objectProperties.get(propertyURI);
    if (!property) return 1; // Vacuous truth

    const relations = property.relations.get(individualURI);
    if (!relations || relations.size === 0) return 1;

    let minDegree = 1;
    for (const [objectURI, relationDegree] of relations) {
      const conceptDegree = this.evaluateConceptExpression(objectURI, concept);
      // Implication: ¬r ∨ c
      const implication = Math.max(1 - relationDegree, conceptDegree);
      minDegree = Math.min(minDegree, implication);
    }

    return minDegree;
  }

  // Alpha-cut retrieval
  getInstances(conceptURI: string, alphaCut: number = 0): Map<string, MembershipDegree> {
    const concept = this.concepts.get(conceptURI);
    if (!concept) return new Map();

    const result = new Map<string, MembershipDegree>();

    for (const [individualURI, degree] of concept.instances) {
      if (degree >= alphaCut) {
        result.set(individualURI, degree);
      }
    }

    return result;
  }

  // Export methods
  toJSON(): any {
    return {
      concepts: Array.from(this.concepts.values()),
      individuals: Array.from(this.individuals.values()),
      objectProperties: Array.from(this.objectProperties.values()),
      dataProperties: Array.from(this.dataProperties.values()),
      axioms: this.axioms
    };
  }

  getStatistics() {
    return {
      concepts: this.concepts.size,
      individuals: this.individuals.size,
      objectProperties: this.objectProperties.size,
      dataProperties: this.dataProperties.size,
      axioms: this.axioms.length,
      avgMembershipDegree: this.calculateAverageMembership()
    };
  }

  private calculateAverageMembership(): number {
    let sum = 0;
    let count = 0;

    for (const individual of this.individuals.values()) {
      for (const degree of individual.memberships.values()) {
        sum += degree;
        count++;
      }
    }

    return count > 0 ? sum / count : 0;
  }
}
