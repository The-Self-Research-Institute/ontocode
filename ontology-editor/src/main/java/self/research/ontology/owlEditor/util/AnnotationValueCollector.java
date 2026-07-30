package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.model.Value;

import java.util.*;

/**
 * Collects multiple annotation values per property.
 */
public final class AnnotationValueCollector {

    private AnnotationValueCollector() {}

    public static Map<String, List<String>> newMap() {
        return new LinkedHashMap<>();
    }

    public static void add(Map<String, List<String>> annotations, String propertyIri, String value) {
        if (propertyIri == null || value == null || value.isBlank()) {
            return;
        }
        annotations.computeIfAbsent(propertyIri, ignored -> new ArrayList<>()).add(value);
    }

    public static void addFromBinding(Map<String, List<String>> annotations,
                                      String propertyIri,
                                      Value valueNode) {
        if (propertyIri == null || valueNode == null) {
            return;
        }
        String value = valueNode.isLiteral() ? valueNode.stringValue() : valueNode.toString();
        add(annotations, propertyIri, value);
    }

    /** Backward-compatible single-value map for legacy clients. */
    public static Map<String, String> toSingleValueMap(Map<String, List<String>> multi) {
        Map<String, String> single = new LinkedHashMap<>();
        if (multi == null) {
            return single;
        }
        multi.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                single.put(key, values.get(0));
            }
        });
        return single;
    }
}
