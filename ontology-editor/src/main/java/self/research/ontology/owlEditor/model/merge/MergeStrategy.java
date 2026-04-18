package self.research.ontology.owlEditor.model.merge;

/**
 * Strategy for merging ontologies
 */
public enum MergeStrategy {
    /**
     * Simple union - combine all axioms from both ontologies (default)
     * Duplicates are kept as-is
     */
    SIMPLE_UNION,
    
    /**
     * Replace duplicates - source ontology entities overwrite target ontology entities
     */
    REPLACE_DUPLICATES,
    
    /**
     * Keep both - rename conflicting entities in source ontology before merging
     */
    KEEP_BOTH,
    
    /**
     * Manual resolution - user specifies how to resolve each conflict
     */
    MANUAL_RESOLUTION
}
