package self.research.ontology.owlEditor.service;

import java.time.Duration;

public class ProjectWriteLeaseUnavailableException extends RuntimeException {

    private final String projectId;
    private final Duration waited;

    public ProjectWriteLeaseUnavailableException(String projectId, Duration waited, Throwable cause) {
        super("Another server is still writing to project " + projectId
                + "; could not get its write lease within " + waited.toMillis() + " ms", cause);
        this.projectId = projectId;
        this.waited = waited;
    }

    public String getProjectId() {
        return projectId;
    }

    public Duration getWaited() {
        return waited;
    }
}
