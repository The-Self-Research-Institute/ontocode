package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextRulesThroughEngineTest {

    private static final String NS = "http://example.org/t#";
    private static final String SWRLB = "http://www.w3.org/2003/11/swrlb#";

    private String run(String builtIn, String constant, boolean typedData) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/t"));
        var patient = df.getOWLClass(IRI.create(NS + "Patient"));
        var general = df.getOWLClass(IRI.create(NS + "GeneralWard"));
        var ward = df.getOWLDataProperty(IRI.create(NS + "inWard"));
        var bob = df.getOWLNamedIndividual(IRI.create(NS + "bob"));
        var ann = df.getOWLNamedIndividual(IRI.create(NS + "ann"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(patient));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(general));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(ward));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(patient, bob));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(patient, ann));
        manager.addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(ward, bob, typedData ? df.getOWLLiteral("ICU", OWL2Datatype.XSD_STRING) : df.getOWLLiteral("ICU")));
        manager.addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(ward, ann, typedData ? df.getOWLLiteral("General", OWL2Datatype.XSD_STRING) : df.getOWLLiteral("General")));
        var p = df.getSWRLVariable(IRI.create(NS + "p"));
        var w = df.getSWRLVariable(IRI.create(NS + "w"));
        SWRLAtom builtin = df.getSWRLBuiltInAtom(IRI.create(SWRLB + builtIn),
                Arrays.asList(w, df.getSWRLLiteralArgument(df.getOWLLiteral(constant, OWL2Datatype.XSD_STRING))));
        manager.addAxiom(ontology, df.getSWRLRule(
                Set.of(df.getSWRLClassAtom(patient, p), df.getSWRLDataPropertyAtom(ward, p, w), builtin),
                Set.of(df.getSWRLClassAtom(general, p))));
        StringDocumentTarget target = new StringDocumentTarget();
        manager.saveOntology(ontology, new FunctionalSyntaxDocumentFormat(), target);

        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        return service.inferOntologyRules(target.toString());
    }

    @Test
    void textBuiltInsFireOnPlainAndTypedData() throws Exception {
        for (boolean typed : new boolean[]{false, true}) {
            String contains = run("contains", "Gen", typed);
            assertTrue(contains.contains("ann"), "contains typed=" + typed + ": " + contains);
            assertFalse(contains.contains("bob"));
            String equal = run("equal", "ICU", typed);
            assertTrue(equal.contains("bob"), "equal typed=" + typed + ": " + equal);
            assertFalse(equal.contains("ann"));
        }
    }
}
