package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLAxiom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuleRunPager {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    private RuleRunPager() {
    }

    public static Map<String, Object> page(RuleRunStore.Run run, String kind, String query, int offset, int limit) {
        boolean conclusions = "conclusions".equals(kind);
        List<OWLAxiom> source = conclusions ? run.conclusions() : run.structure();
        String category = conclusions ? "conclusion" : "structure";
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = new ArrayList<>();
        int total = 0;
        for (OWLAxiom axiom : source) {
            Map<String, Object> item = null;
            if (!needle.isEmpty()) {
                item = RuleExecutionPresenter.toJson(axiom, category);
                if (!matchesQuery(item, needle)) {
                    continue;
                }
            }
            if (total >= offset && matches.size() < limit) {
                matches.add(item != null ? item : RuleExecutionPresenter.toJson(axiom, category));
            }
            total++;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("axioms", matches);
        body.put("total", total);
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("kind", kind);
        return body;
    }

    private static boolean matchesQuery(Map<String, Object> item, String needle) {
        for (String key : List.of("description", "readable", "subjectIri", "predicateIri", "objectIri", "objectLiteral")) {
            Object value = item.get(key);
            if (value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
