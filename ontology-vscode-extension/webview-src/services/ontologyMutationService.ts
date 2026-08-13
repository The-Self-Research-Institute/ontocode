import apiClient from './apiClient';
import { resolveMutationActor } from '../utils/mutationActor';

export interface MutationOp {
  type: string;
  iri: string;
  label?: string;
  parent?: string;
  property?: string;
  value?: string;
  target?: string;
  classIri?: string;

  restrictionType?: string;
  cardinality?: number;
  axiomType?: string;
  oldValue?: string;
  ancestorIri?: string;
}

let realTimeSyncEnabled = true;

let draftEditBlocked = false;
let onDraftEditBlocked: (() => void) | null = null;

export function isPrivateEditMode(): boolean {
  return !realTimeSyncEnabled;
}

function resolveUseDraft(draft?: boolean): boolean {
  return draft !== undefined ? draft : !realTimeSyncEnabled;
}

export const ontologyMutationService = {
  setRealTimeSync(enabled: boolean) {
    realTimeSyncEnabled = enabled;
    console.log(`[MutationService] Real-time sync ${enabled ? 'ENABLED' : 'DISABLED'}`);
  },

  setDraftRequired(blocked: boolean, onBlocked?: () => void) {
    draftEditBlocked = blocked;
    onDraftEditBlocked = onBlocked ?? null;
  },

  isPrivateEditMode,
  resolveUseDraft,

  async applyMutations(projectId: string, ops: MutationOp[], draft?: boolean,
                      userId?: string, username?: string, sessionId?: string): Promise<void> {

    const useDraft = resolveUseDraft(draft);
    const actor = resolveMutationActor(userId, username);

    if (draftEditBlocked) {
      if (onDraftEditBlocked) onDraftEditBlocked();
      const e = new Error('This project requires Draft Mode for editing. Start your private copy to make changes.');
      (e as any).reason = 'draftRequired';
      throw e;
    }

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

      try {
        window.dispatchEvent(new CustomEvent('ontology:mutated', {
          detail: { projectId, ops: ops.map(o => o.type) },
        }));
      } catch { /* non-fatal */ }
    } catch (err: any) {
      if (err?.status === 403 && err?.data?.requiresUpgrade) {
        const e = new Error('Your current plan is Free. Upgrade to Pro to edit ontologies.');
        (e as any).reason = 'requiresUpgrade';
        throw e;
      }
      if (err?.status === 403 && err?.data?.viewOnly && err?.data?.draftAllowed) {
        const e = new Error('You can view this project and edit via draft mode. Switch to Draft Mode to make your changes, then raise a pull request.');
        (e as any).reason = 'draftRequired';
        throw e;
      }
      if (err?.status === 403 && err?.data?.viewOnly) {
        const e = new Error('You have view-only access to this project. Contact the project owner to request edit permissions.');
        (e as any).reason = 'viewOnly';
        throw e;
      }
      if (err?.status === 409 && err?.data?.draftCopyNotReady) {
        const e = new Error(
          'Private draft is still copying the ontology. Wait a moment and try again, or switch to Public mode.',
        );
        (e as any).reason = 'draftCopyNotReady';
        throw e;
      }
      throw err;
    }
  },

  async createClass(projectId: string, iri: string, label: string, parentIri: string, 
                   userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createClass',
      iri,
      label,
      parent: parentIri
    }], undefined, userId, username);
  },

  async deleteClass(projectId: string, iri: string, userId?: string, username?: string, label?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteClass',
      iri,
      ...(label ? { label } : {}),
    }], undefined, userId, username);
  },

  async deleteClasses(projectId: string, iris: string[], userId?: string, username?: string, labels?: Record<string, string>): Promise<void> {
    await this.applyMutations(
      projectId,
      iris.map((iri) => ({
        type: 'deleteClass',
        iri,
        ...(labels?.[iri] ? { label: labels[iri] } : {}),
      })),
      undefined, userId, username,
    );
  },

  async getDescendants(projectId: string, iri: string): Promise<{ iris: string[]; labels: Record<string, string>; truncated: boolean }> {
    return apiClient.get(`/api/ontology/classes/descendants/${projectId}`, { parentIri: iri });
  },

  async updateClassLabel(projectId: string, iri: string, label: string, 
                        userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateClassLabel',
      iri,
      label
    }], undefined, userId, username);
  },

  async addAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string,
                     userId?: string, username?: string, language?: string, datatype?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAnnotation',
      iri: entityIri,
      property: propertyIri,
      value,
      language,
      datatype,
    }], undefined, userId, username);
  },

  async deleteAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string,
                        userId?: string, username?: string, language?: string, datatype?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAnnotation',
      iri: entityIri,
      property: propertyIri,
      value,
      language,
      datatype,
    }], undefined, userId, username);
  },

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
    }], undefined, userId, username);
  },

  async addSubClassOf(projectId: string, classIri: string, superClassIri: string, 
                      userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addSubClassOf',
      iri: classIri,
      target: superClassIri
    }], undefined, userId, username); // Apply immediately with user info
  },

  async deleteSubClassOf(projectId: string, classIri: string, superClassIri: string,
                         userId?: string, username?: string, definition?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteSubClassOf',
      iri: classIri,
      target: superClassIri,

      ...(definition ? { value: definition } : {})
    }], undefined, userId, username); // Apply immediately with user info
  },

  async updateSubClassOf(projectId: string, classIri: string, oldSuperClassIri: string, newSuperClassIri: string,
                         userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateSubClassOf',
      iri: classIri,
      value: oldSuperClassIri,  // old target
      target: newSuperClassIri   // new target
    }], undefined, userId, username); // Apply immediately with user info
  },

  async addEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string,
                           userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addEquivalentClass',
      iri: classIri,
      target: equivalentClassIri
    }], undefined, userId, username); // Apply immediately with user info
  },

  async deleteEquivalentClass(projectId: string, classIri: string, equivalentClassIri: string,
                              userId?: string, username?: string, definition?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteEquivalentClass',
      iri: classIri,
      target: equivalentClassIri,

      ...(definition ? { value: definition } : {})
    }], undefined, userId, username); // Apply immediately with user info
  },

  async updateEquivalentClass(projectId: string, classIri: string, oldEquivalentClassIri: string, newEquivalentClassIri: string,
                              userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateEquivalentClass',
      iri: classIri,
      value: oldEquivalentClassIri,  // old target
      target: newEquivalentClassIri   // new target
    }], undefined, userId, username); // Apply immediately with user info
  },

  async addDisjointWith(projectId: string, classIri: string, disjointClassIri: string,
                        userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }], undefined, userId, username); // Apply immediately with user info
  },

  async deleteDisjointWith(projectId: string, classIri: string, disjointClassIri: string,
                           userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDisjointWith',
      iri: classIri,
      target: disjointClassIri
    }], undefined, userId, username); // Apply immediately with user info
  },

  async updateDisjointWith(projectId: string, classIri: string, oldDisjointClassIri: string, newDisjointClassIri: string,
                           userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateDisjointWith',
      iri: classIri,
      value: oldDisjointClassIri,  // old target
      target: newDisjointClassIri   // new target
    }], undefined, userId, username); // Apply immediately with user info
  },

  async createIndividual(projectId: string, iri: string, label: string, classIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createIndividual',
      iri,
      label,
      classIri
    }]);
  },

  async addIndividual(projectId: string, name: string, classIri: string): Promise<void> {

    await this.applyMutations(projectId, [{
      type: 'createIndividual',
      iri: name, // If it's not a full IRI, backend will generate one
      label: name,
      classIri
    }]);
  },

  async addClassAssertion(projectId: string, individualIri: string, classIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addClassAssertion',
      iri: individualIri,
      classIri
    }]);
  },

  async removeClassAssertion(projectId: string, individualIri: string, classIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'removeClassAssertion',
      iri: individualIri,
      classIri
    }]);
  },

  async deleteIndividual(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteIndividual',
      iri
    }], undefined, userId, username);
  },

  async createObjectProperty(projectId: string, iri: string, label: string, parentIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createObjectProperty',
      iri,
      label,
      parent: parentIri
    }], undefined, userId, username);
  },

  async createDataProperty(projectId: string, iri: string, label: string, parentIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createDataProperty',
      iri,
      label,
      parent: parentIri
    }], undefined, userId, username);
  },

  async createAnnotationProperty(projectId: string, iri: string, label: string, parentIri?: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createAnnotationProperty',
      iri,
      label,
      parent: parentIri
    }], undefined, userId, username);
  },

  async deleteObjectProperty(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteObjectProperty',
      iri
    }], undefined, userId, username);
  },

  async deleteDataProperty(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDataProperty',
      iri
    }], undefined, userId, username);
  },

  async renameEntity(projectId: string, oldIri: string, newIri: string, userId?: string, username?: string): Promise<void> {
    const actor = resolveMutationActor(userId, username);
    const useDraft = resolveUseDraft();
    await apiClient.post(
      `/api/ontology/entity/${encodeURIComponent(projectId)}/rename?draft=${useDraft}&userId=${encodeURIComponent(actor.userId)}&username=${encodeURIComponent(actor.username)}`,
      { oldIri, newIri },
    );
  },

  async deleteAnnotationProperty(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAnnotationProperty',
      iri
    }], undefined, userId, username);
  },

  async createDatatype(projectId: string, iri: string, label: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createDatatype',
      iri,
      label
    }], undefined, userId, username);
  },

  async deleteDatatype(projectId: string, iri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDatatype',
      iri
    }], undefined, userId, username);
  },

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
    ], undefined, userId, username);
  },

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
    ], undefined, userId, username);
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

  async addObjectPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, targetIndividualIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'addObjectPropertyAssertion', iri: individualIri, property: propertyIri, target: targetIndividualIri }], undefined, userId, username);
  },

  async deleteObjectPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, targetIndividualIri: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteObjectPropertyAssertion', iri: individualIri, property: propertyIri, target: targetIndividualIri }], undefined, userId, username);
  },

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

  async deleteDataPropertyAssertion(projectId: string, individualIri: string, propertyIri: string, literalValue: string, userId?: string, username?: string): Promise<void> {
    await this.applyMutations(projectId, [{ type: 'deleteDataPropertyAssertion', iri: individualIri, property: propertyIri, value: literalValue }], undefined, userId, username);
  },

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

    async addSameIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'addSameIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

    async deleteSameIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'deleteSameIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

    async addDifferentIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'addDifferentIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

    async deleteDifferentIndividual(projectId: string, individualIri1: string, individualIri2: string, userId?: string, username?: string): Promise<void> {
      await this.applyMutations(projectId, [{ type: 'deleteDifferentIndividual', iri: individualIri1, target: individualIri2 }], undefined, userId, username);
    },

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
    const actor = resolveMutationActor(params.userId, params.username);
    const useDraft = resolveUseDraft();
    try {
      await apiClient.put(
        `/api/ontology/${projectId}/relation?draft=${useDraft}`,
        { ...params, userId: actor.userId, username: actor.username },
      );
    } catch (err: any) {
      if (err?.status === 403 && err?.data?.requiresUpgrade) {
        const e = new Error('Your current plan is Free. Upgrade to Pro to edit ontologies.');
        (e as any).reason = 'requiresUpgrade';
        throw e;
      }
      if (err?.status === 403 && err?.data?.viewOnly && err?.data?.draftAllowed) {
        const e = new Error('You can view this project and edit via draft mode. Switch to Draft Mode to make your changes, then raise a pull request.');
        (e as any).reason = 'draftRequired';
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

  async makeSiblingsDisjoint(projectId: string, classIds: string[], userId?: string, username?: string): Promise<void> {
    const actor = resolveMutationActor(userId, username);
    const ops: MutationOp[] = [];
    for (let i = 0; i < classIds.length; i++) {
      for (let j = i + 1; j < classIds.length; j++) {
        ops.push({ type: 'addDisjointWith', iri: classIds[i], target: classIds[j] });
      }
    }
    if (ops.length === 0) return;
    await this.applyMutations(projectId, ops, undefined, actor.userId, actor.username);
  },

  async addDlQueryClass(
    projectId: string,
    expression: string,
    className: string,
    userEmail?: string,
  ): Promise<void> {
    const actor = resolveMutationActor(userEmail);
    const useDraft = resolveUseDraft();
    await apiClient.post(`/api/ontology/${projectId}/dl/add?draft=${useDraft}`, {
      expression,
      className,
      userEmail: actor.userId,
      userId: actor.userId,
      username: actor.username,
    });
  },

  async addDlQueryClassViaMutations(
    projectId: string,
    newIri: string,
    className: string,
    targetIri: string,
    userId?: string,
    username?: string,
  ): Promise<void> {
    const actor = resolveMutationActor(userId, username);
    await this.applyMutations(
      projectId,
      [
        { type: 'createClass', iri: newIri, label: className, parent: 'http://www.w3.org/2002/07/owl#Thing' },
        { type: 'addEquivalentClass', iri: newIri, target: targetIri },
      ],
      undefined,
      actor.userId,
      actor.username,
      `dl-add-${Date.now()}`,
    );
  },

  async addDisjointUnion(projectId: string, classIri: string, memberClassIris: string[]): Promise<void> {
    console.log('[MutationService] addDisjointUnion called:', { projectId, classIri, memberClassIris });
    const valueStr = memberClassIris.join(',');
    console.log('[MutationService] DisjointUnion value string:', valueStr);

    await this.applyMutations(projectId, [{
      type: 'addDisjointUnion',
      iri: classIri,
      value: valueStr
    }], undefined);

    console.log('[MutationService] addDisjointUnion completed');
  },

  async deleteDisjointUnion(projectId: string, classIri: string, listNodeId: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDisjointUnion',
      iri: classIri,
      target: listNodeId
    }], undefined);
  },

  async addHasKey(projectId: string, classIri: string, propertyIris: string[]): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addHasKey',
      iri: classIri,
      value: propertyIris.join(',')
    }], undefined);
  },

  async deleteHasKey(projectId: string, classIri: string, listNodeId: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteHasKey',
      iri: classIri,
      target: listNodeId
    }], undefined);
  },

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

    if (old.iri && newVal.iri) {
      const opType = axiomType === 'EquivalentTo' ? 'updateEquivalentClass'
                   : axiomType === 'DisjointWith'  ? 'updateDisjointWith'
                   : 'updateSubClassOf';
      await this.applyMutations(projectId, [{ type: opType, iri: classIri, value: old.iri, target: newVal.iri }], undefined, userId, username);
      return;
    }

    const ops: MutationOp[] = [];

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

    await this.applyMutations(projectId, ops, undefined, userId, username);
  },

  async addAxiom(projectId: string, classIri: string, type: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith' | 'GeneralClassAxiom', expression: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAxiom',
      iri: classIri,
      classIri,
      target: expression,
      value: type
    }], undefined);
  },

  async addGCAIntersection(projectId: string, classIri: string, memberIris: string[]): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addGCAIntersection',
      iri: classIri,
      value: memberIris.join(',')
    }], undefined);
  },

  async addGCAUnion(projectId: string, classIri: string, memberIris: string[]): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addGCAUnion',
      iri: classIri,
      value: memberIris.join(',')
    }], undefined);
  },

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
    }], undefined);
  },

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
    }], undefined);
  },

  async deleteAxiom(projectId: string, axiomId: string, ancestorIri?: string, definition?: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAxiom',
      iri: axiomId,
      ...(ancestorIri ? { ancestorIri } : {}),

      ...(definition ? { value: definition } : {})
    }], undefined);
  },

  async addObjectRestriction(
    projectId: string,
    classIri: string,
    axiomType: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value',
    fillerClassIri: string,
    cardinality?: number,
    userId?: string,
    username?: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addObjectRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: fillerClassIri,
      cardinality: cardinality,
      axiomType // EquivalentTo or SubClassOf
    }], undefined, userId, username);
  },

  async addDataRestriction(
    projectId: string,
    classIri: string,
    axiomType: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly',
    datatypeIri: string,
    cardinality?: number,
    userId?: string,
    username?: string
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addDataRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: datatypeIri,
      cardinality: cardinality,
      axiomType
    }], undefined, userId, username);
  },

  async deleteObjectRestriction(
    projectId: string,
    classIri: string,
    axiomType: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value',
    fillerClassIri: string,
    cardinality?: number,
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteObjectRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: fillerClassIri,
      cardinality,
      axiomType
    }], undefined);
  },

  async deleteDataRestriction(
    projectId: string,
    classIri: string,
    axiomType: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith',
    propertyIri: string,
    restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly',
    datatypeIri: string,
    cardinality?: number,
  ): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteDataRestriction',
      iri: classIri,
      property: propertyIri,
      restrictionType,
      target: datatypeIri,
      cardinality,
      axiomType
    }], undefined);
  },
};

export default ontologyMutationService;
