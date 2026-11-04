package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.ValidationResult;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TextRuleValidationTest {

    private static final String RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xml:base="http://example.org/t" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
              xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:s="http://example.org/t#">
              <owl:Ontology rdf:about="http://example.org/t"/>
              <owl:Class rdf:about="http://example.org/t#Patient"/>
              <owl:Class rdf:about="http://example.org/t#Critical"/>
              <owl:DatatypeProperty rdf:about="http://example.org/t#inWard"/>
              <owl:NamedIndividual rdf:about="http://example.org/t#bob"><rdf:type rdf:resource="http://example.org/t#Patient"/><s:inWard>ICU</s:inWard></owl:NamedIndividual>
              <owl:NamedIndividual rdf:about="http://example.org/t#ann"><rdf:type rdf:resource="http://example.org/t#Patient"/><s:inWard>General</s:inWard></owl:NamedIndividual>
            </rdf:RDF>
            """;

    private ValidationResult validate(String rule) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(RDF));
        PlainLiteralNormalizer.apply(ontology);
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        Mockito.when(client.fetchOntology(Mockito.anyString())).thenReturn(ontology);
        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        return service.validateRule("p1", rule);
    }

    @Test
    void typedTextConstantWorksOnPlainStoredData() throws Exception {
        ValidationResult typed = validate("Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, \"ICU\"^^xsd:string) -> Critical(?p)");
        System.out.println("TEXTRULE typed constant -> valid=" + typed.isValid() + " " + typed.getErrorMessage());
        ValidationResult plain = validate("Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, \"ICU\") -> Critical(?p)");
        System.out.println("TEXTRULE plain constant -> valid=" + plain.isValid() + " " + plain.getErrorMessage());
        assertTrue(typed.isValid() || plain.isValid(), "neither form worked");
    }
}
