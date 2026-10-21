package self.research.ontology.owlEditor.service.spreadsheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ImportItemsSummary {

    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final String RDF_TYPE = RDF + "type";
    private static final String RDFS_LABEL = RDFS + "label";

    public static final String UPDATED_INDIVIDUALS = "updatedIndividuals";

    private static final Map<String, String> KINDS = new LinkedHashMap<>();
    private static final Set<String> ANNOTATION_PREDICATES = Set.of(
        RDFS + "comment", RDFS + "seeAlso", RDFS + "isDefinedBy");
    private static final Set<String> OTHER_PREDICATES = Set.of(OWL + "sameAs", OWL + "differentFrom");

    static {
        KINDS.put(OWL + "Class", "classes");
        KINDS.put(OWL + "NamedIndividual", "individuals");
        KINDS.put(OWL + "ObjectProperty", "objectProperties");
        KINDS.put(OWL + "DatatypeProperty", "dataProperties");
        KINDS.put(OWL + "AnnotationProperty", "annotationProperties");
        KINDS.put(RDFS + "Datatype", "datatypes");
    }

    public record Link(String property, String propertyIri, String target, String targetIri) {
    }

    public record Value(String property, String propertyIri, String value) {
    }

    public record Assertions(List<String> classes, List<Link> objects, List<Value> data,
                             List<Value> annotations, List<Link> other) {
        boolean isEmpty() {
            return classes.isEmpty() && objects.isEmpty() && data.isEmpty() && annotations.isEmpty() && other.isEmpty();
        }
    }

    public record Item(String iri, String name, Assertions assertions) {
    }

    public record Group(int total, int offset, List<Item> items) {
    }

    private record Triple(String subject, String predicate, String object, boolean objectIsIri) {
    }

    private ImportItemsSummary() {
    }

    public static Map<String, Group> of(List<String> addedTriples, int limit) {
        return parse(addedTriples).summary(limit);
    }

    public static Group page(List<String> addedTriples, String group, int offset, int limit) {
        return parse(addedTriples).pageOf(group, offset, limit);
    }

    public static Parsed parse(List<String> addedTriples) {
        List<Triple> triples = new ArrayList<>();
        for (String line : addedTriples == null ? List.<String>of() : addedTriples) {
            Triple t = parseTriple(line);
            if (t != null) {
                triples.add(t);
            }
        }

        Parsed p = new Parsed();
        KINDS.values().forEach(k -> p.groups.put(k, new LinkedHashSet<>()));
        p.groups.put(UPDATED_INDIVIDUALS, new LinkedHashSet<>());

        for (Triple t : triples) {
            if (RDF_TYPE.equals(t.predicate) && t.objectIsIri) {
                String kind = KINDS.get(t.object);
                if (kind != null) {
                    p.groups.get(kind).add(t.subject);
                }
            } else if (RDFS_LABEL.equals(t.predicate) && !t.objectIsIri) {
                String label = literalText(t.object);
                if (label != null && !label.isBlank()) {
                    p.labels.putIfAbsent(t.subject, label);
                }
            }
        }

        Set<String> nonIndividuals = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : p.groups.entrySet()) {
            if (!e.getKey().equals("individuals") && !e.getKey().equals(UPDATED_INDIVIDUALS)) {
                nonIndividuals.addAll(e.getValue());
            }
        }

        for (Triple t : triples) {
            if (nonIndividuals.contains(t.subject) || RDFS_LABEL.equals(t.predicate)) {
                continue;
            }
            if (RDF_TYPE.equals(t.predicate)) {
                if (t.objectIsIri && !KINDS.containsKey(t.object) && !isBuiltIn(t.object)) {
                    p.assertionsOf(t.subject).classes().add(p.name(t.object));
                }
                continue;
            }
            if (OTHER_PREDICATES.contains(t.predicate)) {
                p.assertionsOf(t.subject).other().add(new Link(p.name(t.predicate), t.predicate,
                    t.objectIsIri ? p.name(t.object) : literalText(t.object), t.objectIsIri ? t.object : null));
                continue;
            }
            boolean annotation = ANNOTATION_PREDICATES.contains(t.predicate)
                || p.groups.get("annotationProperties").contains(t.predicate);
            if (annotation) {
                p.assertionsOf(t.subject).annotations().add(new Value(p.name(t.predicate), t.predicate,
                    t.objectIsIri ? t.object : literalText(t.object)));
                continue;
            }
            if (isBuiltIn(t.predicate)) {
                continue;
            }
            boolean object = p.groups.get("objectProperties").contains(t.predicate)
                || (!p.groups.get("dataProperties").contains(t.predicate) && t.objectIsIri);
            if (object && t.objectIsIri) {
                p.assertionsOf(t.subject).objects().add(new Link(p.name(t.predicate), t.predicate,
                    p.name(t.object), t.object));
            } else {
                p.assertionsOf(t.subject).data().add(new Value(p.name(t.predicate), t.predicate,
                    t.objectIsIri ? t.object : literalText(t.object)));
            }
        }

        Set<String> declared = p.groups.get("individuals");
        for (Map.Entry<String, Assertions> e : p.assertions.entrySet()) {
            if (!declared.contains(e.getKey()) && !e.getValue().isEmpty()) {
                p.groups.get(UPDATED_INDIVIDUALS).add(e.getKey());
            }
        }
        return p;
    }

    public static final class Parsed {
        final Map<String, Set<String>> groups = new LinkedHashMap<>();
        final Map<String, String> labels = new HashMap<>();
        final Map<String, Assertions> assertions = new LinkedHashMap<>();

        public Map<String, Group> summary(int limit) {
            Map<String, Group> summary = new LinkedHashMap<>();
            for (String group : groups.keySet()) {
                summary.put(group, page(group, 0, limit));
            }
            return summary;
        }

        public Group pageOf(String group, int offset, int limit) {
            return groups.containsKey(group) ? page(group, offset, limit) : null;
        }

        Assertions assertionsOf(String subject) {
            return assertions.computeIfAbsent(subject, s -> new Assertions(
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        }

        String name(String iri) {
            return labels.getOrDefault(iri, localName(iri));
        }

        Group page(String group, int offset, int limit) {
            List<String> iris = new ArrayList<>(groups.get(group));
            int from = Math.max(0, Math.min(offset, iris.size()));
            int to = Math.min(iris.size(), from + Math.max(0, limit));
            boolean withAssertions = group.equals("individuals") || group.equals(UPDATED_INDIVIDUALS);
            List<Item> items = new ArrayList<>();
            for (String iri : iris.subList(from, to)) {
                items.add(new Item(iri, name(iri), withAssertions ? assertions.get(iri) : null));
            }
            return new Group(iris.size(), from, items);
        }
    }

    private static Triple parseTriple(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.endsWith(" .")) {
            s = s.substring(0, s.length() - 2).trim();
        }
        if (!s.startsWith("<")) {
            return null;
        }
        int subjectEnd = s.indexOf('>');
        if (subjectEnd < 0) {
            return null;
        }
        String subject = s.substring(1, subjectEnd);
        String rest = s.substring(subjectEnd + 1).trim();
        if (!rest.startsWith("<")) {
            return null;
        }
        int predicateEnd = rest.indexOf('>');
        if (predicateEnd < 0) {
            return null;
        }
        String predicate = rest.substring(1, predicateEnd);
        String object = rest.substring(predicateEnd + 1).trim();
        if (object.startsWith("<") && object.endsWith(">")) {
            return new Triple(subject, predicate, object.substring(1, object.length() - 1), true);
        }
        return new Triple(subject, predicate, object, false);
    }

    private static boolean isBuiltIn(String iri) {
        return iri.startsWith(RDF) || iri.startsWith(RDFS) || iri.startsWith(OWL);
    }

    static String localName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }

    private static String literalText(String literal) {
        if (!literal.startsWith("\"")) {
            return literal;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < literal.length(); i++) {
            char ch = literal.charAt(i);
            if (ch == '\\' && i + 1 < literal.length()) {
                char next = literal.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
            } else if (ch == '"') {
                return out.toString();
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
