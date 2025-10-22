package self.research.ontology.owlEditor.service;

public class LoadRequest {
    private String projectId;
    private String owlFileContent;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getOwlFileContent() {
        return owlFileContent;
    }

    public void setOwlFileContent(String owlFileContent) {
        this.owlFileContent = owlFileContent;
    }
}