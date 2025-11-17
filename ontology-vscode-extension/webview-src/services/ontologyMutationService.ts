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
   */
  async applyMutations(projectId: string, ops: MutationOp[]): Promise<void> {
    await apiClient.post(`/api/ontology/mutations/${projectId}`, { ops });
  },

  /**
   * Create a new class
   */
  async createClass(projectId: string, iri: string, label: string, parentIri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'createClass',
      iri,
      label,
      parent: parentIri
    }]);
  },

  /**
   * Delete a class
   */
  async deleteClass(projectId: string, iri: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteClass',
      iri
    }]);
  },

  /**
   * Update class label
   */
  async updateClassLabel(projectId: string, iri: string, label: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'updateClassLabel',
      iri,
      label
    }]);
  },

  /**
   * Add annotation to an entity
   */
  async addAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'addAnnotation',
      iri: entityIri,
      property: propertyIri,
      value
    }]);
  },

  /**
   * Delete annotation from an entity
   */
  async deleteAnnotation(projectId: string, entityIri: string, propertyIri: string, value: string): Promise<void> {
    await this.applyMutations(projectId, [{
      type: 'deleteAnnotation',
      iri: entityIri,
      property: propertyIri,
      value
    }]);
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
  }
};

export default ontologyMutationService;
