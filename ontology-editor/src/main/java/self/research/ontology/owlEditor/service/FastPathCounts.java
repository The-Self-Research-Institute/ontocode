package self.research.ontology.owlEditor.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class FastPathCounts {

    static final long LARGE_AXIOMS = 200_000;
    static final long LARGE_TRIPLES = 600_000;
    static final long LARGE_CLASSES = 50_000;

    private FastPathCounts() {
    }

    static boolean large(Map<String, Object> saved) {
        return number(saved, "axiomCount") > LARGE_AXIOMS
                || number(saved, "tripleCount") > LARGE_TRIPLES
                || number(saved, "classCount") > LARGE_CLASSES;
    }

    static Map<String, Supplier<Map<String, Object>>> tasks(OntologyMetrics metrics, String projectId,
                                                           Map<String, Object> saved, boolean liveModel) {
        Map<String, Supplier<Map<String, Object>>> tasks = new LinkedHashMap<>();
        if (!liveModel && large(saved)) {
            return tasks;
        }
        tasks.put("annotationPropertyCount", () -> Map.of("annotationPropertyCount", metrics.annotationPropertyCount(projectId)));
        tasks.put("datatypeCount", () -> Map.of("datatypeCount", metrics.datatypeCount(projectId)));
        tasks.put("propertyAndAssertion", () -> metrics.getPropertyAndAssertionAxiomCounts(projectId));
        tasks.put("owlAxioms", () -> {
            OntologyMetrics.OwlApiAxiomCounts counts = metrics.getOwlApiAxiomCounts(projectId);
            if (counts.total() == 0) {
                CachedMetadataCounts.markFailed();
            }
            return Map.of("axiomCount", counts.total(), "logicalAxiomCount", counts.logical(),
                    "declarationAxiomCount", counts.declarations());
        });
        return tasks;
    }

    private static long number(Map<String, Object> saved, String key) {
        return saved.get(key) instanceof Number value ? value.longValue() : 0L;
    }
}
