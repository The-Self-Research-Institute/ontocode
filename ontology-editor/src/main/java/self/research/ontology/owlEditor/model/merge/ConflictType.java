package self.research.ontology.owlEditor.model.merge;

/**
 * Types of conflicts that can occur during ontology merging
 */
public enum ConflictType {
    /**
     * Same entity exists in both ontologies but with different definitions
     */
    DIFFERENT_DEFINITION,
    
    /**
     * Same individual exists in both ontologies but with different assertions
     */
    DIFFERENT_ASSERTIONS,
    
    /**
     * Axioms that are semantically incompatible (e.g., different disjointness constraints)
     */
    INCOMPATIBLE_AXIOMS,
    
    /**
     * Different domain/range definitions for properties
     */
    INCOMPATIBLE_PROPERTY_CONSTRAINTS,
    
    /**
     * Namespace collision
     */
    NAMESPACE_CONFLICT,
    
    /**
     * Ontology IRI conflict
     */
    ONTOLOGY_IRI_CONFLICT
}
