package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.springframework.test.util.ReflectionTestUtils;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferOntologyRulesTest {

    private static final String NS = "http://example.org/t#";

    @Test
    void builtInRuleInTheOntologyIsFiredAndInFileRulesAreKept() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/t"));
        var person = df.getOWLClass(IRI.create(NS + "Person"));
        var senior = df.getOWLClass(IRI.create(NS + "Senior"));
        var age = df.getOWLDataProperty(IRI.create(NS + "hasAge"));
        var next = df.getOWLDataProperty(IRI.create(NS + "nextAge"));
        var ann = df.getOWLNamedIndividual(IRI.create(NS + "ann"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(person));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(senior));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(age));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(next));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(person, ann));
        manager.addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(age, ann, 64));
        var blank = df.getOWLAnonymousIndividual();
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(person, blank));
        manager.addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(age, blank, 40));
        var x = df.getSWRLVariable(IRI.create(NS + "x"));
        var v = df.getSWRLVariable(IRI.create(NS + "v"));
        var w = df.getSWRLVariable(IRI.create(NS + "w"));
        SWRLAtom add = df.getSWRLBuiltInAtom(IRI.create("http://www.w3.org/2003/11/swrlb#add"),
                java.util.Arrays.asList(w, v, df.getSWRLLiteralArgument(df.getOWLLiteral(1))));
        manager.addAxiom(ontology, df.getSWRLRule(
                java.util.Set.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), add),
                java.util.Set.of(df.getSWRLDataPropertyAtom(next, x, w))));
        StringDocumentTarget target = new StringDocumentTarget();
        manager.saveOntology(ontology, new FunctionalSyntaxDocumentFormat(), target);

        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 30);
        String answer = service.inferOntologyRules(target.toString());

        OWLOntology inferred = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(answer));
        assertTrue(inferred.getAxioms().stream().anyMatch(a -> a.toString().contains("nextAge") && a.toString().contains("65")),
                "expected nextAge 65 in: " + answer);
        assertFalse(inferred.getAxioms().stream().anyMatch(a -> a.toString().contains("hasAge")));
    }
}
