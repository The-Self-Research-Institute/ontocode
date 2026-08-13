package self.research.ontology.owlEditor.model.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MergeAnalysisResult {

    private List<MergeConflict> conflicts = new ArrayList<>();
    private int sourceClassCount;
    private int sourcePropertyCount;
    private int targetClassCount;
    private int targetPropertyCount;
    private int sourceIndividualCount;
    private int targetIndividualCount;
    private int sourceOnlyAxiomCount;
    private int sourceOnlyClassCount;
    private int sourceOnlyPropertyCount;
    private int sourceOnlyIndividualCount;
    private int targetOnlyAxiomCount;
    private int targetOnlyClassCount;
    private int targetOnlyPropertyCount;
    private int targetOnlyIndividualCount;
    private List<String> sourceOnlyClasses = new ArrayList<>();
    private List<String> sourceOnlyProperties = new ArrayList<>();
    private List<String> sourceOnlyIndividuals = new ArrayList<>();
    private Map<String, String> sourceOnlyClassLabels = new HashMap<>();
    private Map<String, String> sourceOnlyPropertyLabels = new HashMap<>();
    private Map<String, String> sourceOnlyIndividualLabels = new HashMap<>();

    private Map<String, List<String>> classHierarchy = new HashMap<>();
    private Map<String, List<String>> propertyHierarchy = new HashMap<>();

    public void addConflict(MergeConflict conflict) {
        this.conflicts.add(conflict);
    }

    public int getTotalConflicts() {
        return conflicts.size();
    }

    public int getConflictsByType(ConflictType type) {
        return (int) conflicts.stream()
                            .filter(c -> c.getConflictType() == type)
                            .count();
    }

    public List<MergeConflict> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<MergeConflict> conflicts) {
        this.conflicts = conflicts;
    }

    public int getSourceClassCount() {
        return sourceClassCount;
    }

    public void setSourceClassCount(int sourceClassCount) {
        this.sourceClassCount = sourceClassCount;
    }

    public int getSourcePropertyCount() {
        return sourcePropertyCount;
    }

    public void setSourcePropertyCount(int sourcePropertyCount) {
        this.sourcePropertyCount = sourcePropertyCount;
    }

    public int getTargetClassCount() {
        return targetClassCount;
    }

    public void setTargetClassCount(int targetClassCount) {
        this.targetClassCount = targetClassCount;
    }

    public int getTargetPropertyCount() {
        return targetPropertyCount;
    }

    public void setTargetPropertyCount(int targetPropertyCount) {
        this.targetPropertyCount = targetPropertyCount;
    }

    public int getSourceIndividualCount() {
        return sourceIndividualCount;
    }

    public void setSourceIndividualCount(int sourceIndividualCount) {
        this.sourceIndividualCount = sourceIndividualCount;
    }

    public int getTargetIndividualCount() {
        return targetIndividualCount;
    }

    public void setTargetIndividualCount(int targetIndividualCount) {
        this.targetIndividualCount = targetIndividualCount;
    }

    public int getSourceOnlyAxiomCount() {
        return sourceOnlyAxiomCount;
    }

    public void setSourceOnlyAxiomCount(int sourceOnlyAxiomCount) {
        this.sourceOnlyAxiomCount = sourceOnlyAxiomCount;
    }

    public int getSourceOnlyClassCount() {
        return sourceOnlyClassCount;
    }

    public void setSourceOnlyClassCount(int sourceOnlyClassCount) {
        this.sourceOnlyClassCount = sourceOnlyClassCount;
    }

    public int getSourceOnlyPropertyCount() {
        return sourceOnlyPropertyCount;
    }

    public void setSourceOnlyPropertyCount(int sourceOnlyPropertyCount) {
        this.sourceOnlyPropertyCount = sourceOnlyPropertyCount;
    }

    public int getSourceOnlyIndividualCount() {
        return sourceOnlyIndividualCount;
    }

    public void setSourceOnlyIndividualCount(int sourceOnlyIndividualCount) {
        this.sourceOnlyIndividualCount = sourceOnlyIndividualCount;
    }

    public int getTargetOnlyAxiomCount() {
        return targetOnlyAxiomCount;
    }

    public void setTargetOnlyAxiomCount(int targetOnlyAxiomCount) {
        this.targetOnlyAxiomCount = targetOnlyAxiomCount;
    }

    public int getTargetOnlyClassCount() {
        return targetOnlyClassCount;
    }

    public void setTargetOnlyClassCount(int targetOnlyClassCount) {
        this.targetOnlyClassCount = targetOnlyClassCount;
    }

    public int getTargetOnlyPropertyCount() {
        return targetOnlyPropertyCount;
    }

    public void setTargetOnlyPropertyCount(int targetOnlyPropertyCount) {
        this.targetOnlyPropertyCount = targetOnlyPropertyCount;
    }

    public int getTargetOnlyIndividualCount() {
        return targetOnlyIndividualCount;
    }

    public void setTargetOnlyIndividualCount(int targetOnlyIndividualCount) {
        this.targetOnlyIndividualCount = targetOnlyIndividualCount;
    }

    public List<String> getSourceOnlyClasses() {
        return sourceOnlyClasses;
    }

    public void setSourceOnlyClasses(List<String> sourceOnlyClasses) {
        this.sourceOnlyClasses = sourceOnlyClasses;
    }

    public List<String> getSourceOnlyProperties() {
        return sourceOnlyProperties;
    }

    public void setSourceOnlyProperties(List<String> sourceOnlyProperties) {
        this.sourceOnlyProperties = sourceOnlyProperties;
    }

    public List<String> getSourceOnlyIndividuals() {
        return sourceOnlyIndividuals;
    }

    public void setSourceOnlyIndividuals(List<String> sourceOnlyIndividuals) {
        this.sourceOnlyIndividuals = sourceOnlyIndividuals;
    }

    public Map<String, String> getSourceOnlyClassLabels() {
        return sourceOnlyClassLabels;
    }

    public void setSourceOnlyClassLabels(Map<String, String> sourceOnlyClassLabels) {
        this.sourceOnlyClassLabels = sourceOnlyClassLabels;
    }

    public Map<String, String> getSourceOnlyPropertyLabels() {
        return sourceOnlyPropertyLabels;
    }

    public void setSourceOnlyPropertyLabels(Map<String, String> sourceOnlyPropertyLabels) {
        this.sourceOnlyPropertyLabels = sourceOnlyPropertyLabels;
    }

    public Map<String, String> getSourceOnlyIndividualLabels() {
        return sourceOnlyIndividualLabels;
    }

    public void setSourceOnlyIndividualLabels(Map<String, String> sourceOnlyIndividualLabels) {
        this.sourceOnlyIndividualLabels = sourceOnlyIndividualLabels;
    }

    public Map<String, List<String>> getClassHierarchy() {
        return classHierarchy;
    }

    public void setClassHierarchy(Map<String, List<String>> classHierarchy) {
        this.classHierarchy = classHierarchy;
    }

    public Map<String, List<String>> getPropertyHierarchy() {
        return propertyHierarchy;
    }

    public void setPropertyHierarchy(Map<String, List<String>> propertyHierarchy) {
        this.propertyHierarchy = propertyHierarchy;
    }
}
