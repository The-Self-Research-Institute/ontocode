package self.research.ontology.owlEditor.model.merge;

/**
 * Severity level of a merge conflict
 */
public enum ConflictSeverity {
    /**
     * Low severity - minor differences that likely don't affect semantics
     */
    LOW,
    
    /**
     * Medium severity - differences that should be reviewed
     */
    MEDIUM,
    
    /**
     * High severity - significant differences that must be resolved
     */
    HIGH,
    
    /**
     * Critical - conflicts that prevent automatic merging
     */
    CRITICAL
}
