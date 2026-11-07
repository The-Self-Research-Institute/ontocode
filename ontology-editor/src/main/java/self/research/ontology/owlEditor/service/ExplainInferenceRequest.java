package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExplainInferenceRequest(
        String axiomType,
        String subjectIri,
        String predicateIri,
        String objectIri,
        String objectLiteral,
        String literalDatatypeIri,
        List<String> objectIris,
        String objectOperator,
        String reasoner) {
}
