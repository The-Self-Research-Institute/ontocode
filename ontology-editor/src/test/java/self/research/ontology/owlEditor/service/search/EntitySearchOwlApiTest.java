package self.research.ontology.owlEditor.service.search;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.service.search.EntitySearch.EntityType;
import self.research.ontology.owlEditor.service.search.EntitySearch.SearchRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntitySearchOwlApiTest {

    private static final String NS = "http://ex.org/t#";

    private static String labelOf(int i) {
        return (i % 7 == 0 ? "Alpha " : "Beta ") + i;
    }

    private static OWLOntology ontologyWith(int classes) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://ex.org/t"));
        for (int i = 0; i < classes; i++) {
            var cls = df.getOWLClass(IRI.create(NS + "C" + i));
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls.getIRI(),
                    df.getOWLLiteral(labelOf(i))));
        }
        return ontology;
    }

    @SuppressWarnings("unchecked")
    private static List<String> classIds(Map<String, Object> response) {
        Map<String, Object> groups = (Map<String, Object>) response.get("groups");
        List<String> ids = new ArrayList<>();
        for (Object group : groups.values()) {
            if (group instanceof List<?> hits) {
                for (Object hit : hits) {
                    if (hit instanceof Map<?, ?> m && String.valueOf(m.get("id")).startsWith(NS + "C")) {
                        ids.add(String.valueOf(m.get("id")));
                    }
                }
            }
        }
        return ids;
    }

    private static List<String> search(OWLOntology ontology, String query) {
        SearchRequest request = new SearchRequest(SearchMatcher.of(query, false, false, false, false),
                EntityType.parse(null), false, false, false, 5000, true);
        return classIds(new EntitySearchOwlApi(new HierarchySnapshotBuilder()).search(ontology, request, Imports.EXCLUDED));
    }

    private static List<String> expected(int classes, boolean alpha) {
        return IntStream.range(0, classes).filter(i -> (i % 7 == 0) == alpha).boxed()
                .sorted(Comparator.comparing((Integer i) -> labelOf(i).toLowerCase()).thenComparing(i -> NS + "C" + i))
                .map(i -> NS + "C" + i).collect(Collectors.toList());
    }

    @Test
    void aLargeOntologyReturnsTheRightHitsInTheRightOrder() throws Exception {
        int size = 3000;

        assertEquals(expected(size, true), search(ontologyWith(size), "alpha"));
    }

    @Test
    void aSmallOntologyStillReturnsTheRightHitsInOrder() throws Exception {
        OWLOntology ontology = ontologyWith(50);

        assertEquals(expected(50, false), search(ontology, "beta"));
        assertEquals(List.of(), search(ontology, "nothingmatches"));
    }
}
