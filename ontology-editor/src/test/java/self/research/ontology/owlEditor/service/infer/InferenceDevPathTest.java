package self.research.ontology.owlEditor.service.infer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.SparqlQueryScope;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceDevPathTest {

    InferenceTestKit kit;
    SparqlQueryScope scope;

    @BeforeEach
    void setUp() {
        kit = new InferenceTestKit();
        scope = InferenceFixtures.publicScope();
    }

    private void load(String body) {
        kit.source.set(scope, InferenceFixtures.turtle(InferenceEqualityTest.BASE + body, scope.graphs().get(0)));
    }

    @Test
    void declaredPairWithSameAsAndDifferentFromInOneInsertIsInconsistent() {
        load(":Bob a owl:NamedIndividual . :Carol a owl:NamedIndividual . :Bob owl:sameAs :Carol . :Bob owl:differentFrom :Carol . ");
        InconsistentOntologyException e = assertThrows(InconsistentOntologyException.class,
                () -> kit.askInferred(scope, null, ":Bob a :Person", true));
        assertTrue(e.explanation().contains("http://e/Bob") && e.explanation().contains("http://e/Carol"), e.explanation());
    }

    @Test
    void pairWithoutAnyDeclarationAndWithoutTypesIsStillInconsistent() {
        load(":Bob owl:sameAs :Carol . :Bob owl:differentFrom :Carol . ");
        assertThrows(InconsistentOntologyException.class, () -> kit.askInferred(scope, null, ":Bob a :Person", true));
    }

    @Test
    void sameAsToAnUndeclaredIriStillSharesTheFacts() {
        load(":DrSmith owl:sameAs :DraftTwin . ");
        assertTrue(kit.askInferred(scope, null, ":DraftTwin a :Doctor", true));
        assertTrue(kit.askInferred(scope, null, ":DraftTwin :worksAt :HospitalA", false));
        assertFalse(kit.askAsserted(scope, ":DraftTwin a :Doctor"));
    }

    @Test
    void sameAsFromAnUndeclaredIriStillSharesTheFacts() {
        load(":DraftTwin owl:sameAs :DrSmith . ");
        assertTrue(kit.askInferred(scope, null, ":DraftTwin a :Doctor", true));
    }
}
