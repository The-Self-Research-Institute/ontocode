import apiClient from './apiClient';
import { isDesktop } from '../utils/desktop';

export interface MutationOp {
  type: string;
  iri: string;
  label?: string;
  parent?: string;
  property?: string;
  value?: string;
  target?: string;
  classIri?: string;
  // Restriction and axiom fields (match backend MutationOp record)
  restrictionType?: string;
  cardinality?: number;
  axiomType?: string;
  oldValue?: string;
}

// Global flag to control real-time sync behavior
// When true: changes apply immediately (for shared files)
// When false: changes save as drafts (for private files)
let realTimeSyncEnabled = false;

const DESKTOP_USER_ID = 'desktop-user-local';

/** Resolve user id for draft graph writes — must match JWT / SparqlQueryContext on read. */
function resolveMutationActor(userId?: string, username?: string): { userId: string; username: string } {
  if (isDesktop()) {
    return { userId: DESKTOP_USER_ID, username: username || 'Desktop User' };
  }
  try {
    const token = localStorage.getItem('authToken');
    if (token) {
      const parts = token.split('.');
      if (parts.length === 3) {
        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        const jwtUserId = payload.userId || payload.id || payload.sub;
        const resolvedName = payload.sub || payload.email || username || 'Anonymous';
        if (jwtUserId) {
          return { userId: jwtUserId, username: resolvedName };
        }
      }
    }
  } catch {
    /* fall through */
  }
  if (userId && userId !== 'anonymous') {
    return { userId, username: username || 'Anonymous' };
  }
  return { userId: userId || 'anonymous', username: username || 'Anonymous' };
}

