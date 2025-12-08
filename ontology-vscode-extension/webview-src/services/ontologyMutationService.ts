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
    
    console.log(`[MutationService] 🔄 Applying mutations to ${projectId}`,ops, {
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
                        userId?: string, username?: string, oldValue?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateAnnotation',
      iri: entityIri,
      property: propertyIri,
      value: newValue,
      oldValue: oldValue
    }], undefined, userId, username);
  },

  /**
   * Add SubClassOf axiom (applied immediately, not as draft)
   */
  async addSubClassOf(projectId: string, classIri: string, superClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addSubClassOf',
      iri: classIri,
      target: superClassIri
    }], false); // Apply immediately
  },

  /**
   * Delete SubClassOf axiom (applied immediately, not as draft)
   */
  async deleteSubClassOf(projectId: string, classIri: string, superClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteSubClassOf',
      iri: classIri,
      target: superClassIri
    }], false); // Apply immediately
  },

  /**
   * Update SubClassOf axiom (replaces old target with new target in a single transaction)
   */
  async updateSubClassOf(projectId: string, classIri: string, oldSuperClassIri: string, newSuperClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateSubClassOf',
      iri: classIri,
      value: oldSuperClassIri,  // old target
      target: newSuperClassIri   // new target
    }], false); // Apply immediately
  },

  /**
   * Add EquivalentClass axiom (applied immediately, not as draft)
   */
  async addEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addEquivalentClass',
      iri: classIri,
      target: equivalentClassIri
    }], false); // Apply immediately
  },

  /**
   * Delete EquivalentClass axiom (applied immediately, not as draft)
   */
  async deleteEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteEquivalentClass',
      iri: classIri,
      target: equivalentClassIri
    }], false); // Apply immediately
  },

  /**
   * Update EquivalentClass axiom (replaces old target with new target in a single transaction)
   */
  async updateEquivalentClass(projectId: string, classIri: string, oldEquivalentClassIri: string, newEquivalentClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateEquivalentClass',
      iri: classIri,
      value: oldEquivalentClassIri,  // old target
      target: newEquivalentClassIri   // new target
    }], false); // Apply immediately
  },

  /**
   * Add DisjointWith axiom (applied immediately, not as draft)
   */
  async addDisjointWith(projectId: string, classIri: string, disjointClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }], false); // Apply immediately
  },

  /**
   * Delete DisjointWith axiom (applied immediately, not as draft)
   */
  async deleteDisjointWith(projectId: string, classIri: string, disjointClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }], false); // Apply immediately
  },

  /**
   * Update DisjointWith axiom (replaces old target with new target in a single transaction)
   */
  async updateDisjointWith(projectId: string, classIri: string, oldDisjointClassIri: string, newDisjointClassIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateDisjointWith',
      iri: classIri,
      value: oldDisjointClassIri,  // old target
      target: newDisjointClassIri   // new target
    }], false); // Apply immediately
  },

  /**
   * Create a new individual (named individual)
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
   * Add an individual with just a name and create class assertion axiom
   * Backend generates IRI from name and adds class assertion axiom
   */
  async addIndividual(projectId: string, name: string, classIri: string): Promise<void> {
    // Backend will generate IRI from name and create class assertion axiom
    await this.applyMutations(projectId, [{
      type: 'createIndividual',
      iri: name, // If it's not a full IRI, backend will generate one
      label: name,
      classIri
    }]);
  },

  /**
   * Add a class assertion axiom to an existing individual
   * Adds: <individualIri> rdf:type <classIri>
   * Used when adding an existing individual as an instance of a class
   */
  async addClassAssertion(projectId: string, individualIri: string, classIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addClassAssertion',
      iri: individualIri,
      classIri
    }]);
  },

  /**
   * Remove a class assertion axiom from an individual
   * Removes: <individualIri> rdf:type <classIri>
   * The individual itself remains, only the type assertion is removed
   */
  async removeClassAssertion(projectId: string, individualIri: string, classIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'removeClassAssertion',
      iri: individualIri,
      classIri
    }]);
  },

  /**
   * Delete an individual completely (removes all axioms about the individual)
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

  // --- Property Assertions on Individuals ---

  /**
   * Add an object property assertion to an individual
   */
  async addObjectPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, targetIndividualIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addObjectPropertyAssertion', iri: individualIri, property: propertyIri, target: targetIndividualIri }], undefined, userId, username);
  },

  /**
   * Delete an object property assertion from an individual
   */
  async deleteObjectPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, targetIndividualIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteObjectPropertyAssertion', iri: individualIri, property: propertyIri, target: targetIndividualIri }], undefined, userId, username);
  },

  /**
   * Add a data property assertion to an individual
   */
  async addDataPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, literalValue: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addDataPropertyAssertion', iri: individualIri, property: propertyIri, value: literalValue }], undefined, userId, username);
  },

  /**
   * Delete a data property assertion from an individual
   */
  async deleteDataPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, literalValue: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteDataPropertyAssertion', iri: individualIri, property: propertyIri, value: literalValue }], undefined, userId, username);
  },

  /**
   * Make siblings disjoint - adds pairwise disjointWith axioms
   */
  async makeSiblingsDisjoint(projectId: string, classIds: string[], userId?: string, username?: string): Promise<void> {
    await apiClient.post(`/api/ontology/make-siblings-disjoint/${projectId}?userId=${userId || 'anonymous'}&username=${encodeURIComponent(username || 'Anonymous')}`, { classIds });
  },

  /**
   * Add DisjointUnionOf axiom (applied immediately)
   * This creates a disjoint union: the class becomes equivalent to the disjoint union of the member classes
   * @param classIri - The class IRI that will have the disjoint union
   * @param memberClassIris - Array of member class IRIs
   */
  async addDisjointUnion(projectId: string, classIri: string, memberClassIris: string[]): Promise<void> {
    console.log('[MutationService] addDisjointUnion called:', { projectId, classIri, memberClassIris });
    const valueStr = memberClassIris.join(',');
    console.log('[MutationService] DisjointUnion value string:', valueStr);
    
    await this.applyMutations(projectId, [{
      type: 'addDisjointUnion',
      iri: classIri,
      value: valueStr
    }], false); // Apply immediately
    
    console.log('[MutationService] addDisjointUnion completed');
  },

  /**
   * Delete DisjointUnionOf axiom (applied immediately)
   * @param classIri - The class IRI
   * @param listNodeId - The list node ID (from the axiom's id field)
   */
  async deleteDisjointUnion(projectId: string, classIri: string, listNodeId: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDisjointUnion',
      iri: classIri,
      target: listNodeId
    }], false); // Apply immediately
  },

  /**
   * Add HasKey axiom (applied immediately)
   * HasKey defines properties that uniquely identify individuals of a class
   * @param classIri - The class IRI
   * @param propertyIris - Array of property IRIs that form the key
   */
  async addHasKey(projectId: string, classIri: string, propertyIris: string[]): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addHasKey',
      iri: classIri,
      value: propertyIris.join(',')
    }], false); // Apply immediately
  },

  /**
   * Delete HasKey axiom (applied immediately)
   * @param classIri - The class IRI
   * @param listNodeId - The list node ID (from the axiom's id field)
   */
  async deleteHasKey(projectId: string, classIri: string, listNodeId: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteHasKey',
      iri: classIri,
      target: listNodeId
    }], false); // Apply immediately
  },

  /**
   * Add an axiom using Manchester Syntax (applied immediately, not as draft)
   * NOTE: Backend Manchester parser not yet implemented - complex expressions may not work
   */
  async addAxiom(projectId: string, classIri: string, type: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith', expression: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAxiom',
      classIri,
      target: expression, // We use 'target' for the expression
      value: type // We use 'value' for the axiom type
    }], false); // Apply immediately
  },

  /**
   * Add an object restriction (e.g., "hasProperty some ClassName")
   * This sends structured data that the backend can process without Manchester parsing
   */
  async addObjectRestriction(
    projectId: string, 
    classIri: string, 
    axiomType: 'EquivalentTo' | 'SubClassOf',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value',
    fillerClassIri: string,
    cardinality?: number
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addObjectRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: fillerClassIri,
      cardinality: cardinality,
      axiomType // EquivalentTo or SubClassOf
    }], false); // Apply immediately
  },

  /**
   * Add a data restriction (e.g., "hasAge some xsd:integer")
   * This sends structured data that the backend can process without Manchester parsing
   */
  async addDataRestriction(
    projectId: string,
    classIri: string,
    axiomType: 'EquivalentTo' | 'SubClassOf',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly',
    datatypeIri: string,
    cardinality?: number
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addDataRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: datatypeIri,
      cardinality: cardinality,
      axiomType
    }], false); // Apply immediately
  },

  /**
   * Delete an object restriction
   */
  async deleteObjectRestriction(
    projectId: string,
    classIri: string,
    axiomType: 'EquivalentTo' | 'SubClassOf',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value',
    fillerClassIri: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteObjectRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: fillerClassIri,
      axiomType
    }], false);
  },

  /**
   * Delete a data restriction
   */
  async deleteDataRestriction(
    projectId: string,
    classIri: string,
    axiomType: 'EquivalentTo' | 'SubClassOf',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly',
    datatypeIri: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDataRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: datatypeIri,
      axiomType
    }], false);
  },
};

export default ontologyMutationService;
