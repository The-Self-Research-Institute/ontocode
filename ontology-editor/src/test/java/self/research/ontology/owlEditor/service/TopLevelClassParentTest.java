package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopLevelClassParentTest {

    @Test
    void owlThingIsNotARealParent() {
        assertFalse(OntologyMutationService.isRealParentClass("http://www.w3.org/2002/07/owl#Thing"));
        assertFalse(OntologyMutationService.isRealParentClass("owl:Thing"));
    }

    @Test
    void missingParentIsNotARealParent() {
        assertFalse(OntologyMutationService.isRealParentClass(null));
        assertFalse(OntologyMutationService.isRealParentClass(" "));
    }

    @Test
    void namedClassIsARealParent() {
        assertTrue(OntologyMutationService.isRealParentClass("http://example.org/Parent"));
    }
}
