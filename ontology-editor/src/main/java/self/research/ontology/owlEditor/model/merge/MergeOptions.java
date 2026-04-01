package self.research.ontology.owlEditor.model.merge;

import java.util.HashMap;
import java.util.Map;

/**
 * Options for configuring how ontologies should be merged
 */
public class MergeOptions {
    
    private MergeStrategy strategy = MergeStrategy.SIMPLE_UNION;
    private String renameSuffix = "_imported";
    private boolean preserveSourceAnnotations = true;
    private boolean preserveTargetAnnotations = true;
    private boolean mergeOntologyAnnotations = false;
    private Map<String, ConflictResolution> conflictResolutions = new HashMap<>();
    
    // Getters and Setters
    
    public MergeStrategy getStrategy() {
        return strategy;
    }
    
    public void setStrategy(MergeStrategy strategy) {
        this.strategy = strategy;
    }
    
    public String getRenameSuffix() {
        return renameSuffix;
    }
    
    public void setRenameSuffix(String renameSuffix) {
        this.renameSuffix = renameSuffix;
    }
    
    public boolean isPreserveSourceAnnotations() {
        return preserveSourceAnnotations;
    }
    
    public void setPreserveSourceAnnotations(boolean preserveSourceAnnotations) {
        this.preserveSourceAnnotations = preserveSourceAnnotations;
    }
    
    public boolean isPreserveTargetAnnotations() {
        return preserveTargetAnnotations;
    }
    
    public void setPreserveTargetAnnotations(boolean preserveTargetAnnotations) {
        this.preserveTargetAnnotations = preserveTargetAnnotations;
    }
    
    public boolean isMergeOntologyAnnotations() {
        return mergeOntologyAnnotations;
    }
    
    public void setMergeOntologyAnnotations(boolean mergeOntologyAnnotations) {
        this.mergeOntologyAnnotations = mergeOntologyAnnotations;
    }
    
    public Map<String, ConflictResolution> getConflictResolutions() {
        return conflictResolutions;
    }
    
    public void setConflictResolutions(Map<String, ConflictResolution> conflictResolutions) {
        this.conflictResolutions = conflictResolutions;
    }
    
    public void addConflictResolution(String entityIRI, ConflictResolution resolution) {
        this.conflictResolutions.put(entityIRI, resolution);
    }
}
