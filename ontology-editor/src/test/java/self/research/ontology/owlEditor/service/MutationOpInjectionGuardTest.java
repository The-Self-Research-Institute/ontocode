package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationOpInjectionGuardTest {

    private static final String EVIL = "x> ?p ?o } ; DROP ALL ; #";

    private static MutationOp op(String type, String iri, String parent, String property, String target,
                                 String classIri, String language, String datatype, String ancestorIri) {
        return new MutationOp(type, iri, "label", parent, property, "value", target, classIri, null, null, null, null,
                language, datatype, ancestorIri);
    }

    private static boolean refused(MutationOp op) {
        return MutationIriGuard.firstProblem(List.of(op)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"updateClassLabel", "deleteClass", "addAnnotation", "addSubClassOf", "removeSubClassOf",
            "updateAnnotation", "addDisjointUnion", "createClass"})
    void anInjectedIriIsRefusedOnEveryKindOfOperation(String type) {
        assertTrue(refused(op(type, "http://ex.org/o#" + EVIL, null, null, null, null, null, null, null)), type);
    }

    @Test
    void everyIriCarryingFieldIsChecked() {
        assertTrue(refused(op("addSubClassOf", "http://ex.org/o#A", EVIL, null, null, null, null, null, null)));
        assertTrue(refused(op("addAnnotation", "http://ex.org/o#A", null, EVIL, null, null, null, null, null)));
        assertTrue(refused(op("addSubClassOf", "http://ex.org/o#A", null, null, EVIL, null, null, null, null)));
        assertTrue(refused(op("addObjectRestriction", "http://ex.org/o#A", null, null, null, EVIL, null, null, null)));
        assertTrue(refused(op("addSubClassOf", "http://ex.org/o#A", null, null, null, null, null, null, EVIL)));
    }

    @Test
    void aLanguageTagCannotCarrySparql() {
        assertTrue(refused(op("addAnnotation", "http://ex.org/o#A", null, "http://ex.org/p", null, null,
                "en . } ; DROP ALL ; #", null, null)));
    }

    @Test
    void aDatatypeCannotCarrySparql() {
        assertTrue(refused(op("addAnnotation", "http://ex.org/o#A", null, "http://ex.org/p", null, null, null,
                "xsd:string . } ; DROP ALL ; #", null)));
        assertTrue(refused(op("addAnnotation", "http://ex.org/o#A", null, "http://ex.org/p", null, null, null,
                "http://www.w3.org/2001/XMLSchema#string> . } ; DROP ALL ; #", null)));
    }

    @Test
    void ordinaryOperationsAreStillAccepted() {
        assertFalse(refused(op("addAnnotation", "http://ex.org/o#A", null, "http://www.w3.org/2000/01/rdf-schema#label",
                null, null, "en-GB", null, null)));
        assertFalse(refused(op("addAnnotation", "http://ex.org/o#A", null, "http://ex.org/p", null, null, null,
                "xsd:string", null)));
        assertFalse(refused(op("addAnnotation", "http://ex.org/o#A", null, "http://ex.org/p", null, null, null,
                "http://www.w3.org/2001/XMLSchema#integer", null)));
        assertFalse(refused(op("addSubClassOf", "http://ex.org/o#A", "owl:Thing", null, "http://ex.org/o#B", null, null,
                null, null)));
        assertFalse(refused(op("deleteClass", "http://ex.org/o##old", null, null, null, null, null, null, null)));
    }
}
