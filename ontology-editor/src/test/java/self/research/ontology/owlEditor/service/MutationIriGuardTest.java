package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationIriGuardTest {

    private static MutationOp create(String type, String iri) {
        return MutationOp.forTypeAssertion(type, iri, "x");
    }

    @Test
    void rejectsDoubleHashOnCreate() {
        var problem = MutationIriGuard.firstProblem(List.of(create("createClass", "http://ex.org/o##dollar")));
        assertTrue(problem.isPresent());
        assertTrue(problem.get().contains("more than one '#'"));
    }

    @Test
    void rejectsInvalidCharactersOnCreate() {
        assertTrue(MutationIriGuard.firstProblem(List.of(create("createObjectProperty", "http://ex.org/o#a b"))).isPresent());
        assertTrue(MutationIriGuard.firstProblem(List.of(create("createIndividual", "http://ex.org/o#a<b"))).isPresent());
    }

    @Test
    void acceptsNormalCreateAndIgnoresNonCreateOps() {
        assertFalse(MutationIriGuard.firstProblem(List.of(create("createClass", "http://ex.org/o#Dollar"))).isPresent());
        assertFalse(MutationIriGuard.firstProblem(List.of(create("deleteClass", "http://ex.org/o##old"))).isPresent());
    }
}