export const ontologyMutationService = {
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
    // Private mode (realTimeSyncEnabled=false): write to per-user draft named graph in Fuseki.
    // Public/shared mode: write directly to the project main graph.
    const useDraft = draft !== undefined ? draft : !realTimeSyncEnabled;
    const actor = resolveMutationActor(userId, username);

    console.log(`[MutationService] 🔄 Applying mutations to ${projectId}`,ops, {
      opsCount: ops.length,
      draft: useDraft,
      realTimeSyncEnabled,
      ops: ops.map(o => o.type)
    });

    try {
      await apiClient.post(`/api/ontology/mutations/${projectId}?draft=${useDraft}`, {
        ops,
        userId: actor.userId,
        username: actor.username,
        sessionId: sessionId || `session_${Date.now()}`
      });
    } catch (err: any) {
      if (err?.status === 403 && err?.data?.requiresUpgrade) {
        const e = new Error('Your current plan is Free. Upgrade to Pro to edit ontologies.');
        (e as any).reason = 'requiresUpgrade';
        throw e;
      }
      if (err?.status === 403 && err?.data?.viewOnly) {
        const e = new Error('You have view-only access to this project. Contact the project owner to request edit permissions.');
        (e as any).reason = 'viewOnly';
        throw e;
      }
      throw err;
    }
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
   * Add annotation to an entity.
   * Always applied directly (draft=false) so annotations persist through tab switches
   * and re-fetches regardless of private/public sync mode.
   */
  async addAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string,
                     userId?: string, username?: string, language?: string, datatype?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAnnotation',
      iri: entityIri,
      property: propertyIri,
      value,
      language,
      datatype,
    }], false, userId, username);
  },

  /**
   * Delete annotation from an entity.
   * Always applied directly (draft=false) — see addAnnotation.
   */
  async deleteAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string,
                        userId?: string, username?: string, language?: string, datatype?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAnnotation',
      iri: entityIri,
      property: propertyIri,
      value,
      language,
      datatype,
    }], false, userId, username);
  },

  /**
   * Update annotation value (atomic operation).
   * Always applied directly (draft=false) — see addAnnotation.
   */
  async updateAnnotation(projectId: string, entityIri: string, propertyIri: string, newValue: string,
                        userId?: string, username?: string, oldValue?: string, language?: string, datatype?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateAnnotation',
      iri: entityIri,
      property: propertyIri,
      value: newValue,
      oldValue,
      language,
      datatype,
    }], false, userId, username);
  },

  /**
   * Add SubClassOf axiom (applied immediately, not as draft)
   */
  async addSubClassOf(projectId: string, classIri: string, superClassIri: string, 
                      userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addSubClassOf',
      iri: classIri,
      target: superClassIri
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Delete SubClassOf axiom (applied immediately, not as draft)
   */
  async deleteSubClassOf(projectId: string, classIri: string, superClassIri: string,
                         userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteSubClassOf',
      iri: classIri,
      target: superClassIri
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Update SubClassOf axiom (replaces old target with new target in a single transaction)
   */
  async updateSubClassOf(projectId: string, classIri: string, oldSuperClassIri: string, newSuperClassIri: string,
                         userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateSubClassOf',
      iri: classIri,
      value: oldSuperClassIri,  // old target
      target: newSuperClassIri   // new target
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Add EquivalentClass axiom (applied immediately, not as draft)
   */
  async addEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string,
                           userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addEquivalentClass',
      iri: classIri,
      target: equivalentClassIri
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Delete EquivalentClass axiom (applied immediately, not as draft)
   */
  async deleteEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string,
                              userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteEquivalentClass',
      iri: classIri,
      target: equivalentClassIri
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Update EquivalentClass axiom (replaces old target with new target in a single transaction)
   */
  async updateEquivalentClass(projectId: string, classIri: string, oldEquivalentClassIri: string, newEquivalentClassIri: string,
                              userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateEquivalentClass',
      iri: classIri,
      value: oldEquivalentClassIri,  // old target
      target: newEquivalentClassIri   // new target
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Add DisjointWith axiom (applied immediately, not as draft)
   */
  async addDisjointWith(projectId: string, classIri: string, disjointClassIri: string,
                        userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Delete DisjointWith axiom (applied immediately, not as draft)
   */
  async deleteDisjointWith(projectId: string, classIri: string, disjointClassIri: string,
                           userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }], false, userId, username); // Apply immediately with user info
  },

  /**
   * Update DisjointWith axiom (replaces old target with new target in a single transaction)
   */
  async updateDisjointWith(projectId: string, classIri: string, oldDisjointClassIri: string, newDisjointClassIri: string,
                           userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateDisjointWith',
      iri: classIri,
      value: oldDisjointClassIri,  // old target
      target: newDisjointClassIri   // new target
    }], false, userId, username); // Apply immediately with user info
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
  async renameEntity(projectId: string, oldIri: string, newIri: string, userId?: string, username?: string): Promise<void> {
    await apiClient.post(
      `/api/ontology/entity/${encodeURIComponent(projectId)}/rename?userId=${encodeURIComponent(userId || 'anonymous')}&username=${encodeURIComponent(username || 'Anonymous')}`,
      { oldIri, newIri },
    );
  },

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

  async addPropertyDomain(
    projectId: string, 
    propertyIri: string, 
    domainIri: string, 
    userId?: string, 
    username?: string,
    restrictionData?: {
      propertyIri: string;
      restrictionType: string;
      fillerIri: string;
      cardinality?: number;
      isDataProperty?: boolean;
    }
  ): Promise<void> {
    const op: any = { type: 'addPropertyDomain', iri: propertyIri, target: domainIri };
    
    if (restrictionData) {
      op.property = restrictionData.propertyIri;
      op.restrictionType = restrictionData.restrictionType;
      op.target = restrictionData.fillerIri; // Override target with filler
      op.cardinality = restrictionData.cardinality;
      op.axiomType = restrictionData.isDataProperty ? 'DataRestriction' : 'ObjectRestriction';
    }
    
    await this.applyMutations(projectId, [op], undefined, userId, username);
  },
  async deletePropertyDomain(projectId: string, propertyIri: string, domainIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deletePropertyDomain', iri: propertyIri, target: domainIri }], undefined, userId, username);
  },

  async addPropertyRange(
    projectId: string, 
    propertyIri: string, 
    rangeIri: string, 
    userId?: string, 
    username?: string,
    restrictionData?: {
      propertyIri: string;
      restrictionType: string;
      fillerIri: string;
      cardinality?: number;
      isDataProperty?: boolean;
    }
  ): Promise<void> {
    const op: any = { type: 'addPropertyRange', iri: propertyIri, target: rangeIri };
    
    if (restrictionData) {
      op.property = restrictionData.propertyIri;
      op.restrictionType = restrictionData.restrictionType;
      op.target = restrictionData.fillerIri; // Override target with filler
      op.cardinality = restrictionData.cardinality;
      op.axiomType = restrictionData.isDataProperty ? 'DataRestriction' : 'ObjectRestriction';
    }

    await this.applyMutations(projectId, [op], undefined, userId, username);
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

  /**
   * Replace a property relation (domain/range/subProperty/inverse/disjoint/equivalent)
   * in a single API call — [deleteOp, addOp] together, no orphaned deletes.
   */
  async replacePropertyRelation(
    projectId: string,
    propertyIri: string,
    relation: 'domain' | 'range' | 'subProperty' | 'inverse' | 'disjoint' | 'equivalent',
    oldIri: string,
    newIri: string,
    userId?: string,
    username?: string,
  ): Promise<void> {
    const deleteTypes: Record<string, string> = {
      domain: 'deletePropertyDomain', range: 'deletePropertyRange',
      subProperty: 'deleteSubPropertyOf', inverse: 'deleteInverseProperty',
      disjoint: 'deleteDisjointProperty', equivalent: 'deleteEquivalentProperty',
    };
    const addTypes: Record<string, string> = {
      domain: 'addPropertyDomain', range: 'addPropertyRange',
      subProperty: 'addSubPropertyOf', inverse: 'addInverseProperty',
      disjoint: 'addDisjointProperty', equivalent: 'addEquivalentProperty',
    };
    await this.applyMutations(projectId, [
      { type: deleteTypes[relation], iri: propertyIri, target: oldIri },
      { type: addTypes[relation],    iri: propertyIri, target: newIri },
    ], false, userId, username);
  },

  /**
   * Replace a Same/Different individual relation in a single API call.
   */
  async replaceIndividualRelation(
    projectId: string,
    individualIri: string,
    relation: 'same' | 'different',
    oldIri: string,
    newIri: string,
    userId?: string,
    username?: string,
  ): Promise<void> {
    const deleteType = relation === 'same' ? 'deleteSameIndividual' : 'deleteDifferentIndividual';
    const addType    = relation === 'same' ? 'addSameIndividual'    : 'addDifferentIndividual';
    await this.applyMutations(projectId, [
      { type: deleteType, iri: individualIri, target: oldIri },
      { type: addType,    iri: individualIri, target: newIri },
    ], false, userId, username);
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
  async addDataPropertyAssertion(
    projectId: string,
    individualIri: string,
    propertyIri: string,
    literalValue: string,
    userId?: string,
    username?: string,
    language?: string,
    datatype?: string,
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addDataPropertyAssertion',
      iri: individualIri,
      property: propertyIri,
      value: literalValue,
      language,
      datatype,
    }], undefined, userId, username);
  },

  /**
   * Delete a data property assertion from an individual
   */
  async deleteDataPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, literalValue: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteDataPropertyAssertion', iri: individualIri, property: propertyIri, value: literalValue }], undefined, userId, username);
  },

  /**
   * Add a negative object property assertion to an individual
   * Adds an owl:NegativePropertyAssertion blank node.
   */
  async addNegativeObjectPropertyAssertion(
    projectId: string,
    individualIri: string,
    propertyIri: string,
    targetIndividualIri: string,
    userId?: string,
    username?: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addNegativeObjectPropertyAssertion',
      iri: individualIri,
      property: propertyIri,
      target: targetIndividualIri
    }], undefined, userId, username);
  },

  /**
   * Delete a negative object property assertion from an individual
   */
  async deleteNegativeObjectPropertyAssertion(
    projectId: string,
    individualIri: string,
    propertyIri: string,
    targetIndividualIri: string,
    userId?: string,
    username?: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteNegativeObjectPropertyAssertion',
      iri: individualIri,
      property: propertyIri,
      target: targetIndividualIri
    }], undefined, userId, username);
  },

  /**
   * Add a negative data property assertion to an individual
   */
  async addNegativeDataPropertyAssertion(
    projectId: string,
    individualIri: string,
    propertyIri: string,
    literalValue: string,
    userId?: string,
    username?: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addNegativeDataPropertyAssertion',
      iri: individualIri,
      property: propertyIri,
      value: literalValue
    }], undefined, userId, username);
  },

  /**
   * Delete a negative data property assertion from an individual
   */
  async deleteNegativeDataPropertyAssertion(
    projectId: string,
    individualIri: string,
    propertyIri: string,
    literalValue: string,
    userId?: string,
    username?: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteNegativeDataPropertyAssertion',
      iri: individualIri,
      property: propertyIri,
      value: literalValue
    }], undefined, userId, username);
  },

  /**
     * Add a SameIndividual axiom between two individuals
     */
    async addSameIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'addSameIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

    /**
     * Delete a SameIndividual axiom between two individuals
     */
    async deleteSameIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'deleteSameIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

    /**
     * Add a DifferentIndividuals axiom between two individuals
     */
    async addDifferentIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'addDifferentIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

    /**
     * Delete a DifferentIndividuals axiom between two individuals
     */
    async deleteDifferentIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'deleteDifferentIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

  /**
   * Single endpoint for add / edit / delete of any entity relation.
   * For "edit": atomically deletes the old value and inserts the new value
   * in one SPARQL UPDATE on the server — no race condition, no orphaned triples.
   */
  async editRelation(
    projectId: string,
    params: {
      operation: 'add' | 'edit' | 'delete';
      entityIri: string;
      relationshipType: string;
      targetIri?: string;
      oldTargetIri?: string;
      userId?: string;
      username?: string;
      restrictionData?: {
        propertyIri: string;
        restrictionType: string;
        fillerIri: string;
        cardinality?: number;
        isDataRestriction: boolean;
      };
      oldRestrictionData?: {
        propertyIri: string;
        restrictionType: string;
        fillerIri: string;
        cardinality?: number;
        isDataRestriction: boolean;
      };
      memberIris?: string[];
      expressionType?: 'intersection' | 'union';
    }
  ): Promise<void> {
    try {
      await apiClient.put(`/api/ontology/${projectId}/relation`, params);
    } catch (err: any) {
      if (err?.status === 403 && err?.data?.requiresUpgrade) {
        const e = new Error('Your current plan is Free. Upgrade to Pro to edit ontologies.');
        (e as any).reason = 'requiresUpgrade';
        throw e;
      }
      if (err?.status === 403 && err?.data?.viewOnly) {
        const e = new Error('You have view-only access to this project. Contact the project owner to request edit permissions.');
        (e as any).reason = 'viewOnly';
        throw e;
      }
      throw err;
    }
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
   * Replace an axiom in a single API call — sends [deleteOp, addOp] together so
   * the add can never be orphaned if the delete succeeded. Handles all combinations:
   * simple IRI, object/data restriction, intersection, union.
   */
  async replaceAxiom(
    projectId: string,
    classIri: string,
    axiomType: 'SubClassOf' | 'EquivalentTo' | 'DisjointWith',
    old: {
      iri?: string;
      restriction?: { property: string; restrictionType: string; filler: string; cardinality?: number; isData?: boolean };
    },
    newVal: {
      iri?: string;
      restriction?: { property: string; restrictionType: string; filler: string; cardinality?: number; isData?: boolean };
      intersection?: string[];
      union?: string[];
    },
    userId?: string,
    username?: string,
  ): Promise<void> {
    // Simple IRI → Simple IRI: use single atomic SPARQL DELETE+INSERT+WHERE
    if (old.iri && newVal.iri) {
      const opType = axiomType === 'EquivalentTo' ? 'updateEquivalentClass'
                   : axiomType === 'DisjointWith'  ? 'updateDisjointWith'
                   : 'updateSubClassOf';
      await this.applyMutations(projectId, [{ type: opType, iri: classIri, value: old.iri, target: newVal.iri }], false, userId, username);
      return;
    }

    const ops: MutationOp[] = [];

    // --- Delete op ---
    if (old.restriction) {
      ops.push({
        type: old.restriction.isData ? 'deleteDataRestriction' : 'deleteObjectRestriction',
        iri: classIri,
        axiomType,
        property: old.restriction.property,
        restrictionType: old.restriction.restrictionType,
        target: old.restriction.filler,
        cardinality: old.restriction.cardinality,
      });
    } else if (old.iri) {
      ops.push({
        type: axiomType === 'EquivalentTo' ? 'deleteEquivalentClass'
            : axiomType === 'DisjointWith'  ? 'deleteDisjointWith'
            : 'deleteSubClassOf',
        iri: classIri,
        target: old.iri,
      });
    }

    // --- Add op ---
    if (newVal.restriction) {
      ops.push({
        type: newVal.restriction.isData ? 'addDataRestriction' : 'addObjectRestriction',
        iri: classIri,
        axiomType,
        property: newVal.restriction.property,
        restrictionType: newVal.restriction.restrictionType,
        target: newVal.restriction.filler,
        cardinality: newVal.restriction.cardinality,
      });
    } else if (newVal.iri) {
      ops.push({
        type: axiomType === 'EquivalentTo' ? 'addEquivalentClass'
            : axiomType === 'DisjointWith'  ? 'addDisjointWith'
            : 'addSubClassOf',
        iri: classIri,
        target: newVal.iri,
      });
    } else if (newVal.intersection) {
      ops.push({ type: 'addIntersection', iri: classIri, axiomType, value: newVal.intersection.join(',') });
    } else if (newVal.union) {
      ops.push({ type: 'addUnion', iri: classIri, axiomType, value: newVal.union.join(',') });
    }

    await this.applyMutations(projectId, ops, false, userId, username);
  },

  /**
   * Add an axiom using Manchester Syntax (applied immediately, not as draft)
   * NOTE: Backend Manchester parser not yet implemented - complex expressions may not work
   */
  async addAxiom(projectId: string, classIri: string, type: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith' | 'GeneralClassAxiom', expression: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAxiom',
      iri: classIri,
      classIri,
      target: expression,
      value: type
    }], false); // Apply immediately
  },

  /**
   * Add a General Class Axiom where an anonymous intersection is the subject:
   * (A and B) rdfs:subClassOf <classIri>
   */
  async addGCAIntersection(projectId: string, classIri: string, memberIris: string[]): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addGCAIntersection',
      iri: classIri,
      value: memberIris.join(',')
    }], false);
  },

  /**
   * Add a General Class Axiom where an anonymous union is the subject:
   * (A or B) rdfs:subClassOf <classIri>
   */
  async addGCAUnion(projectId: string, classIri: string, memberIris: string[]): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addGCAUnion',
      iri: classIri,
      value: memberIris.join(',')
    }], false);
  },

  /**
   * Add an intersection class expression (classIri EquivalentTo/SubClassOf: A and B and ...)
   */
  async addIntersection(
    projectId: string,
    classIri: string,
    memberIris: string[],
    axiomType: 'EquivalentTo' | 'SubClassOf'
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addIntersection',
      iri: classIri,
      value: memberIris.join(','),
      axiomType
    }], false);
  },

  /**
   * Add a union class expression (classIri EquivalentTo/SubClassOf: A or B or ...)
   */
  async addUnion(
    projectId: string,
    classIri: string,
    memberIris: string[],
    axiomType: 'EquivalentTo' | 'SubClassOf'
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addUnion',
      iri: classIri,
      value: memberIris.join(','),
      axiomType
    }], false);
  },

  /**
   * Delete an axiom by its ID (blank node ID for anonymous axioms)
   * Used for deleting General Class Axioms, restrictions, etc.
   */
  async deleteAxiom(projectId: string, axiomId: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAxiom',
      iri: axiomId
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
