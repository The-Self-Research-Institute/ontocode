package self.research.ontology.swrl.model;

import java.util.List;

public class ExecutionResult {
    private boolean success;
    private long executionTimeMs;
    private int inferredAxiomsCount;
    private List<InferredAxiom> inferredAxioms;
    private String errorMessage;

    public ExecutionResult() {}

    public ExecutionResult(boolean success, long executionTimeMs, int inferredAxiomsCount,
                          List<InferredAxiom> inferredAxioms, String errorMessage) {
        this.success = success;
        this.executionTimeMs = executionTimeMs;
        this.inferredAxiomsCount = inferredAxiomsCount;
        this.inferredAxioms = inferredAxioms;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public int getInferredAxiomsCount() { return inferredAxiomsCount; }
    public void setInferredAxiomsCount(int inferredAxiomsCount) { this.inferredAxiomsCount = inferredAxiomsCount; }

    public List<InferredAxiom> getInferredAxioms() { return inferredAxioms; }
    public void setInferredAxioms(List<InferredAxiom> inferredAxioms) { this.inferredAxioms = inferredAxioms; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}