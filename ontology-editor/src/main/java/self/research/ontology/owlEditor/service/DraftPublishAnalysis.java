package self.research.ontology.owlEditor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of checking whether a user's draft can be published safely.
 */
public class DraftPublishAnalysis {

    public enum ConflictType {
        NONE,
        MAIN_CHANGED,
        IRI_OVERLAP
    }

    private final ConflictType conflictType;
    private final boolean mainChangedSinceDraft;
    private final long baselineRevision;
    private final long currentRevision;
    private final long baselineTripleCount;
    private final long currentTripleCount;
    private final List<Map<String, Object>> conflicts;

    public DraftPublishAnalysis(ConflictType conflictType,
                                boolean mainChangedSinceDraft,
                                long baselineRevision,
                                long currentRevision,
                                long baselineTripleCount,
                                long currentTripleCount,
                                List<Map<String, Object>> conflicts) {
        this.conflictType = conflictType;
        this.mainChangedSinceDraft = mainChangedSinceDraft;
        this.baselineRevision = baselineRevision;
        this.currentRevision = currentRevision;
        this.baselineTripleCount = baselineTripleCount;
        this.currentTripleCount = currentTripleCount;
        this.conflicts = conflicts != null ? conflicts : new ArrayList<>();
    }

    public boolean isBlocked(boolean force) {
        if (force) {
            return false;
        }
        return conflictType == ConflictType.IRI_OVERLAP
                || (conflictType == ConflictType.MAIN_CHANGED && mainChangedSinceDraft);
    }

    public ConflictType getConflictType() { return conflictType; }
    public boolean isMainChangedSinceDraft() { return mainChangedSinceDraft; }
    public long getBaselineRevision() { return baselineRevision; }
    public long getCurrentRevision() { return currentRevision; }
    public long getBaselineTripleCount() { return baselineTripleCount; }
    public long getCurrentTripleCount() { return currentTripleCount; }
    public List<Map<String, Object>> getConflicts() { return conflicts; }

    public Map<String, Object> toResponseMap() {
        return Map.of(
                "conflictType", conflictType.name(),
                "mainChangedSinceDraft", mainChangedSinceDraft,
                "baselineRevision", baselineRevision,
                "currentRevision", currentRevision,
                "baselineTripleCount", baselineTripleCount,
                "currentTripleCount", currentTripleCount,
                "conflicts", conflicts
        );
    }
}
