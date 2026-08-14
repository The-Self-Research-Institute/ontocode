package self.research.ontology.owlEditor.model.merge;

/**
 * Represents a conflict detected during ontology merge analysis
 */
public class MergeConflict {
    
    private String entityIRI;
    private String entityType;  // Class, ObjectProperty, DataProperty, Individual, etc.
    private ConflictType conflictType;
    private String sourceDefinition;
    private String targetDefinition;
    private String description;
    private ConflictSeverity severity = ConflictSeverity.MEDIUM;
    
    // Getters and Setters
    
    public String getEntityIRI() {
        return entityIRI;
    }
    
    public void setEntityIRI(String entityIRI) {
        this.entityIRI = entityIRI;
    }
    
    public String getEntityType() {
        return entityType;
    }
    
    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }
    
    public ConflictType getConflictType() {
        return conflictType;
    }
    
    public void setConflictType(ConflictType conflictType) {
        this.conflictType = conflictType;
    }
    
    public String getSourceDefinition() {
        return sourceDefinition;
    }
    
    public void setSourceDefinition(String sourceDefinition) {
        this.sourceDefinition = sourceDefinition;
    }
    
    public String getTargetDefinition() {
        return targetDefinition;
    }
    
    public void setTargetDefinition(String targetDefinition) {
        this.targetDefinition = targetDefinition;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public ConflictSeverity getSeverity() {
        return severity;
    }
    
    public void setSeverity(ConflictSeverity severity) {
        this.severity = severity;
    }
}
