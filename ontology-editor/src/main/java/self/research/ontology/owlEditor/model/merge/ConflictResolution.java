package self.research.ontology.owlEditor.model.merge;

public class ConflictResolution {

    private ResolutionAction action;
    private String renameSuffix;
    private String notes;

    public ResolutionAction getAction() {
        return action;
    }

    public void setAction(ResolutionAction action) {
        this.action = action;
    }

    public String getRenameSuffix() {
        return renameSuffix;
    }

    public void setRenameSuffix(String renameSuffix) {
        this.renameSuffix = renameSuffix;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
