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
    ONTOLOGY_IRI_CONFLICT,
    
    /**
     * Source ontology is identical or nearly identical to target (duplicate upload)
     * This indicates the same file was uploaded again - no new content to merge
     */
    DUPLICATE_FILE_CONTENT,
    
    /**
     * Source ontology is identical to target, suggesting a re-upload of the same file
     * User should be alerted that this operation won't add new content
     */
    IDENTICAL_FILE_UPLOAD
}
