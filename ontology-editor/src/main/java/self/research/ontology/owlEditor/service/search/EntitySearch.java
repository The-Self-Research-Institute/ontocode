package self.research.ontology.owlEditor.service.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class EntitySearch {

    private EntitySearch() {
    }

    public enum EntityType {
        CLASS,
        OBJECT_PROPERTY,
        DATA_PROPERTY,
        ANNOTATION_PROPERTY,
        DATATYPE,
        INDIVIDUAL;

        public static Set<EntityType> parse(String csv) {
            if (csv == null || csv.isBlank()) {
                return EnumSet.allOf(EntityType.class);
            }
            Set<EntityType> types = EnumSet.noneOf(EntityType.class);
            for (String token : csv.split(",")) {
                String name = token.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                try {
                    types.add(EntityType.valueOf(name));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown entity type: " + token.trim());
                }
            }
            return types.isEmpty() ? EnumSet.allOf(EntityType.class) : types;
        }
    }

    public enum MatchKind {
        LABEL,
        IRI,
        ANNOTATION
    }

    public record AnnotationValue(String property, String value, boolean literal) {
    }

    public record SearchRequest(
            SearchMatcher matcher,
            Set<EntityType> types,
            boolean annotations,
            boolean hideDeprecated,
            boolean hideBuiltins,
            int limitPerType,
            boolean searchIris) {

        public SearchRequest(SearchMatcher matcher, Set<EntityType> types, boolean annotations,
                             boolean hideDeprecated, boolean hideBuiltins, int limitPerType) {
            this(matcher, types, annotations, hideDeprecated, hideBuiltins, limitPerType, true);
        }
    }

    public record EntityMatch(MatchKind kind, String property, String propertyLabel, String value, List<int[]> ranges) {

        public static EntityMatch label(String label, List<int[]> ranges) {
            return new EntityMatch(MatchKind.LABEL, null, null, label, ranges);
        }

        public static EntityMatch iri(String iri, List<int[]> ranges) {
            return new EntityMatch(MatchKind.IRI, null, null, iri, ranges);
        }

        public static EntityMatch annotation(String property, String propertyLabel, String value, List<int[]> ranges) {
            return new EntityMatch(MatchKind.ANNOTATION, property, propertyLabel, value, ranges);
        }
    }

    public record EntityHit(String id, String label, List<EntityMatch> matches) {

        EntityHit withMatches(List<EntityMatch> extra) {
            List<EntityMatch> merged = new ArrayList<>(matches);
            merged.addAll(extra);
            return new EntityHit(id, label, merged);
        }
    }

    public static final class BuiltInEntities {

        private static final String OWL = "http://www.w3.org/2002/07/owl#";
        private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
        private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
        private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

        private static final Map<EntityType, Set<String>> BY_TYPE = new EnumMap<>(EntityType.class);

        static {
            BY_TYPE.put(EntityType.CLASS, Set.of(OWL + "Thing", OWL + "Nothing"));
            BY_TYPE.put(EntityType.OBJECT_PROPERTY, Set.of(OWL + "topObjectProperty", OWL + "bottomObjectProperty"));
            BY_TYPE.put(EntityType.DATA_PROPERTY, Set.of(OWL + "topDataProperty", OWL + "bottomDataProperty"));
            BY_TYPE.put(EntityType.ANNOTATION_PROPERTY, Set.of(
                    RDFS + "label", RDFS + "comment", RDFS + "seeAlso", RDFS + "isDefinedBy", OWL + "deprecated"));
            BY_TYPE.put(EntityType.DATATYPE, Set.of(
                    RDFS + "Literal", RDF + "PlainLiteral", XSD + "string", XSD + "integer", XSD + "decimal",
                    XSD + "float", XSD + "double", XSD + "boolean", XSD + "date", XSD + "dateTime", XSD + "anyURI"));
            BY_TYPE.put(EntityType.INDIVIDUAL, Set.of());
        }

        private BuiltInEntities() {
        }

        public static boolean isBuiltIn(EntityType type, String iri) {
            return BY_TYPE.get(type).contains(iri);
        }

        public static Set<String> of(EntityType type) {
            return BY_TYPE.get(type);
        }
    }

    public static final class SparqlText {

        private SparqlText() {
        }

        public static String escape(String text) {
            if (text == null) {
                return "";
            }
            StringBuilder out = new StringBuilder(text.length() + 8);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    out.append("\\\\");
                } else if (c == '"') {
                    out.append("\\\"");
                } else if (Character.isISOControl(c)) {
                    out.append(' ');
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    public static final class EntityMatching {

        public static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
        public static final String OWL_DEPRECATED = "http://www.w3.org/2002/07/owl#deprecated";

        private EntityMatching() {
        }

        public record Evaluation(String label, boolean deprecated, List<EntityMatch> matches) {
        }

        public record Candidate(String id, String label, List<EntityMatch> matches) {
        }

        public static Evaluation evaluate(String iri, List<AnnotationValue> values, SearchRequest request,
                                          UnaryOperator<String> propertyLabel) {
            SearchMatcher matcher = request.matcher();
            List<EntityMatch> matches = new ArrayList<>();
            List<int[]> iriRanges = request.searchIris() ? matcher.find(iri) : List.of();
            if (!iriRanges.isEmpty()) {
                matches.add(EntityMatch.iri(iri, iriRanges));
            }
            String label = null;
            boolean deprecated = false;
            for (AnnotationValue annotation : values) {
                String property = annotation.property();
                String value = annotation.value();
                boolean isLabel = RDFS_LABEL.equals(property);
                if (isLabel && annotation.literal() && label == null) {
                    label = value;
                }
                if (OWL_DEPRECATED.equals(property) && "true".equalsIgnoreCase(value.trim())) {
                    deprecated = true;
                }
                if (!isLabel && !request.annotations()) {
                    continue;
                }
                List<int[]> ranges = matcher.find(value);
                if (ranges.isEmpty()) {
                    continue;
                }
                matches.add(isLabel
                        ? EntityMatch.label(value, ranges)
                        : EntityMatch.annotation(property, propertyLabel.apply(property), value, ranges));
            }
            boolean named = matches.stream().anyMatch(m -> m.kind() != MatchKind.ANNOTATION);
            if (!named && (request.searchIris() || label == null)) {
                String name = localName(iri);
                List<int[]> nameRanges = matcher.find(name);
                if (!nameRanges.isEmpty()) {
                    matches.add(0, EntityMatch.label(name, nameRanges));
                }
            }
            return new Evaluation(label != null ? label : localName(iri), deprecated, matches);
        }

        public static void addSorted(EntitySearchResult.Builder out, EntityType type, List<Candidate> candidates) {
            List<Candidate> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator
                    .comparing((Candidate c) -> c.label() == null ? "" : c.label().toLowerCase(Locale.ROOT))
                    .thenComparing(Candidate::id));
            for (Candidate candidate : sorted) {
                out.add(type, candidate.id(), candidate.label(), candidate.matches());
            }
        }

        public static String localName(String iri) {
            if (iri == null || iri.isBlank()) {
                return "";
            }
            int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
            return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
        }
    }

    public static final class EntitySearchResult {

        private final Map<EntityType, List<EntityHit>> groups;
        private final boolean truncated;

        private EntitySearchResult(Map<EntityType, List<EntityHit>> groups, boolean truncated) {
            this.groups = groups;
            this.truncated = truncated;
        }

        public Map<EntityType, List<EntityHit>> groups() {
            return groups;
        }

        public boolean truncated() {
            return truncated;
        }

        public int totalHits() {
            return groups.values().stream().mapToInt(List::size).sum();
        }

        public Map<String, Object> toResponse(List<Map<String, Object>> classAncestors) {
            Map<String, Object> jsonGroups = new LinkedHashMap<>();
            for (EntityType type : EntityType.values()) {
                List<Map<String, Object>> hits = new ArrayList<>();
                for (EntityHit hit : groups.get(type)) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("id", hit.id());
                    h.put("label", hit.label());
                    List<Map<String, Object>> matches = new ArrayList<>();
                    for (EntityMatch m : hit.matches()) {
                        Map<String, Object> mm = new LinkedHashMap<>();
                        mm.put("kind", m.kind().name().toLowerCase(Locale.ROOT));
                        if (m.property() != null) {
                            mm.put("property", m.property());
                            mm.put("propertyLabel", m.propertyLabel());
                        }
                        mm.put("value", m.value());
                        mm.put("ranges", m.ranges());
                        matches.add(mm);
                    }
                    h.put("matches", matches);
                    hits.add(h);
                }
                jsonGroups.put(type.name(), hits);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("truncated", truncated);
            body.put("groups", jsonGroups);
            body.put("classAncestors", classAncestors == null ? List.of() : classAncestors);
            return body;
        }

        public static Builder builder(int limitPerType) {
            return new Builder(limitPerType);
        }

        public static final class Builder {
            private final int limitPerType;
            private final Map<EntityType, Map<String, EntityHit>> byType = new EnumMap<>(EntityType.class);
            private boolean truncated;

            private Builder(int limitPerType) {
                this.limitPerType = Math.max(1, limitPerType);
                for (EntityType type : EntityType.values()) {
                    byType.put(type, new LinkedHashMap<>());
                }
            }

            public Builder add(EntityType type, String id, String label, List<EntityMatch> matches) {
                if (id == null || matches == null || matches.isEmpty()) {
                    return this;
                }
                Map<String, EntityHit> group = byType.get(type);
                EntityHit existing = group.get(id);
                if (existing != null) {
                    group.put(id, existing.withMatches(matches));
                    return this;
                }
                if (group.size() >= limitPerType) {
                    truncated = true;
                    return this;
                }
                group.put(id, new EntityHit(id, label, new ArrayList<>(matches)));
                return this;
            }

            public EntitySearchResult build() {
                Map<EntityType, List<EntityHit>> groups = new EnumMap<>(EntityType.class);
                byType.forEach((type, hits) -> groups.put(type, List.copyOf(hits.values())));
                return new EntitySearchResult(groups, truncated);
            }
        }
    }
}
