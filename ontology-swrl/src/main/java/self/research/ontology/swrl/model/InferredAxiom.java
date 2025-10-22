package self.research.ontology.swrl.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InferredAxiom {
    private String axiomType;
    private String description;
    private String readable;
}