package self.research.ontology.swrl.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import self.research.ontology.swrl.model.InferredAxiom;

import java.util.List;

@Data
@AllArgsConstructor
public class ExecutionResponse {
    private boolean success;
    private long executionTimeMs;
    private int inferredAxiomsCount;
    private int rulesExecuted;
    private List<InferredAxiom> inferredAxioms;
    private String message;
}