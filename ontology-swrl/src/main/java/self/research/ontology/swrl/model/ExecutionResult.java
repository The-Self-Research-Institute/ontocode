package self.research.ontology.swrl.model;

import java.util.List;
import java.util.ArrayList;

public class ExecutionResult {
    private boolean success;
    private long executionTimeMs;
    private int inferredAxiomsCount;
    private List<InferredAxiom> inferredAxioms;
    private String errorMessage;
    private List<String> executedRuleNames = new ArrayList<>();
    private List<String> executedRuleIds = new ArrayList<>();
    private String executionMode = "all"; // "all" or "selected"
    private self.research.ontology.common.preview.PreviewStamp stamp;
    private int conclusionCount;
    private int structureCount;
    private int omittedTrivialCount;
    private boolean truncated;
    private List<self.research.ontology.swrl.model.SkippedRule> skippedRules = new ArrayList<>();
    private String completion = "completed";

    public ExecutionResult() {}

    public ExecutionResult(boolean success, long executionTimeMs, int inferredAxiomsCount,
                          List<InferredAxiom> inferredAxioms, String errorMessage) {
        this.success = success;
        this.executionTimeMs = executionTimeMs;
        this.inferredAxiomsCount = inferredAxiomsCount;
        this.inferredAxioms = inferredAxioms;
        this.errorMessage = errorMessage;
        this.completion = success ? "completed" : "failed";
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

    public List<String> getExecutedRuleNames() { return executedRuleNames; }
    public void setExecutedRuleNames(List<String> executedRuleNames) { this.executedRuleNames = executedRuleNames; }

    public List<String> getExecutedRuleIds() { return executedRuleIds; }
    public void setExecutedRuleIds(List<String> executedRuleIds) { this.executedRuleIds = executedRuleIds; }

    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }

    public self.research.ontology.common.preview.PreviewStamp getStamp() { return stamp; }
    public void setStamp(self.research.ontology.common.preview.PreviewStamp stamp) { this.stamp = stamp; }

    public int getConclusionCount() { return conclusionCount; }
    public void setConclusionCount(int conclusionCount) { this.conclusionCount = conclusionCount; }
    public int getStructureCount() { return structureCount; }
    public void setStructureCount(int structureCount) { this.structureCount = structureCount; }
    public int getOmittedTrivialCount() { return omittedTrivialCount; }
    public void setOmittedTrivialCount(int omittedTrivialCount) { this.omittedTrivialCount = omittedTrivialCount; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
    public List<self.research.ontology.swrl.model.SkippedRule> getSkippedRules() { return skippedRules; }
    public void setSkippedRules(List<self.research.ontology.swrl.model.SkippedRule> skippedRules) { this.skippedRules = skippedRules; }

    public String getCompletion() { return completion; }
    public void setCompletion(String completion) { this.completion = completion; }
}
