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

// Global flag to control real-time sync behavior
// When true: changes apply immediately (for shared files)
// When false: changes save as drafts (for private files)
let realTimeSyncEnabled = false;

export const ontologyMutationService = {
  /**
   * Enable or disable real-time sync mode
   * Should be enabled for shared files, disabled for private files
   */
  setRealTimeSync(enabled: boolean) {
    realTimeSyncEnabled = enabled;
    console.log(`[MutationService] Real-time sync ${enabled ? 'ENABLED' : 'DISABLED'}`);
  },

  /**
   * Apply mutations to the ontology
   * @param projectId - The project ID
   * @param ops - The mutation operations
   * @param draft - If true, records as draft without applying to GraphDB (default: uses realTimeSyncEnabled flag)
   * @param userId - User ID for tracking
   * @param username - Username for tracking
   * @param sessionId - Session ID for tracking related operations
   */
  async applyMutations(projectId: string, ops: MutationOp[], draft?: boolean, 
                      userId?: string, username?: string, sessionId?: string): Promise<void> {
    // Use the draft parameter if explicitly provided, otherwise use the inverse of realTimeSyncEnabled
    const useDraft = draft !== undefined ? draft : !realTimeSyncEnabled;
    
    console.log(`[MutationService] 🔄 Applying mutations to ${projectId}`, {
      opsCount: ops.length,
      draft: useDraft,
      realTimeSyncEnabled,
      ops: ops.map(o => o.type)
    });
    
    await apiClient.post(`/api/ontology/mutations/${projectId}?draft=${useDraft}`, { 
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
    }], undefined, userId, username);
  },

  /**
   * Delete a class
   */
  async deleteClass(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteClass',
      iri
    }], undefined, userId, username);
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
    }], undefined, userId, username);
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
    }], undefined, userId, username);
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
    }], undefined, userId, username);
  },

  /**
   * Update annotation value (atomic operation)
   */
  async updateAnnotation(projectId: string, entityIri: string, propertyIri: string, newValue: string,
                        userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateAnnotation',
      iri: entityIri,
      property: propertyIri,
      value: newValue
    }], undefined, userId, username);
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
  async deleteIndividual(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteIndividual',
      iri
    }], undefined, userId, username);
  },

  /**
   * Create a new object property
   */
  async createObjectProperty(projectId: string, iri: string, label: string, parentIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createObjectProperty',
      iri,
      label,
      parent: parentIri
    }], undefined, userId, username);
  },

  /**
   * Create a new data property
   */
  async createDataProperty(projectId: string, iri: string, label: string, parentIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createDataProperty',
      iri,
      label,
      parent: parentIri
    }], undefined, userId, username);
  },

  /**
   * Create a new annotation property
   */
  async createAnnotationProperty(projectId: string, iri: string, label: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createAnnotationProperty',
      iri,
      label
    }], undefined, userId, username);
  },

  /**
   * Delete an object property
   */
  async deleteObjectProperty(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteObjectProperty',
      iri
    }], undefined, userId, username);
  },

  /**
   * Delete a data property
   */
  async deleteDataProperty(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDataProperty',
      iri
    }], undefined, userId, username);
  },

  /**
   * Delete an annotation property
   */
  async deleteAnnotationProperty(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAnnotationProperty',
      iri
    }], undefined, userId, username);
  },

  /**
   * Create a new datatype
   */
  async createDatatype(projectId: string, iri: string, label: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createDatatype',
      iri,
      label
    }], undefined, userId, username);
  },

  /**
   * Delete a datatype
   */
  async deleteDatatype(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDatatype',
      iri
    }], undefined, userId, username);
  },

  // --- Property Mutations ---

  async addPropertyDomain(projectId: string, propertyIri: string, domainIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addPropertyDomain', iri: propertyIri, target: domainIri }], undefined, userId, username);
  },
  async deletePropertyDomain(projectId: string, propertyIri: string, domainIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deletePropertyDomain', iri: propertyIri, target: domainIri }], undefined, userId, username);
  },

  async addPropertyRange(projectId: string, propertyIri: string, rangeIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addPropertyRange', iri: propertyIri, target: rangeIri }], undefined, userId, username);
  },
  async deletePropertyRange(projectId: string, propertyIri: string, rangeIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deletePropertyRange', iri: propertyIri, target: rangeIri }], undefined, userId, username);
  },

  async addSubPropertyOf(projectId: string, propertyIri: string, superPropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addSubPropertyOf', iri: propertyIri, target: superPropertyIri }], undefined, userId, username);
  },
  async deleteSubPropertyOf(projectId: string, propertyIri: string, superPropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteSubPropertyOf', iri: propertyIri, target: superPropertyIri }], undefined, userId, username);
  },

  async addInverseProperty(projectId: string, propertyIri: string, inversePropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addInverseProperty', iri: propertyIri, target: inversePropertyIri }], undefined, userId, username);
  },
  async deleteInverseProperty(projectId: string, propertyIri: string, inversePropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteInverseProperty', iri: propertyIri, target: inversePropertyIri }], undefined, userId, username);
  },

  async addDisjointProperty(projectId: string, propertyIri: string, disjointPropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addDisjointProperty', iri: propertyIri, target: disjointPropertyIri }], undefined, userId, username);
  },
  async deleteDisjointProperty(projectId: string, propertyIri: string, disjointPropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteDisjointProperty', iri: propertyIri, target: disjointPropertyIri }], undefined, userId, username);
  },

  async addEquivalentProperty(projectId: string, propertyIri: string, equivalentPropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addEquivalentProperty', iri: propertyIri, target: equivalentPropertyIri }], undefined, userId, username);
  },
  async deleteEquivalentProperty(projectId: string, propertyIri: string, equivalentPropertyIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteEquivalentProperty', iri: propertyIri, target: equivalentPropertyIri }], undefined, userId, username);
  },

  async addPropertyChain(projectId: string, propertyIri: string, chainExpression: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addPropertyChain', iri: propertyIri, value: chainExpression }], undefined, userId, username);
  },
  async deletePropertyChain(projectId: string, propertyIri: string, chainExpression: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deletePropertyChain', iri: propertyIri, value: chainExpression }], undefined, userId, username);
  },

  async addCharacteristic(projectId: string, propertyIri: string, characteristicIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addCharacteristic', iri: propertyIri, target: characteristicIri }], undefined, userId, username);
  },
  async deleteCharacteristic(projectId: string, propertyIri: string, characteristicIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteCharacteristic', iri: propertyIri, target: characteristicIri }], undefined, userId, username);
  },

  /**
   * Make siblings disjoint - adds pairwise disjointWith axioms
   */
  async makeSiblingsDisjoint(projectId: string, classIds: string[], userId?: string, username?: string): Promise<void> {
    await apiClient.post(`/api/ontology/make-siblings-disjoint/${projectId}?userId=${userId || 'anonymous'}&username=${encodeURIComponent(username || 'Anonymous')}`, { classIds });
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
