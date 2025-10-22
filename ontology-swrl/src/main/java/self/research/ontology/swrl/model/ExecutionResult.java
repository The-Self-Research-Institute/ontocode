package self.research.ontology.swrl.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {
    private boolean success;
    private long executionTimeMs;
    private int inferredAxiomsCount;
    private List<InferredAxiom> inferredAxioms;
    private String errorMessage;
}