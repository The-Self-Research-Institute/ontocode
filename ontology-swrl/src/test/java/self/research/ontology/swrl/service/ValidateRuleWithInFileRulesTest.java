package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.ValidationResult;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateRuleWithInFileRulesTest {

    private static final String S = "http://example.org/smoke#";
    private static final String XS = "http://www.w3.org/2001/XMLSchema#";
    private static final String NIL = "http://www.w3.org/1999/02/22-rdf-syntax-ns#nil";

    private static final String RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xml:base="http://example.org/smoke" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
              xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
              xmlns:s="%1$s" xmlns:swrl="http://www.w3.org/2003/11/swrl#">
              <owl:Ontology rdf:about="http://example.org/smoke"/>
              <owl:Class rdf:about="%1$sPerson"/>
              <owl:Class rdf:about="%1$sAdult"/>
              <owl:DatatypeProperty rdf:about="%1$shasAge"><rdfs:range rdf:resource="%2$sinteger"/></owl:DatatypeProperty>
              <owl:NamedIndividual rdf:about="%1$sann"><rdf:type rdf:resource="%1$sPerson"/><s:hasAge rdf:datatype="%2$sinteger">30</s:hasAge></owl:NamedIndividual>
              <rdf:Description><rdf:type rdf:resource="%1$sPerson"/><s:hasAge rdf:datatype="%2$sinteger">40</s:hasAge></rdf:Description>
              <swrl:Variable rdf:about="%1$sp"/>
              <swrl:Variable rdf:about="%1$sa"/>
              <swrl:Imp>
                <swrl:body><swrl:AtomList>
                  <rdf:first><swrl:ClassAtom><swrl:classPredicate rdf:resource="%1$sPerson"/><swrl:argument1 rdf:resource="%1$sp"/></swrl:ClassAtom></rdf:first>
                  <rdf:rest rdf:resource="%3$s"/>
                </swrl:AtomList></swrl:body>
                <swrl:head><swrl:AtomList>
                  <rdf:first><swrl:ClassAtom><swrl:classPredicate rdf:resource="%1$sAdult"/><swrl:argument1 rdf:resource="%1$sp"/></swrl:ClassAtom></rdf:first>
                  <rdf:rest rdf:resource="%3$s"/>
                </swrl:AtomList></swrl:head>
              </swrl:Imp>
            </rdf:RDF>
            """.formatted(S, XS, NIL);

    @Test
    void validatingARuleWorksOnAnOntologyThatAlreadyHasRulesInTheFile() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(RDF));
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        Mockito.when(client.fetchOntology(Mockito.anyString())).thenReturn(ontology);
        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 30);

        ValidationResult result = service.validateRule("p1",
                "Person(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThan(?a, 17) -> Adult(?p)");

        assertTrue(result.isValid(), "validation said: " + result.getErrorMessage());
    }
}
