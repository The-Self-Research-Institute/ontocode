package self.research.ontology.owlEditor.model.merge;

/**
 * Actions that can be taken to resolve a merge conflict
 */
public enum ResolutionAction {
    /**
     * Keep the source ontology version of the entity
     */
    KEEP_SOURCE,
    
    /**
     * Keep the target ontology version of the entity
     */
    KEEP_TARGET,
    
    /**
     * Rename the source entity and keep both
     */
    RENAME_SOURCE,
    
    /**
     * Merge both versions (keep all axioms from both)
     */
    MERGE,
    
    /**
     * Skip this entity entirely
     */
    SKIP
}
