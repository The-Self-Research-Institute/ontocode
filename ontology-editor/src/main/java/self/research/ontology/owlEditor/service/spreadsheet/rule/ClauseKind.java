package self.research.ontology.owlEditor.service.spreadsheet.rule;

public enum ClauseKind {
    TYPES("Types", false),
    FACTS("Facts", true),
    ANNOTATIONS("Annotations", true),
    SUB_CLASS_OF("SubClassOf", false),
    EQUIVALENT_TO("EquivalentTo", false),
    DISJOINT_WITH("DisjointWith", false),
    SAME_AS("SameAs", false),
    DIFFERENT_FROM("DifferentFrom", false),
    DOMAIN("Domain", false),
    RANGE("Range", false),
    SUB_PROPERTY_OF("SubPropertyOf", false),
    INVERSE_OF("InverseOf", false),
    CHARACTERISTICS("Characteristics", false);

    private final String keyword;
    private final boolean pairs;

    ClauseKind(String keyword, boolean pairs) {
        this.keyword = keyword;
        this.pairs = pairs;
    }

    public String keyword() {
        return keyword;
    }

    public boolean pairs() {
        return pairs;
    }

    public static ClauseKind fromKeyword(String keyword) {
        for (ClauseKind kind : values()) {
            if (kind.keyword.equals(keyword)) {
                return kind;
            }
        }
        return null;
    }
}
