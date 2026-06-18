package self.research.ontology.reasoner.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SubmitReasoningJobRequest {

    @NotNull
    private ReasoningJob.JobType jobType;

    @NotBlank
    private String projectId;

    private String expression;
    private List<String> queryTypes;
    private String reasonerType;
    private String ownerEmail;
    private Map<String, Object> params;
}
