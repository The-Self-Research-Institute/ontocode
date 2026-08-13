package self.research.ontology.owlEditor.model.merge;

public class MergeResult {

    private boolean success;
    private String message;
    private String targetProjectId;
    private int axiomsAdded;
    private int axiomsReplaced;
    private int axiomsRemoved;
    private int entitiesRenamed;
    private int conflictsResolved;
    private long durationMs;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTargetProjectId() {
        return targetProjectId;
    }

    public void setTargetProjectId(String targetProjectId) {
        this.targetProjectId = targetProjectId;
    }

    public int getAxiomsAdded() {
        return axiomsAdded;
    }

    public void setAxiomsAdded(int axiomsAdded) {
        this.axiomsAdded = axiomsAdded;
    }

    public int getAxiomsReplaced() {
        return axiomsReplaced;
    }

    public void setAxiomsReplaced(int axiomsReplaced) {
        this.axiomsReplaced = axiomsReplaced;
    }

    public int getAxiomsRemoved() {
        return axiomsRemoved;
    }

    public void setAxiomsRemoved(int axiomsRemoved) {
        this.axiomsRemoved = axiomsRemoved;
    }

    public int getEntitiesRenamed() {
        return entitiesRenamed;
    }

    public void setEntitiesRenamed(int entitiesRenamed) {
        this.entitiesRenamed = entitiesRenamed;
    }

    public int getConflictsResolved() {
        return conflictsResolved;
    }

    public void setConflictsResolved(int conflictsResolved) {
        this.conflictsResolved = conflictsResolved;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
