package self.research.ontology.owlEditor.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClassSearchResult {

    private static final String OWL_THING = "http://www.w3.org/2002/07/owl#Thing";

    private ClassSearchResult() {
    }

    public static List<Map<String, Object>> ancestorsOf(Collection<String> matchIris,
                                                        Map<String, Set<String>> parentsOf) {
        Map<String, Integer> depth = new HashMap<>();
        Map<String, Boolean> visiting = new HashMap<>();
        Map<String, Integer> ancestorDepth = new LinkedHashMap<>();
        for (String match : matchIris) {
            for (String parent : parentsOf.getOrDefault(match, Set.of())) {
                collect(parent, parentsOf, depth, visiting, ancestorDepth);
            }
        }

        List<Map<String, Object>> ancestors = new ArrayList<>();
        ancestorDepth.forEach((iri, d) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", iri);
            a.put("depth", d);
            ancestors.add(a);
        });
        ancestors.sort((x, y) -> Integer.compare((Integer) x.get("depth"), (Integer) y.get("depth")));
        return ancestors;
    }

    private static int collect(String iri, Map<String, Set<String>> parentsOf, Map<String, Integer> depth,
                               Map<String, Boolean> visiting, Map<String, Integer> out) {
        if (OWL_THING.equals(iri)) {
            return -1;
        }
        Integer known = depth.get(iri);
        if (known != null) {
            return known;
        }
        if (visiting.putIfAbsent(iri, Boolean.TRUE) != null) {
            return Integer.MAX_VALUE / 2;
        }
        int best = -1;
        for (String parent : parentsOf.getOrDefault(iri, Set.of())) {
            if (parent.equals(iri)) {
                continue;
            }
            int pd = collect(parent, parentsOf, depth, visiting, out);
            if (pd >= Integer.MAX_VALUE / 2) {
                continue;
            }
            if (best == -1 || pd + 1 < best) {
                best = pd + 1;
            }
        }
        int d = Math.max(best, 0);
        depth.put(iri, d);
        visiting.remove(iri);
        out.put(iri, d);
        return d;
    }
}
