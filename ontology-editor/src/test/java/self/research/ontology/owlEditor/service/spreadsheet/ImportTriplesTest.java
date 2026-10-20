package self.research.ontology.owlEditor.service.spreadsheet;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportTriplesTest {

    private static final String EX = "http://example.org/";
    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private static MutationOp createClass(String parent) {
        return new MutationOp("createClass", EX + "A", null, parent,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void topLevelClassHasNoSubClassOfThing() {
        List<String> triples = ImportTriples.of(createClass(THING));
        assertFalse(triples.stream().anyMatch(t -> t.contains("subClassOf")));
    }

    @Test
    void classWithoutParentHasNoSubClassOf() {
        List<String> triples = ImportTriples.of(createClass(null));
        assertFalse(triples.stream().anyMatch(t -> t.contains("subClassOf")));
    }

    @Test
    void classWithRealParentKeepsSubClassOf() {
        List<String> triples = ImportTriples.of(createClass(EX + "Parent"));
        assertTrue(triples.stream().anyMatch(t -> t.contains("subClassOf") && t.contains(EX + "Parent")));
    }
}
