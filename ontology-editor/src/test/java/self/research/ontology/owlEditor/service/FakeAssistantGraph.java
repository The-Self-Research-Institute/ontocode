package self.research.ontology.owlEditor.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

final class FakeAssistantGraph {

    private static final Pattern IRI = Pattern.compile("<([^>]*)>");

    final Set<String> existing = new HashSet<>();
    final Map<String, Set<String>> kinds = new HashMap<>();
    final List<String> queries = new ArrayList<>();
    boolean everythingExists;
    RuntimeException failure;

    static FakeAssistantGraph installOn(SparqlDatasetService datasetService) {
        FakeAssistantGraph graph = new FakeAssistantGraph();
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> graph.answer(inv.getArgument(1)));
        return graph;
    }

    FakeAssistantGraph declare(String iri, String kind) {
        kinds.computeIfAbsent(iri, k -> new HashSet<>()).add(kind);
        return this;
    }

    List<String> lookedUp() {
        List<String> iris = new ArrayList<>();
        for (String query : queries) {
            if (!query.contains("?t")) {
                iris.addAll(valuesOf(query));
            }
        }
        return iris;
    }

    private SparqlDatasetService.CappedSparqlResult answer(String query) {
        queries.add(query);
        if (failure != null) {
            throw failure;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        if (query.contains("?t")) {
            for (String iri : valuesOf(query)) {
                for (String kind : kinds.getOrDefault(iri, Set.of())) {
                    rows.add(Map.of("x", iri, "t", kind));
                }
            }
            return new SparqlDatasetService.CappedSparqlResult(List.of("x", "t"), rows, false, null);
        }
        for (String iri : valuesOf(query)) {
            if (everythingExists || existing.contains(iri) || kinds.containsKey(iri)) {
                rows.add(Map.of("x", iri));
            }
        }
        return new SparqlDatasetService.CappedSparqlResult(List.of("x"), rows, false, null);
    }

    private static List<String> valuesOf(String query) {
        int start = query.indexOf("VALUES ?x {");
        int end = query.indexOf('}', start);
        List<String> iris = new ArrayList<>();
        Matcher matcher = IRI.matcher(query.substring(start, end));
        while (matcher.find()) {
            iris.add(matcher.group(1));
        }
        return iris;
    }
}
