import apiClient from './apiClient';

export interface MutationOp {
  type: string;
  iri: string;
  label?: string;
  parent?: string;
  property?: string;
  value?: string;
  target?: string;
  classIri?: string;
}

export const ontologyMutationService = {
  /**
   * Apply mutations to the ontology
   * @param projectId - The project ID
   * @param ops - The mutation operations
   * @param draft - If true, records as draft without applying to GraphDB (default: true)
   * @param userId - User ID for tracking
   * @param username - Username for tracking
   * @param sessionId - Session ID for tracking related operations
   */
  async applyMutations(projectId: string, ops: MutationOp[], draft: boolean = true, 
                      userId?: string, username?: string, sessionId?: string): Promise<void> {
    await apiClient.post(`/api/ontology/mutations/${projectId}?draft=${draft}`, { 
      ops,
      userId: userId || 'anonymous',
      username: username || 'Anonymous',
      sessionId: sessionId || `session_${Date.now()}`
    });
  },

  /**
   * Create a new class
   */
  async createClass(projectId: string, iri: string, label: string, parentIri: string, 
                   userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createClass',
      iri,
      label,
      parent: parentIri
    }], true, userId, username);
  },

  /**
   * Delete a class
   */
  async deleteClass(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteClass',
      iri
    }], true, userId, username);
  },

  /**
   * Update class label
   */
  async updateClassLabel(projectId: string, iri: string, label: string, 
                        userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateClassLabel',
      iri,
      label
    }], true, userId, username);
  },

  /**
   * Add annotation to an entity
   */
  async addAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string,
                     userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAnnotation',
      iri: entityIri,
      property: propertyIri,
      value
    }], true, userId, username);
  },

  /**
   * Delete annotation from an entity
   */
  async deleteAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string,
                        userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAnnotation',
      iri: entityIri,
      property: propertyIri,
      value
    }], true, userId, username);
  },

  /**
   * Add SubClassOf axiom
   */
  async addSubClassOf(projectId: string, classIri: string, superClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addSubClassOf',
      iri: classIri,
      target: superClassIri
    }]);
  },

  /**
   * Delete SubClassOf axiom
   */
  async deleteSubClassOf(projectId: string, classIri: string, superClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteSubClassOf',
      iri: classIri,
      target: superClassIri
    }]);
  },

  /**
   * Add EquivalentClass axiom
   */
  async addEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addEquivalentClass',
      iri: classIri,
      target: equivalentClassIri
    }]);
  },

  /**
   * Delete EquivalentClass axiom
   */
  async deleteEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteEquivalentClass',
      iri: classIri,
      target: equivalentClassIri
    }]);
  },

  /**
   * Add DisjointWith axiom
   */
  async addDisjointWith(projectId: string, classIri: string, disjointClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }]);
  },

  /**
   * Delete DisjointWith axiom
   */
  async deleteDisjointWith(projectId: string, classIri: string, disjointClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }]);
  },

  /**
   * Create a new individual
   */
  async createIndividual(projectId: string, iri: string, label: string, classIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createIndividual',
      iri,
      label,
      classIri
    }]);
  },

  /**
   * Delete an individual
   */
  async deleteIndividual(projectId: string, iri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteIndividual',
      iri
    }]);
  },

  /**
   * Create a new object property
   */
  async createObjectProperty(projectId: string, iri: string, label: string, parentIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createObjectProperty',
      iri,
      label,
      parent: parentIri
    }]);
  },

  /**
   * Delete an object property
   */
  async deleteObjectProperty(projectId: string, iri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteObjectProperty',
      iri
    }]);
  },

  /**
   * Create a new datatype
   */
  async createDatatype(projectId: string, iri: string, label: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createDatatype',
      iri,
      label
    }]);
  },

  /**
   * Delete a datatype
   */
  async deleteDatatype(projectId: string, iri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDatatype',
      iri
    }]);
  },

  // --- Property Mutations ---

  async addPropertyDomain(projectId: string, propertyIri: string, domainIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addPropertyDomain', iri: propertyIri, target: domainIri }]);
  },
  async deletePropertyDomain(projectId: string, propertyIri: string, domainIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deletePropertyDomain', iri: propertyIri, target: domainIri }]);
  },

  async addPropertyRange(projectId: string, propertyIri: string, rangeIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addPropertyRange', iri: propertyIri, target: rangeIri }]);
  },
  async deletePropertyRange(projectId: string, propertyIri: string, rangeIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deletePropertyRange', iri: propertyIri, target: rangeIri }]);
  },

  async addSubPropertyOf(projectId: string, propertyIri: string, superPropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addSubPropertyOf', iri: propertyIri, target: superPropertyIri }]);
  },
  async deleteSubPropertyOf(projectId: string, propertyIri: string, superPropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteSubPropertyOf', iri: propertyIri, target: superPropertyIri }]);
  },

  async addInverseProperty(projectId: string, propertyIri: string, inversePropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addInverseProperty', iri: propertyIri, target: inversePropertyIri }]);
  },
  async deleteInverseProperty(projectId: string, propertyIri: string, inversePropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteInverseProperty', iri: propertyIri, target: inversePropertyIri }]);
  },

  async addDisjointProperty(projectId: string, propertyIri: string, disjointPropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addDisjointProperty', iri: propertyIri, target: disjointPropertyIri }]);
  },
  async deleteDisjointProperty(projectId: string, propertyIri: string, disjointPropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteDisjointProperty', iri: propertyIri, target: disjointPropertyIri }]);
  },

  async addEquivalentProperty(projectId: string, propertyIri: string, equivalentPropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addEquivalentProperty', iri: propertyIri, target: equivalentPropertyIri }]);
  },
  async deleteEquivalentProperty(projectId: string, propertyIri: string, equivalentPropertyIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteEquivalentProperty', iri: propertyIri, target: equivalentPropertyIri }]);
  },

  async addPropertyChain(projectId: string, propertyIri: string, chainExpression: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addPropertyChain', iri: propertyIri, value: chainExpression }]);
  },
  async deletePropertyChain(projectId: string, propertyIri: string, chainExpression: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deletePropertyChain', iri: propertyIri, value: chainExpression }]);
  },

  async addCharacteristic(projectId: string, propertyIri: string, characteristicIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addCharacteristic', iri: propertyIri, target: characteristicIri }]);
  },
  async deleteCharacteristic(projectId: string, propertyIri: string, characteristicIri: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteCharacteristic', iri: propertyIri, target: characteristicIri }]);
  },

  /**
   * Make siblings disjoint - adds pairwise disjointWith axioms
   */
  async makeSiblingsDisjoint(projectId: string, classIds: string[]): Promise<void> {
    await apiClient.post(`/api/ontology/make-siblings-disjoint/${projectId}`, { classIds });
  },

  /**
   * Add an axiom using Manchester Syntax
   */
  async addAxiom(projectId: string, classIri: string, type: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith', expression: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAxiom',
      classIri,
      target: expression, // We use 'target' for the expression
      value: type // We use 'value' for the axiom type
    }]);
  },
};

export default ontologyMutationService;
